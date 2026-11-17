package com.yuier.yuni.plugin.api.session.admin;

/**
 * 会话统计。
 *
 * @param activeSessions        当前活跃会话数
 * @param activePlugins         当前有活跃会话的插件数
 * @param activeParticipants    当前参与者总数
 * @param totalOpened           累计打开会话数
 * @param totalClosed           累计关闭会话数
 * @param totalTimeouts         累计超时关闭数
 * @param totalErrors           累计错误关闭数
 * @param totalDroppedMessages  累计丢弃消息数
 */
public record SessionStats(
        int activeSessions,
        int activePlugins,
        int activeParticipants,
        long totalOpened,
        long totalClosed,
        long totalTimeouts,
        long totalErrors,
        long totalDroppedMessages) {
}
