package com.yuier.yuni.plugin.api.session;

/**
 * 会话关闭原因。
 */
public enum SessionCloseReason {

    /** 插件主动关闭（正常结束/放弃）。 */
    PLUGIN_CLOSED,

    /** 用户退出指令（GROUP_MEMBER / PRIVATE；部分群友最后一人用 NO_PARTICIPANTS）。 */
    USER_EXIT,

    /** 空闲超时。 */
    IDLE_TIMEOUT,

    /** 达到 maxDuration。 */
    MAX_DURATION,

    /** 达到 maxTurns。 */
    TURN_LIMIT,

    /** 插件卸载/热重载。 */
    PLUGIN_UNLOAD,

    /** 插件在群内被禁用/全局禁用。 */
    SCOPE_REVOKED,

    /** 连续处理失败达到上限。 */
    ERROR_LIMIT,

    /** 机器人被踢出群、目标成员退群等。 */
    TARGET_UNAVAILABLE,

    /** 部分群友最后一名参与者离开。 */
    NO_PARTICIPANTS,

    /** 管理端强制关闭。 */
    ADMIN_CLOSED;

    /**
     * 是否允许回调 {@code onClose}。
     *
     * @return 允许则返回 {@code true}
     */
    public boolean notifiesOnClose() {
        return switch (this) {
            case PLUGIN_CLOSED, USER_EXIT, IDLE_TIMEOUT, MAX_DURATION,
                    TURN_LIMIT, ERROR_LIMIT, NO_PARTICIPANTS -> true;
            case PLUGIN_UNLOAD, SCOPE_REVOKED, TARGET_UNAVAILABLE, ADMIN_CLOSED -> false;
        };
    }
}
