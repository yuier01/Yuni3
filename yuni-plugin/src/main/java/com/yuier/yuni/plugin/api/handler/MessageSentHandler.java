package com.yuier.yuni.plugin.api.handler;

import com.yuier.yuni.plugin.api.Reply;
import com.yuier.yuni.plugin.api.event.MessageSentEvent;

/**
 * 消息发出事件处理器。
 *
 * <p>由 {@code ctx.onMessageSent(...)} 注册。事件描述的是<b>机器人自己已经发出</b>
 * 的消息，因此处理器的典型用途是「同步给外部系统」或「审计」，而不是回复。</p>
 *
 * <h2>示例</h2>
 * <pre>{@code
 * // 把机器人发出的群消息转发给 maimai
 * ctx.onMessageSent(ev -> ev.isGroup(), (ev, reply) -> maiMai.push(ev.getRawJson()));
 * }</pre>
 *
 * <h2>与现行系统的重要差异</h2>
 * <p>现行 {@code MessageSentPlugin} 无条件执行，不检查启停与权限。新系统中本类
 * 处理器与其他事件走同一流程，{@code ScopeFilter} 与权限检查都是必经关卡。</p>
 *
 * @see com.yuier.yuni.plugin.api.YuniContext#onMessageSent
 */
@FunctionalInterface
public interface MessageSentHandler {

    /**
     * 处理一条「机器人自身发出消息」的事件。
     *
     * @param event 事件（只读）
     * @param reply 回复入口；注意再次发送会产生新消息
     * @throws Exception 处理失败时抛出
     */
    void handle(MessageSentEvent event, Reply reply) throws Exception;
}
