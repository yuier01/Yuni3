package com.yuier.yuni.plugin.api.session;

/**
 * 会话目标/加入指令/用户归属冲突。
 */
public final class SessionConflictException extends SessionException {

    private final SessionTarget target;
    private final String ownerPluginId;
    private final long ownerSessionId;

    /**
     * 构造冲突。
     *
     * @param target         目标
     * @param ownerPluginId  已有会话的插件标识
     * @param ownerSessionId 已有会话 ID
     */
    public SessionConflictException(SessionTarget target,
                                    String ownerPluginId,
                                    long ownerSessionId) {
        super("会话冲突: " + (target == null ? "?" : target.displayName())
                + " 已被插件 " + ownerPluginId + " 的会话 " + ownerSessionId + " 占用");
        this.target = target;
        this.ownerPluginId = ownerPluginId;
        this.ownerSessionId = ownerSessionId;
    }

    /**
     * 构造带说明的冲突。
     *
     * @param message        说明
     * @param target         目标
     * @param ownerPluginId  已有会话的插件标识
     * @param ownerSessionId 已有会话 ID
     */
    public SessionConflictException(String message,
                                    SessionTarget target,
                                    String ownerPluginId,
                                    long ownerSessionId) {
        super(message);
        this.target = target;
        this.ownerPluginId = ownerPluginId;
        this.ownerSessionId = ownerSessionId;
    }

    public SessionTarget getTarget() {
        return target;
    }

    public String getOwnerPluginId() {
        return ownerPluginId;
    }

    public long getOwnerSessionId() {
        return ownerSessionId;
    }
}
