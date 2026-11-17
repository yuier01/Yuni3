package com.yuier.yuni.webapi.controller.session;

import com.yuier.yuni.webapi.dto.Result;
import com.yuier.yuni.webapi.dto.session.SessionCloseReq;
import com.yuier.yuni.webapi.service.session.SessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话管理接口。
 */
@RestController
@RequestMapping("/admin/session")
@RequiredArgsConstructor
public class AdminSessionController {

    private final SessionService sessionService;

    /**
     * 会话列表。
     *
     * @return 会话列表
     */
    @PostMapping("/list")
    public Result<?> list() {
        return Result.ok(sessionService.list());
    }

    /**
     * 会话统计。
     *
     * @return 统计
     */
    @PostMapping("/stats")
    public Result<?> stats() {
        return Result.ok(sessionService.stats());
    }

    /**
     * 强制关闭会话。
     *
     * @param req 请求
     * @return 操作结果
     */
    @PostMapping("/close")
    public Result<Void> close(@RequestBody SessionCloseReq req) {
        if (req == null || req.getSessionId() == null || req.getSessionId() <= 0) {
            return Result.fail(400, "sessionId 不能为空");
        }
        boolean closed = sessionService.close(req.getSessionId(), req.getReason());
        if (!closed) {
            return Result.fail(404, "会话不存在或已关闭");
        }
        return Result.ok(null, "会话已关闭");
    }
}
