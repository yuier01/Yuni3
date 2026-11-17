package com.yuier.yuni.plugin.api.session.admin;

import com.yuier.yuni.plugin.api.session.SessionTarget;
import java.time.Instant;

/**
 * 会话管理端视图——只包含元数据，不包含插件业务状态。
 *
 * @param id              会话 ID
 * @param target          会话目标
 * @param pluginId        所属插件
 * @param tag             通道标签
 * @param openerUserId    发起者
 * @param createdAt       创建时间
 * @param lastActiveAt    最后活跃时间
 * @param expiresAt       绝对过期时间
 * @param turnCount       已处理回合数
 * @param participantCount 参与者数量；非部分群友为 0
 * @param pendingMessages 排队消息数
 * @param active          是否可接收消息
 */
public record SessionInfo(
        long id,
        SessionTarget target,
        String pluginId,
        String tag,
        long openerUserId,
        Instant createdAt,
        Instant lastActiveAt,
        Instant expiresAt,
        int turnCount,
        int participantCount,
        int pendingMessages,
        boolean active) {
}
