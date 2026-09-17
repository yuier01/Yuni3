package com.yuier.yuni.engine.event;

import com.yuier.yuni.core.event.YuniMessageEvent;
import com.yuier.yuni.core.event.YuniMessageSentEvent;
import com.yuier.yuni.core.event.meta.YuniMetaEvent;
import com.yuier.yuni.core.event.notice.YuniNoticeEvent;
import com.yuier.yuni.core.event.request.YuniRequestEvent;
import com.yuier.yuni.core.event.ws.YuniMessagePushEvent;
import com.yuier.yuni.event.persistence.YuniEventSaver;
import com.yuier.yuni.event.util.EventLogUtil;
import com.yuier.yuni.plugin.runtime.PluginRuntime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 事件分发器——把 Spring 事件转交给插件系统的分发流水线。
 *
 * <h2>P6 迁移说明：本类是「新系统接管」的那一个开关</h2>
 * <p>P5 期间新旧两套插件系统并存：新的 {@code EventPipeline} 已实现完毕并有
 * 20 个单元测试覆盖，但真正在路上跑的仍然是旧的 {@code PluginManager} +
 * {@code PassivePluginMatcher}。本类就是那个把流量切过去的地方。</p>
 *
 * <table border="1">
 *   <caption>旧 → 新</caption>
 *   <tr><th>旧实现</th><th>新实现</th></tr>
 *   <tr><td>{@code pluginManager.handleMessageEvent(event)}</td>
 *       <td>{@code runtime.pipeline().onMessage(event)}</td></tr>
 *   <tr><td>{@code pluginManager.handleNoticeEvent(event)}</td>
 *       <td>{@code runtime.pipeline().onNotice(event)}</td></tr>
 *   <tr><td>{@code pluginManager.handleRequestEvent(event)}</td>
 *       <td>{@code runtime.pipeline().onRequest(event)}</td></tr>
 *   <tr><td>{@code pluginManager.handleMetaEvent(event)}</td>
 *       <td>{@code runtime.pipeline().onMeta(event)}</td></tr>
 *   <tr><td>{@code pluginManager.handleMessageSentEvent(event)}</td>
 *       <td>{@code runtime.pipeline().onMessageSent(event)}</td></tr>
 * </table>
 *
 * <h2>为什么注入 {@code PluginRuntime} 而不是 {@code EventPipeline}</h2>
 * <p>流水线是<b>惰性构造</b>的（{@code PluginRuntime.pipeline()} 在首次访问时
 * 才建适配器与命令分发器），因为它需要 bot 实例与权限服务——这两者可能晚于
 * 本 Bean 的装配时机就绪。若把 {@code EventPipeline} 声明为注入依赖，
 * Spring 会在启动早期就构造它，那时 bot 还是 {@code null}，
 * 于是「第一次事件到达时回复不了」这类问题会被固化在 Bean 装配顺序里。</p>
 *
 * <p>注入运行时并在每次事件里调 {@code pipeline()}，让「何时构造」由
 * <b>首次事件到达</b>决定——那时一切依赖必然就绪。代价是一次 volatile 读
 * 加一次 {@code null} 判断，相对于事件处理的整体开销可以忽略。</p>
 *
 * <h2>保留未动的部分</h2>
 * <p>事件落库（{@code eventSaver}）、日志、以及向管理端推送的
 * {@code YuniMessagePushEvent} 三件事与插件系统无关，原样保留。
 * 其中「只推送群聊消息」那个 {@code TODO} 也原样保留——它属于另一个议题，
 * 不应当被这次重构顺手改掉（改了就无法从本次变更中区分出它的影响）。</p>
 */
@Component
@Slf4j
public class YuniEventDispatcher {

    @Autowired
    PluginRuntime pluginRuntime;
    @Autowired
    YuniEventSaver eventSaver;
    @Autowired
    ApplicationEventPublisher eventPublisher;

    @EventListener
    public void messageEventHandler(YuniMessageEvent event) {
        log.info(EventLogUtil.toLog(event));
        eventSaver.saveEvent(event);
        pluginRuntime.pipeline().onMessage(event);
        if (event.isGroup()) {  // TODO 为什么被写成只推送群聊消息？？？
            eventPublisher.publishEvent(YuniMessagePushEvent.from(event));
        }
    }

    @EventListener
    public void messageSentEventHandler(YuniMessageSentEvent event) {
        log.info(EventLogUtil.toLog(event));
        eventSaver.saveEvent(event);
        pluginRuntime.pipeline().onMessageSent(event);
        if (event.isGroup()) {
            eventPublisher.publishEvent(YuniMessagePushEvent.from(event));
        }
    }

    @EventListener
    public void noticeEventHandler(YuniNoticeEvent event) {
        pluginRuntime.pipeline().onNotice(event);
    }

    @EventListener
    public void requestEventHandler(YuniRequestEvent event) {
        log.info(event.toLogString());
        pluginRuntime.pipeline().onRequest(event);
    }

    @EventListener
    public void metaEventHandler(YuniMetaEvent event) {
        pluginRuntime.pipeline().onMeta(event);
    }
}
