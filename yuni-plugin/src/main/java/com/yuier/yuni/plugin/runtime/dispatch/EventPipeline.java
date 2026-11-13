package com.yuier.yuni.plugin.runtime.dispatch;

import com.yuier.yuni.core.event.YuniMessageEvent;
import com.yuier.yuni.core.event.YuniMessageSentEvent;
import com.yuier.yuni.core.event.meta.YuniMetaEvent;
import com.yuier.yuni.core.event.notice.YuniNoticeEvent;
import com.yuier.yuni.core.event.request.YuniRequestEvent;
import com.yuier.yuni.plugin.api.Reply;
import com.yuier.yuni.plugin.api.event.Event;
import com.yuier.yuni.plugin.runtime.PluginAbilityRegistry;
import com.yuier.yuni.plugin.runtime.PluginFiber;
import com.yuier.yuni.plugin.runtime.PluginRuntime;
import com.yuier.yuni.plugin.runtime.adapter.CoreLifecycleEvents;
import com.yuier.yuni.plugin.runtime.adapter.CoreMessageEvent;
import com.yuier.yuni.plugin.runtime.adapter.CoreMessageSentEvent;
import com.yuier.yuni.plugin.runtime.adapter.PluginEventAdapter;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 事件分发流水线——取代现行系统的 {@code PassivePluginMatcher}。
 *
 * <h2>取代了什么，以及为什么必须换</h2>
 * <p>现行 {@code PassivePluginMatcher}（301 行）的问题不是「写得不好」，而是
 * <b>五条事件路径各写了一遍「检查 → 匹配 → 异步执行 → 异常处理」</b>，
 * 且这五遍并不一致：</p>
 *
 * <table border="1">
 *   <caption>现行五条路径的检查差异</caption>
 *   <tr><th>路径</th><th>启停检查</th><th>权限检查</th><th>异常处理</th></tr>
 *   <tr><td>消息（命令）</td><td>有</td><td>有（含节点级递归）</td><td>{@code catch Throwable} + 回复用户</td></tr>
 *   <tr><td>消息（模式）</td><td>有（在异步体内）</td><td>有</td><td>{@code catch Exception} + 仅 printStackTrace</td></tr>
 *   <tr><td>通知</td><td>有</td><td>仅「未封禁」</td><td>{@code catch Exception} + 仅 printStackTrace</td></tr>
 *   <tr><td>请求</td><td><b>无</b></td><td><b>无</b></td><td>{@code catch Exception} + 仅 printStackTrace</td></tr>
 *   <tr><td>元</td><td><b>无</b></td><td><b>无</b></td><td>{@code catch Exception} + 仅 printStackTrace</td></tr>
 *   <tr><td>消息发出</td><td><b>无</b></td><td><b>无</b></td><td>{@code catch Exception} + 仅 printStackTrace</td></tr>
 * </table>
 *
 * <p>本类把这份差异<b>收敛为一处</b>：所有事件走同一条流水线，
 * 作用域过滤与异常兜底是<b>必经关卡</b>，任何一类事件都无法绕过。
 * 权限检查只存在于命令路径：{@link CommandDispatcher} 做节点级递归校验；
 * 通知 / 请求 / 元 / 消息发出<b>不做</b>权限检查——通知的「未封禁」也不再
 * 由框架隐式施加，见 {@link com.yuier.yuni.plugin.api.event.NoticeEvent}。</p>
 *
 * <h2>执行阶段（保持现行不变式）</h2>
 * <ol>
 *   <li><b>命令优先</b>：同步遍历命令注册项并匹配。同步是刻意的——
 *       {@code CommandResult} 必须在「是否短路模式插件」的判定之前就绪。</li>
 *   <li><b>命中命令则短路</b>：不再匹配模式插件（现行 {@code isCommand} 语义）。</li>
 *   <li><b>模式/消息</b>：仅在前一阶段未命中时执行。</li>
 * </ol>
 *
 * <h2>异步边界</h2>
 * <p>处理器在 {@code ForkJoinPool.commonPool} 上执行，不阻塞 OneBot 的
 * {@code /event} 连接线程。匹配与作用域过滤是同步的（廉价），
 * 只有处理器调用被异步化。</p>
 *
 * <h2>异常兜底</h2>
 * <p>全部处理器调用统一经 {@link #invokeSafely}，捕获 {@code Throwable}
 * （不只是 {@code Exception}）——类加载失败抛的是 {@code Error}，
 * 只 catch Exception 会让插件静默消失，这正是 2026-08 那次 P0 事故的成因之一。</p>
 *
 * @see PluginAbilityRegistry
 */
@Slf4j
@RequiredArgsConstructor
public final class EventPipeline {

    /** 能力注册总表。 */
    private final PluginAbilityRegistry registry;

    /** 运行时，用于状态检查与失败记录。 */
    private final PluginRuntime runtime;

    /** 事件适配工厂。 */
    private final PluginEventAdapter adapter;

    /** 命令匹配器（复用现有引擎，见设计文档 §6）。 */
    private final CommandDispatcher commandDispatcher;

    /* 构造器由 @RequiredArgsConstructor 生成，参数按字段声明顺序：
     * (PluginAbilityRegistry, PluginRuntime, PluginEventAdapter, CommandDispatcher)。 */

    // ==================== 公开入口（与 core 事件类型一一对应）====================

    /**
     * 处理消息事件。
     *
     * @param event 核心消息事件
     */
    public void onMessage(YuniMessageEvent event) {
        CoreMessageEvent adapted = adapter.adapt(event);
        Long groupId = adapted.getGroupId().orElse(null);
        Reply reply = adapted.getReply();

        // 阶段 1：命令（同步匹配）
        boolean commandMatched = false;
        if (commandDispatcher != null) {
            commandMatched = commandDispatcher.dispatch(event, groupId, reply);
        }
        if (commandMatched) {
            // 现行不变式：命中命令后不再匹配模式插件
            return;
        }

        // 阶段 2：消息/模式处理器
        for (PluginAbilityRegistry.Registration.Message registration :
                registry.getMessageRegistrations()) {
            if (!passesGates(registration, adapted, groupId)) {
                continue;
            }
            if (!conditionMatches(registration.owner(), registration.when(), adapted)) {
                continue;
            }
            dispatchAsync(registration.owner(), adapted,
                    () -> registration.handler().handle(adapted, reply));
        }
    }

    /**
     * 处理机器人自身发出消息的事件。
     *
     * <p><b>与现行系统的重要差异</b>：现行 {@code matchMessageSentEvent} 既不检查
     * 插件启停也不检查权限，无条件执行全部该类插件。新流程中作用域过滤是必经关卡，
     * 「无条件」需要插件显式写出（{@code ctx.onMessageSent(ev -> true, handler)}）。</p>
     *
     * @param event 核心消息发出事件
     */
    public void onMessageSent(YuniMessageSentEvent event) {
        CoreMessageSentEvent adapted = adapter.adapt(event);
        Reply reply = adapted.getReply();
        Long groupId = adapted.getGroupId().orElse(null);
        for (PluginAbilityRegistry.Registration.MessageSent registration :
                registry.getMessageSentRegistrations()) {
            if (!passesGates(registration, adapted, groupId)) {
                continue;
            }
            if (!conditionMatches(registration.owner(), registration.when(), adapted)) {
                continue;
            }
            dispatchAsync(registration.owner(), adapted,
                    () -> registration.handler().handle(adapted, reply));
        }
    }

    /**
     * 处理通知事件。
     *
     * @param event 核心通知事件
     */
    public void onNotice(YuniNoticeEvent event) {
        CoreLifecycleEvents.CoreNoticeEvent adapted = adapter.adapt(event);
        Reply reply = replyOf(adapted);
        Long groupId = adapted.getGroupId().orElse(null);
        for (PluginAbilityRegistry.Registration.Notice registration :
                registry.getNoticeRegistrations()) {
            if (!passesGates(registration, adapted, groupId)) {
                continue;
            }
            if (!conditionMatches(registration.owner(), registration.when(), adapted)) {
                continue;
            }
            dispatchAsync(registration.owner(), adapted,
                    () -> registration.handler().handle(adapted, reply));
        }
    }

    /**
     * 处理请求事件。
     *
     * @param event 核心请求事件
     */
    public void onRequest(YuniRequestEvent event) {
        CoreLifecycleEvents.CoreRequestEvent adapted = adapter.adapt(event);
        Reply reply = replyOf(adapted);
        Long groupId = adapted.getGroupId().orElse(null);
        for (PluginAbilityRegistry.Registration.Request registration :
                registry.getRequestRegistrations()) {
            if (!passesGates(registration, adapted, groupId)) {
                continue;
            }
            if (!conditionMatches(registration.owner(), registration.when(), adapted)) {
                continue;
            }
            dispatchAsync(registration.owner(), adapted,
                    () -> registration.handler().handle(adapted, reply));
        }
    }

    /**
     * 处理元事件。
     *
     * @param event 核心元事件
     */
    public void onMeta(YuniMetaEvent event) {
        CoreLifecycleEvents.CoreMetaEvent adapted = adapter.adapt(event);
        Reply reply = replyOf(adapted);
        Long groupId = adapted.getGroupId().orElse(null);
        for (PluginAbilityRegistry.Registration.Meta registration :
                registry.getMetaRegistrations()) {
            if (!passesGates(registration, adapted, groupId)) {
                continue;
            }
            if (!conditionMatches(registration.owner(), registration.when(), adapted)) {
                continue;
            }
            dispatchAsync(registration.owner(), adapted,
                    () -> registration.handler().handle(adapted, reply));
        }
    }

    // ==================== 核心分发 ====================

    /**
     * 判断注册项自带的附加条件是否匹配当前事件。
     *
     * <p>条件由插件编写，可能抛任意 {@code Throwable}；这里视为不匹配并记录，
     * 不能让一个写坏的条件把整条分发链路带崩。</p>
     *
     * @param owner 插件 Fiber，用于日志归因；可为 {@code null}
     * @param when  注册项的附加条件
     * @param event 插件侧事件
     * @param <E>   事件类型
     * @return 条件通过时返回 {@code true}
     */
    private <E extends Event> boolean conditionMatches(PluginFiber owner,
                                                       Predicate<E> when,
                                                       E event) {
        try {
            return when.test(event);
        } catch (Throwable t) {
            String pluginId = owner == null ? "?" : owner.getId();
            log.error("插件 {} 的事件条件判定抛异常，按不匹配处理", pluginId, t);
            return false;
        }
    }

    /**
     * 异步执行处理器，异常统一兜底。
     *
     * @param owner  插件 Fiber，用于日志归因；可为 {@code null}
     * @param event  插件侧事件
     * @param action 处理器调用体
     * @param <E>    事件类型
     */
    private <E extends Event> void dispatchAsync(PluginFiber owner,
                                                 E event,
                                                 ThrowingRunnable action) {
        // 异步执行：不阻塞 OneBot 的 /event 连接线程
        CompletableFuture.runAsync(() -> invokeSafely(owner, event, action));
    }

    /**
     * 前三道关卡的统一判定；附加条件由 {@link #conditionMatches} 单独处理。
     *
     * @param registration 注册项
     * @param event        插件侧事件
     * @param groupId      事件群号
     * @param <E>          事件类型
     * @return 全部通过返回 {@code true}
     */
    private <E extends Event> boolean passesGates(PluginAbilityRegistry.Registration registration,
                                                  E event,
                                                  Long groupId) {
        // 关卡 1：插件必须处于 ACTIVE。FAILED / UNLOADING / DISPOSED 的插件不再收事件。
        if (!registration.ownerServiceable()) {
            return false;
        }
        // 关卡 2：子类型过滤（注册时用 ctx.onNotice("poke", ...) 声明的）
        if (!registration.subTypeMatches(event.getSubType())) {
            return false;
        }
        // 关卡 3：作用域过滤。
        //
        // 两道判定：注册项自带的过滤器（插件作者的基线约束 / 测试显式构造）
        // + 运行时的当前作用域（用户的群粒度启停配置）。
        //
        // 后者是必需的：注册项在插件加载时就固定了，而用户随时可以在管理端
        // 改配置。此前只查注册项，导致管理端的启停完全不生效——见
        // PluginAbilityRegistry.Registration#scopeMatches 的说明。
        //
        // 顺带说明：现行系统在 request/meta/messageSent 三条路径上漏掉了
        // 这个检查，现在所有事件类型都必经此处。
        return registration.scopeMatches(groupId, runtime::effectiveScopeOf);
    }

    /**
     * 统一异常兜底。
     *
     * <p>捕获 {@code Throwable} 而非 {@code Exception}：类加载失败抛的是
     * {@code Error}，只 catch Exception 会让插件静默消失
     * （2026-08 P0 事故的成因之一）。</p>
     *
     * @param owner  插件 Fiber（用于日志归因）；可为 {@code null}
     * @param event  事件（用于日志）
     * @param action 处理器调用体
     * @param <E>    事件类型
     */
    private <E extends Event> void invokeSafely(PluginFiber owner,
                                                E event,
                                                ThrowingRunnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            String pluginId = owner == null ? "?" : owner.getId();
            log.error("插件 {} 处理事件失败: {}", pluginId, event.toLogString(), t);
            replyFailure(owner, event, t);
        }
    }

    /**
     * 可抛异常的 Runnable。
     */
    @FunctionalInterface
    private interface ThrowingRunnable {

        /**
         * 执行。
         *
         * @throws Exception 执行失败
         */
        void run() throws Exception;
    }

    /**
     * 处理器失败时向会话回报一条提示。
     *
     * <p>现行系统的命令路径会回复用户，其余路径只打日志。新设计中
     * 「失败必须可见」是统一要求，因此这里对所有可回复的事件都回一条提示。</p>
     *
     * @param owner 插件 Fiber
     * @param event 事件
     * @param cause 失败原因
     * @param <E>   事件类型
     */
    private <E extends Event> void replyFailure(PluginFiber owner, E event, Throwable cause) {
        Reply reply = replyOf(event);
        if (reply == null) {
            // 元事件等无会话目标的事件：日志已经是唯一的反馈渠道
            return;
        }
        try {
            String pluginName = owner == null ? "未知插件" : owner.getMetadata().getName();
            reply.text("执行插件 " + pluginName + " 失败（"
                            + cause.getClass().getSimpleName() + "），请联系开发人员。")
                    .send();
        } catch (Throwable t) {
            // 连失败提示都发不出去：只记日志，避免异常在此处逃逸
            log.error("回报插件失败信息时又出错", t);
        }
    }

    /**
     * 取事件的回复入口。
     *
     * <p>只有消息类事件（message / messageSent）携带 Reply；
     * 通知与请求事件在适配时未绑定 Reply（其会话目标不一定存在），
     * 因此这里返回 {@code null}。</p>
     *
     * @param event 插件侧事件
     * @param <E>   事件类型
     * @return 回复入口；无则返回 {@code null}
     */
    private <E extends Event> Reply replyOf(E event) {
        if (event instanceof CoreMessageEvent messageEvent) {
            return messageEvent.getReply();
        }
        if (event instanceof CoreMessageSentEvent sentEvent) {
            return sentEvent.getReply();
        }
        return null;
    }
}
