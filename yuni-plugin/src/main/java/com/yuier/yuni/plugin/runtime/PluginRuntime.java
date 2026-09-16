package com.yuier.yuni.plugin.runtime;

import com.yuier.yuni.core.task.DynamicTaskManager;
import com.yuier.yuni.plugin.api.Disposable;
import com.yuier.yuni.plugin.api.PluginMetadata;
import com.yuier.yuni.plugin.api.ScopeFilter;
import com.yuier.yuni.plugin.api.YuniPlugin;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 插件运行时——加载、依赖解析、卸载、重载的总入口。
 *
 * <h2>取代了什么</h2>
 * <p>现行系统把这份职责分散在四个类里：{@code PluginManager}（总调度）、
 * {@code PluginLoadProcessor}（装配）、{@code PluginRegisterProcessor}（注册）、
 * {@code PluginEnableProcessor}（启停判定）。热重载逻辑则<b>不在框架内</b>，
 * 而是外置在 {@code plugin-manage} 内置插件的 {@code PluginReloadProcessor}
 * 与 webapi 的 {@code PluginService} 中各写了一遍。</p>
 *
 * <p>本类把「加载 → 依赖检查 → apply → 注册索引」与「卸载 → 撤销 Effect → 清索引」
 * 两条链路收拢到一处，热重载因此变成「记录位置 → 卸载 → 换 ClassLoader → 原位重挂」，
 * 不再需要维护八条类型列表与修正 index 偏移。</p>
 *
 * <h2>依赖驱动的加载</h2>
 * <p>插件通过 {@link YuniPlugin#inject()} 声明所需服务。加载流程会检查这些服务
 * 是否已在 {@link ServiceRegistry} 中注册：</p>
 * <ul>
 *   <li>全部就绪 → 调用 {@code apply(ctx)}，转入 {@link FiberState#ACTIVE}；</li>
 *   <li>有缺失 → 停在 {@link FiberState#PENDING}，记录缺失的服务类型，
 *       <b>不</b>调用 {@code apply}。</li>
 * </ul>
 * <p>{@link #reportPendingPlugins()} 用于在启动流程结束时把「谁因为缺什么而没加载」
 * 显式报出来——这是现行系统完全缺失的可观测性，也是「我的插件为什么没反应」
 * 这类问题的直接答案。</p>
 *
 * <h2>失败处理</h2>
 * <p>{@code apply(ctx)} 抛出的任何 {@code Throwable}（含 {@code Error}）都会被捕获：
 * 记录完整堆栈、把 Fiber 置为 {@link FiberState#FAILED}、<b>并回滚已建立的 Effect</b>。
 * 回滚是必要的——否则会出现「插件标记为失败但监听器仍在工作」的半初始化状态。</p>
 *
 * <h2>与 ClassLoader 的关系</h2>
 * <p>每个 {@link PluginFiber} 持有加载它的 ClassLoader。{@link #unload} 会断言
 * Effect 已归零；调用方只有在此之后才允许关闭 ClassLoader。这条约束来自
 * 2026-08 的 P0 事故（插件重载静默失败），见设计文档 §9.4。</p>
 *
 * <h2>P1 阶段的边界</h2>
 * <p>本类当前实现：加载、依赖检查、apply、索引登记、卸载、Effect 归零断言、
 * 子插件挂载。尚未实现：jar 扫描与 ClassLoader 管理（{@link #reload} 抛
 * {@link UnsupportedOperationException}）、事件分发（属 P4）。</p>
 *
 * @see PluginFiber
 * @see FiberRegistry
 * @see RegistrationTable
 */
@Slf4j
public final class PluginRuntime {

    /** 阶段标记，用于未接线方法的报错信息。 */
    private static final String PHASE_NOTE = "（当前为重构 P1 阶段，该方法待后续阶段接线）";

    /** 能力注册总表：所有插件注册的事件处理器与命令树。 */
    private final RegistrationTable registrations = new RegistrationTable();

    /** 服务注册表：框架内置服务与插件提供的服务。 */
    private final ServiceRegistry services = new ServiceRegistry();

    /** Fiber 容器：树形索引 + 模块索引。 */
    private final FiberRegistry fibers = new FiberRegistry();

    /** 插件标识 → 资源目录；用于依赖推进时重建上下文。 */
    private final java.util.Map<String, Path> pluginDataDirs = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 插件标识 → 作用域过滤器。
     *
     * <p>记录每个插件注册时的 {@link com.yuier.yuni.plugin.api.ScopeFilter}，
     * 供 {@code ctx.scope()} 回答「本插件在某群是否生效」。
     * 主动插件（定时任务）没有事件上下文，框架无法替它施加过滤，
     * 只能由插件主动查询——这是该映射存在的唯一理由。</p>
     */
    private final java.util.Map<String, com.yuier.yuni.plugin.api.ScopeFilter> pluginScopeFilters =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 机器人实例供应函数。
     *
     * <p>由启动流程在 bot 创建完毕后设置（见 {@link #bindBot}）。用供应函数
     * 而非直接持有实例：bot 由 engine 的 {@code BotManager} 动态创建，
     * 时机晚于本运行时的构造。</p>
     */
    private volatile java.util.function.Supplier<com.yuier.yuni.core.bot.YuniBot> botSupplier;

    /** 事件分发流水线；首次访问时构造并缓存。 */
    private volatile com.yuier.yuni.plugin.runtime.dispatch.EventPipeline pipeline;

    /** 命令分发器；由 {@link #pipeline()} 惰性构造。 */
    private volatile com.yuier.yuni.plugin.runtime.dispatch.CommandDispatcher commandDispatcher;

    /** 事件适配工厂；由 {@link #pipeline()} 惰性构造。 */
    private volatile com.yuier.yuni.plugin.runtime.adapter.PluginEventAdapter eventAdapter;

    /** 机器人业务配置（提供命令前缀）；由启动流程设置。 */
    private volatile com.yuier.yuni.core.model.bot.BotApp botApp;

    /** 定时任务管理器；由外部注入，可为 {@code null}（测试场景）。 */
    private final DynamicTaskManager taskManager;

    /** JSON 编解码器；可为 {@code null}（测试场景，则依赖它的能力不可用）。 */
    private final com.yuier.yuni.core.bot.JsonCodec jsonCodec;

    /**
     * 构造运行时。
     *
     * @param taskManager 定时任务管理器；{@code null} 时 {@code ctx.schedule(...)} 不可用
     */
    public PluginRuntime(DynamicTaskManager taskManager) {
        this(taskManager, null);
    }

    /**
     * 构造运行时（含 JSON 编解码器）。
     *
     * @param taskManager 定时任务管理器；{@code null} 时调度能力不可用
     * @param jsonCodec   JSON 编解码器；{@code null} 时配置/缓存/HTTP 能力不可用
     */
    public PluginRuntime(DynamicTaskManager taskManager, com.yuier.yuni.core.bot.JsonCodec jsonCodec) {
        this.taskManager = taskManager;
        this.jsonCodec = jsonCodec;
    }

    // ==================== 注册基础设施 ====================

    /**
     * 注册一个框架内置服务。
     *
     * <p>由启动流程在加载插件之前调用。内置服务没有提供者 Fiber，
     * 因此不会被任何插件卸载流程连带注销。</p>
     *
     * @param type 服务类型
     * @param impl 实现实例
     * @param <T>  服务类型
     * @return {@code this}，便于链式注册
     */
    public <T> PluginRuntime registerService(Class<T> type, T impl) {
        Optional<Object> previous = services.register(type, impl, null);
        if (previous.isPresent()) {
            log.warn("内置服务 {} 被重复注册，旧实现将失效", type.getName());
        }
        return this;
    }

    /**
     * 注册框架内置服务（自动按实现的接口类型登记）。
     *
     * <p>与 {@link #registerService(Class, Object)} 的区别：本方法按传入对象
     * <b>实现的服务接口</b>登记，调用方无需指定类型。用于批量装配内置服务。</p>
     *
     * @param impl 服务实现
     * @return {@code this}
     */
    public PluginRuntime registerService(Object impl) {
        if (impl == null) {
            return this;
        }
        // 按实现类实现的接口逐一登记：一个实现可以同时提供多个服务契约
        Class<?>[] interfaces = impl.getClass().getInterfaces();
        if (interfaces.length == 0) {
            log.warn("服务实现 {} 未实现任何接口，无法按契约登记", impl.getClass().getName());
            return this;
        }
        for (Class<?> type : interfaces) {
            if (type.getPackageName().startsWith("com.yuier.yuni.plugin.api")) {
                services.register(type, impl, null);
            }
        }
        return this;
    }

    // ==================== 加载 ====================

    /**
     * 加载一个插件。
     *
     * <p>流程：解析元数据 → 建 Fiber → 检查依赖 → 调 {@code apply(ctx)} → 登记索引。
     * 依赖未满足时停在 PENDING 并<b>不</b>调用 {@code apply}。</p>
     *
     * @param plugin      插件实例
     * @param moduleId    所属模块 ID；{@code null} 表示不属于任何模块
     * @param dataDir     插件资源目录；可为 {@code null}
     * @param classLoader 加载该插件的类加载器；可为 {@code null}
     * @return 建好的 Fiber（无论加载成功、失败还是停在 PENDING）
     */
    public PluginFiber load(YuniPlugin plugin, String moduleId, Path dataDir, ClassLoader classLoader) {
        return load(plugin, moduleId, dataDir, classLoader, null, null);
    }

    /**
     * 加载一个插件（完整签名）。
     *
     * @param plugin      插件实例
     * @param moduleId    所属模块 ID；可为 {@code null}
     * @param dataDir     插件资源目录；可为 {@code null}
     * @param classLoader 类加载器；可为 {@code null}
     * @param parent      父 Fiber；挂载子插件时传入，顶层插件传 {@code null}
     * @param filter      作用域过滤器；{@code null} 视为 {@link ScopeFilter#ALL}
     * @return 建好的 Fiber
     */
    public PluginFiber load(YuniPlugin plugin,
                            String moduleId,
                            Path dataDir,
                            ClassLoader classLoader,
                            PluginFiber parent,
                            ScopeFilter filter) {
        if (plugin == null) {
            throw new IllegalArgumentException("插件实例不能为 null");
        }
        PluginMetadata metadata = safeMetadata(plugin);
        String id = buildPluginId(moduleId, metadata, plugin);

        if (fibers.byId(id).isPresent()) {
            // 同 ID 已存在：说明调用方没有先卸载旧实例。
            // 不静默覆盖——静默覆盖正是现行系统「重载后旧实例仍活着」类问题的根源。
            throw new IllegalStateException(
                    "插件 " + id + " 已加载。请先 unload 再重新 load，或使用 reload。");
        }

        PluginFiber fiber = new PluginFiber(id, plugin, metadata, classLoader);
        fibers.add(fiber, moduleId, parent == null);
        if (dataDir != null) {
            // 记录资源目录：依赖未满足而停在 PENDING 的插件，后续推进时
            // 需要用同一个目录重建上下文
            pluginDataDirs.put(id, dataDir);
        }
        if (filter != null) {
            // 记录作用域过滤器：供主动插件经 ctx.scope() 查询群粒度启停
            pluginScopeFilters.put(id, filter);
        }
        if (parent != null) {
            parent.addChild(fiber);
        }

        // 先登记索引再 apply：这样插件在 apply 期间就能通过 ctx.service 找到自己
        // 注册的服务，也能被其他插件按 ID 反查。

        List<Class<?>> missing = missingDependencies(plugin);
        if (!missing.isEmpty()) {
            for (Class<?> type : missing) {
                fiber.addPendingDependency(type);
            }
            log.warn("插件 {} 依赖未满足，停在 PENDING 状态。缺失服务: {}",
                    id, missing.stream().map(Class::getName).toList());
            return fiber;
        }

        applyTo(fiber, dataDir, filter);
        return fiber;
    }

    /**
     * 执行 {@code apply(ctx)} 并处理成功 / 失败。
     *
     * @param fiber  目标 Fiber
     * @param dataDir 插件资源目录
     * @param filter  作用域过滤器；当前仅记录，P4 分发时使用
     */
    private void applyTo(PluginFiber fiber, Path dataDir, ScopeFilter filter) {
        /*
         * 前置校验：只允许对「尚未 apply 过」的 Fiber 执行。
         *
         * 若 Fiber 已被 dispose（或 apply 失败时回滚过），其 EffectRegistry 已封存，
         * 此时再 apply 会让插件欣然注册一切然后静默丢弃——比直接报错难排查得多。
         */
        if (fiber.effects().isSealed()) {
            throw new IllegalStateException(
                    "插件 " + fiber.id() + " 的 Effect 登记表已封存（当前状态 "
                            + fiber.state().displayName() + "），不能重复 apply。"
                            + "请先卸载再用新实例加载。");
        }

        PluginContext ctx = PluginContext.forFiber(
                fiber, services, registrations, taskManager, dataDir, jsonCodec);
        // 让子插件挂载能回调到运行时
        ctx.bindRuntime(this);

        fiber.markLoading();
        try {
            fiber.plugin().apply(ctx);
            fiber.markActive();
            log.info("插件已加载: {} | 贡献能力 {}", fiber.id(), registrations.kindsOf(fiber));
        } catch (Throwable t) {
            // 必须捕获 Throwable：类加载失败抛的是 Error，
            // 只 catch Exception 会让插件静默消失（2026-08 P0 事故的教训）
            fiber.markFailed(t);
            log.error("插件 {} 加载失败，已回滚其 Effect", fiber.id(), t);

            // 回滚：撤销已建立的 Effect，避免「标记为失败但监听器仍在工作」。
            // 用 rollbackEffects 而非 dispose——后者会把状态改成 DISPOSED，
            // 使这次失败在管理端与 reportFailedPlugins() 中消失。
            List<String> failures = fiber.rollbackEffects();
            if (!failures.isEmpty()) {
                log.error("插件 {} 回滚期间有清理失败: {}", fiber.id(), failures);
            }
            // 索引保留（状态为 FAILED），以便管理端展示失败插件；调用方可选择移除
        }
    }

    /**
     * 尝试加载一个此前停在 PENDING 的插件。
     *
     * <p>在某个插件提供了新服务之后调用，用于推进依赖已满足的等待者。</p>
     *
     * @param pluginId 插件标识
     * @param dataDir  插件资源目录
     * @return 成功转为 ACTIVE 返回 {@code true}；仍缺依赖或非 PENDING 状态返回 {@code false}
     */
    public boolean tryActivate(String pluginId, Path dataDir) {
        Optional<PluginFiber> found = fibers.byId(pluginId);
        if (found.isEmpty()) {
            return false;
        }
        PluginFiber fiber = found.get();
        if (fiber.state() != FiberState.PENDING) {
            // 已经 ACTIVE（可能被上一轮推进）或已 FAILED / DISPOSED，都不再尝试
            return false;
        }
        List<Class<?>> missing = missingDependencies(fiber.plugin());
        if (!missing.isEmpty()) {
            fiber.clearPendingDependencies();
            missing.forEach(fiber::addPendingDependency);
            return false;
        }
        fiber.clearPendingDependencies();
        applyTo(fiber, dataDir, ScopeFilter.ALL);
        return fiber.state() == FiberState.ACTIVE;
    }

    /**
     * 尝试加载<b>全部</b>停在 PENDING 的插件，资源目录由运行时记录自动解析。
     *
     * <p>每次循环都可能让新的插件变得可加载（它依赖的服务由刚加载的插件提供），
     * 因此需要反复扫描直到一轮下来没有任何进展。这个不动点迭代同时能识别
     * <b>循环依赖</b>：互相依赖的插件在一轮轮扫描中永远无法推进，循环结束后
     * 仍是 PENDING，由 {@link #reportPendingPlugins()} 报出。</p>
     *
     * @return 本次新激活的插件数量
     */
    public int activatePending() {
        // 用 orElse(null) 而非 get()：未记录资源目录不是错误，
        // 只是该插件的上下文拿不到资源路径而已
        return activatePending(id -> dataDirOfId(id).orElse(null));
    }

    /**
     * 尝试加载<b>全部</b>停在 PENDING 的插件。
     *
     * @param dataDirResolver 由插件标识解析资源目录的函数；可为 {@code null}
     * @return 本次新激活的插件数量
     */
    public int activatePending(java.util.function.Function<String, Path> dataDirResolver) {
        int activated = 0;
        boolean progressed = true;
        while (progressed) {
            progressed = false;
            for (PluginFiber fiber : fibers.withState(FiberState.PENDING)) {
                Path dir = dataDirResolver == null ? null : dataDirResolver.apply(fiber.id());
                if (tryActivate(fiber.id(), dir)) {
                    activated++;
                    progressed = true;
                }
            }
        }
        return activated;
    }

    /**
     * 报告仍停在 PENDING 状态的插件及其缺失依赖。
     *
     * <p>启动流程结束时应调用本方法。返回非空表示有插件无法加载——
     * 调用方应将其作为<b>启动警告</b>输出，而不是静默放过。</p>
     *
     * @return 每条为「插件标识：缺失服务列表」；无 PENDING 插件时为空列表
     */
    public List<String> reportPendingPlugins() {
        List<PluginFiber> pending = fibers.withState(FiberState.PENDING);
        if (pending.isEmpty()) {
            return List.of();
        }
        List<String> report = new ArrayList<>();
        for (PluginFiber fiber : pending) {
            String deps = fiber.pendingDependencies().stream()
                    .map(Class::getName)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("(未记录)");
            report.add(fiber.id() + "：缺失 " + deps);
        }
        return Collections.unmodifiableList(report);
    }

    /**
     * 报告加载失败的插件及其异常摘要。
     *
     * @return 每条为「插件标识：异常摘要」；无失败插件时为空列表
     */
    public List<String> reportFailedPlugins() {
        List<PluginFiber> failed = fibers.withState(FiberState.FAILED);
        if (failed.isEmpty()) {
            return List.of();
        }
        List<String> report = new ArrayList<>();
        for (PluginFiber fiber : failed) {
            String cause = fiber.failureCause()
                    .map(t -> t.getClass().getSimpleName() + ": " + t.getMessage())
                    .orElse("(未记录)");
            report.add(fiber.id() + "：" + cause);
        }
        return Collections.unmodifiableList(report);
    }

    // ==================== 卸载 ====================

    /**
     * 卸载一个插件。
     *
     * <p>流程：注销其提供的服务 → 递归卸载子 Fiber → 撤销全部 Effect → 清索引。
     * 返回清理失败的描述，供调用方记录——卸载本身永不因单个 Disposer 失败而中断。</p>
     *
     * @param pluginId 插件标识
     * @return 清理失败的描述列表；插件不存在或全部成功时为空列表
     */
    public List<String> unload(String pluginId) {
        Optional<PluginFiber> found = fibers.byId(pluginId);
        if (found.isEmpty()) {
            log.warn("卸载失败：插件不存在 {}", pluginId);
            return List.of();
        }
        PluginFiber fiber = found.get();

        // 先注销服务：让依赖它的插件后续查询失败，而不是拿到一个已卸载插件的对象
        List<Class<?>> removedServices = services.unregisterAll(fiber);
        if (!removedServices.isEmpty()) {
            log.debug("插件 {} 注销服务: {}", pluginId,
                    removedServices.stream().map(Class::getName).toList());
        }

        List<String> failures = fiber.dispose();

        // 索引必须清掉，即使卸载过程中有清理失败——
        // 否则会出现「已卸载却仍在分发表里」的悬挂引用
        fibers.remove(fiber);
        pluginDataDirs.remove(pluginId);
        pluginScopeFilters.remove(pluginId);

        if (failures.isEmpty()) {
            log.info("插件已卸载: {}", pluginId);
        } else {
            log.warn("插件 {} 已卸载，但有 {} 项清理失败: {}", pluginId, failures.size(), failures);
        }
        return failures;
    }

    /**
     * 卸载全部插件。
     *
     * <p>从叶子往根卸载（{@link FiberRegistry#roots()} 的逆序），
     * 保证子插件先于父插件消失。</p>
     *
     * @return 清理失败的描述列表
     */
    public List<String> unloadAll() {
        List<String> failures = new ArrayList<>();
        List<PluginFiber> roots = fibers.roots();
        for (int i = roots.size() - 1; i >= 0; i--) {
            failures.addAll(unload(roots.get(i).id()));
        }
        return Collections.unmodifiableList(failures);
    }

    // ==================== 重载 ====================

    /**
     * 重载一个模块。
     *
     * <p>设计目标（对比现行实现）：</p>
     * <table border="1">
     *   <caption>重载步骤对比</caption>
     *   <tr><th>现行 {@code PluginReloadProcessor}</th><th>新实现</th></tr>
     *   <tr><td>记录 index 与全量列表位置</td><td>记录挂载顺序（父上下文 + 序号）</td></tr>
     *   <tr><td>手工从 4 个索引结构移除</td><td>{@code unload()} 一次完成</td></tr>
     *   <tr><td>修正后续模块的 index 偏移</td><td><b>不存在</b>——树结构没有全局序号</td></tr>
     * </table>
     *
     * <p><b>P1 阶段未实现</b>：本方法需要 jar 扫描与 ClassLoader 生命周期管理，
     * 属 P2/P3 范围。</p>
     *
     * @param moduleId 模块 ID
     * @throws UnsupportedOperationException 当前阶段未实现
     */
    public void reload(String moduleId) {
        throw new UnsupportedOperationException(
                "reload 需要 jar 扫描与 ClassLoader 生命周期管理" + PHASE_NOTE
                        + "。当前可用 PluginRuntime.unload + load 手动完成重载。");
    }

    /**
     * 绑定机器人实例供应函数与业务配置。
     *
     * <p>由启动流程在 bot 创建完毕、业务配置就绪后调用。这两个依赖都晚于
     * 本运行时的构造时机，因此采用后置绑定而非构造器注入。</p>
     *
     * @param botSupplier 机器人实例供应函数；{@code null} 表示不提供
     * @param botApp      机器人业务配置（提供命令前缀）；可为 {@code null}
     * @return {@code this}，便于链式配置
     */
    public PluginRuntime bindBot(java.util.function.Supplier<com.yuier.yuni.core.bot.YuniBot> botSupplier,
                                 com.yuier.yuni.core.model.bot.BotApp botApp) {
        this.botSupplier = botSupplier;
        this.botApp = botApp;
        // 使已构造的流水线失效，下次访问时用新的 bot 重建
        this.pipeline = null;
        this.commandDispatcher = null;
        this.eventAdapter = null;
        return this;
    }

    // ==================== 事件分发 ====================

    /**
     * 取事件分发流水线（惰性构造并缓存）。
     *
     * <p>流水线需要 bot（用于构造 Reply）与 BotApp（提供命令前缀）。
     * 二者都由 {@link #bindBot} 后置注入，因此这里是惰性构造：
     * 若在绑定之前就收到事件，构造出的适配器会在解析 bot 时安全降级
     * （事件可翻译但没有 Reply），不会抛异常。</p>
     *
     * @return 流水线，永不为 {@code null}
     */
    public com.yuier.yuni.plugin.runtime.dispatch.EventPipeline pipeline() {
        com.yuier.yuni.plugin.runtime.dispatch.EventPipeline current = this.pipeline;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (this.pipeline == null) {
                this.eventAdapter = new com.yuier.yuni.plugin.runtime.adapter.PluginEventAdapter(
                        botSupplier, jsonCodec);
                this.commandDispatcher = new com.yuier.yuni.plugin.runtime.dispatch.CommandDispatcher(
                        registrations,
                        // 命令引擎需要 bot 的具体实例（用于回复段解析）；
                        // 这里即时解析一次，未就绪时传 null，由引擎的静态回退兜住
                        resolveBot(),
                        botApp,
                        // 权限判定委托给权限服务——插件可通过 ctx.permission() 覆盖注册
                        com.yuier.yuni.plugin.runtime.dispatch.CommandDispatcher.fromService(
                                services.resolve(com.yuier.yuni.plugin.api.service.PermissionService.class)
                                        .orElse(null)));
                this.pipeline = new com.yuier.yuni.plugin.runtime.dispatch.EventPipeline(
                        this, eventAdapter, registrations, commandDispatcher);
            }
            return this.pipeline;
        }
    }

    /**
     * 取插件的作用域过滤器。
     *
     * @param pluginId 插件标识
     * @return 过滤器；未记录时为空
     */
    public java.util.Optional<com.yuier.yuni.plugin.api.ScopeFilter> scopeFilterOf(String pluginId) {
        return java.util.Optional.ofNullable(pluginScopeFilters.get(pluginId));
    }

    /**
     * 判定插件是否在指定群生效（供主动插件经 {@code ctx.scope()} 查询）。
     *
     * @param pluginId 插件标识
     * @param groupId  群号
     * @return 生效返回 {@code true}；无过滤器记录时返回 {@code null}，由调用方回落默认值
     */
    public Boolean isPluginEnabledIn(String pluginId, long groupId) {
        com.yuier.yuni.plugin.api.ScopeFilter filter = pluginScopeFilters.get(pluginId);
        return filter == null ? null : filter.matches(groupId);
    }

    /**
     * 判定插件是否在全局作用域生效。
     *
     * @param pluginId 插件标识
     * @return 无过滤器记录或过滤器覆盖全部群时返回 {@code true}
     */
    public boolean isGloballyEnabled(String pluginId) {
        com.yuier.yuni.plugin.api.ScopeFilter filter = pluginScopeFilters.get(pluginId);
        if (filter == null) {
            return true;
        }
        // 无显式群例外即视为全局生效（只对具名实现可判定）
        java.util.Optional<java.util.Set<Long>> whitelist = filter.explicitGroupWhitelist();
        java.util.Optional<java.util.Set<Long>> blacklist = filter.explicitGroupBlacklist();
        if (whitelist.isPresent()) {
            // 白名单语义：只有列出的群生效，不算全局
            return false;
        }
        if (blacklist.isPresent()) {
            // 黑名单语义：全局开启 + 个别群关闭
            return true;
        }
        // 无法反向解析的过滤器（lambda 组合）：按「覆盖全部」处理
        return true;
    }

    /**
     * 取「插件生效的全部群号」。
     *
     * <p>用于主动插件只向启用了它的群广播。需要向机器人查询群列表——
     * 若 bot 未就绪或查询失败，返回空列表（宁可少发也不误发）。</p>
     *
     * @param pluginId 插件标识
     * @return 群号列表，不可变；无法判定时为空列表
     */
    public java.util.List<Long> groupsWhereEnabled(String pluginId) {
        com.yuier.yuni.core.bot.YuniBot bot = resolveBot();
        if (bot == null || pluginId == null) {
            return java.util.List.of();
        }
        try {
            java.util.List<com.yuier.yuni.core.bot.BotGroupInfo> groups =
                    bot.getGroupList().orElse(java.util.List.of());
            java.util.List<Long> enabled = new java.util.ArrayList<>();
            for (com.yuier.yuni.core.bot.BotGroupInfo group : groups) {
                Boolean hit = isPluginEnabledIn(pluginId, group.getGroupId());
                // 无过滤器记录时按「全局生效」处理——与 isGloballyEnabled 一致
                if (hit == null || hit) {
                    enabled.add(group.getGroupId());
                }
            }
            return java.util.Collections.unmodifiableList(enabled);
        } catch (Exception e) {
            log.warn("查询启用群列表失败: pluginId={}", pluginId, e);
            return java.util.List.of();
        }
    }

    /**
     * 取插件的作用域状态对象（供外部诊断）。
     *
     * @param pluginId 插件标识
     * @return 过滤器描述；未记录时返回 {@code "(未记录)"}
     */
    public String describeScope(String pluginId) {
        com.yuier.yuni.plugin.api.ScopeFilter filter = pluginScopeFilters.get(pluginId);
        if (filter == null) {
            return "(未记录)";
        }
        return filter.explicitGroupWhitelist()
                .map(set -> "仅限群 " + set)
                .or(() -> filter.explicitGroupBlacklist().map(set -> "排除群 " + set))
                .orElse("全部群");
    }

    /**
     * 立即解析机器人实例（不抛异常）。
     *
     * <p>供 {@code PluginContext.bot()} 与命令引擎使用。解析失败返回
     * {@code null}，由调用方决定降级策略。</p>
     *
     * @return 机器人实例；未就绪时返回 {@code null}
     */
    public com.yuier.yuni.core.bot.YuniBot resolveBot() {
        java.util.function.Supplier<com.yuier.yuni.core.bot.YuniBot> supplier = this.botSupplier;
        if (supplier == null) {
            return null;
        }
        try {
            return supplier.get();
        } catch (Exception e) {
            log.debug("机器人实例尚未就绪", e);
            return null;
        }
    }

    /**
     * 取命令分发器（惰性构造，与 {@link #pipeline()} 共享实例）。
     *
     * <p>供插件注册命令时绑定处理器使用。</p>
     *
     * @return 命令分发器，永不为 {@code null}
     */
    public com.yuier.yuni.plugin.runtime.dispatch.CommandDispatcher commandDispatcher() {
        pipeline();
        return commandDispatcher;
    }

    // ==================== 访问器 ====================

    /**
     * 能力注册总表。
     *
     * @return 注册表
     */
    public RegistrationTable registrations() {
        return registrations;
    }

    /**
     * 服务注册表。
     *
     * @return 注册表
     */
    public ServiceRegistry services() {
        return services;
    }

    /**
     * Fiber 容器。
     *
     * @return 容器
     */
    public FiberRegistry fibers() {
        return fibers;
    }

    /**
     * 按插件标识取其资源目录。
     *
     * <p>优先返回加载时记录的资源目录；未记录时回退到模块句柄的
     * {@code jarParentPath}。</p>
     *
     * @param pluginId 插件标识
     * @return 资源目录；无信息时为空
     */
    public Optional<Path> dataDirOfId(String pluginId) {
        Path recorded = pluginDataDirs.get(pluginId);
        if (recorded != null) {
            return Optional.of(recorded);
        }
        return fibers.byId(pluginId)
                .flatMap(fiber -> fibers.allModules().stream()
                        .filter(m -> m.pluginIds().contains(pluginId))
                        .findFirst())
                .map(m -> m.jarParentPath() == null ? null : Path.of(m.jarParentPath()))
                .filter(java.util.Objects::nonNull);
    }

    /**
     * 运行时诊断描述。
     *
     * @return 形如 {@code "modules=13, plugins=23, effects=41, states={...}"}
     */
    public String describe() {
        return fibers.describe() + ", registrations=" + registrations.totalSize();
    }

    // ==================== 内部 ====================

    /**
     * 安全读取插件元数据。
     *
     * <p>{@code metadata()} 是插件代码，可能抛异常。此处兜底为一个默认元数据，
     * 使「元数据写错」不至于让插件完全无法加载——真正的失败应发生在
     * {@code apply} 阶段并带上完整上下文。</p>
     *
     * @param plugin 插件实例
     * @return 元数据；读取失败时返回以类名命名的默认值
     */
    private static PluginMetadata safeMetadata(YuniPlugin plugin) {
        try {
            PluginMetadata metadata = plugin.metadata();
            return metadata == null
                    ? PluginMetadata.of(plugin.getClass().getSimpleName()).build()
                    : metadata;
        } catch (Throwable t) {
            log.warn("插件 {} 的 metadata() 执行失败，使用默认元数据", plugin.getClass().getName(), t);
            return PluginMetadata.of(plugin.getClass().getSimpleName()).build();
        }
    }

    /**
     * 构造插件标识。
     *
     * <p>格式为 {@code 模块ID/插件名}；无模块时退化为 {@code 插件名}。
     * 与现行系统的 {@code 模块ID-插件类全限定名} 相比，不再把类名暴露给用户——
     * 类名是内部实现，改包名不应该导致插件标识变化。</p>
     *
     * @param moduleId 模块 ID；可为 {@code null}
     * @param metadata 元数据
     * @param plugin   插件实例（元数据名为空时用作兜底）
     * @return 插件标识
     */
    private static String buildPluginId(String moduleId, PluginMetadata metadata, YuniPlugin plugin) {
        String name = metadata.name();
        if (name == null || name.isBlank()) {
            name = plugin.getClass().getSimpleName();
        }
        return (moduleId == null || moduleId.isBlank()) ? name : moduleId + "/" + name;
    }

    /**
     * 计算插件尚未满足的依赖服务。
     *
     * @param plugin 插件实例
     * @return 缺失的服务类型列表；全部满足时为空列表
     */
    private List<Class<?>> missingDependencies(YuniPlugin plugin) {
        List<Class<?>> injected;
        try {
            injected = plugin.inject();
        } catch (Throwable t) {
            log.warn("插件 {} 的 inject() 执行失败，按无依赖处理", plugin.getClass().getName(), t);
            return List.of();
        }
        if (injected == null || injected.isEmpty()) {
            return List.of();
        }
        List<Class<?>> missing = new ArrayList<>();
        for (Class<?> type : injected) {
            if (type != null && !services.contains(type)) {
                missing.add(type);
            }
        }
        return missing;
    }

    /**
     * 供 {@link PluginContext} 委托子插件挂载。
     *
     * @param child   子插件
     * @param parent  父 Fiber
     * @param dataDir 子插件资源目录
     * @param filter  作用域过滤器
     * @return 子插件 Fiber
     */
    PluginFiber mountChild(YuniPlugin child, PluginFiber parent, Path dataDir, ScopeFilter filter) {
        return load(child, null, dataDir, parent == null ? null : parent.classLoader(), parent, filter);
    }

    /**
     * 供 {@link PluginContext} 构造撤销句柄：卸载一个子插件。
     *
     * @param pluginId 子插件标识
     * @return 撤销句柄
     */
    Disposable unloadHandle(String pluginId) {
        return () -> unload(pluginId);
    }
}
