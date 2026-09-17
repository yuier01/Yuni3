package com.yuier.yuni.plugin.runtime;

import com.yuier.yuni.core.bot.YuniBot;
import com.yuier.yuni.core.task.DynamicTaskManager;
import com.yuier.yuni.event.detector.message.command.model.CommandNode;
import com.yuier.yuni.plugin.api.Disposable;
import com.yuier.yuni.plugin.api.Effect;
import com.yuier.yuni.plugin.api.PluginMetadata;
import com.yuier.yuni.plugin.api.ScopeFilter;
import com.yuier.yuni.plugin.api.YuniContext;
import com.yuier.yuni.plugin.api.YuniPlugin;
import com.yuier.yuni.plugin.api.event.MetaEvent;
import com.yuier.yuni.plugin.api.event.MessageEvent;
import com.yuier.yuni.plugin.api.event.MessageSentEvent;
import com.yuier.yuni.plugin.api.event.NoticeEvent;
import com.yuier.yuni.plugin.api.event.RequestEvent;
import com.yuier.yuni.plugin.api.handler.MetaHandler;
import com.yuier.yuni.plugin.api.handler.MessageHandler;
import com.yuier.yuni.plugin.api.handler.MessageSentHandler;
import com.yuier.yuni.plugin.api.handler.NoticeHandler;
import com.yuier.yuni.plugin.api.handler.RequestHandler;
import com.yuier.yuni.plugin.api.service.Cache;
import com.yuier.yuni.plugin.api.service.ConfigLoader;
import com.yuier.yuni.plugin.api.service.Http;
import com.yuier.yuni.plugin.api.service.PermissionService;
import com.yuier.yuni.plugin.api.service.Sender;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * {@link YuniContext} 的默认实现。
 *
 * <h2>职责</h2>
 * <p>把插件的注册调用翻译成两件事：</p>
 * <ol>
 *   <li>往 {@link RegistrationTable} 里放一条 {@link RegistrationTable.Entry}，
 *       供 {@code EventPipeline} 分发时查用；</li>
 *   <li>往所属 {@link PluginFiber} 的 {@link EffectRegistry} 登记一个撤销句柄，
 *       保证插件卸载时该条目被移除且相关资源被释放。</li>
 * </ol>
 * <p>「注册」与「撤销」在一处配对，是消除现行系统「启停回调链路分散在三处」
 * 问题的关键。</p>
 *
 * <h2>服务解析</h2>
 * <p>本实现持有对 {@link ServiceRegistry} 的引用。子上下文（{@code ctx.plugin(child)}
 * 产生的）共享同一个注册表——这是「服务沿上下文链可见」的实现方式。
 * 插件提供的服务通过 {@code provide(...)} 注册进同一个表，因此插件之间
 * 可以直接通过 {@code ctx.service(...)} 互相调用，无需静态查 Spring Bean。</p>
 *
 * <h2>作用域状态</h2>
 * <p>{@link #state(Class)} 是按 {@code (状态类型, scopeId)} 分键的懒创建表。
 * 未使用的组合不占内存——这是「单一插件实例 + 作用域过滤」方案
 * （设计文档 §8.3）中状态隔离的落地点。</p>
 *
 * <h2>P1 阶段的实现边界</h2>
 * <p>本阶段先完成：事件注册、命令注册、调度、Effect、服务、作用域状态。
 * 以下部分<b>尚未接线</b>，当前抛出 {@link UnsupportedOperationException}
 * 并带上阶段标记，避免迁移期间被误当成「已可用」：</p>
 * <ul>
 *   <li>{@link #bot()} / {@link #sender()} —— 待 P3（内置服务的委托实现）；</li>
 *   <li>{@link #command(CommandNode)} 的<b>分发</b> —— 注册可用，
 *       匹配由 P4 的 {@code EventPipeline} 接入；</li>
 *   <li>{@link #http()} / {@link #config()} / {@link #cache()} —— 待 P3。</li>
 * </ul>
 * <p>这样做的目的是让 P1 的单元测试可以独立验证「注册 → 撤销 → 资源归零」
 * 这条最小闭环，而不必等待整条链路打通。</p>
 *
 * @see YuniContext
 * @see PluginRuntime
 */
@Slf4j
public final class PluginContext implements YuniContext {

    /** 当前实现阶段标记，用于未接线方法的报错信息。 */
    private static final String PHASE_NOTE = "（当前为重构 P1 阶段，该方法待后续阶段接线）";

    /** 服务注册表；子上下文与父上下文共享同一实例。 */
    private final ServiceRegistry services;

    /** 能力注册总表；子上下文与父上下文共享同一实例。 */
    private final RegistrationTable registrations;

    /** 插件资源目录；未注入时为 {@code null}。 */
    private final Path dataDir;

    /** 归属的 Fiber；异步任务里用它判断插件是否已卸载。 */
    private PluginFiber fiber;

    /** 所属运行时；由 {@code PluginRuntime} 在构造后回填，用于挂载子插件。 */
    private PluginRuntime runtime;

    /** 作用域查询的缓存实例（惰性构造）。 */
    private volatile com.yuier.yuni.plugin.api.ScopeQuery scopeQuery;

    /**
     * 作用域状态表，键为「状态类型 + 作用域标识」。
     *
     * <p>用组合键而非二层 Map，是为了让「同一类型在不同作用域得到不同实例」
     * 这一语义有一个显式的载体。仅在实际调用 {@link #state(Class)} 时才创建条目。</p>
     */
    private final Map<StateKey, Object> scopeStates;

    /** 定时任务管理器；由运行时注入，未接线时为 {@code null}。 */
    private final DynamicTaskManager taskManager;

    /**
     * JSON 编解码器。
     *
     * <p>供 {@code config()} / {@code cache()} / {@code http()} 以及原始消息段
     * 构造使用。为 {@code null} 时相关能力会给出明确错误（测试场景）。</p>
     */
    private final com.yuier.yuni.core.bot.JsonCodec jsonCodec;

    /**
     * 构造根上下文。
     *
     * <p>资源目录或任务管理器为 {@code null} 时，只有访问对应能力的方会抛异常——
     * 测试场景可以只提供需要的部分。</p>
     *
     * @param services      服务注册表
     * @param registrations 能力注册表
     * @param taskManager   定时任务管理器；可为 {@code null}（则调度方法不可用）
     * @param dataDir       默认资源目录；可为 {@code null}
     */
    public PluginContext(ServiceRegistry services,
                         RegistrationTable registrations,
                         DynamicTaskManager taskManager,
                         Path dataDir) {
        this(services, registrations, taskManager, dataDir, null);
    }

    /**
     * 构造根上下文（含 JSON 编解码器）。
     *
     * @param services      服务注册表
     * @param registrations 能力注册表
     * @param taskManager   定时任务管理器；可为 {@code null}
     * @param dataDir       默认资源目录；可为 {@code null}
     * @param jsonCodec     JSON 编解码器；可为 {@code null}
     */
    public PluginContext(ServiceRegistry services,
                         RegistrationTable registrations,
                         DynamicTaskManager taskManager,
                         Path dataDir,
                         com.yuier.yuni.core.bot.JsonCodec jsonCodec) {
        this.services = services;
        this.registrations = registrations;
        this.taskManager = taskManager;
        this.dataDir = dataDir;
        this.jsonCodec = jsonCodec;
        this.scopeStates = new ConcurrentHashMap<>();
    }

    /**
     * 为指定 Fiber 创建上下文。
     *
     * <p>由 {@code PluginRuntime} 在加载每个插件时调用。之所以分成「构造」与
     * 「绑定 Fiber」两步，是因为 {@link PluginFiber} 的构造需要元数据，
     * 而元数据由插件实例提供——先有插件才有 Fiber，先有 Fiber 才有上下文。</p>
     *
     * @param fiber        插件 Fiber
     * @param services     服务注册表
     * @param registrations 能力注册表
     * @param taskManager  定时任务管理器；可为 {@code null}
     * @param dataDir      插件资源目录；可为 {@code null}
     * @return 该插件专属的上下文
     */
    public static PluginContext forFiber(PluginFiber fiber,
                                         ServiceRegistry services,
                                         RegistrationTable registrations,
                                         DynamicTaskManager taskManager,
                                         Path dataDir,
                                         com.yuier.yuni.core.bot.JsonCodec jsonCodec) {
        PluginContext ctx = new PluginContext(services, registrations, taskManager, dataDir, jsonCodec);
        ctx.fiber = fiber;
        return ctx;
    }

    // ==================== 元信息 ====================

    @Override
    public String scopeId() {
        return "default";
    }

    @Override
    public Logger logger() {
        return log;
    }

    @Override
    public PluginMetadata self() {
        return fiber == null ? PluginMetadata.of("unknown").build() : fiber.metadata();
    }

    // ==================== 事件注册 ====================

    @Override
    public Disposable onMessage(MessageHandler handler) {
        return onMessage((Predicate<MessageEvent>) null, handler);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Disposable onMessage(Predicate<MessageEvent> when, MessageHandler handler) {
        return registerEvent(RegistrationTable.Kind.MESSAGE, null,
                when == null ? null : (Predicate<Object>) (Predicate<?>) when, handler);
    }

    @Override
    public Disposable onNotice(String subType, NoticeHandler handler) {
        return registerEvent(RegistrationTable.Kind.NOTICE, subType, null, handler);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Disposable onNotice(Predicate<NoticeEvent> when, NoticeHandler handler) {
        return registerEvent(RegistrationTable.Kind.NOTICE, null,
                when == null ? null : (Predicate<Object>) (Predicate<?>) when, handler);
    }

    @Override
    public Disposable onRequest(String subType, RequestHandler handler) {
        return registerEvent(RegistrationTable.Kind.REQUEST, subType, null, handler);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Disposable onRequest(Predicate<RequestEvent> when, RequestHandler handler) {
        return registerEvent(RegistrationTable.Kind.REQUEST, null,
                when == null ? null : (Predicate<Object>) (Predicate<?>) when, handler);
    }

    @Override
    public Disposable onMeta(String subType, MetaHandler handler) {
        return registerEvent(RegistrationTable.Kind.META, subType, null, handler);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Disposable onMeta(Predicate<MetaEvent> when, MetaHandler handler) {
        return registerEvent(RegistrationTable.Kind.META, null,
                when == null ? null : (Predicate<Object>) (Predicate<?>) when, handler);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Disposable onMessageSent(Predicate<MessageSentEvent> when, MessageSentHandler handler) {
        return registerEvent(RegistrationTable.Kind.MESSAGE_SENT, null,
                when == null ? null : (Predicate<Object>) (Predicate<?>) when, handler);
    }

    /**
     * 事件注册的公共实现。
     *
     * <p>一次调用产生两个效果，且必须<b>原子地</b>一起成功或一起失败：
     * 往注册表放入条目，往 Fiber 的 Effect 登记表放入移除句柄。
     * 若登记失败（插件正在卸载），注册表条目也会被立即撤销——
     * 否则会出现「插件已卸载但事件仍会分发到它」的悬挂引用。</p>
     *
     * @param kind      能力种类
     * @param subType   子类型过滤；{@code null} 表示不过滤
     * @param predicate 附加条件；{@code null} 表示无条件
     * @param handler   处理器载荷
     * @return 撤销句柄
     */
    private Disposable registerEvent(RegistrationTable.Kind kind,
                                     String subType,
                                     Predicate<Object> predicate,
                                     Object handler) {
        RegistrationTable.Entry entry = new RegistrationTable.Entry(
                kind, fiber, ScopeFilter.ALL, predicate, subType, handler);
        Disposable removeEntry = registrations.add(entry);

        // 把「移除注册表条目」交给 Effect 登记表管理，使卸载时自动摘除
        if (fiber == null) {
            // 无 Fiber（测试或框架内部用法）：直接返回移除句柄
            return removeEntry;
        }
        boolean accepted = fiber.effects().register(removeEntry);
        if (!accepted) {
            // 插件正在卸载：登记表已封存，注册表条目必须立即回滚
            removeEntry.dispose();
        }
        return removeEntry;
    }

    // ==================== 命令注册 ====================

    @Override
    public Disposable command(CommandNode tree) {
        if (tree == null) {
            throw new IllegalArgumentException("命令树不能为 null");
        }
        RegistrationTable.Entry entry = new RegistrationTable.Entry(
                RegistrationTable.Kind.COMMAND, fiber, ScopeFilter.ALL, null, null, tree);
        Disposable removeEntry = registrations.add(entry);
        if (fiber == null) {
            return removeEntry;
        }
        if (!fiber.effects().register(removeEntry)) {
            removeEntry.dispose();
        }
        return removeEntry;
    }

    // ==================== 调度 ====================

    @Override
    public Disposable schedule(String cron, Runnable task) {
        if (taskManager == null) {
            throw new IllegalStateException("定时任务管理器未注入，无法注册 cron 任务" + PHASE_NOTE);
        }
        if (cron == null || cron.isBlank()) {
            throw new IllegalArgumentException("cron 表达式不能为空");
        }
        if (task == null) {
            throw new IllegalArgumentException("任务不能为 null");
        }
        /*
         * 用句柄式 API 而非字符串 taskId：
         * 现行系统以 pluginFullId 作任务 ID，导致一个插件只能有一个定时任务；
         * 而新系统中一个插件完全可以注册多个（例如 steam 插件既轮询状态又做日报）。
         */
        java.util.concurrent.ScheduledFuture<?> future =
                taskManager.scheduleCron(cron, wrapTask(task));
        return registerDisposable(() -> taskManager.cancelTask(future));
    }

    @Override
    public Disposable every(Duration interval, Runnable task) {
        if (taskManager == null) {
            throw new IllegalStateException("定时任务管理器未注入，无法注册周期任务" + PHASE_NOTE);
        }
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("执行间隔必须为正");
        }
        if (task == null) {
            throw new IllegalArgumentException("任务不能为 null");
        }
        // Spring 的 cron 最小粒度是秒，这里把间隔折算成「每 N 秒」的 cron 表达式。
        // 对亚秒级间隔无法表达，明确拒绝而不是静默降级。
        long seconds = interval.getSeconds();
        if (seconds <= 0) {
            throw new IllegalArgumentException("执行间隔至少为 1 秒（cron 最小粒度），当前: " + interval);
        }
        String cron = "0/" + seconds + " * * * * ?";
        java.util.concurrent.ScheduledFuture<?> future =
                taskManager.scheduleCron(cron, wrapTask(task));
        return registerDisposable(() -> taskManager.cancelTask(future));
    }

    @Override
    public Disposable runNow(Runnable task) {
        if (task == null) {
            throw new IllegalArgumentException("任务不能为 null");
        }
        // 对应现行 ImmediatePlugin：注册后异步执行一次
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            if (disposed()) {
                // 插件在任务执行前就被卸载了，跳过
                return;
            }
            try {
                task.run();
            } catch (Throwable t) {
                // 必须捕获 Throwable：否则 Error 会被 commonPool 静默吞掉
                // （2026-08 P0 事故的教训之一）
                log.error("[{}] runNow 任务执行失败", self().name(), t);
            }
        });
        // 任务已提交，无可撤销的资源
        return Disposable.NOOP;
    }

    @Override
    public Disposable effect(Effect effect) {
        if (effect == null) {
            throw new IllegalArgumentException("Effect 不能为 null");
        }
        Disposable disposer;
        try {
            disposer = effect.apply();
        } catch (Exception e) {
            // 资源获取失败：不登记任何 Disposer，异常上抛使插件进入 FAILED。
            // 这样避免「资源已创建但清理句柄丢失」的泄漏。
            throw new IllegalStateException("Effect 执行失败，插件将进入 FAILED 状态", e);
        }
        return registerDisposable(disposer == null ? Disposable.NOOP : disposer);
    }

    /**
     * 包装任务体，加上「插件已卸载则跳过」与「异常兜底」两道保护。
     *
     * @param task 原始任务
     * @return 包装后的任务
     */
    private Runnable wrapTask(Runnable task) {
        return () -> {
            if (disposed()) {
                return;
            }
            try {
                task.run();
            } catch (Throwable t) {
                log.error("[{}] 定时任务执行失败", self().name(), t);
            }
        };
    }

    /**
     * 把一个 Disposable 交给所属 Fiber 的 Effect 登记表。
     *
     * @param disposable 撤销句柄
     * @return 原句柄，便于调用方继续使用
     */
    private Disposable registerDisposable(Disposable disposable) {
        if (fiber == null) {
            return disposable;
        }
        if (!fiber.effects().register(disposable)) {
            // 已封存：立即撤销，避免泄漏
            disposable.dispose();
        }
        return disposable;
    }

    // ==================== 服务 ====================

    @Override
    @SuppressWarnings("unchecked")
    public <T> T service(Class<T> type) {
        return (T) services.resolve(type).orElseThrow(() -> new IllegalStateException(
                "服务未注册: " + type.getName()
                        + "。若这是插件提供的服务，请在本插件的 inject() 中声明依赖，"
                        + "否则插件可能在服务就绪前就开始 apply。"));
    }

    @Override
    public <T> Optional<T> serviceIfPresent(Class<T> type) {
        return services.resolve(type);
    }

    @Override
    public Disposable provide(Class<?> type, Object impl) {
        if (type == null || impl == null) {
            throw new IllegalArgumentException("服务类型与实现均不能为 null");
        }
        Optional<Object> previous = services.register(type, impl, fiber);
        previous.ifPresent(old -> log.warn(
                "[{}] 服务 {} 被覆盖注册：旧实现 {} → 新实现 {}",
                self().name(), type.getSimpleName(),
                old.getClass().getName(), impl.getClass().getName()));

        /*
         * 服务注册可能让此前停在 PENDING 的插件变得可加载——它等的就是这个服务。
         * 立即尝试推进，避免「依赖已满足但仍不生效，必须等下次启动」。
         * 推进失败（仍缺其他依赖）会由后续的 provide 再触发，或由启动收尾报出。
         */
        if (runtime != null) {
            runtime.activatePending();
        }

        // 撤销时按提供者校验，避免误删其他插件后来覆盖注册的服务
        return registerDisposable(() -> services.unregister(type, fiber));
    }

    @Override
    public <T> Disposable provide(Class<T> type, java.util.function.Function<Class<T>, T> factory) {
        if (type == null || factory == null) {
            throw new IllegalArgumentException("服务类型与工厂均不能为 null");
        }
        T impl = factory.apply(type);
        if (impl == null) {
            throw new IllegalStateException("服务工厂未返回实现: " + type.getName());
        }
        return provide(type, (Object) impl);
    }

    @Override
    public com.yuier.yuni.plugin.api.ScopeQuery scope() {
        // 惰性构造并缓存：作用域查询在广播场景下会被反复调用（每个群一次）
        com.yuier.yuni.plugin.api.ScopeQuery cached = this.scopeQuery;
        if (cached != null) {
            return cached;
        }
        com.yuier.yuni.plugin.api.ScopeQuery created = new DefaultScopeQuery();
        this.scopeQuery = created;
        return created;
    }

    @Override
    public com.yuier.yuni.plugin.api.PluginAdmin plugins() {
        /*
         * 不缓存，每次新建。
         *
         * 与 scope() 的惰性缓存形成对比，原因是两者的使用模式不同：
         * scope() 在广播场景下按群调用（可能几百次），必须避免重复构造；
         * plugins() 是管理操作，调用频率是「用户敲一条命令」量级，
         * 而它返回的适配器本身不持有状态（全部状态在运行时里），
         * 构造一个的开销只是几次字段赋值。
         *
         * 不缓存还有一个正确性收益：适配器持有一个 PluginModuleLoader 引用，
         * 而该引用是在运行时装配完成后才绑定的（bindModuleLoader）。
         * 缓存会导致「第一次访问时加载器还没绑上」的适配器被一直复用。
         */
        if (runtime == null) {
            throw new IllegalStateException(
                    "插件管理服务不可用：本上下文未绑定 PluginRuntime。"
                            + "这通常意味着插件在框架之外被手动构造（测试场景）。");
        }
        return runtime.admin();
    }

    /**
     * 作用域查询的默认实现。
     *
     * <p>判定来源按优先级：</p>
     * <ol>
     *   <li>{@code PluginRuntime} 里该插件注册项的 {@code ScopeFilter}
     *       （即管理端配置的群例外）；</li>
     *   <li>无注册项可查时，回落为「按插件元数据的 {@code defaultEnable} 全局生效」。</li>
     * </ol>
     *
     * <p>本实现只做「按当前配置查询」，不写库——写库属管理端职责（见设计文档 §10.5）。</p>
     */
    private final class DefaultScopeQuery implements com.yuier.yuni.plugin.api.ScopeQuery {

        @Override
        public boolean isEnabledIn(long groupId) {
            if (fiber == null || runtime == null) {
                return true;
            }
            // 询问运行时：该插件是否<b>显式</b>设置过这个群
            Boolean enabled = runtime.isPluginEnabledIn(fiber.id(), groupId);
            if (enabled != null) {
                return enabled;
            }
            // 没有显式配置：按元数据的默认值
            return fiber.metadata().defaultEnable();
        }

        @Override
        public java.util.List<Long> enabledGroups() {
            if (runtime == null) {
                return java.util.List.of();
            }
            /*
             * 语义：只返回「机器人当前所在、且本插件在这些群里生效」的群。
             *
             * 两个约束都是必要的：
             *   - 与实际群列表取交集，避免向不存在的群发送（白名单里可能残留
             *     已退出的群号）；
             *   - 按本插件的 defaultEnable 与群例外判定，而不是「有例外才算生效」。
             *
             * 实现上直接复用 runtime.groupsWhereEnabled：它内部已经做了这两件事，
             * 且与 PluginAdmin.isEnabledIn 走同一条判定链，因此不会出现
             * 「ctx.scope() 说启用、管理端说关闭」的不一致。
             */
            return runtime.groupsWhereEnabled(fiber == null ? null : fiber.id());
        }

        @Override
        public boolean isGloballyEnabled() {
            if (fiber == null) {
                return true;
            }
            // 全局生效 = 未配置任何群例外（ScopeFilter 为 ALL）
            return runtime.isGloballyEnabled(fiber.id());
        }
    }

    // ==================== 作用域状态 ====================

    @Override
    @SuppressWarnings("unchecked")
    public <T> T state(Class<T> type) {
        if (type == null) {
            throw new IllegalArgumentException("状态类型不能为 null");
        }
        StateKey key = new StateKey(type, scopeId());
        return (T) scopeStates.computeIfAbsent(key, k -> {
            try {
                return type.getDeclaredConstructor().newInstance();
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(
                        "作用域状态类型 " + type.getName()
                                + " 缺少可访问的无参构造器。"
                                + "请提供 public 无参构造器，或改为在插件内自行管理状态。", e);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("无法实例化作用域状态类型 " + type.getName(), e);
            }
        });
    }

    // ==================== 内置服务快捷方式 ====================

    @Override
    public YuniBot bot() {
        /*
         * 优先经运行时解析：bot 由 engine 的 BotManager 动态创建，
         * 运行时持有的供应函数是唯一在启动全阶段都可靠的来源。
         * 服务表作为回退——某些集成场景会直接注册 bot。
         */
        if (runtime != null) {
            YuniBot resolved = runtime.resolveBot();
            if (resolved != null) {
                return resolved;
            }
        }
        return service(YuniBot.class);
    }

    @Override
    public Sender sender() {
        /*
         * 按需构造而非预注册：Sender 绑定到具体的 YuniBot 实例，而 bot 由
         * BotManager 在启动流程中动态创建并注册为单例，时机晚于 PluginContext
         * 的构造。因此这里在首次访问时从服务表取 bot 并即时包装。
         */
        return new com.yuier.yuni.plugin.runtime.service.CoreSender(bot(), jsonCodec());
    }

    @Override
    public com.yuier.yuni.plugin.data.PluginDataService db() {
        return service(com.yuier.yuni.plugin.data.PluginDataService.class);
    }

    @Override
    public Cache cache() {
        /*
         * 优先取注册表里的实例（启动配置会注入一个共享实例），
         * 未注册时按需构造。共享实例的价值在于连接池与线程池的复用——
         * 若每个插件各持一个 HttpClient，连接池就被割裂了。
         */
        return services.resolve(Cache.class)
                .orElseGet(() -> new com.yuier.yuni.plugin.runtime.service.RedisCache(jsonCodec()));
    }

    @Override
    public ConfigLoader config() {
        // 按插件构造：每个插件的资源目录不同，这是 ConfigLoader 的关键语义
        if (dataDir == null) {
            throw new IllegalStateException(
                    "插件资源目录未注入，无法读取配置文件。"
                            + "若插件不需要读取资源，请勿调用 ctx.config()。" + PHASE_NOTE);
        }
        return new com.yuier.yuni.plugin.runtime.service.FileConfigLoader(dataDir, jsonCodec());
    }

    @Override
    public Http http() {
        /*
         * 同 cache()：优先复用注册表里的共享实例。
         * 现行 PluginUtils.simpleGet/simplePost 每次调用都 new RestTemplate()，
         * 不复用连接池——这正是本设计要修掉的问题之一。
         */
        return services.resolve(Http.class)
                .orElseGet(() -> new com.yuier.yuni.plugin.runtime.service.RestTemplateHttp(jsonCodec()));
    }

    @Override
    public PermissionService permission() {
        return service(PermissionService.class);
    }

    @Override
    public Path dataDir() {
        if (dataDir == null) {
            throw new IllegalStateException("插件资源目录未注入" + PHASE_NOTE);
        }
        return dataDir;
    }

    /**
     * 取 JSON 编解码器。
     *
     * <p>由运行时注入；测试场景可能为 {@code null}，此时依赖它的能力
     * （{@code config()} / {@code cache()} / {@code http()} / 原始段构造）
     * 会给出明确的错误而不是 NPE。</p>
     *
     * @return 编解码器
     */
    private com.yuier.yuni.core.bot.JsonCodec jsonCodec() {
        if (jsonCodec == null) {
            throw new IllegalStateException(
                    "JsonCodec 未注入，无法执行需要 JSON 编解码的操作" + PHASE_NOTE);
        }
        return jsonCodec;
    }

    // ==================== 挂载子插件 ====================

    @Override
    public Disposable plugin(YuniPlugin child) {
        return plugin(child, ScopeFilter.ALL);
    }

    @Override
    public Disposable plugin(YuniPlugin child, ScopeFilter filter) {
        if (child == null) {
            throw new IllegalArgumentException("子插件不能为 null");
        }
        if (runtime == null) {
            throw new IllegalStateException(
                    "上下文未绑定运行时，无法挂载子插件" + PHASE_NOTE
                            + "。请通过 PluginRuntime.load(...) 创建上下文。");
        }
        // 子插件继承本插件的类加载器与资源目录
        PluginFiber childFiber = runtime.mountChild(child, fiber, dataDir, filter);

        /*
         * 子插件可能因依赖未满足而停在 PENDING。此时注册一个撤销句柄是不够的——
         * 还需要在「本插件后续提供了该依赖」时推进它。
         *
         * 这里采用的做法是在 provide() 之后统一尝试推进（见 provide 的实现），
         * 因此挂载动作本身只负责登记与撤销。
         */
        return registerDisposable(() -> runtime.unload(childFiber.id()));
    }

    /**
     * 绑定所属运行时。
     *
     * <p>由 {@link PluginRuntime} 在构造上下文之后立即调用。之所以不放进构造器，
     * 是因为运行时与上下文互相引用：运行时需要上下文来执行 {@code apply}，
     * 上下文需要运行时来挂载子插件。</p>
     *
     * @param runtime 运行时
     */
    void bindRuntime(PluginRuntime runtime) {
        this.runtime = runtime;
    }

    // ==================== 生命周期查询 ====================

    @Override
    public boolean disposed() {
        return fiber != null && fiber.isDisposed();
    }

    // ==================== 内部 ====================

    /**
     * 作用域状态表的组合键。
     *
     * @param type    状态类型
     * @param scopeId 作用域标识
     */
    private record StateKey(Class<?> type, String scopeId) {
    }

    /**
     * 服务注册表（供运行时与测试访问）。
     *
     * @return 注册表
     */
    public ServiceRegistry serviceRegistry() {
        return services;
    }

    /**
     * 能力注册表（供运行时与测试访问）。
     *
     * @return 注册表
     */
    public RegistrationTable registrationTable() {
        return registrations;
    }

    /**
     * 归属的 Fiber（可能为 {@code null}，例如测试构造的匿名上下文）。
     *
     * @return Fiber
     */
    public PluginFiber fiber() {
        return fiber;
    }
}
