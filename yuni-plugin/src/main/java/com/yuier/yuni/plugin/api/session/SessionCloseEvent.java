package com.yuier.yuni.plugin.api.session;

import java.time.Instant;

/**
 * 会话关闭事件。
 *
 * @param sessionId        会话 ID
 * @param target           会话目标
 * @param openerUserId     发起者 QQ 号
 * @param pluginId         所属插件标识
 * @param tag              通道标签
 * @param reason           关闭原因
 * @param createdAt        创建时间
 * @param closedAt         关闭时间
 * @param turnCount        已处理回合数
 * @param participantCount 关闭时参与者数量；非部分群友为 0
 * @param droppedMessages  因队列满/关闭丢弃的消息数
 */
public record SessionCloseEvent(
        long sessionId,
        SessionTarget target,
        long openerUserId,
        String pluginId,
        String tag,
        SessionCloseReason reason,
        Instant createdAt,
        Instant closedAt,
        int turnCount,
        int participantCount,
        int droppedMessages) {
}
