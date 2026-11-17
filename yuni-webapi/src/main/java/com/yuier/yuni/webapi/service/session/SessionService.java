package com.yuier.yuni.webapi.service.session;

import com.yuier.yuni.plugin.api.session.SessionCloseReason;
import com.yuier.yuni.plugin.api.session.admin.SessionAdmin;
import com.yuier.yuni.plugin.api.session.admin.SessionInfo;
import com.yuier.yuni.plugin.api.session.admin.SessionStats;
import com.yuier.yuni.plugin.runtime.PluginSystem;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 会话管理端服务——把 {@link SessionAdmin} 翻译成 HTTP 响应。
 */
@Service
@RequiredArgsConstructor
public class SessionService {

    private final PluginSystem pluginSystem;

    /**
     * 会话列表。
     *
     * @return 会话信息
     */
    public List<SessionInfo> list() {
        return admin().list();
    }

    /**
     * 会话统计。
     *
     * @return 统计
     */
    public SessionStats stats() {
        return admin().stats();
    }

    /**
     * 强制关闭会话。
     *
     * @param sessionId 会话 ID
     * @param reason    原因；为空时使用 ADMIN_CLOSED
     * @return 是否找到并关闭
     */
    public boolean close(long sessionId, SessionCloseReason reason) {
        return admin().close(sessionId,
                reason == null ? SessionCloseReason.ADMIN_CLOSED : reason);
    }

    private SessionAdmin admin() {
        return pluginSystem.getRuntime().getSessionAdmin();
    }
}
