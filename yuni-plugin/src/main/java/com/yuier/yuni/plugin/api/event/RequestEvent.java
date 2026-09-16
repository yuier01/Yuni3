package com.yuier.yuni.plugin.api.event;

import java.util.Optional;

/**
 * 请求事件。
 *
 * <p>对应 OneBot {@code post_type = "request"}，涵盖加好友请求、加群请求、
 * 被邀请入群请求。处理这类事件通常需要调用 OneBot 的
 * {@code set_friend_add_request} / {@code set_group_add_request} 接口来批准或拒绝，
 * 该能力由 {@code Sender}/{@code YuniBot} 服务提供。</p>
 *
 * <h2>子类型判定</h2>
 * <pre>{@code
 * // 只处理加好友请求
 * ctx.onRequest("friend", (ev, reply) -> {
 *     if (ev.comment().orElse("").contains("暗号")) {
 *         reply.approve();          // 批准
 *     }
 * });
 * }</pre>
 *
 * <h2>与现行系统的差异</h2>
 * <p>现行 {@code PassivePluginMatcher.matchRequestEvent} 既不检查插件启停也不检查权限
 * （审计报告已记录该不一致），且 {@code EventTranslator} 翻译时丢弃了
 * {@code sub_type}，导致插件无法区分「主动加群」与「被邀请入群」。
 * 新系统中 {@link Event#subType()} 保留该字段。</p>
 */
public interface RequestEvent extends Event {

    /**
     * 请求类型（OneBot {@code request_type}）。
     *
     * @return {@code friend}（加好友）或 {@code group}（加群/被邀请入群）
     */
    String requestType();

    /**
     * 发起请求的用户账号。
     *
     * @return 用户 QQ 号
     */
    long userId();

    /**
     * 验证信息 / 附言。
     *
     * <p>用户申请时填写的内容。注意：这是<b>不可信输入</b>，不应直接用于
     * 拼接命令或 SQL。</p>
     *
     * @return 验证信息；未提供时为空
     */
    Optional<String> comment();

    /**
     * 请求标识（OneBot {@code flag}）。
     *
     * <p>批准或拒绝该请求时必须原样回传，否则 OneBot 实现无法定位请求。</p>
     *
     * @return 请求标识；未提供时为 {@code null}
     */
    String flag();

    /**
     * 是否为加好友请求。
     *
     * @return {@code requestType} 为 {@code friend} 时返回 {@code true}
     */
    default boolean isFriendRequest() {
        return "friend".equals(requestType());
    }

    /**
     * 是否为群相关请求。
     *
     * @return {@code requestType} 为 {@code group} 时返回 {@code true}
     */
    default boolean isGroupRequest() {
        return "group".equals(requestType());
    }

    /**
     * 是否为「被邀请入群」。
     *
     * <p>仅当 {@code requestType = group} 且 {@code sub_type = invite} 时为真。</p>
     *
     * @return 被邀请入群时返回 {@code true}
     */
    default boolean isInviteToGroup() {
        return isGroupRequest() && "invite".equals(subType());
    }

    /**
     * 是否为「用户申请入群」。
     *
     * <p>仅当 {@code requestType = group} 且 {@code sub_type = add} 时为真。</p>
     *
     * @return 申请入群时返回 {@code true}
     */
    default boolean isAddGroup() {
        return isGroupRequest() && "add".equals(subType());
    }
}
