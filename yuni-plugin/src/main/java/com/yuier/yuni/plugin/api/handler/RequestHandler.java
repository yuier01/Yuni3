package com.yuier.yuni.plugin.api.handler;

import com.yuier.yuni.plugin.api.Reply;
import com.yuier.yuni.core.event.request.YuniRequestEvent;

/**
 * 请求事件处理器。
 *
 * <p>由 {@code ctx.onRequest(...)} 注册。处理加好友、加群、被邀请入群三类请求。</p>
 *
 * <h2>示例</h2>
 * <pre>{@code
 * ctx.onRequest("friend", (ev, reply) -> {
 *     if (ev.getComment().orElse("").contains("暗号")) {
 *         reply.approve();
 *     }
 * });
 * }</pre>
 *
 * <h2>关于批准/拒绝</h2>
 * <p>{@link YuniRequestEvent#getFlag()} 是 OneBot 定位请求的唯一凭据，批准或拒绝时必须
 * 原样回传。相关 API 由机器人服务暴露（见 {@code YuniBot} 的群管理能力）。</p>
 *
 * <h2>与现行系统的差异</h2>
 * <p>现行 {@code PassivePluginMatcher.matchYuniRequestEvent} <b>既不检查插件启停也不检查
 * 权限</b>，且 {@code EventTranslator} 翻译请求事件时丢弃了 {@code sub_type}，
 * 使插件无法区分「主动加群」与「被邀请入群」。新系统两者都已修正。</p>
 *
 * @see com.yuier.yuni.plugin.api.YuniContext#onRequest
 */
@FunctionalInterface
public interface RequestHandler {

    /**
     * 处理一条请求事件。
     *
     * @param event 请求事件（只读）
     * @param reply 回复入口
     * @throws Exception 处理失败时抛出
     */
    void handle(YuniRequestEvent event, Reply reply) throws Exception;
}
