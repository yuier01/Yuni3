package com.yuier.yuni.plugin.api.session;

import com.yuier.yuni.core.event.YuniMessageEvent;
import com.yuier.yuni.plugin.api.Reply;

/**
 * 会话消息处理器。
 *
 * <p>在会话串行邮箱中执行：同一会话不会并发调用，不同会话可以并行。</p>
 *
 * <p><b>消息一旦落入通道就被消费</b>，没有“交还常规分发”的选项。
 * 要恢复其他功能，只能由插件结束通道；如果插件启用了退出指令，用户也可以退出。</p>
 */
@FunctionalInterface
public interface SessionHandler {

    /**
     * 处理通道中的一条消息。
     *
     * @param session 会话上下文
     * @param event   消息事件
     * @param reply   绑定到该消息的回复入口；可能为 {@code null}
     * @return 处理动作；返回 {@code null} 视为 {@link SessionAction#CONSUMED}
     * @throws Exception 处理失败；由框架统一兜底
     */
    SessionAction handle(SessionContext session,
                         YuniMessageEvent event,
                         Reply reply) throws Exception;
}
