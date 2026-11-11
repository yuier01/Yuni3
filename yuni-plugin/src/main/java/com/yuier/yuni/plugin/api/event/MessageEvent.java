package com.yuier.yuni.plugin.api.event;

import com.yuier.yuni.plugin.api.Reply;

import java.util.Optional;

/**
 * 收到消息事件。
 *
 * <p>对应 OneBot {@code post_type = "message"}。这是插件最常处理的事件类型。</p>
 *
 * <h2>典型用法</h2>
 * <pre>{@code
 * // 模式匹配：条件与执行写在一个表达式里
 * ctx.onMessage(ev -> ev.getChain().containsText("你好"),
 *               (ev, reply) -> reply.text("你好，我是 " + ctx.getBot().nickName() + " !"));
 *
 * // 取发送者信息
 * ctx.onMessage(ev -> {
 *     if (ev.isGroup()) {
 *         // 群成员名片优先，回退到昵称
 *         String name = ev.getSender().cardOrNickname();
 *     }
 * });
 * }</pre>
 *
 * @see MessageSentEvent 机器人自己发出的消息
 */
public interface MessageEvent extends Event {

    /** OneBot {@code message_type} 取值：群聊。 */
    String TYPE_GROUP = "group";

    /** OneBot {@code message_type} 取值：私聊。 */
    String TYPE_PRIVATE = "private";

    /**
     * 消息类型。
     *
     * @return {@link #TYPE_GROUP} 或 {@link #TYPE_PRIVATE}
     */
    String getMessageType();

    /**
     * 是否为群聊消息。
     *
     * @return 群聊返回 {@code true}
     */
    default boolean isGroup() {
        return TYPE_GROUP.equals(getMessageType());
    }

    /**
     * 是否为私聊消息。
     *
     * @return 私聊返回 {@code true}
     */
    default boolean isPrivate() {
        return TYPE_PRIVATE.equals(getMessageType());
    }

    /**
     * 消息 ID。
     *
     * @return OneBot 消息 ID；协议未提供时为 {@code 0}
     */
    long getMessageId();

    /**
     * 发送者账号。
     *
     * @return 发送者 QQ 号
     */
    long getUserId();

    /**
     * 消息内容（只读视图）。
     *
     * @return 消息链视图，永不为 {@code null}
     */
    MessageChainView getChain();

    /**
     * 原始消息串。
     *
     * <p>当协议以 CQ 码格式上报时，这里是 CQ 码字符串；以数组格式上报时，
     * 这里是消息段的 JSON 数组串。与 {@link #getChain()} 的区别是本方法不做解析。</p>
     *
     * @return 原始消息内容；未提供时为 {@code null}
     */
    String getRawMessage();

    /**
     * 发送者信息。
     *
     * @return 发送者视图，永不为 {@code null}
     */
    Sender getSender();

    /**
     * 本消息所属会话的回复入口。
     *
     * <p>与处理器参数 {@code reply} 等价。保留本方法是为了让插件在异步回调
     * （例如定时任务里）也能拿到回复入口——不需要把 {@code reply} 参数
     * 捕获到闭包里。</p>
     *
     * @return 回复入口
     */
    Reply getReply();

    /**
     * 消息发送位置的可读描述，用于日志。
     *
     * @return 形如 {@code "group@123456"} 或 {@code "private@10001"}
     */
    default String getPosition() {
        return isGroup()
                ? TYPE_GROUP + "@" + getGroupId().orElse(0L)
                : TYPE_PRIVATE + "@" + getUserId();
    }

    /**
     * 消息发送者视图。
     *
     * <p>字段随会话类型而异：群聊有 {@code role} 与群名片 {@code card}，
     * 私聊两者均为空。</p>
     */
    interface Sender {

        /**
         * 发送者账号。
         *
         * @return QQ 号
         */
        long getUserId();

        /**
         * 发送者昵称。
         *
         * @return 昵称；未提供时为 {@code null}
         */
        String getNickname();

        /**
         * 群名片（群聊专有）。
         *
         * @return 群名片；私聊或未设置时为空
         */
        Optional<String> getCard();

        /**
         * 群内角色（群聊专有）。
         *
         * @return {@code owner} / {@code admin} / {@code member}；私聊时为空
         */
        Optional<String> getRole();

        /**
         * 群头衔（群聊专有）。
         *
         * @return 专属头衔；未设置时为空
         */
        Optional<String> getTitle();

        /**
         * 显示名——群名片优先，回退昵称，最后回退账号字符串。
         *
         * <p>用于播报类插件的「谁做了什么」。现行系统把这个逻辑放在
         * {@code PluginUtils.getGroupMemberName} 里，且需要额外一次
         * {@code get_group_member_info} 接口调用；此处直接取事件里已有的字段，
         * 无网络开销。</p>
         *
         * @return 显示名，永不为 {@code null}
         */
        default String getDisplayName() {
            return getCard().filter(s -> !s.isBlank())
                    .or(() -> Optional.ofNullable(getNickname()).filter(s -> !s.isBlank()))
                    .orElseGet(() -> String.valueOf(getUserId()));
        }

        /**
         * 是否为群主。
         *
         * @return 角色为 {@code owner} 时返回 {@code true}
         */
        default boolean isOwner() {
            return getRole().map("owner"::equals).orElse(false);
        }

        /**
         * 是否为管理员（含群主）。
         *
         * @return 角色为 {@code owner} 或 {@code admin} 时返回 {@code true}
         */
        default boolean isAdmin() {
            return getRole().map(r -> "owner".equals(r) || "admin".equals(r)).orElse(false);
        }
    }
}
