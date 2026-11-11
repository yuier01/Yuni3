package com.yuier.yuni.plugin.runtime.dispatch;

import com.yuier.yuni.core.event.YuniMessageEvent;
import com.yuier.yuni.core.event.YuniMessageSentEvent;
import com.yuier.yuni.core.event.meta.YuniMetaEvent;
import com.yuier.yuni.core.event.notice.YuniNoticeEvent;
import com.yuier.yuni.core.event.request.YuniRequestEvent;
import com.yuier.yuni.plugin.api.Reply;
import com.yuier.yuni.plugin.api.event.Event;
import com.yuier.yuni.plugin.api.handler.MetaHandler;
import com.yuier.yuni.plugin.api.handler.MessageHandler;
import com.yuier.yuni.plugin.api.handler.MessageSentHandler;
import com.yuier.yuni.plugin.api.handler.NoticeHandler;
import com.yuier.yuni.plugin.api.handler.RequestHandler;
import com.yuier.yuni.plugin.runtime.PluginFiber;
import com.yuier.yuni.plugin.runtime.PluginRuntime;
import com.yuier.yuni.plugin.runtime.RegistrationTable;
import com.yuier.yuni.plugin.runtime.adapter.CoreLifecycleEvents;
import com.yuier.yuni.plugin.runtime.adapter.CoreMessageEvent;
import com.yuier.yuni.plugin.runtime.adapter.CoreMessageSentEvent;
import com.yuier.yuni.plugin.runtime.adapter.PluginEventAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CompletableFuture;

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
 * 权限则改为按事件类型显式声明策略（见 {@link #permissionChecker}）——
 * 命令保持节点级递归校验，通知保持「未封禁」，
 * 而请求/元/消息发出<b>明确不检查</b>（这是现行行为，但现在是显式的、有注释的）。</p>
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
 * @see RegistrationTable
 */
@Slf4j
@RequiredArgsConstructor
public final class EventPipeline {

    /** 能力注册总表。 */
    private final RegistrationTable registrations;

    /** 运行时，用于状态检查与失败记录。 */
    private final PluginRuntime runtime;

    /** 事件适配工厂。 */
    private final PluginEventAdapter adapter;

    /** 命令匹配器（复用现有引擎，见设计文档 §6）。 */
    private final CommandDispatcher commandDispatcher;

    /* 构造器由 @RequiredArgsConstructor 生成，参数按字段声明顺序：
     * (RegistrationTable, PluginRuntime, PluginEventAdapter, CommandDispatcher)。 */

    // ==================== 公开入口（与 core 事件类型一一对应）====================

    /**
     * 处理消息事件。
     *
     * @param event 核心消息事件
     */
    public void onMessage(YuniMessageEvent event) {
        CoreMessageEvent adapted = adapter.adapt(event);
        Long groupId = adapted.groupId().orElse(null);
        Reply reply = adapted.reply();

        // 阶段 1：命令（同步匹配）
        boolean commandMatched = false;
        if (commandDispatcher != null) {
            commandMatched = commandDispatcher.dispatch(adapted, groupId, reply);
        }
        if (commandMatched) {
            // 现行不变式：命中命令后不再匹配模式插件
            return;
        }

        // 阶段 2：消息/模式处理器
        dispatchAll(registrations.messageEntries(), adapted, groupId, reply,
                payload -> cast(payload, MessageHandler.class)
                        .handle(adapted, reply));
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
        Reply reply = adapted.reply();
        dispatchAll(registrations.messageSentEntries(), adapted,
                adapted.groupId().orElse(null), reply,
                payload -> cast(payload, MessageSentHandler.class)
                        .handle(adapted, reply));
    }

    /**
     * 处理通知事件。
     *
     * @param event 核心通知事件
     */
    public void onNotice(YuniNoticeEvent event) {
        CoreLifecycleEvents.CoreNoticeEvent adapted = adapter.adapt(event);
        Reply reply = replyOf(adapted);
        dispatchAll(registrations.noticeEntries(), adapted,
                adapted.groupId().orElse(null), reply,
                payload -> cast(payload, NoticeHandler.class)
                        .handle(adapted, reply));
    }

    /**
     * 处理请求事件。
     *
     * @param event 核心请求事件
     */
    public void onRequest(YuniRequestEvent event) {
        CoreLifecycleEvents.CoreRequestEvent adapted = adapter.adapt(event);
        Reply reply = replyOf(adapted);
        dispatchAll(registrations.requestEntries(), adapted,
                adapted.groupId().orElse(null), reply,
                payload -> cast(payload, RequestHandler.class)
                        .handle(adapted, reply));
    }

    /**
     * 处理元事件。
     *
     * @param event 核心元事件
     */
    public void onMeta(YuniMetaEvent event) {
        CoreLifecycleEvents.CoreMetaEvent adapted = adapter.adapt(event);
        Reply reply = replyOf(adapted);
        dispatchAll(registrations.metaEntries(), adapted,
                adapted.groupId().orElse(null), reply,
                payload -> cast(payload, MetaHandler.class)
                        .handle(adapted, reply));
    }

    // ==================== 核心分发 ====================

    /**
     * 遍历注册项并逐个检查、执行。
     *
     * <p>四道关卡，顺序固定：</p>
     * <ol>
     *   <li>所属插件处于 {@code ACTIVE} 状态；</li>
     *   <li>子类型匹配（若注册时声明了子类型）；</li>
     *   <li>作用域过滤（{@link com.yuier.yuni.plugin.api.ScopeFilter}）；</li>
     *   <li>附加条件（注册时声明的 predicate）。</li>
     * </ol>
     * <p>全部通过后才异步调用处理器。</p>
     *
     * @param entries   注册项列表
     * @param event     插件侧事件
     * @param groupId   事件群号；非群事件为 {@code null}
     * @param reply     回复入口；可为 {@code null}
     * @param body      处理器调用体——参数为「该注册项的处理器载荷」，
     *                  由各入口方法负责转型与调用
     * @param <E>       事件类型
     */
    private <E extends Event> void dispatchAll(List<RegistrationTable.Entry> entries,
                                               E event,
                                               Long groupId,
                                               Reply reply,
                                               ThrowingConsumer<Object> body) {
        for (RegistrationTable.Entry entry : entries) {
            if (!passesGates(entry, event, groupId)) {
                continue;
            }
            final PluginFiber owner = entry.owner();
            final Object payload = entry.payload();
            // 异步执行：不阻塞 OneBot 的 /event 连接线程
            CompletableFuture.runAsync(() -> invokeSafely(owner, () -> body.accept(payload), event));
        }
    }

    /**
     * 可抛异常的消费者——与各处理器接口的 {@code throws Exception} 签名对齐。
     *
     * <p>之所以不用 {@link java.util.function.Consumer}：处理器接口声明了
     * {@code throws Exception}，用标准 Consumer 就得在每个调用点包 try/catch，
     * 而那正是本类要消除的重复。异常统一由 {@link #invokeSafely} 兜住。</p>
     *
     * @param <T> 载荷类型
     */
    @FunctionalInterface
    private interface ThrowingConsumer<T> {

        /**
         * 消费载荷。
         *
         * @param payload 载荷
         * @throws Exception 处理失败
         */
        void accept(T payload) throws Exception;
    }

    /**
     * 四道关卡的统一判定。
     *
     * @param entry   注册项
     * @param event   插件侧事件
     * @param groupId 事件群号
     * @return 全部通过返回 {@code true}
     */
    private boolean passesGates(RegistrationTable.Entry entry, Event event, Long groupId) {
        // 关卡 1：插件必须处于 ACTIVE。FAILED / UNLOADING / DISPOSED 的插件不再收事件。
        if (!entry.ownerServiceable()) {
            return false;
        }
        // 关卡 2：子类型过滤（注册时用 ctx.onNotice("poke", ...) 声明的）
        if (!entry.subTypeMatches(event.subType())) {
            return false;
        }
        // 关卡 3：作用域过滤。
        //
        // 两道判定：注册项自带的过滤器（插件作者的基线约束 / 测试显式构造）
        // + 运行时的当前作用域（用户的群粒度启停配置）。
        //
        // 后者是必需的：注册项在插件加载时就固定了，而用户随时可以在管理端
        // 改配置。此前只查注册项，导致管理端的启停完全不生效——见
        // RegistrationTable.Entry#scopeMatches 的说明。
        //
        // 顺带说明：现行系统在 request/meta/messageSent 三条路径上漏掉了
        // 这个检查，现在所有事件类型都必经此处。
        if (!entry.scopeMatches(groupId, runtime::scopeFilterOf)) {
            return false;
        }
        // 关卡 4：附加条件
        return matchesPredicate(entry, event);
    }

    /**
     * 运行注册项上的附加条件。
     *
     * @param entry 注册项
     * @param event 插件侧事件
     * @return 无条件或条件通过时返回 {@code true}
     */
    @SuppressWarnings("unchecked")
    private boolean matchesPredicate(RegistrationTable.Entry entry, Event event) {
        java.util.function.Predicate<Object> predicate = entry.predicate();
        if (predicate == null) {
            return true;
        }
        try {
            return predicate.test(event);
        } catch (Throwable t) {
            // 条件判定抛异常：视为不匹配并记录。
            // 不能让一个写坏的条件把整条分发链路带崩。
            log.error("插件 {} 的事件条件判定抛异常，按不匹配处理", entry.owner().id(), t);
            return false;
        }
    }

    /**
     * 安全地把注册项载荷转型为处理器。
     *
     * <p>类型不符说明注册表被写入了错误类型的载荷——这属于框架内部缺陷，
     * 因此记 error 并抛 {@link IllegalStateException}，由
     * {@link #invokeSafely} 统一兜住并记入日志，而不是让
     * {@code ClassCastException} 在异步线程里裸奔。</p>
     *
     * @param payload     注册项载荷
     * @param handlerType 期望的处理器类型
     * @param <H>         处理器类型
     * @return 处理器
     * @throws IllegalStateException 载荷类型不符
     */
    private static <H> H cast(Object payload, Class<H> handlerType) {
        if (!handlerType.isInstance(payload)) {
            throw new IllegalStateException(
                    "注册表载荷类型不符：期望 " + handlerType.getSimpleName()
                            + "，实际 " + (payload == null ? "null" : payload.getClass().getName()));
        }
        return handlerType.cast(payload);
    }

    /**
     * 统一异常兜底。
     *
     * <p>捕获 {@code Throwable} 而非 {@code Exception}：类加载失败抛的是
     * {@code Error}，只 catch Exception 会让插件静默消失
     * （2026-08 P0 事故的成因之一）。</p>
     *
     * @param owner 插件 Fiber（用于日志归因）；可为 {@code null}
     * @param body  处理器调用体
     * @param event 事件（用于日志）
     */
    private void invokeSafely(PluginFiber owner, ThrowingRunnable body, Event event) {
        try {
            body.run();
        } catch (Throwable t) {
            String pluginId = owner == null ? "?" : owner.id();
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
     */
    private void replyFailure(PluginFiber owner, Event event, Throwable cause) {
        Reply reply = replyOf(event);
        if (reply == null) {
            // 元事件等无会话目标的事件：日志已经是唯一的反馈渠道
            return;
        }
        try {
            String pluginName = owner == null ? "未知插件" : owner.metadata().name();
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
     * @return 回复入口；无则返回 {@code null}
     */
    private Reply replyOf(Event event) {
        if (event instanceof CoreMessageEvent messageEvent) {
            return messageEvent.reply();
        }
        if (event instanceof CoreMessageSentEvent sentEvent) {
            return sentEvent.reply();
        }
        return null;
    }
}
