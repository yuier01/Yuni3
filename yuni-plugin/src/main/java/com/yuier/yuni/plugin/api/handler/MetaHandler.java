package com.yuier.yuni.plugin.api.handler;

import com.yuier.yuni.plugin.api.Reply;
import com.yuier.yuni.plugin.api.event.MetaEvent;

/**
 * 元事件处理器。
 *
 * <p>由 {@code ctx.onMeta(...)} 注册。处理心跳与生命周期两类<b>连接层</b>事件。</p>
 *
 * <h2>示例</h2>
 * <pre>{@code
 * ctx.onMeta("heartbeat", (ev, reply) ->
 *         log.debug("心跳: online={}, interval={}ms",
 *                   ev.isStatusOnline(), ev.getHeartbeatInterval().orElse(0L)));
 * }</pre>
 *
 * <h2>注意</h2>
 * <p>心跳频率较高（默认 30 秒一次），处理器内不要做重活。若要基于心跳做
 * 「连接中断检测」，应使用 {@code ctx.schedule(...)} 做定时判定，
 * 而不是在处理器里累计状态。</p>
 *
 * <h2>与现行系统的差异</h2>
 * <p>现行 {@code EventTranslator} 丢弃了心跳事件的 {@code status} 与 {@code interval}
 * 字段，导致 {@code HeartBeatEvent} 插件实际拿不到间隔。新系统保留这两个字段。</p>
 *
 * @see com.yuier.yuni.plugin.api.YuniContext#onMeta
 */
@FunctionalInterface
public interface MetaHandler {

    /**
     * 处理一条元事件。
     *
     * @param event 元事件（只读）
     * @param reply 回复入口（元事件一般不需要回复）
     * @throws Exception 处理失败时抛出
     */
    void handle(MetaEvent event, Reply reply) throws Exception;
}
