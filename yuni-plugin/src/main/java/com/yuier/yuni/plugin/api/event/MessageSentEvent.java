package com.yuier.yuni.plugin.api.event;

import com.yuier.yuni.plugin.api.Reply;

/**
 * 机器人自身发出消息的事件。
 *
 * <p>对应 OneBot {@code post_type = "message_sent"}。前置条件：OneBot 实现开启了
 * {@code message_sent} 上报（NapCat 默认开启）。</p>
 *
 * <h2>用途</h2>
 * <ul>
 *   <li>把机器人发出的消息同步给外部系统（如 maimai 中转）；</li>
 *   <li>审计与统计。</li>
 * </ul>
 *
 * <h2>与现行系统的重要差异</h2>
 * <p>现行 {@code MessageSentPlugin} 的匹配路径<b>完全不检查插件启停与权限</b>，
 * 也无条件执行全部该类插件（见 {@code PassivePluginMatcher.matchMessageSentEvent}）。
 * 新系统中本事件与其他事件走同一套分发流程——{@code ScopeFilter} 与权限检查
 * 都是必经关卡，插件必须显式写出条件：</p>
 * <pre>{@code
 * // 无条件处理也是显式写出来的，让「无条件是刻意的」这一点可见
 * ctx.onMessageSent(ev -> true, (ev, reply) -> audit(ev));
 *
 * // 典型用法：只中转群消息
 * ctx.onMessageSent(ev -> ev.isGroup(), (ev, reply) -> maiMai.push(ev.rawJson()));
 * }</pre>
 *
 * @see MessageEvent 收到消息事件
 */
public interface MessageSentEvent extends Event {

    /** OneBot {@code message_type} 取值：群聊。 */
    String TYPE_GROUP = "group";

    /** OneBot {@code message_type} 取值：私聊。 */
    String TYPE_PRIVATE = "private";

    /**
     * 消息类型。
     *
     * @return {@link #TYPE_GROUP} 或 {@link #TYPE_PRIVATE}
     */
    String messageType();

    /**
     * 是否为群聊消息。
     *
     * @return 群聊返回 {@code true}
     */
    default boolean isGroup() {
        return TYPE_GROUP.equals(messageType());
    }

    /**
     * 是否为私聊消息。
     *
     * @return 私聊返回 {@code true}
     */
    default boolean isPrivate() {
        return TYPE_PRIVATE.equals(messageType());
    }

    /**
     * 消息 ID。
     *
     * @return OneBot 消息 ID；协议未提供时为 {@code 0}
     */
    long messageId();

    /**
     * 发送者账号。
     *
     * <p>发出消息的通常是机器人自己，因此该值一般等于 {@link Event#selfId()}。
     * 保留本方法是为了让「转发/审计」类插件能像处理普通消息一样读取，
     * 不必特判事件类型。</p>
     *
     * @return 发送者 QQ 号；协议未提供时为 {@code 0}
     */
    long userId();

    /**
     * 消息内容（只读视图）。
     *
     * @return 消息链视图，永不为 {@code null}
     */
    MessageChainView chain();

    /**
     * 原始消息串。
     *
     * @return 原始消息内容；未提供时为 {@code null}
     */
    String rawMessage();

    /**
     * 本消息所属会话的回复入口。
     *
     * <p>注意：本事件是机器人<b>已经发出</b>消息的回执，用该入口再次发送
     * 会产生新消息。多数场景只需要读取，不需要回复。</p>
     *
     * @return 回复入口
     */
    Reply reply();

    /**
     * 消息发送位置的可读描述，用于日志。
     *
     * @return 形如 {@code "group@123456"} 或 {@code "private@10001"}
     */
    default String position() {
        return isGroup()
                ? TYPE_GROUP + "@" + groupId().orElse(0L)
                : TYPE_PRIVATE + "@" + longValue("user_id");
    }
}
