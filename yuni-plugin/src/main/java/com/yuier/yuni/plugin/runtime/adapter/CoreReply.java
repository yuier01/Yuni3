package com.yuier.yuni.plugin.runtime.adapter;

import com.yuier.yuni.core.bot.JsonCodec;
import com.yuier.yuni.core.bot.MessageSentResult;
import com.yuier.yuni.core.bot.MessageTarget;
import com.yuier.yuni.core.bot.YuniBot;
import com.yuier.yuni.core.model.message.MessageChain;
import com.yuier.yuni.core.model.message.MessageSegment;
import com.yuier.yuni.core.model.message.segment.ImageSegment;
import com.yuier.yuni.core.model.message.segment.PokeSegment;
import com.yuier.yuni.plugin.api.Reply;
import com.yuier.yuni.plugin.api.event.MessageChainView;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link Reply} 的默认实现——把插件侧的构建调用翻译成框架内部
 * {@link MessageChain}，最终经 {@link YuniBot#sendMessage} 发出。
 *
 * <h2>构建与发送分离</h2>
 * <p>全部构建方法（{@code text} / {@code at} / {@code image} / …）只累积消息段，
 * <b>不产生网络调用</b>；只有 {@link #send()} 才真正发送。这样「条件不满足时
 * 静默返回」是自然写法，插件不需要构造一个「不发送的空链」。</p>
 *
 * <h2>引用回复的插入位置</h2>
 * <p>QQ 客户端要求回复段位于消息链<b>第一位</b>。{@link #quote()} 与
 * {@link #quote(String)} 记录待插入的消息 ID，在 {@link #send()} 时统一
 * 插到链首——而不是在调用 {@code quote()} 时立即插入。理由：插件可能
 * 先调 {@code quote()} 再追加内容，若立即插入链首而后续方法用
 * {@code addSegment} 追加，结果仍然正确；但若插件在 {@code quote()} 之前
 * 已经追加了内容，立即插入会导致位置错误。延迟到发送时插入可以保证
 * 「无论调用顺序如何，回复段都在第一位」。</p>
 *
 * <h2>发送失败不抛异常</h2>
 * <p>{@link #send()} 捕获全部异常并返回失败态结果。发送失败不应中断插件的
 * 业务逻辑——尤其是「先回复用户，再落库」这类顺序敏感的处理器。</p>
 *
 * @see Reply
 */
@Slf4j
public final class CoreReply implements Reply {

    /** 机器人实例，发送的实际执行者。 */
    private final YuniBot bot;

    /** 发送目标（群或私聊）。 */
    private final MessageTarget target;

    /** 当前累积的消息段。 */
    private final MessageChain chain = new MessageChain();

    /** 触发本回复的事件所属消息 ID；用于 {@link #quote()}。 */
    private final Long sourceMessageId;

    /** JSON 编解码器，用于把插件侧传入的链转成内部消息段。 */
    private final JsonCodec jsonCodec;

    /** 待插入链首的引用消息 ID；{@code null} 表示不引用。 */
    private String quoteMessageId;

    /**
     * 构造回复器。
     *
     * @param bot             机器人实例
     * @param target          发送目标
     * @param sourceMessageId 触发事件的源消息 ID；无则传 {@code null}
     * @param jsonCodec       JSON 编解码器，用于链转换
     */
    public CoreReply(YuniBot bot, MessageTarget target, Long sourceMessageId, JsonCodec jsonCodec) {
        this.bot = bot;
        this.target = target;
        this.sourceMessageId = sourceMessageId;
        this.jsonCodec = jsonCodec;
    }

    // ==================== 构建 ====================

    @Override
    public Reply text(String text) {
        if (text != null) {
            chain.addTextSegment(text);
        }
        return this;
    }

    @Override
    public Reply at(long userId) {
        // addAt 内部会补一个空格，避免「@某人你好」被客户端渲染成「@某人的你好」。
        // 这与现行 MessageChain.addAt 的行为一致。
        chain.addAt(userId);
        return this;
    }

    @Override
    public Reply image(String file) {
        if (file != null) {
            chain.addSegment(new ImageSegment().setFile(file));
        }
        return this;
    }

    @Override
    public Reply face(int faceId) {
        /*
         * 经通用段构造路径而非直接 new FaceSegment：
         * core 的 FaceSegment.data 是 private 且没有公开 setter
         * （只有 getter + Jackson 反序列化入口），因此程序化构造必须走
         * 「type + data → 多态反序列化」这一条路径。这样也保证全部段的
         * 构造方式统一，不会出现「有的段能程序化构造、有的不能」的差异。
         */
        return segment("face", Map.of("id", String.valueOf(faceId)));
    }

    @Override
    public Reply record(String file) {
        return file == null ? this : segment("record", Map.of("file", file));
    }

    @Override
    public Reply video(String file) {
        return file == null ? this : segment("video", Map.of("file", file));
    }

    @Override
    public Reply poke(long userId) {
        PokeSegment segment = new PokeSegment();
        com.yuier.yuni.core.model.message.data.PokeData data =
                new com.yuier.yuni.core.model.message.data.PokeData();
        data.setQq(userId);
        segment.setData(data);
        chain.addSegment(segment);
        return this;
    }

    @Override
    public Reply segment(String type, Map<String, Object> data) {
        if (type == null || type.isBlank()) {
            return this;
        }
        /*
         * 逃生舱：把 {type, data} 经 ObjectMapper 的多态能力还原成具体的
         * MessageSegment 子类。core 的 MessageSegment 已声明
         * @JsonTypeInfo(property = "type")，因此这条路径天然支持全部 23 种段。
         */
        if (jsonCodec == null) {
            log.warn("未注入 JsonCodec，无法构造原始消息段: type={}", type);
            return this;
        }
        try {
            Map<String, Object> payload = Map.of(
                    "type", type,
                    "data", data == null ? Map.of() : data);
            MessageSegment segment = jsonCodec.convertValue(payload, MessageSegment.class);
            if (segment != null) {
                chain.addSegment(segment);
            }
        } catch (Exception e) {
            log.warn("构造原始消息段失败: type={}, data={}", type, data, e);
        }
        return this;
    }

    @Override
    public Reply chain(MessageChainView chainView) {
        if (chainView == null) {
            return this;
        }
        // 复用适配器持有的原始段列表，避免 JSON 往返
        if (chainView instanceof CoreMessageChainView core) {
            for (MessageSegment segment : core.rawSegments()) {
                if (segment != null) {
                    this.chain.addSegment(segment);
                }
            }
            return this;
        }
        // 其他实现：退回逐段 JSON 转换
        for (String segmentJson : chainView.segmentJsonList()) {
            if (segmentJson == null || segmentJson.isBlank() || jsonCodec == null) {
                continue;
            }
            try {
                MessageSegment segment = jsonCodec.fromJson(segmentJson, MessageSegment.class);
                if (segment != null) {
                    this.chain.addSegment(segment);
                }
            } catch (Exception e) {
                log.warn("转换消息段失败: {}", segmentJson, e);
            }
        }
        return this;
    }

    // ==================== 引用 ====================

    @Override
    public Reply quote() {
        if (sourceMessageId != null) {
            this.quoteMessageId = String.valueOf(sourceMessageId);
        } else {
            // 非消息事件触发（如定时任务）时无源消息可引用
            log.debug("当前回复没有源消息，quote() 退化为空操作");
        }
        return this;
    }

    @Override
    public Reply quote(String messageId) {
        if (messageId != null && !messageId.isBlank()) {
            this.quoteMessageId = messageId;
        }
        return this;
    }

    // ==================== 发送 ====================

    @Override
    public SentResult send() {
        try {
            MessageChain toSend = buildFinalChain();
            MessageSentResult result = bot.sendMessage(target, toSend);
            return toSentResult(result);
        } catch (Throwable t) {
            // 必须捕获 Throwable：发送路径可能因类加载或连接问题抛 Error，
            // 而「回复失败」不应该把整个插件处理器打挂
            log.error("发送消息失败: target={}, message={}", target, chain, t);
            return new DefaultSentResult(false, 0L, t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    @Override
    public List<SentResult> sendEach(List<MessageChainView> chains) {
        if (chains == null || chains.isEmpty()) {
            return List.of();
        }
        List<SentResult> results = new ArrayList<>(chains.size());
        for (MessageChainView chainView : chains) {
            // 每条独立构造与发送，保证「逐条顺序」且互不影响
            results.add(chain(chainView).send());
        }
        return Collections.unmodifiableList(results);
    }

    /**
     * 构造最终要发送的消息链——把待引用的回复段插到链首。
     *
     * @return 最终消息链
     */
    private MessageChain buildFinalChain() {
        if (quoteMessageId == null) {
            return chain;
        }
        // 延迟插入：保证无论插件以何种顺序调用构建方法，回复段都在第一位
        MessageChain finalChain = new MessageChain();
        finalChain.addReply(quoteMessageId);
        finalChain.addAll(chain);
        return finalChain;
    }

    /**
     * 把框架的发送结果转换为插件侧的简化结果。
     *
     * @param result 框架结果；{@code null} 视为失败
     * @return 插件侧结果
     */
    private static SentResult toSentResult(MessageSentResult result) {
        if (result == null) {
            return new DefaultSentResult(false, 0L, "发送结果为空");
        }
        long messageId = 0L;
        if (result.getMessageId() != null) {
            try {
                messageId = Long.parseLong(result.getMessageId());
            } catch (NumberFormatException e) {
                // OneBot 实现可能返回非数字消息 ID；此时只标记成功，ID 记 0
                log.debug("消息 ID 非数字，忽略: {}", result.getMessageId());
            }
        }
        return new DefaultSentResult(result.isSuccess(), messageId, result.getErrorMessage());
    }

    /**
     * {@link SentResult} 的默认实现。
     *
     * <p>组件名刻意用 {@code failureReason} 而非 {@code errorMessage}：接口要求
     * {@code errorMessage()} 返回 {@code Optional<String>}，而 record 会自动为
     * 同名组件生成返回裸 {@code String} 的访问器，两者签名冲突。换个组件名后
     * 手工实现接口方法即可。</p>
     *
     * @param success       是否成功
     * @param messageId     消息 ID；失败为 0
     * @param failureReason 失败原因；成功为 {@code null}
     */
    record DefaultSentResult(boolean success, long messageId, String failureReason) implements SentResult {

        @Override
        public Optional<String> errorMessage() {
            return Optional.ofNullable(failureReason);
        }
    }
}
