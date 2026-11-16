package com.yuier.yuni.plugin.runtime.dispatch;

import com.yuier.yuni.core.event.YuniEvent;
import com.yuier.yuni.core.event.YuniMessageEvent;
import com.yuier.yuni.core.event.YuniMessageSentEvent;
import com.yuier.yuni.core.event.meta.YuniMetaEvent;
import com.yuier.yuni.core.event.notice.YuniNoticeEvent;
import com.yuier.yuni.core.event.request.YuniRequestEvent;
import com.yuier.yuni.plugin.api.Reply;
import com.yuier.yuni.plugin.runtime.PluginAbilityRegistry;
import com.yuier.yuni.plugin.runtime.PluginFiber;
import com.yuier.yuni.plugin.runtime.PluginRuntime;
import com.yuier.yuni.plugin.runtime.adapter.ReplyFactory;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 事件分发流水线。
 *
 * <p>所有事件使用 core 事件模型，不再维护插件侧事件视图。分发流程固定为：
 * 作用域过滤 → 附加条件 → 异步调用处理器；异常统一兜底。</p>
 *
 * <h2>命令优先</h2>
 * <p>消息事件先走命令匹配；命中后不再执行消息处理器。命令匹配直接使用
 * 原始 core 事件，不做任何事件适配。</p>
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

    /** 回复入口工厂。 */
    private final ReplyFactory replyFactory;

    /** 命令匹配器。 */
    private final CommandDispatcher commandDispatcher;

    // ==================== 公开入口 ====================

    /**
     * 处理消息事件。
     *
     * @param event 核心消息事件
     */
    public void onMessage(YuniMessageEvent event) {
        Long groupId = event.getGroupId();
        Reply reply = replyFactory.forMessage(event);

        // 阶段 1：命令（同步匹配）——直接使用原始 core 事件
        if (commandDispatcher != null && commandDispatcher.dispatch(event, groupId, reply)) {
            // 命中命令后不再匹配模式插件
            return;
        }

        // 阶段 2：消息/模式处理器
        for (PluginAbilityRegistry.Registration.Message registration :
                registry.getMessageRegistrations()) {
            if (!passesGates(registration, event.getSubType(), groupId)) {
                continue;
            }
            if (!conditionMatches(registration.owner(), registration.when(), event)) {
                continue;
            }
            dispatchAsync(registration.owner(), event.toLogString(), reply,
                    () -> registration.handler().handle(event, reply));
        }
    }

    /**
     * 处理机器人自身发出消息的事件。
     *
     * @param event 核心消息发出事件
     */
    public void onMessageSent(YuniMessageSentEvent event) {
        Long groupId = event.getGroupId();
        Reply reply = replyFactory.forMessageSent(event);
        for (PluginAbilityRegistry.Registration.MessageSent registration :
                registry.getMessageSentRegistrations()) {
            if (!passesGates(registration, event.getSubType(), groupId)) {
                continue;
            }
            if (!conditionMatches(registration.owner(), registration.when(), event)) {
                continue;
            }
            dispatchAsync(registration.owner(), event.toLogString(), reply,
                    () -> registration.handler().handle(event, reply));
        }
    }

    /**
     * 处理通知事件。
     *
     * @param event 核心通知事件
     */
    public void onNotice(YuniNoticeEvent event) {
        Long groupId = event.getGroupId();
        Reply reply = replyFactory.forNotice(event);
        for (PluginAbilityRegistry.Registration.Notice registration :
                registry.getNoticeRegistrations()) {
            if (!passesGates(registration, event.getSubType(), groupId)) {
                continue;
            }
            if (!conditionMatches(registration.owner(), registration.when(), event)) {
                continue;
            }
            dispatchAsync(registration.owner(), event.toLogString(), reply,
                    () -> registration.handler().handle(event, reply));
        }
    }

    /**
     * 处理请求事件。
     *
     * @param event 核心请求事件
     */
    public void onRequest(YuniRequestEvent event) {
        Long groupId = event.getGroupId();
        Reply reply = replyFactory.forRequest(event);
        for (PluginAbilityRegistry.Registration.Request registration :
                registry.getRequestRegistrations()) {
            if (!passesGates(registration, event.getSubType(), groupId)) {
                continue;
            }
            if (!conditionMatches(registration.owner(), registration.when(), event)) {
                continue;
            }
            dispatchAsync(registration.owner(), event.toLogString(), reply,
                    () -> registration.handler().handle(event, reply));
        }
    }

    /**
     * 处理元事件。
     *
     * @param event 核心元事件
     */
    public void onMeta(YuniMetaEvent event) {
        Long groupId = null;
        Reply reply = replyFactory.forMeta(event);
        for (PluginAbilityRegistry.Registration.Meta registration :
                registry.getMetaRegistrations()) {
            if (!passesGates(registration, event.getSubType(), groupId)) {
                continue;
            }
            if (!conditionMatches(registration.owner(), registration.when(), event)) {
                continue;
            }
            dispatchAsync(registration.owner(), event.toLogString(), reply,
                    () -> registration.handler().handle(event, reply));
        }
    }

    // ==================== 核心分发 ====================

    /**
     * 前三道关卡的统一判定：插件状态、子类型、作用域。
     *
     * @param registration 注册项
     * @param subType      事件子类型
     * @param groupId      事件群号
     * @return 全部通过返回 {@code true}
     */
    private boolean passesGates(PluginAbilityRegistry.Registration registration,
                                String subType,
                                Long groupId) {
        if (!registration.ownerServiceable()) {
            return false;
        }
        if (!registration.subTypeMatches(subType)) {
            return false;
        }
        return registration.scopeMatches(groupId, runtime::effectiveScopeOf);
    }

    /**
     * 判断注册项的附加条件是否匹配当前事件。
     *
     * @param owner 插件 Fiber，用于日志归因；可为 {@code null}
     * @param when  注册项的附加条件
     * @param event 核心事件
     * @param <E>   事件类型
     * @return 条件通过时返回 {@code true}
     */
    private <E extends YuniEvent> boolean conditionMatches(PluginFiber owner,
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
     * @param logTag 日志中的事件标识
     * @param reply  回复入口；可为 {@code null}
     * @param action 处理器调用体
     */
    private void dispatchAsync(PluginFiber owner,
                               String logTag,
                               Reply reply,
                               ThrowingRunnable action) {
        if (owner != null) {
            log.info("匹配到插件: {}", owner.getMetadata().getName());
        }
        CompletableFuture.runAsync(() -> invokeSafely(owner, logTag, reply, action));
    }

    /**
     * 统一异常兜底。
     *
     * @param owner  插件 Fiber；可为 {@code null}
     * @param logTag 日志中的事件标识
     * @param reply  回复入口；可为 {@code null}
     * @param action 处理器调用体
     */
    private void invokeSafely(PluginFiber owner,
                              String logTag,
                              Reply reply,
                              ThrowingRunnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            String pluginId = owner == null ? "?" : owner.getId();
            log.error("插件 {} 处理事件失败: {}", pluginId, logTag, t);
            replyFailure(owner, reply, t);
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
     * @param owner 插件 Fiber
     * @param reply 回复入口；{@code null} 表示该事件没有会话目标
     * @param cause 失败原因
     */
    private void replyFailure(PluginFiber owner, Reply reply, Throwable cause) {
        if (reply == null) {
            return;
        }
        try {
            String pluginName = owner == null ? "未知插件" : owner.getMetadata().getName();
            reply.text("执行插件 " + pluginName + " 失败（"
                            + cause.getClass().getSimpleName() + "），请联系开发人员。")
                    .send();
        } catch (Throwable t) {
            log.error("回报插件失败信息时又出错", t);
        }
    }
}
