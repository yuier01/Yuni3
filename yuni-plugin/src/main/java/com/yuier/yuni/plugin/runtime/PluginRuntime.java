package com.yuier.yuni.plugin.runtime;

import com.yuier.yuni.core.bot.BotGroupInfo;
import com.yuier.yuni.core.bot.JsonCodec;
import com.yuier.yuni.core.bot.YuniBot;
import com.yuier.yuni.core.model.bot.BotApp;
import com.yuier.yuni.core.task.DynamicTaskManager;
import com.yuier.yuni.plugin.api.PluginAdmin;
import com.yuier.yuni.plugin.api.PluginDeclaration;
import com.yuier.yuni.plugin.api.PluginDescriptor;
import com.yuier.yuni.plugin.api.PluginMetadata;
import com.yuier.yuni.plugin.api.ScopeFilter;
import com.yuier.yuni.plugin.api.YuniPlugin;
import com.yuier.yuni.plugin.runtime.UserScopeStore.UserScopeRecord;
import com.yuier.yuni.plugin.runtime.adapter.PluginEventAdapter;
import com.yuier.yuni.plugin.runtime.admin.PluginAdminRuntime;
import com.yuier.yuni.plugin.runtime.dispatch.CommandDispatcher;
import com.yuier.yuni.plugin.runtime.dispatch.EventPipeline;
import com.yuier.yuni.plugin.runtime.load.PluginArtifactLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
 * <p>本类把「加载 → 依赖检查 → apply → 注册索引」与「卸载 → 撤销 PluginEffect → 清索引」
 * 两条链路收拢到一处，热重载因此变成「记录位置 → 卸载 → 换 ClassLoader → 原位重挂」，
 * 不再需要维护八条类型列表与修正 index 偏移。</p>
 *
 * <h2>依赖驱动的加载</h2>
 * <p>插件通过 {@link YuniPlugin#declareRequiredServices()} 声明所需服务。加载流程会检查这些服务
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
 * 记录完整堆栈、把 Fiber 置为 {@link FiberState#FAILED}、<b>并回滚已建立的 PluginEffect</b>。
 * 回滚是必要的——否则会出现「插件标记为失败但监听器仍在工作」的半初始化状态。</p>
 *
 * <h2>与 ClassLoader 的关系</h2>
 * <p>每个 {@link PluginFiber} 持有加载它的 ClassLoader。{@link #unload} 会撤销
 * 该插件的全部 PluginEffect 并把 PluginEffect 计数归零，之后调用方才可以关闭 ClassLoader
 * （{@link #reload} 就是这么做的）。这条约束来自 2026-08 的 P0 事故
 * （插件重载静默失败），见 {@code Notes/技术报告-插件重载静默失败.md}。</p>
 *
 * <h2>能力范围</h2>
 * <p>本类已实现：加载单元加载（经 {@code PluginArtifactLoader}）、依赖检查与推进、
 * {@code apply} 调用、索引登记、卸载、热重载、事件分发（经 {@link #getPipeline()}）、
 * 作用域查询与写入、{@link com.yuier.yuni.plugin.api.PluginAdmin} 适配。
 * 重构 P1–P6 已全部完成，本类不再有「待接线」的方法。</p>
 *
 * @see PluginFiber
 * @see FiberContainer
 * @see PluginAbilityRegistry
 * @see com.yuier.yuni.plugin.runtime.UserScopeOverrides
 */
@Slf4j
@RequiredArgsConstructor
public final class PluginRuntime {

    /** 能力注册总表：所有插件注册的事件处理器与命令树。 */
    @Getter
    private final PluginAbilityRegistry registrations = new PluginAbilityRegistry();

    /** 服务注册表：框架内置服务与插件提供的服务。 */
    @Getter
    private final ServiceRegistry services = new ServiceRegistry();

    /** Fiber 容器：插件与加载单元索引。 */
    @Getter
    private final FiberContainer fibers = new FiberContainer();

    /** 插件标识 → 资源目录；用于依赖推进时重建上下文。 */
    private final Map<String, Path> pluginDataDirs = new ConcurrentHashMap<>();

    /**
     * 用户作用域覆盖——只存「用户显式改过」的部分。
     *
     * <p>作用域最终只有两层：</p>
     * <pre>
     *   用户覆盖（本表）  &gt;  元数据 defaultEnable
     * </pre>
     *
     * <p>最终值由 {@link #effectiveScopeOf} / {@link #isPluginEnabledIn}
     * 每次现算。把合并结果缓存下来会成为一份随时可能过期的快照。</p>
     */
    private final UserScopeOverrides userScopeOverrides =
            new UserScopeOverrides();

    /**
     * 作用域持久化端口。
     *
     * <p>为 {@code null} 时作用域配置仅存在于内存——测试场景与未装配数据库的
     * 集成场景都是这样，因此不视为错误。</p>
     */
    private volatile UserScopeStore userScopeStore;

    /**
     * 机器人实例供应函数。
     *
     * <p>由启动流程在 bot 创建完毕后设置（见 {@link #bindBot}）。用供应函数
     * 而非直接持有实例：bot 由 engine 的 {@code BotManager} 动态创建，
     * 时机晚于本运行时的构造。</p>
     */
    private volatile Supplier<YuniBot> botSupplier;

    /** 事件分发流水线；首次访问时构造并缓存。 */
    private volatile EventPipeline pipeline;

    /** 命令分发器；由 {@link #getPipeline()} 惰性构造。 */
    private volatile CommandDispatcher commandDispatcher;

    /** 事件适配工厂；由 {@link #getPipeline()} 惰性构造。 */
    private volatile PluginEventAdapter eventAdapter;

    /** 机器人业务配置（提供命令前缀）；由启动流程设置。 */
    private volatile BotApp botApp;

    /** 定时任务管理器；由外部注入，可为 {@code null}（测试场景）。 */
    private final DynamicTaskManager taskManager;

    /** JSON 编解码器；可为 {@code null}（测试场景，则依赖它的能力不可用）。 */
    private final JsonCodec jsonCodec;

    /**
     * 加载单元加载器；用于热重载时重新读取 jar。
     *
     * <p>为 {@code null} 时 {@link #reload} 会抛出可读的错误而不是 NPE——
     * 测试场景通常不装配它，因此这是常见情况而非异常情况。</p>
     */
    private volatile PluginArtifactLoader artifactLoader;

    /**
     * 构造运行时（不带 JSON 编解码器）。
     *
     * <p>两参构造器（含 {@code JsonCodec}）由 {@link RequiredArgsConstructor}
     * 生成，参数说明见 {@code taskManager} / {@code jsonCodec} 字段注释。</p>
     *
     * @param taskManager 定时任务管理器；{@code null} 时 {@code ctx.schedule(...)} 不可用
     */
    public PluginRuntime(DynamicTaskManager taskManager) {
        this(taskManager, null);
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
        Optional<Object> previous = services.register(type, impl);
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
                services.register(type, impl);
            }
        }
        return this;
    }

    // ==================== 加载 ====================

    /**
     * 加载一个插件。
     *
     * <p>流程：读声明 → 组装元数据 → 建 Fiber → 检查依赖 → 调 {@code apply(ctx)}
     * → 登记索引。依赖未满足时停在 PENDING 并<b>不</b>调用 {@code apply}。</p>
     *
     * @param plugin     插件实例
     * @param artifact   所属加载单元；不能为 {@code null}
     * @param descriptor 配置侧展示信息；不能为 {@code null}
     * @return 建好的 Fiber（无论加载成功、失败还是停在 PENDING）
     */
    public PluginFiber load(YuniPlugin plugin,
                            PluginArtifact artifact,
                            PluginDescriptor descriptor) {
        // 判空
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(descriptor, "descriptor");
        if (artifact.getId() == null || fibers.artifact(artifact.getId()).isEmpty()) {
            // Artifact 由加载器登记；直接调用 load 时也必须先登记，避免出现
            // 「插件已加载但管理端/重载流程找不到它所属的加载单元」
            throw new IllegalStateException("加载单元未登记: " + artifact);
        }

        // 组装 metadata
        PluginDeclaration declaration = safeDeclaration(plugin);
        List<Class<?>> requiredServices = safeRequiredServices(plugin);
        PluginMetadata metadata = PluginMetadata.of(
                artifact.getId(), declaration, descriptor, requiredServices);
        String id = metadata.getId();

        if (fibers.byId(id).isPresent()) {
            throw new IllegalStateException(
                    "插件 " + id + " 已加载。请先 unload 再重新 load，或使用 reload。");
        }

        // 组装 fiber
        ClassLoader classLoader = artifact.getClassLoader();
        PluginFiber fiber = new PluginFiber(plugin, metadata, classLoader);
        fibers.add(fiber);
        Path dataDir = artifact.getDataDir();
        pluginDataDirs.put(id, dataDir);

        // 当依赖未就绪时，先停下来直接返回，待后续流程反复推进
        List<Class<?>> missing = missingDependencies(requiredServices);
        if (!missing.isEmpty()) {
            for (Class<?> type : missing) {
                fiber.addPendingDependency(type);
            }
            log.warn("插件 {} 依赖未满足，停在 PENDING 状态。缺失服务: {}",
                    id, missing.stream().map(Class::getName).toList());
            return fiber;
        }

        // apply
        applyTo(fiber, dataDir);
        return fiber;
    }
    /**
     * 登记一个加载单元。
     *
     * <p>加载器在实例化插件之前调用，保证「jar 里一个插件都没有」时
     * Artifact 仍然可被管理端与重载流程看到。</p>
     *
     * @param artifact 加载单元；不能为 {@code null}
     */
    public void registerArtifact(PluginArtifact artifact) {
        Objects.requireNonNull(artifact, "artifact");
        fibers.addArtifact(artifact);
    }

    /**
     * 执行插件的 {@code apply}，并把 Fiber 推进到 ACTIVE。
     *
     * <p>返回 {@code false} 不是异常路径，而是三种正常结果之一：</p>
     * <ul>
     *   <li>Fiber 已被其他线程接管（并发 {@link #tryActivate} 只有一个赢家）；</li>
     *   <li>抢占成功后、apply 之前就被卸载（登记表已封存）；</li>
     *   <li>apply 正常返回，但期间 Fiber 被卸载，{@code LOADING → ACTIVE} 的 CAS 失败。</li>
     * </ul>
     *
     * @param fiber   目标 Fiber，必须仍处于 PENDING
     * @param dataDir 插件资源目录；可为 {@code null}
     * @return 成功转为 ACTIVE 返回 {@code true}
     */
    private boolean applyTo(PluginFiber fiber, Path dataDir) {
        /*
         * 用 CAS 抢占 PENDING → LOADING。
         *
         * 这里曾写成「先检查 getEffects().isSealed()，再 markLoading()」，那是
         * check-then-act：两个并发 tryActivate 都能通过检查，于是同一个插件被
         * apply 两次（监听器、定时任务、数据库连接全部重复注册）。
         * 现在只有一个线程能拿到执行权。
         */
        if (!fiber.tryBeginLoading()) {
            // 已被其他线程接管，或已离开 PENDING。并发 tryActivate 是合法路径，
            // 因此这里不是错误，静默放弃即可。
            return false;
        }

        /*
         * 抢占成功后仍要复查封存标志：卸载可能恰好发生在「抢占成功」与「开始 apply」  -- 这是大肥鱼说的，虽然我不知道是否真的会有这种极端情况，但再加一层判断也没什么
         * 之间。此时登记表已封存，继续 apply 会让插件注册的一切被就地撤销——
         * 插件作者看到的是「代码跑了但什么也没生效」，很难排查，因此直接放弃。
         */
        if (fiber.getEffects().isSealed()) {
            return false;
        }

        PluginContext ctx = PluginContext.forFiber(
                fiber, services, registrations, taskManager, dataDir, jsonCodec);
        // 让上下文能回调运行时（作用域查询、管理接口）
        ctx.bindRuntime(this);

        try {
            fiber.getPlugin().apply(ctx);
            if (fiber.tryMarkActive()) {
                log.info("插件已加载: {} | 贡献能力 {}", fiber.getId(), registrations.kindsOf(fiber));
                return true;
            }

            /*
             * apply 期间 Fiber 被卸载：状态已不在 LOADING，CAS 拒绝把它回写成 ACTIVE。
             * 必须回滚已建立的 PluginEffect，否则会留下「插件已卸载但监听器还在」的悬挂引用。
             * dispose() 可能已经撤过一遍，rollbackEffects 是幂等的（封存 CAS 兜底）。
             */
            List<String> rollbackFailures = fiber.rollbackEffects();
            log.warn("插件 {} 在 apply 期间被卸载，已放弃激活{}", fiber.getId(),
                    rollbackFailures.isEmpty() ? "" : "（回滚失败: " + rollbackFailures + "）");
            return false;
        } catch (Throwable t) {
            // 必须捕获 Throwable：类加载失败抛的是 Error，
            // 只 catch Exception 会让插件静默消失（2026-08 P0 事故的教训）
            boolean marked = fiber.tryMarkFailed(t);
            if (marked) {
                log.error("插件 {} 加载失败，已回滚其 PluginEffect", fiber.getId(), t);
            } else {
                log.warn("插件 {} 加载失败，但期间已被卸载，状态不再改为 FAILED", fiber.getId(), t);
            }

            // 回滚：撤销已建立的 PluginEffect，避免「标记为失败但监听器仍在工作」。
            // 用 rollbackEffects 而非 dispose——后者会把状态改成 DISPOSED，
            // 使这次失败在管理端与 reportFailedPlugins() 中消失。
            List<String> failures = fiber.rollbackEffects();
            if (!failures.isEmpty()) {
                log.error("插件 {} 回滚期间有清理失败: {}", fiber.getId(), failures);
            }
            // 索引保留（状态为 FAILED），以便管理端展示失败插件；调用方可选择移除
            return false;
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
        if (fiber.getState() != FiberState.PENDING) {
            // 已经 ACTIVE（可能被上一轮推进）或已 FAILED / DISPOSED，都不再尝试
            return false;
        }
        List<Class<?>> missing = missingDependencies(fiber.getMetadata().getRequiredServices());
        if (!missing.isEmpty()) {
            fiber.clearPendingDependencies();
            missing.forEach(fiber::addPendingDependency);
            return false;
        }
        fiber.clearPendingDependencies();
        /*
         * 不在这里判状态——applyTo 内部用 PENDING → LOADING 的 CAS 抢占，
         * 并发调用时只有一个线程能真正执行 apply。上面的 getState() 检查只是
         * 快速跳过已确定不在 PENDING 的 Fiber，不承担正确性。
         */
        return applyTo(fiber, dataDir);
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
     * @param dataDirResolver 由插件标识解析资源目录的函数
     * @return 本次新激活的插件数量
     */
    public int activatePending(Function<String, Path> dataDirResolver) {
        int activated = 0;
        boolean progressed = true;
        while (progressed) {
            progressed = false;
            for (PluginFiber fiber : fibers.withState(FiberState.PENDING)) {
                Path dir = dataDirResolver.apply(fiber.getId());
                if (tryActivate(fiber.getId(), dir)) {
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
            String deps = fiber.getPendingDependencies().stream()
                    .map(Class::getName)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("(未记录)");
            report.add(fiber.getId() + "：缺失 " + deps);
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
            String cause = fiber.getFailureCause()
                    .map(t -> t.getClass().getSimpleName() + ": " + t.getMessage())
                    .orElse("(未记录)");
            report.add(fiber.getId() + "：" + cause);
        }
        return Collections.unmodifiableList(report);
    }

    // ==================== 卸载 ====================

    /**
     * 卸载一个插件。
     *
     * <p>流程：注销其提供的服务 → 递归卸载子 Fiber → 撤销全部 PluginEffect → 清索引。
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
        /*
         * 用户配置的群例外（{@link #userScopeOverrides}）<b>不</b>在这里清除
         * ——重载会先 unload 再 load，清掉就等于重载丢配置。
         * 需要真正清除用户配置时走 resetPluginScope。
         */

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
     * <p>按加载顺序的逆序卸载，与 PluginEffect 的逆序撤销保持一致。</p>
     *
     * @return 清理失败的描述列表
     */
    public List<String> unloadAll() {
        List<String> failures = new ArrayList<>();
        List<PluginFiber> all = fibers.getAll();
        for (int i = all.size() - 1; i >= 0; i--) {
            failures.addAll(unload(all.get(i).getId()));
        }
        return Collections.unmodifiableList(failures);
    }

    // ==================== 重载 ====================

    /**
     * 重载一个插件所属的加载单元（jar）。
     *
     * <p>重载是<b>同步</b>的：调用返回时新插件已经装好。调用方因此能立即判断
     * 「重载成功了吗」，而不是把结果交给一次无法观测的异步任务。</p>
     *
     * <h2>粒度是 jar，不是插件</h2>
     * <p>Java 的 ClassLoader 以 jar 为单位，无法只重载其中一个类。因此本方法会
     * 卸载并重新加载该 jar 内的<b>全部</b>插件。若 jar 内有多个插件，它们一起重启。</p>
     *
     * <h2>作用域配置</h2>
     * <p>卸载前先快照用户配置，重新加载后按插件标识回填——保证「换实现不换配置」。</p>
     *
     * <h2>调用时机约束</h2>
     * <p>不要从目标 jar 自己的处理器里调用本方法：那会在一次命令处理尚未返回时
     * 卸载掉正在执行的类。正确做法是由另一个插件的处理器或 webapi 请求线程触发。</p>
     *
     * @param pluginId 插件标识
     * @return 重载后的插件 Fiber 列表
     * @throws IllegalStateException 插件不存在、不属于任何 jar、jar 缺失，或未装配加载器
     */
    public List<PluginFiber> reload(String pluginId) {
        if (artifactLoader == null) {
            throw new IllegalStateException(
                    "热重载需要 PluginArtifactLoader，但本运行时未装配它"
                            + "（集成场景应调用 bindArtifactLoader）。");
        }
        PluginFiber fiber = fibers.byId(pluginId).orElseThrow(() ->
                new IllegalStateException("插件不存在: " + pluginId));
        String artifactId = fiber.getArtifactId();
        if (artifactId.isBlank()) {
            throw new IllegalStateException("插件不属于任何加载单元: " + pluginId);
        }
        PluginArtifact artifact = fibers.artifact(artifactId).orElseThrow(() ->
                new IllegalStateException("加载单元不存在: " + artifactId));
        java.io.File jarFile = artifact.getJarPath().toFile();
        if (!jarFile.isFile()) {
            throw new IllegalStateException(
                    "找不到加载单元 " + artifactId + " 的 jar 文件: "
                            + jarFile.getAbsolutePath());
        }

        // 快照作用域例外：重新加载后按插件标识回填，保证「换实现不换配置」
        List<PluginFiber> siblings = fibers.pluginsOfArtifact(artifactId);
        Map<String, Map<Long, Boolean>> scopeSnapshot = new LinkedHashMap<>();
        for (PluginFiber sibling : siblings) {
            Map<Long, Boolean> values = userScopeOverrides.all(sibling.getId());
            if (!values.isEmpty()) {
                scopeSnapshot.put(sibling.getId(), values);
            }
        }

        // 逆序卸载，与 PluginEffect 的逆序撤销保持一致
        for (int i = siblings.size() - 1; i >= 0; i--) {
            unload(siblings.get(i).getId());
        }

        // 关闭类加载器：Windows 下 jar 句柄不释放就无法覆盖同名文件
        fibers.removeArtifact(artifactId);
        closeQuietly(artifact.getClassLoader());

        log.info("加载单元 {} 已卸载，开始重新加载 {}", artifactId, jarFile.getName());

        List<PluginFiber> loaded =
                artifactLoader.loadArtifact(jarFile, artifact.getDataDir());

        int restored = 0;
        for (PluginFiber reloaded : loaded) {
            Map<Long, Boolean> values = scopeSnapshot.get(reloaded.getId());
            if (values != null) {
                userScopeOverrides.putAll(reloaded.getId(), values);
                restored++;
            }
        }

        log.info("加载单元 {} 重载完成：{} 个插件，恢复 {} 项作用域配置",
                artifactId, loaded.size(), restored);
        return loaded;
    }
    /**
     * 安静地关闭一个类加载器。
     *
     * <p>关闭失败不应中断重载流程——jar 句柄没释放最多导致下一次覆盖失败，
     * 而抛异常会让「卸载已完成、加载未开始」成为一个无法恢复的中间态。</p>
     *
     * @param classLoader 类加载器；可为 {@code null}
     */
    private static void closeQuietly(ClassLoader classLoader) {
        if (classLoader instanceof java.io.Closeable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.warn("关闭插件类加载器失败（jar 句柄可能未释放）: {}", e.getMessage());
            }
        }
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
    public PluginRuntime bindBot(Supplier<YuniBot> botSupplier,
                                 BotApp botApp) {
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
    public EventPipeline getPipeline() {
        EventPipeline current = this.pipeline;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (this.pipeline == null) {
                this.eventAdapter = new PluginEventAdapter(
                        botSupplier, jsonCodec);
                this.commandDispatcher = new CommandDispatcher(
                        registrations,
                        // 命令引擎需要 bot 的具体实例（用于回复段解析）；
                        // 这里即时解析一次，未就绪时传 null，由引擎的静态回退兜住
                        resolveBot(),
                        botApp,
                        // 权限判定委托给权限服务——插件可通过 ctx.getPermission() 覆盖注册
                        com.yuier.yuni.plugin.runtime.dispatch.CommandDispatcher.fromService(
                                services.resolve(com.yuier.yuni.plugin.api.service.PermissionService.class)
                                        .orElse(null)),
                        // 作用域判定委托给本运行时的当前配置——用户的群粒度启停
                        // 随时可变，因此必须在分发时查而不是注册时定
                        this::effectiveScopeOf);
                this.pipeline = new EventPipeline(
                        registrations, this, eventAdapter, commandDispatcher);
            }
            return this.pipeline;
        }
    }

    /**
     * 取插件的作用域过滤器（合成后的谓词）。
     *
     * <p>用于事件分发：分发路径只需要回答「这个群是否生效」，
     * 因此把多个例外折叠成一个谓词。需要区分「显式设置」与「回落默认值」
     * 的调用方（例如 {@code PluginAdmin}）应当用
     * {@link #isPluginEnabledIn} 而不是本方法。</p>
     *
     * @param pluginId 插件标识
     * @return 过滤器；插件不存在或从未注册时为空
     */
    public Optional<ScopeFilter> effectiveScopeOf(String pluginId) {
        String owner = pluginId;
        return fibers.byId(owner).map(fiber ->
                userScopeOverrides.toFilter(owner, effectiveDefaultEnabled(owner)));
    }

    /**
     * 取插件被显式开启的群（管理端展示用）。
     *
     * @param pluginId 插件标识
     * @return 显式开启的群号；无则为空集
     */
    public Set<Long> explicitlyEnabledGroups(String pluginId) {
        return userScopeOverrides.explicitlyEnabledGroups(pluginId);
    }

    /**
     * 判定插件是否在指定群生效（供主动插件经 {@code ctx.getScope()} 查询）。
     *
     * <p>判定链：用户显式例外 → 元数据的 {@code defaultEnable}（用户显式全局开关
     * 优先于元数据）。插件作者不参与群级配置。</p>
     *
     * @param pluginId 插件标识
     * @param groupId  群号
     * @return 生效返回 {@code true}；<b>插件不存在时返回 {@code null}</b>
     */
    public Boolean isPluginEnabledIn(String pluginId, long groupId) {
        // 用户显式配置的群例外优先级最高
        String owner = pluginId;
        // 用户显式配置的群例外优先级最高
        Optional<Boolean> explicit = userScopeOverrides.explicitValue(owner, groupId);
        if (explicit.isPresent()) {
            return explicit.get();
        }
        if (!fiberExists(owner)) {
            return null;
        }
        return effectiveDefaultEnabled(owner);
    }

    /**
     * 判定插件是否已被加载。
     *
     * @param pluginId 插件标识
     * @return 已加载返回 {@code true}
     */
    private boolean fiberExists(String pluginId) {
        return pluginId != null && fibers.byId(pluginId).isPresent();
    }

    /**
     * 取插件的有效默认启用值——用户显式全局开关优先，否则看元数据。
     *
    
     *
     * @param pluginId 插件标识
     * @return 默认启用返回 {@code true}
     */
    private boolean effectiveDefaultEnabled(String pluginId) {
        String owner = pluginId;
        // 用户显式设置过全局开关 → 以它为准
        Optional<Boolean> userGlobal = userScopeOverrides.explicitGlobalDefault(owner);
        if (userGlobal.isPresent()) {
            return userGlobal.get();
        }
        // 否则回落到元数据。插件不存在时保守返回 false：
        // 配置损坏时「不放行」比「误放行」安全
        return fibers.byId(owner)
                .map(fiber -> fiber.getMetadata().isDefaultEnable())
                .orElse(false);
    }

    /**
     * 判定插件是否在全局作用域生效。
     *
     * <h2>语义</h2>
     * <p>「全局生效」= 未配置任何群例外，或全部例外都是「黑名单」形态
     * （即默认开启、个别群关闭）。白名单形态（默认关闭、个别群开启）
     * 不算全局生效。</p>
     *
     * @param pluginId 插件标识
     * @return 全局生效返回 {@code true}
     */
    public boolean isGloballyEnabled(String pluginId) {
        /*
         * 契约（见 PluginAdmin.isGloballyEnabled）：返回「有效全局默认值是否生效」。
         *
         * <h2>为什么是「默认值」而不是「是否每个群都生效」</h2>
         *
         * 本方法与 setPluginGlobalEnabled 是一对读写操作，<b>必须对偶</b>：
         *
         *     setPluginGlobalEnabled(p, V);  isGloballyEnabled(p) == V
         *
         * 若把语义定义成「是否每个群都生效」，这个等式在「默认开 + 个别群关」
         * 时就不成立了——setter 写 true，getter 读回 false。那会让管理端的
         * 全局开关在保存后立刻显示成相反的状态，也会让「全局关闭」这个操作
         * 无法被幂等地表达（关掉一个已经有群例外的插件时，无法区分
         * 「本来就是关的」与「刚被关掉」）。
         *
         * <h2>与 isPluginEnabledIn 的关系</h2>
         *
         *     isPluginEnabledIn(p, g)  ==  (该群有例外 ? 例外的值 : isGloballyEnabled(p))
         *
         * 也就是说：本方法是「群例外之外的那个底座」，
         * 而 isPluginEnabledIn 是「底座 + 该群的覆盖」。
         *
         * <h2>群例外不影响本方法</h2>
         *
         * 「默认开 + 个别群关」应返回 true——它整体是开的，只是有几个例外。
         * 管理端应当把它展示为「全局开 + 3 个群例外」，
         * 而不是含糊的「全局关」。
         */
        return effectiveDefaultEnabled(pluginId);
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
    public List<Long> groupsWhereEnabled(String pluginId) {
        YuniBot bot = resolveBot();
        if (bot == null || pluginId == null) {
            return java.util.List.of();
        }
        try {
            List<BotGroupInfo> groups =
                    bot.getGroupList().orElse(java.util.List.of());
            List<Long> enabled = new ArrayList<>();
            for (BotGroupInfo group : groups) {
                Boolean hit = isPluginEnabledIn(pluginId, group.getGroupId());
                // 无显式设置时回落到插件的 defaultEnable——与 ctx.getScope() 一致
                boolean effective = hit != null ? hit : effectiveDefaultEnabled(pluginId);
                if (effective) {
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
        // 用户配置的群例外 + 有效默认值
        String owner = pluginId;
        ScopeFilter persisted =
                userScopeOverrides.toFilter(owner, effectiveDefaultEnabled(owner));
        if (persisted.getExceptionGroups().isEmpty()) {
            return persisted.isEnabledByDefault() ? "全部群" : "未记录";
        }
        // 与旧实现保持同样的措辞，便于日志检索与既有文档对照
        return persisted.isEnabledByDefault()
                ? "排除群 " + persisted.getExceptionGroups()
                : "仅限群 " + persisted.getExceptionGroups();
    }

    // ==================== 作用域写入 ====================

    /**
     * 修改「插件在某群是否生效」，并返回是否真的改变了配置。
     *
     * <h2>为什么放在运行时而不是 PluginAdmin 实现里</h2>
     * <p>{@link com.yuier.yuni.plugin.runtime.UserScopeOverrides} 是运行时的私有状态，
     * 而分发给插件看的 {@link #isPluginEnabledIn} 也从它读取。写入与读取必须在
     * 同一个类里，否则会出现「管理端改了 A、分发读的是 B」这类问题。</p>
     *
     * <h2>三态写入语义</h2>
     * <p>每个群有三个状态：<b>未设置</b>（按 {@code defaultEnable}）、
     * 显式开启、显式关闭。写入的值与默认值一致时<b>删除</b>该群的记录，
     * 使操作可逆——「关闭再开启」会回到「未设置」而不是留下一条无意义的记录。
     * 具体实现在 {@link com.yuier.yuni.plugin.runtime.UserScopeOverrides#set}。</p>
     *
     * <h2>写库</h2>
     * <p>本方法在改完内存后立即调用 {@link com.yuier.yuni.plugin.runtime.UserScopeStore}
     * 落库（若已装配）。落库失败<b>不回滚内存</b>：当前进程内的行为已经按新配置
     * 生效，回滚只会让内存与用户刚看到的反馈不一致。失败会记 error 日志，
     * 下一次成功写入会覆盖它。</p>
     *
     * @param pluginId 插件标识
     * @param groupId  群号
     * @param enabled  该群是否生效
     * @throws IllegalArgumentException 插件不存在
     */
    public void setPluginEnabledIn(String pluginId, long groupId, boolean enabled) {
        // 存在性校验
        String owner = pluginId;
        // 存在性校验；后续比较用「有效默认值」而不是元数据默认值——
        // 否则「全局关 + 单独开启某群」在 metadata.defaultEnable = true 时
        // 会被误判为「与默认值一致」而丢掉该群的显式值。
        fibers.byId(owner).orElseThrow(() ->
                new IllegalArgumentException("插件不存在: " + pluginId));

        userScopeOverrides.set(owner, groupId, enabled, effectiveDefaultEnabled(owner));
        log.info("插件 {} 在群 {} 的生效状态改为 {}（当前: {}）",
                pluginId, groupId, enabled, describeScope(owner));
        persistScope(owner);
    }

    /**
     * 设置插件的全局启停（不限群）。
     *
     * <p>语义：把「默认启用值」改为 {@code enabled}，<b>保留</b>已有的群例外。
     * 这是管理端「全局开关」的实现。</p>
     *
     * <h3>为什么不复用 setPluginEnabledIn</h3>
     * <p>群例外与全局开关是三态模型里的两个正交维度：前者是「这个群与默认值
     * 相反」，后者是「默认值是什么」。{@code setPluginEnabledIn} 只改前者，
     * 无法表达「整体打开一个默认关闭的插件」——那需要改默认值本身。</p>
     *
     * @param pluginId 插件标识
     * @param enabled  全局是否生效
     * @throws IllegalArgumentException 插件不存在
     */
    public void setPluginGlobalEnabled(String pluginId, boolean enabled) {
        String owner = pluginId;
        if (!fiberExists(owner)) {
            throw new IllegalArgumentException("插件不存在: " + pluginId);
        }
        userScopeOverrides.setGlobalDefault(owner, enabled);
        log.info("插件 {} 的全局启停改为 {}（当前: {}）", pluginId, enabled, describeScope(owner));
        persistScope(owner);
    }

    /**
     * 清除某插件的全部群例外，回到元数据声明的默认值。
     *
     * @param pluginId 插件标识
     * @return 本来是否有配置可清
     */
    public boolean resetPluginScope(String pluginId) {
        String owner = pluginId;
        boolean hadExceptions = userScopeOverrides.clear(owner).isPresent();
        boolean hadGlobal = userScopeOverrides.clearGlobalDefault(owner);
        if (hadExceptions || hadGlobal) {
            persistScope(owner);
            log.info("插件 {} 的群例外与全局开关已重置为插件默认值", pluginId);
        }
        return hadExceptions || hadGlobal;
    }

    // ==================== 作用域装载与持久化 ====================

    /**
     * 绑定作用域持久化端口。
     *
     * @param store 持久化实现；{@code null} 表示仅内存（测试场景）
     * @return {@code this}，便于链式配置
     */
    public PluginRuntime bindUserScopeStore(
            UserScopeStore store) {
        this.userScopeStore = store;
        return this;
    }

    /**
     * 在插件加载<b>之后</b>从持久化存储恢复作用域配置。
     *
     * <h2>为什么必须在插件加载之后</h2>
     * <p>恢复时需要用每个插件的 {@code defaultEnable} 判断「这条记录算不算例外」，
     * 而 {@code defaultEnable} 在代码里（{@code PluginMetadata}），
     * 只有插件实例化后才能读到。因此本方法由
     * {@code PluginBootstrap.initialize()} 在全部插件加载完之后调用。</p>
     *
     * <h2>与插件自己声明的 {@code ScopeFilter} 的关系</h2>
     * <p>持久化配置<b>覆盖</b>运行期显式传入的过滤器。理由：持久化的是用户的
     * 显式选择，而过滤器是插件作者写的默认建议——用户选择应当胜出。
     * （与旧系统一致：{@code group_plugin_ability} 记录优先于
     * {@code metadata.defaultEnable}。）</p>
     *
     * @return 恢复了配置的插件数量；未装配存储时返回 0
     */
    public int restoreScopes() {
        UserScopeStore store = this.userScopeStore;
        if (store == null) {
            return 0;
        }
        Map<String, UserScopeRecord> stored;
        try {
            stored = store.loadAll();
        } catch (Exception e) {
            // 读配置失败不应阻止启动——退化为「全部按默认值」
            log.error("读取插件作用域配置失败，将全部按插件默认值处理", e);
            return 0;
        }
        if (stored == null || stored.isEmpty()) {
            return 0;
        }

        int restored = 0;
        for (PluginFiber fiber : fibers.getAll()) {
            UserScopeRecord record =
                    stored.get(fiber.getId());
            if (record == null || record.isEmpty()) {
                continue;
            }
            boolean pluginDefault = fiber.getMetadata().isDefaultEnable();
            // 全局开关：用户设置过就用它，否则用插件默认值。
            // 例外群的「相反值」判定以全局开关为基准，因此必须先定下它
            boolean global = record.getGlobalEnabled() != null ? record.getGlobalEnabled() : pluginDefault;
            userScopeOverrides.setGlobalDefault(fiber.getId(), global);
            userScopeOverrides.putAll(fiber.getId(),
                    toValueMap(record.getExceptionGroups(), global));
            restored++;
        }
        log.info("已从存储恢复 {} 个插件的群粒度启停配置", restored);
        return restored;
    }

    /**
     * 把「与全局开关相反的例外群集合」还原成「群号 → 该群是否生效」。
     *
     * @param exceptionGroups 例外群集合
     * @param globalEnabled   全局是否生效
     * @return 群号 → 是否生效
     */
    private static Map<Long, Boolean> toValueMap(Set<Long> exceptionGroups,
                                                           boolean globalEnabled) {
        Map<Long, Boolean> values = new LinkedHashMap<>();
        for (Long groupId : exceptionGroups) {
            // 例外群的值与全局开关相反——这正是「例外」的定义
            values.put(groupId, !globalEnabled);
        }
        return values;
    }

    /**
     * 把一个插件当前的作用域配置落库。
     *
     * <p>写的是「该插件的完整配置」，因此实现应当是「先删后插」——
     * 这样与用户的三态配置完全一致，不会留下旧群的残留记录。</p>
     *
     * @param pluginId 插件标识
     */
    private void persistScope(String pluginId) {
        UserScopeStore store = this.userScopeStore;
        if (store == null) {
            return;
        }
        
        String owner = pluginId;
        try {
            boolean pluginDefault = fibers.byId(owner)
                    .map(fiber -> fiber.getMetadata().isDefaultEnable())
                    .orElse(false);
            // 只持久化「用户显式设置过」的全局开关；
            // 未设置时存 null，让下次启动重新回落到插件元数据
            Boolean global = userScopeOverrides.explicitGlobalDefault(owner).orElse(null);
            boolean effectiveGlobal = global != null ? global : pluginDefault;
            // 例外群 = 与有效全局值相反的那些群
            Set<Long> exceptionGroups =
                    userScopeOverrides.oppositeGroups(owner, effectiveGlobal);
            store.save(owner,
                    new UserScopeRecord(
                            global, exceptionGroups));
        } catch (Exception e) {
            // 不回滚内存：用户已经看到操作成功，回滚只会让内存与实际反馈不一致。
            // 下一次成功写入会覆盖这次失败的记录。
            log.error("保存插件 {} 的作用域配置失败（当前进程内配置已生效，重启后会丢失）",
                    owner, e);
        }
    }

    // ==================== 加载单元与插件的反向查询 ====================

    /**
     * 取某插件所属的加载单元标识。
     *
     * @param pluginId 插件标识
     * @return 加载单元标识；插件不属于任何加载单元或不存在时为空
     */
    public Optional<String> artifactIdOf(String pluginId) {
        return fibers.byId(pluginId)
                .map(PluginFiber::getArtifactId)
                .filter(id -> !id.isBlank());
    }

    /**
     * 取可用的插件管理服务。
     *
     * <p>返回的实例是<b>每次新建的轻量适配器</b>，它不持有状态（全部状态都在
     * 本运行时里）。因此不需要缓存，也不存在「同一运行时的两个管理视图不一致」
     * 的问题。</p>
     *
     * @return 插件管理服务
     */
    public PluginAdmin getAdmin() {
        return new PluginAdminRuntime(this);
    }

    /**
     * 绑定加载单元加载器。
     *
     * <p>热重载需要重新读 jar，而读 jar 的逻辑在 {@code PluginArtifactLoader} 里。
     * 该对象由 Spring 装配，晚于运行时的构造时机，因此用后置绑定而非构造器注入。</p>
     *
     * @param artifactLoader 加载单元加载器；可为 {@code null}（测试场景，则 reload 不可用）
     * @return {@code this}，便于链式配置
     */
    public PluginRuntime bindArtifactLoader(
            PluginArtifactLoader artifactLoader) {
        this.artifactLoader = artifactLoader;
        return this;
    }

    /**
     * 立即解析机器人实例（不抛异常）。
     *
     * <p>供 {@code PluginContext.getBot()} 与命令引擎使用。解析失败返回
     * {@code null}，由调用方决定降级策略。</p>
     *
     * @return 机器人实例；未就绪时返回 {@code null}
     */
    public YuniBot resolveBot() {
        Supplier<YuniBot> supplier = this.botSupplier;
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
     * 取命令分发器（惰性构造，与 {@link #getPipeline()} 共享实例）。
     *
     * <p>供插件注册命令时绑定处理器使用。</p>
     *
     * @return 命令分发器，永不为 {@code null}
     */
    public CommandDispatcher getCommandDispatcher() {
        getPipeline();
        return commandDispatcher;
    }

    // ==================== 访问器 ====================
    // getRegistrations() / getServices() / getFibers() 由字段上的 @Getter 生成。

    /**
     * 按插件标识取其资源目录。
     *
     * <p>优先返回加载时记录的资源目录；未记录时回退到加载单元的
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
                .map(PluginFiber::getArtifactId)
                .flatMap(fibers::artifact)
                .map(PluginArtifact::getDataDir);
    }

    /**
     * 运行时诊断描述。
     *
     * @return 形如 {@code "artifacts=13, plugins=23, effects=41, states={...}"}
     */
    public String describe() {
        return fibers.describe() + ", registrations=" + registrations.getTotalSize();
    }

    // ==================== 内部 ====================

    /**
     * 安全读取插件声明。
     *
     * <p>{@code getDeclaration()} 是插件代码，可能抛异常。此处兜底为
     * 「以类名命名、默认启用、非内置」，使声明写错不至于让插件完全无法加载。</p>
     *
     * @param plugin 插件实例
     * @return 声明；读取失败时返回以类名命名的默认值
     */
    private static PluginDeclaration safeDeclaration(YuniPlugin plugin) {
        try {
            PluginDeclaration declaration = plugin.getDeclaration();
            return declaration == null
                    ? PluginDeclaration.of(plugin.getClass().getSimpleName()).build()
                    : declaration;
        } catch (Throwable t) {
            log.warn("插件 {} 的 getDeclaration() 执行失败，使用默认声明",
                    plugin.getClass().getName(), t);
            return PluginDeclaration.of(plugin.getClass().getSimpleName()).build();
        }
    }

    /**
     * 安全读取插件声明的必需服务。
     *
     * @param plugin 插件实例
     * @return 服务类型列表；读取失败时为空
     */
    private static List<Class<?>> safeRequiredServices(YuniPlugin plugin) {
        try {
            List<Class<?>> services = plugin.declareRequiredServices();
            return services == null ? List.of() : List.copyOf(services);
        } catch (Throwable t) {
            log.warn("插件 {} 的 declareRequiredServices() 执行失败，按无依赖处理",
                    plugin.getClass().getName(), t);
            return List.of();
        }
    }

    /**
     * 计算插件尚未满足的依赖服务。
     *
     * @param required 插件声明的必需服务
     * @return 缺失的服务类型列表；全部满足时为空列表
     */
    private List<Class<?>> missingDependencies(List<Class<?>> required) {
        if (required == null || required.isEmpty()) {
            return List.of();
        }
        List<Class<?>> missing = new ArrayList<>();
        for (Class<?> type : required) {
            if (type != null && !services.contains(type)) {
                missing.add(type);
            }
        }
        return missing;
    }
}
