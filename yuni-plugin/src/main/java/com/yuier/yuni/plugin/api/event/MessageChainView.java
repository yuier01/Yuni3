package com.yuier.yuni.plugin.api.event;

import java.util.List;

/**
 * 插件侧消息链视图。
 *
 * <h2>为什么不是一个直接可用的可变链对象</h2>
 * <p>发送消息与读取消息是两个不同的关注点：</p>
 * <ul>
 *   <li>读取（本接口）：只需要查询能力——「这段话里有没有『你好』」「第一个是不是图片」。</li>
 *   <li>发送（{@code Reply}）：需要构建能力——链式叠加文本、At、图片、引用。</li>
 * </ul>
 * <p>现行系统把两者都塞进 {@code MessageChain}，于是读事件得到的对象与准备发送的
 * 对象是同一个类型，插件容易误改传入的事件内容。新设计把两者分开：
 * 本接口<b>只读</b>，发送用 {@code Reply}。</p>
 *
 * <h2>文本提取语义</h2>
 * <p>{@link #getPlainText()} 返回<b>全部文本段拼接</b>的结果，非文本段（图片、At、
 * 表情等）被忽略。这是模式匹配最常用的输入：</p>
 * <pre>{@code
 * ctx.onMessage(ev -> ev.getChain().getPlainText().contains("granbluefantasy.jp"),
 *               (ev, reply) -> reply.text("大家不要信，这是骑空士的陷阱！"));
 * }</pre>
 * <p>注意：{@code null} 值会替换为占位符（{@link #getNullSegmentPlaceholder()}），
 * 避免拼接时抛 NPE——这是现行 {@code MessageChain.toString()} 会崩溃的场景
 * （{@code AnonymousSegment.toString()} 在 data 为 null 时曾返回空串，
 * 而部分段的 {@code toString()} 直接解引用字段）。</p>
 */
public interface MessageChainView {

    /**
     * 消息链中全部文本段按顺序拼接的结果。
     *
     * <p>非文本段被跳过，段之间不插入分隔符。本方法永不返回 {@code null}；
     * 无文本段时返回空串。</p>
     *
     * @return 拼接后的纯文本
     */
    String getPlainText();

    /**
     * 消息链是否为「纯文本」——即全部消息段都是文本类型。
     *
     * <p>空链视为纯文本（{@code true}）。</p>
     *
     * @return 全部段均为文本时返回 {@code true}
     */
    boolean isPlainText();

    /**
     * 消息链是否包含指定类型的消息段。
     *
     * <p>类型名与 OneBot 协议一致：{@code text} / {@code at} / {@code image} /
     * {@code face} / {@code reply} / {@code forward} / {@code file} / {@code video} /
     * {@code record} / {@code json} / {@code xml} / {@code markdown} 等。</p>
     *
     * @param segmentType 消息段类型名
     * @return 至少含一个该类型段时返回 {@code true}
     */
    boolean contains(String segmentType);

    /**
     * 消息链是否包含可读文本中指定的子串。
     *
     * <p>与 {@code getPlainText().contains(s)} 等价，但避免重复拼接。</p>
     *
     * @param text 待查找子串；{@code null} 返回 {@code false}
     * @return 任一文本段含该子串时返回 {@code true}
     */
    boolean containsText(String text);

    /**
     * 消息链是否为「以非空文本段开头」。
     *
     * <p>命令匹配的前置条件之一——首段必须是文本才能取到命令头。
     * 前导空白被视为空文本。</p>
     *
     * @return 首段是非空文本时返回 {@code true}
     */
    boolean startsWithText();

    /**
     * 消息链是否以回复段开头。
     *
     * <p>QQ 客户端的特性：只要消息包含回复，回复段必定被放在消息链首位
     * （见 {@code CommandDetector.tokenize} 的注释）。因此「以回复开头」
     * 等价于「这条消息是一条回复」。</p>
     *
     * @return 首段是回复段时返回 {@code true}
     */
    boolean startsWithReply();

    /**
     * 是否包含合并转发消息段。
     *
     * <p>合并转发的内容需要额外调接口拉取，一般不参与文本匹配。</p>
     *
     * @return 含 {@code forward} 段时返回 {@code true}
     */
    boolean containsForward();

    /**
     * 消息段数量。
     *
     * @return 段数
     */
    int size();

    /**
     * 消息链是否为空（零个消息段）。
     *
     * @return 无消息段时返回 {@code true}
     */
    default boolean isEmpty() {
        return size() == 0;
    }

    /**
     * 全部消息段的类型名，按顺序。
     *
     * <p>用于日志与条件判定，例如「这条消息是不是纯图片」可以用
     * {@code types().equals(List.of("image"))}。</p>
     *
     * @return 类型名列表，不可变
     */
    List<String> getSegmentTypes();

    /**
     * 取指定位置消息段的<b>JSON 表示</b>。
     *
     * <p>返回的是该段的 JSON 字符串（形如
     * {@code {"type":"image","data":{"file":"..."}}}），而不是框架内部对象。
     * 这样插件既能读取字段（自行解析 JSON），又不会绑定到框架的消息段类型上。
     * 需要访问某个段的具体字段时，推荐使用本方法配合一个轻量 JSON 解析
     * （或 {@code ctx.requireService(JsonCodec.class)}）。</p>
     *
     * @param index 从 0 开始的段下标
     * @return 该段的 JSON 字符串
     * @throws IndexOutOfBoundsException 下标越界
     */
    String segmentJson(int index);

    /**
     * 全部消息段的 JSON 表示。
     *
     * @return JSON 字符串列表，不可变
     */
    List<String> getSegmentJsonList();

    /**
     * 拼接时用于替换 {@code null} 消息段的占位符。
     *
     * <p>默认空串。需要更强诊断能力时（例如希望日志里能看出某段是坏的）
     * 可以返回 {@code "[null]"}。</p>
     *
     * @return 占位符
     */
    default String getNullSegmentPlaceholder() {
        return "";
    }
}
