package com.yuier.yuni.plugin.runtime.adapter;

import com.yuier.yuni.core.event.YuniMessageEvent;
import com.yuier.yuni.core.model.user.MessageSender;
import com.yuier.yuni.plugin.api.Reply;
import com.yuier.yuni.plugin.api.event.MessageChainView;
import com.yuier.yuni.plugin.api.event.MessageEvent;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.Optional;

/**
 * {@link MessageEvent} 的适配实现——把 {@link YuniMessageEvent} 包装为插件侧视图。
 *
 * <h2>字段映射</h2>
 * <table border="1">
 *   <caption>core → 插件侧</caption>
 *   <tr><th>插件侧方法</th><th>来源</th></tr>
 *   <tr><td>{@link #messageType()}</td><td>{@code YuniMessageEvent.messageType}</td></tr>
 *   <tr><td>{@link #messageId()}</td><td>{@code messageId}</td></tr>
 *   <tr><td>{@link #userId()}</td><td>{@code userId}</td></tr>
 *   <tr><td>{@link #chain()}</td><td>{@code messageChain}（经 {@link CoreMessageChainView} 包装）</td></tr>
 *   <tr><td>{@link #sender()}</td><td>{@code sender}（nickname/card/role/title）</td></tr>
 *   <tr><td>{@link #reply()}</td><td>新建 {@link CoreReply}，绑定本消息的会话与消息 ID</td></tr>
 * </table>
 *
 * <h2>差异字段</h2>
 * <p>{@code rawMessage} / {@code font} / {@code messageFormat} / {@code realId}
 * 等放入字段表，插件可用 {@code ev.stringValue("raw_message")} 访问，
 * 无需为每个字段增加一个方法。</p>
 *
 * @see MessageEvent
 */
@Getter
@Accessors(fluent = true)
public final class CoreMessageEvent extends BaseCoreEvent implements MessageEvent {

    /** 消息类型（group / private）。 */
    private final String messageType;

    /** 消息 ID。 */
    private final long messageId;

    /** 发送者账号。 */
    private final long userId;

    /** 消息链视图。 */
    private final MessageChainView chain;

    /** 发送者视图。 */
    private final Sender sender;

    /** 回复入口。 */
    private final Reply reply;

    /** 原始消息串。 */
    private final String rawMessage;

    /**
     * 从核心消息事件构造插件侧事件。
     *
     * @param source   核心消息事件
     * @param chain    已包装的消息链视图；{@code null} 时使用空视图
     * @param reply    回复入口；{@code null} 时该事件不可回复
     */
    public CoreMessageEvent(YuniMessageEvent source, MessageChainView chain, Reply reply) {
        super(source.getTime() == null ? 0L : source.getTime(),
                source.getSelfId() == null ? 0L : source.getSelfId(),
                source.getGroupId(),
                // 消息事件的「子类型」使用 sub_type（normal / anonymous / friend / group / other）
                source.getSubType(),
                buildFields(source),
                source.getRawJson());
        this.messageType = source.getMessageType();
        this.messageId = source.getMessageId() == null ? 0L : source.getMessageId();
        this.userId = source.getUserId() == null ? 0L : source.getUserId();
        this.chain = nullSafe(chain);
        this.sender = new CoreSenderView(source.getSender(), this.userId);
        this.reply = reply;
        this.rawMessage = source.getRawMessage();
    }

    /**
     * 组装差异字段表。
     *
     * @param source 核心事件
     * @return 字段表
     */
    private static java.util.Map<String, Object> buildFields(YuniMessageEvent source) {
        java.util.Map<String, Object> fields = fieldsBuilder();
        putIfPresent(fields, "raw_message", source.getRawMessage());
        putIfPresent(fields, "font", source.getFont());
        putIfPresent(fields, "message_format", source.getMessageFormat());
        putIfPresent(fields, "real_id", source.getRealId());
        putIfPresent(fields, "message_id", source.getMessageId());
        putIfPresent(fields, "user_id", source.getUserId());
        return fields;
    }

    /*
     * messageType() / messageId() / userId() / chain() / sender() / reply() /
     * rawMessage() 由类上的 @Getter + @Accessors(fluent) 生成。
     */

    @Override
    public String toLogString() {
        return "MessageEvent" + describeCommon();
    }

    /**
     * {@link Sender} 的适配实现。
     *
     * <p>全部字段直接来自事件已携带的 {@code sender} 对象，
     * <b>不产生额外的接口调用</b>。现行系统里 {@code PluginUtils.getGroupMemberName}
     * 需要额外调一次 {@code get_group_member_info}，而事件里其实已经有
     * {@code card} 与 {@code nickname}。</p>
     */
    private static final class CoreSenderView implements Sender {

        /** 核心发送者对象；可能为 {@code null}（协议未提供）。 */
        private final MessageSender source;

        /** 兜底的发送者账号（sender 缺失时用事件顶层的 userId）。 */
        private final long fallbackUserId;

        private CoreSenderView(MessageSender source, long fallbackUserId) {
            this.source = source;
            this.fallbackUserId = fallbackUserId;
        }

        @Override
        public long userId() {
            if (source != null && source.getUserId() != null) {
                return source.getUserId();
            }
            return fallbackUserId;
        }

        @Override
        public String nickname() {
            return source == null ? null : source.getNickname();
        }

        @Override
        public Optional<String> card() {
            return Optional.ofNullable(source).map(MessageSender::getCard)
                    .filter(s -> !s.isBlank());
        }

        @Override
        public Optional<String> role() {
            return Optional.ofNullable(source).map(MessageSender::getRole)
                    .filter(s -> !s.isBlank());
        }

        @Override
        public Optional<String> title() {
            return Optional.ofNullable(source).map(MessageSender::getTitle)
                    .filter(s -> !s.isBlank());
        }
    }
}
