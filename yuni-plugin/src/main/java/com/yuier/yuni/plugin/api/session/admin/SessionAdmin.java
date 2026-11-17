package com.yuier.yuni.plugin.api.session.admin;

import com.yuier.yuni.plugin.api.session.SessionCloseReason;
import java.util.List;

/**
 * 会话管理端接口。
 *
 * <p>供 webapi / 管理类插件使用，普通插件不应依赖。可通过
 * {@code ctx.findService(SessionAdmin.class)} 获取。</p>
 */
public interface SessionAdmin {

    /**
     * 当前活跃会话快照。
     *
     * @return 会话信息列表
     */
    List<SessionInfo> list();

    /**
     * 按 ID 强制关闭会话。
     *
     * @param sessionId 会话 ID
     * @param reason    关闭原因
     * @return 找到并关闭返回 {@code true}
     */
    boolean close(long sessionId, SessionCloseReason reason);

    /**
     * 当前统计。
     *
     * @return 统计
     */
    SessionStats stats();
}
