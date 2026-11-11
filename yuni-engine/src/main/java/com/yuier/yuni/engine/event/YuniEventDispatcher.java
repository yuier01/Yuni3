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
import org.springframework.beans.factory.ObjectProvider;
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
 *       <td>{@code runtime.getPipeline().onMessage(event)}</td></tr>
 *   <tr><td>{@code pluginManager.handleNoticeEvent(event)}</td>
 *       <td>{@code runtime.getPipeline().onNotice(event)}</td></tr>
 *   <tr><td>{@code pluginManager.handleRequestEvent(event)}</td>
 *       <td>{@code runtime.getPipeline().onRequest(event)}</td></tr>
 *   <tr><td>{@code pluginManager.handleMetaEvent(event)}</td>
 *       <td>{@code runtime.getPipeline().onMeta(event)}</td></tr>
 *   <tr><td>{@code pluginManager.handleMessageSentEvent(event)}</td>
 *       <td>{@code runtime.getPipeline().onMessageSent(event)}</td></tr>
 * </table>
 *
 * <h2>为什么注入 {@code ObjectProvider<PluginRuntime>} 而不是运行时本身</h2>
 * <p>插件运行时<b>不是</b>容器在 refresh 期创建的 Bean：它必须等
 * {@code BotManager.startBot()} 建好机器人后，由 {@code SystemInitializeRunner}
 * 手动组装并通过 {@code registerSingleton} 交回容器。因此本 Bean 装配时
 * 运行时还不存在，只能持有一个「查找句柄」，在每次事件到达时解析。</p>
 *
 * <p>运行时为空不会丢事件——事件落库与推送照常进行，只是插件分发被跳过。
 * 这在启动早期（bot 尚未就绪）是正确行为：此时本就不该有插件在监听。</p>
 *
 * <h2>为什么拿到运行时后不缓存它</h2>
 * <p>运行时的流水线是<b>惰性构造</b>的（{@code getPipeline()} 首次访问时才建
 * 适配器与命令分发器），因为 bot 与权限服务可能更晚就绪。每次事件里调用
 * {@code getPipeline()}，让「何时构造」由首次事件到达决定——那时一切依赖必然
 * 就绪。代价是一次 volatile 读加一次空判断，相对事件处理开销可忽略。</p>
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

    /**
     * 插件运行时的查找句柄。
     *
     * <p>运行时由 {@code SystemInitializeRunner} 在 bot 就绪后手动组装并注册，
     * 因此这里注入 provider 而非实例。</p>
     */
    @Autowired
    ObjectProvider<PluginRuntime> pluginRuntimeProvider;
    @Autowired
    YuniEventSaver eventSaver;
    @Autowired
    ApplicationEventPublisher eventPublisher;

    /**
     * 若插件系统已装配完成，则执行分发动作。
     *
     * <p>启动早期（运行时尚未注册）静默跳过——此时还没有任何插件在监听。</p>
     *
     * @param action 分发动作
     */
    private void dispatchIfReady(java.util.function.Consumer<PluginRuntime> action) {
        PluginRuntime runtime = pluginRuntimeProvider.getIfAvailable();
        if (runtime == null) {
            return;
        }
        action.accept(runtime);
    }

    @EventListener
    public void messageEventHandler(YuniMessageEvent event) {
        log.info(EventLogUtil.toLog(event));
        eventSaver.saveEvent(event);
        dispatchIfReady(runtime -> runtime.getPipeline().onMessage(event));
        if (event.isGroup()) {  // TODO 为什么被写成只推送群聊消息？？？
            eventPublisher.publishEvent(YuniMessagePushEvent.from(event));
        }
    }

    @EventListener
    public void messageSentEventHandler(YuniMessageSentEvent event) {
        log.info(EventLogUtil.toLog(event));
        eventSaver.saveEvent(event);
        dispatchIfReady(runtime -> runtime.getPipeline().onMessageSent(event));
        if (event.isGroup()) {
            eventPublisher.publishEvent(YuniMessagePushEvent.from(event));
        }
    }

    @EventListener
    public void noticeEventHandler(YuniNoticeEvent event) {
        dispatchIfReady(runtime -> runtime.getPipeline().onNotice(event));
    }

    @EventListener
    public void requestEventHandler(YuniRequestEvent event) {
        log.info(event.toLogString());
        dispatchIfReady(runtime -> runtime.getPipeline().onRequest(event));
    }

    @EventListener
    public void metaEventHandler(YuniMetaEvent event) {
        dispatchIfReady(runtime -> runtime.getPipeline().onMeta(event));
    }
}
