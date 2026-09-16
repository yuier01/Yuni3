package com.yuier.yuni.plugin.runtime.adapter;

import com.yuier.yuni.core.bot.JsonCodec;
import com.yuier.yuni.core.constants.MessageSegmentTypes;
import com.yuier.yuni.core.model.message.MessageChain;
import com.yuier.yuni.core.model.message.MessageSegment;
import com.yuier.yuni.core.model.message.segment.TextSegment;
import com.yuier.yuni.plugin.api.event.MessageChainView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@link MessageChainView} 的适配器——把框架内部的 {@link MessageChain}
 * 包装成插件侧只读视图。
 *
 * <h2>为什么需要适配器而不是直接用 MessageChain</h2>
 * <p>插件侧视图是一个<b>只读接口</b>（设计文档 §5.4）：读事件与发消息被拆成
 * 两个类型，避免插件误改传入的事件内容。而框架内部的 {@code MessageChain}
 * 既可读又可写，且直接暴露可变集合（Lombok getter 原样返回 {@code content}）。
 * 适配器是两者之间的桥，同时承担两个职责：</p>
 * <ol>
 *   <li><b>只读收口</b>：对外只暴露查询方法，插件拿不到可变集合；</li>
 *   <li><b>防御空值</b>：{@code null} 消息段、{@code null} 文本字段在拼接时被
 *       替换为占位符而不是抛 NPE。现行 {@code MessageChain.toString()} 在多处
 *       直接解引用字段（如 {@code AnonymousSegment} 的 data 为 null 时返回空串，
 *       而其他段的实现不一致），插件做文本匹配时可能踩到。</li>
 * </ol>
 *
 * <h2>消息段的 JSON 暴露</h2>
 * <p>{@link #segmentJson(int)} 返回消息段的 JSON 字符串而非框架内部对象。
 * 这样插件既能读取段的字段（自行解析或借助 {@code JsonCodec}），又不会把
 * 插件代码绑定到 {@code com.yuier.yuni.core.model.message.segment.*} 的具体类型上
 * ——那是框架内部实现，改一次就会破坏全部插件。</p>
 *
 * <h2>不可变性</h2>
 * <p>本类不持有 {@code MessageChain} 的可变引用语义：构造时对段列表做快照，
 * 之后即使原链被修改（理论上分发期间不应该发生），视图内容也不变。</p>
 */
public final class CoreMessageChainView implements MessageChainView {

    /** 构造时对消息段做的快照；保证视图在生命周期内内容稳定。 */
    private final List<MessageSegment> segments;

    /** JSON 编解码器，用于 {@link #segmentJson(int)}。 */
    private final JsonCodec jsonCodec;

    /**
     * 构造视图。
     *
     * @param chain     框架内部消息链；{@code null} 视为空链
     * @param jsonCodec JSON 编解码器；为 {@code null} 时 {@code segmentJson} 返回空串
     */
    public CoreMessageChainView(MessageChain chain, JsonCodec jsonCodec) {
        this.jsonCodec = jsonCodec;
        if (chain == null || chain.getContent() == null) {
            this.segments = List.of();
        } else {
            // 快照：避免原链在视图存续期间被修改导致行为漂移
            List<MessageSegment> snapshot = new ArrayList<>();
            for (MessageSegment segment : chain.getContent()) {
                snapshot.add(segment);
            }
            this.segments = Collections.unmodifiableList(snapshot);
        }
    }

    /**
     * 空链视图的便捷工厂。
     *
     * @return 不含任何段的视图
     */
    public static CoreMessageChainView empty(JsonCodec jsonCodec) {
        return new CoreMessageChainView(null, jsonCodec);
    }

    @Override
    public String plainText() {
        StringBuilder builder = new StringBuilder();
        for (MessageSegment segment : segments) {
            if (segment == null) {
                // 空段按占位符处理，而不是让拼接整体失败
                builder.append(nullSegmentPlaceholder());
                continue;
            }
            if (isText(segment)) {
                String text = ((TextSegment) segment).getText();
                // 文本段内部的 null/空白保留原样，只有 null 才替换：
                // 空白是用户真实输入的一部分，做命令匹配时需要保留
                builder.append(text == null ? nullSegmentPlaceholder() : text);
            }
        }
        return builder.toString();
    }

    @Override
    public boolean isPlainText() {
        for (MessageSegment segment : segments) {
            if (segment == null || !isText(segment)) {
                return false;
            }
        }
        // 空链视为纯文本，与现行 MessageChain.isPlainText 一致
        return true;
    }

    @Override
    public boolean contains(String segmentType) {
        if (segmentType == null) {
            return false;
        }
        for (MessageSegment segment : segments) {
            if (segment == null) {
                continue;
            }
            // 用框架已有的 typeOf 做类型判定，避免在此重复维护类型表
            if (Boolean.TRUE.equals(segment.typeOf(segmentType))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean containsText(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return plainText().contains(text);
    }

    @Override
    public boolean startsWithText() {
        if (segments.isEmpty()) {
            return false;
        }
        MessageSegment first = segments.get(0);
        if (first == null || !isText(first)) {
            return false;
        }
        String text = ((TextSegment) first).getText();
        // 首段为纯空白不算「以文本开头」——命令头取不到有效内容
        return text != null && !text.trim().isEmpty();
    }

    @Override
    public boolean startsWithReply() {
        if (segments.isEmpty()) {
            return false;
        }
        return contains(MessageSegmentTypes.REPLY) && isType(segments.get(0), MessageSegmentTypes.REPLY);
    }

    @Override
    public boolean containsForward() {
        return contains(MessageSegmentTypes.FORWARD);
    }

    @Override
    public int size() {
        return segments.size();
    }

    @Override
    public List<String> segmentTypes() {
        List<String> types = new ArrayList<>(segments.size());
        for (MessageSegment segment : segments) {
            if (segment == null) {
                types.add("null");
            } else {
                String type = segment.getType();
                types.add(type == null ? "unknown" : type);
            }
        }
        return Collections.unmodifiableList(types);
    }

    @Override
    public String segmentJson(int index) {
        MessageSegment segment = segments.get(index);
        if (segment == null || jsonCodec == null) {
            return "";
        }
        try {
            return jsonCodec.toJson(segment);
        } catch (Exception e) {
            // 序列化失败不应让插件的读取逻辑崩溃；返回空串并由插件自行判断
            return "";
        }
    }

    @Override
    public List<String> segmentJsonList() {
        if (jsonCodec == null || segments.isEmpty()) {
            return List.of();
        }
        // 逐段序列化：段级失败只影响该段（返回空串），不牵连整批
        List<String> result = new ArrayList<>(segments.size());
        for (int i = 0; i < segments.size(); i++) {
            result.add(segmentJson(i));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 取指定位置的消息段（框架内部对象）。
     *
     * <p><b>仅供框架内部与适配器使用</b>，不通过 {@link MessageChainView} 暴露。
     * 插件侧应使用 {@link #segmentJson(int)}。</p>
     *
     * @param index 段下标
     * @return 框架内部消息段
     */
    public MessageSegment rawSegment(int index) {
        return segments.get(index);
    }

    /**
     * 底层消息段快照（框架内部用途）。
     *
     * @return 不可变列表
     */
    public List<MessageSegment> rawSegments() {
        return segments;
    }

    /**
     * 判定段是否为文本类型。
     *
     * @param segment 消息段
     * @return 文本段返回 {@code true}
     */
    private static boolean isText(MessageSegment segment) {
        return isType(segment, MessageSegmentTypes.TEXT);
    }

    /**
     * 判定段的类型。
     *
     * @param segment 消息段
     * @param type    期望类型
     * @return 匹配返回 {@code true}
     */
    private static boolean isType(MessageSegment segment, String type) {
        return segment != null && Boolean.TRUE.equals(segment.typeOf(type));
    }
}
