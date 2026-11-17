package com.yuier.yuni.plugin.api.session;

/**
 * 会话目标——通道捕获哪一类消息。
 *
 * <p>v0.4 只有三种目标：部分群友、群内单个成员、私聊。没有“整个群”：
 * 任何插件都不能一次性把整个群纳入通道。</p>
 *
 * <p>{@code GROUP_PARTICIPANTS} 必须带一个 {@code channelKey}，用于在同一个群里
 * 区分多个并行通道（例如“猜数字”和“轮盘赌”各有一个只捕获各自参与者的通道）。</p>
 *
 * @param type       目标类型
 * @param groupId    群号；私聊为 {@code null}
 * @param userId     用户 QQ 号；部分群友为 {@code null}
 * @param channelKey 部分群友通道的唯一键；其他类型为 {@code null}
 */
public record SessionTarget(Type type, Long groupId, Long userId, String channelKey) {

    /**
     * 目标类型。
     */
    public enum Type {
        /** 部分群友：只有当前参与者的消息进入；非参与者可用加入指令。 */
        GROUP_PARTICIPANTS,
        /** 群内单个成员：只有该成员在该群的消息进入。 */
        GROUP_MEMBER,
        /** 私聊：只有该用户的私聊消息进入。 */
        PRIVATE
    }

    /**
     * 归一化并校验。
     */
    public SessionTarget {
        if (type == null) {
            throw new IllegalArgumentException("会话目标类型不能为 null");
        }
        switch (type) {
            case GROUP_PARTICIPANTS -> {
                if (groupId == null || groupId <= 0) {
                    throw new IllegalArgumentException("部分群友目标必须提供有效群号");
                }
                if (userId != null) {
                    throw new IllegalArgumentException("部分群友目标不能指定单个用户");
                }
                if (channelKey == null || channelKey.isBlank()) {
                    throw new IllegalArgumentException("部分群友目标必须提供非空 channelKey");
                }
                channelKey = channelKey.strip();
                if (channelKey.length() > 64) {
                    throw new IllegalArgumentException("channelKey 最长 64 个字符");
                }
            }
            case GROUP_MEMBER -> {
                if (groupId == null || groupId <= 0) {
                    throw new IllegalArgumentException("群成员目标必须提供有效群号");
                }
                if (userId == null || userId <= 0) {
                    throw new IllegalArgumentException("群成员目标必须提供有效用户号");
                }
                if (channelKey != null) {
                    throw new IllegalArgumentException("群成员目标不能带 channelKey");
                }
            }
            case PRIVATE -> {
                if (groupId != null) {
                    throw new IllegalArgumentException("私聊目标不能带群号");
                }
                if (userId == null || userId <= 0) {
                    throw new IllegalArgumentException("私聊目标必须提供有效用户号");
                }
                if (channelKey != null) {
                    throw new IllegalArgumentException("私聊目标不能带 channelKey");
                }
            }
        }
    }

    /**
     * 部分群友目标。
     *
     * @param groupId    群号
     * @param channelKey 通道唯一键
     * @return 目标
     */
    public static SessionTarget groupParticipants(long groupId, String channelKey) {
        return new SessionTarget(Type.GROUP_PARTICIPANTS, groupId, null, channelKey);
    }

    /**
     * 群内单个成员目标。
     *
     * @param groupId 群号
     * @param userId  用户 QQ 号
     * @return 目标
     */
    public static SessionTarget groupMember(long groupId, long userId) {
        return new SessionTarget(Type.GROUP_MEMBER, groupId, userId, null);
    }

    /**
     * 私聊目标。
     *
     * @param userId 用户 QQ 号
     * @return 目标
     */
    public static SessionTarget privateChat(long userId) {
        return new SessionTarget(Type.PRIVATE, null, userId, null);
    }

    /**
     * 是否部分群友目标。
     *
     * @return 是则返回 {@code true}
     */
    public boolean isGroupParticipants() {
        return type == Type.GROUP_PARTICIPANTS;
    }

    /**
     * 是否群内目标（部分群友或单个成员）。
     *
     * @return 是则返回 {@code true}
     */
    public boolean isGroupScoped() {
        return type == Type.GROUP_PARTICIPANTS || type == Type.GROUP_MEMBER;
    }

    /**
     * 日志/异常中的可读标识。
     *
     * @return 形如 {@code group-participants:123#guess} 的字符串
     */
    public String displayName() {
        return switch (type) {
            case GROUP_PARTICIPANTS -> "group-participants:" + groupId + "#" + channelKey;
            case GROUP_MEMBER -> "group-member:" + groupId + "/" + userId;
            case PRIVATE -> "private:" + userId;
        };
    }
}
