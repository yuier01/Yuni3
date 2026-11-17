package com.yuier.yuni.webapi.dto.session;

import com.yuier.yuni.plugin.api.session.SessionCloseReason;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 强制关闭会话请求。
 */
@Data
@NoArgsConstructor
public class SessionCloseReq {

    /** 会话 ID。 */
    private Long sessionId;

    /** 关闭原因；为空时使用 ADMIN_CLOSED。 */
    private SessionCloseReason reason;
}
