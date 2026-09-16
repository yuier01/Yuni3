package com.yuier.yuni.plugin.runtime.adapter;

import com.yuier.yuni.core.event.YuniMessageSentEvent;
import com.yuier.yuni.plugin.api.Reply;
import com.yuier.yuni.plugin.api.event.MessageChainView;
import com.yuier.yuni.plugin.api.event.MessageSentEvent;

/**
 * {@link MessageSentEvent} 的适配实现——机器人自身已发出消息的视图。
 *
 * <h2>与 {@link CoreMessageEvent} 的差异</h2>
 * <ul>
 *   <li>没有 {@code sender}（发送者是机器人自己），因此不提供发送者视图；</li>
 *   <li>发送者账号放在字段表里（键 {@code user_id}），需要时用
 *       {@code ev.longValue("user_id")} 取；</li>
 *   <li>典型用途是转发与审计，而非回复。</li>
 * </ul>
 *
 * @see MessageSentEvent
 */
public final class CoreMessageSentEvent extends BaseCoreEvent implements MessageSentEvent {

    /** 消息类型（group / private）。 */
    private final String messageType;

    /** 消息 ID。 */
    private final long messageId;

    /** 消息链视图。 */
    private final MessageChainView chain;

    /** 回复入口。 */
    private final Reply reply;

    /** 原始消息串。 */
    private final String rawMessage;

    /** 发送者账号（即机器人自己，或协议上报的发送者）。 */
    private final long userId;

    /**
     * 从核心消息发出事件构造插件侧事件。
     *
     * @param source 核心事件
     * @param chain  已包装的消息链视图；{@code null} 时使用空视图
     * @param reply  回复入口；{@code null} 时该事件不可回复
     */
    public CoreMessageSentEvent(YuniMessageSentEvent source, MessageChainView chain, Reply reply) {
        super(source.getTime() == null ? 0L : source.getTime(),
                source.getSelfId() == null ? 0L : source.getSelfId(),
                source.getGroupId(),
                source.getSubType(),
                buildFields(source),
                source.getRawJson());
        this.messageType = source.getMessageType();
        this.messageId = source.getMessageId() == null ? 0L : source.getMessageId();
        this.userId = source.getUserId() == null ? 0L : source.getUserId();
        this.chain = nullSafe(chain);
        this.reply = reply;
        this.rawMessage = source.getRawMessage();
    }

    /**
     * 组装差异字段表。
     *
     * @param source 核心事件
     * @return 字段表
     */
    private static java.util.Map<String, Object> buildFields(YuniMessageSentEvent source) {
        java.util.Map<String, Object> fields = fieldsBuilder();
        putIfPresent(fields, "raw_message", source.getRawMessage());
        putIfPresent(fields, "message_id", source.getMessageId());
        // 发出消息的 sender 就是机器人自己；仍按协议字段名放进表里，
        // 让插件可以像处理普通消息一样读取
        if (source.getSender() != null) {
            putIfPresent(fields, "sender_name", source.getSender().getNickname());
            putIfPresent(fields, "role", source.getSender().getRole());
        }
        putIfPresent(fields, "user_id", source.getUserId());
        putIfPresent(fields, "font", source.getFont());
        putIfPresent(fields, "message_format", source.getMessageFormat());
        putIfPresent(fields, "real_id", source.getRealId());
        return fields;
    }

    @Override
    public String messageType() {
        return messageType;
    }

    @Override
    public long messageId() {
        return messageId;
    }

    @Override
    public long userId() {
        return userId;
    }

    @Override
    public MessageChainView chain() {
        return chain;
    }

    @Override
    public String rawMessage() {
        return rawMessage;
    }

    @Override
    public Reply reply() {
        return reply;
    }

    @Override
    public String toLogString() {
        return "MessageSentEvent" + describeCommon();
    }
}
