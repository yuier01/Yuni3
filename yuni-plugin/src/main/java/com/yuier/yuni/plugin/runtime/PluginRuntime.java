package com.yuier.yuni.plugin.runtime;

import com.yuier.yuni.core.bot.BotGroupInfo;
import com.yuier.yuni.core.bot.JsonCodec;
import com.yuier.yuni.core.bot.YuniBot;
import com.yuier.yuni.core.model.bot.BotApp;
import com.yuier.yuni.core.task.DynamicTaskManager;
import com.yuier.yuni.plugin.api.Disposable;
import com.yuier.yuni.plugin.api.PluginAdmin;
import com.yuier.yuni.plugin.api.PluginMetadata;
import com.yuier.yuni.plugin.api.ScopeFilter;
import com.yuier.yuni.plugin.api.YuniPlugin;
import com.yuier.yuni.plugin.runtime.FiberRegistry.ModuleHandle;
import com.yuier.yuni.plugin.runtime.PluginScopeStore.PluginScopeRecord;
import com.yuier.yuni.plugin.runtime.adapter.PluginEventAdapter;
import com.yuier.yuni.plugin.runtime.admin.PluginAdminRuntime;
import com.yuier.yuni.plugin.runtime.dispatch.CommandDispatcher;
import com.yuier.yuni.plugin.runtime.dispatch.EventPipeline;
import com.yuier.yuni.plugin.runtime.load.PluginModuleLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * <p>每个 {@link PluginFiber} 持有加载它的 ClassLoader。{@link #unload} 会撤销
 * 该插件的全部 Effect 并把 Effect 计数归零，之后调用方才可以关闭 ClassLoader
 * （{@link #reload} 就是这么做的）。这条约束来自 2026-08 的 P0 事故
 * （插件重载静默失败），见 {@code Notes/技术报告-插件重载静默失败.md}。</p>
 *
 * <h2>能力范围</h2>
 * <p>本类已实现：模块加载（经 {@code PluginModuleLoader}）、依赖检查与推进、
 * {@code apply} 调用、索引登记、卸载、热重载、事件分发（经 {@link #getPipeline()}）、
 * 作用域查询与写入、{@link com.yuier.yuni.plugin.api.PluginAdmin} 适配。
 * 重构 P1–P6 已全部完成，本类不再有「待接线」的方法。</p>
 *
 * @see PluginFiber
 * @see FiberRegistry
 * @see RegistrationTable
 * @see com.yuier.yuni.plugin.runtime.PluginScopes
 */
@Slf4j
@RequiredArgsConstructor
public final class PluginRuntime {

    /** 能力注册总表：所有插件注册的事件处理器与命令树。 */
    @Getter
    private final RegistrationTable registrations = new RegistrationTable();

    /** 服务注册表：框架内置服务与插件提供的服务。 */
    @Getter
    private final ServiceRegistry services = new ServiceRegistry();

    /** Fiber 容器：树形索引 + 模块索引。 */
    @Getter
    private final FiberRegistry fibers = new FiberRegistry();

    /** 插件标识 → 资源目录；用于依赖推进时重建上下文。 */
    private final Map<String, Path> pluginDataDirs = new ConcurrentHashMap<>();

    /**
     * 插件作用域配置表——「每个插件在哪些群被显式开启 / 关闭」。
     *
     * <p>记录每个插件的群粒度启停例外，供三处使用：</p>
     * <ol>
     *   <li>{@code ctx.getScope()} 回答「本插件在某群是否生效」——
     *       主动插件（定时任务）没有事件上下文，框架无法替它施加过滤；</li>
     *   <li>事件分发给每个注册项合成一个过滤器；</li>
     *   <li>{@code PluginAdmin} 读写并在重启后从数据库恢复。</li>
     * </ol>
     *
     * <p>P6 之前这里是一个 {@code Map<String, ScopeFilter>}。换成
     * {@link com.yuier.yuni.plugin.runtime.PluginScopes} 的原因是表达力：
     * 单个过滤器无法同时表达「一部分群显式开启、另一部分显式关闭」，
     * 而管理端的实际配置正是这个三态模型（见该类的说明）。</p>
     */
    private final PluginScopes pluginScopes =
            new PluginScopes();

    /**
     * 插件注册时自带的基线作用域配置——「作者意图」这一层。
     *
     * <p>与 {@link #pluginScopes}（用户配置）分工明确：</p>
     * <ul>
     *   <li><b>基线</b>来自插件作者的代码（{@code ctx.plugin(child, filter)}）；</li>
     *   <li><b>用户配置</b>来自管理端的显式操作，优先于基线。</li>
     * </ul>
     * <p>没有基线时默认值取插件元数据的 {@code defaultEnable}。</p>
     *
     * <p>基线就是 {@link ScopeFilter} 本身——它已经是「默认值 + 例外群」的
     * 值对象，不再需要另一种表示，也不需要记「原本是白名单还是黑名单」：
     * 默认值本身就把两种意图区分开了。</p>
     */
    private final Map<String, ScopeFilter> baselines = new ConcurrentHashMap<>();

    /**
     * 作用域持久化端口。
     *
     * <p>为 {@code null} 时作用域配置仅存在于内存——测试场景与未装配数据库的
     * 集成场景都是这样，因此不视为错误。</p>
     */
    private volatile PluginScopeStore scopeStore;

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
     * 模块加载器；用于热重载时重新读取 jar。
     *
     * <p>为 {@code null} 时 {@link #reload} 会抛出可读的错误而不是 NPE——
     * 测试场景通常不装配它，因此这是常见情况而非异常情况。</p>
     */
    private volatile PluginModuleLoader moduleLoader;

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
        ensureModuleHandle(moduleId, classLoader);
        if (dataDir != null) {
            // 记录资源目录：依赖未满足而停在 PENDING 的插件，后续推进时
            // 需要用同一个目录重建上下文
            pluginDataDirs.put(id, dataDir);
        }
        if (filter != null && !isNoConstraint(filter)) {
            /*
             * 记录「插件注册时自带的基线过滤器」。
             *
             * 它表达的是插件作者写下的建议（例如 ctx.plugin(child, onlyGroups(a,b))），
             * 本身就是「默认值 + 例外群」——与用户配置同一模型，因此直接存，
             * 不需要再做一次形态转换。
             */
            baselines.put(id, filter);
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
     * 保证某个模块在索引里存在。
     *
     * <h2>为什么 {@code load} 也要管模块索引</h2>
     * <p>模块句柄（{@code ModuleHandle}）本来只由
     * {@code PluginModuleLoader.loadModule} 登记，因为它掌握 jar 文件名与资源目录。
     * 但 {@link #load} 是一个公开入口——测试、集成场景、以及「用代码直接装一个插件」
     * 都会走它。若此时不登记模块，就会出现两种不一致：</p>
     * <ul>
     *   <li>{@code PluginAdmin.moduleIdOf} 查不到插件所属模块，
     *       于是管理视图里的 {@code moduleId} 是空的；</li>
     *   <li>模块分组视图（{@code admin.getModules()}）看不到这些插件——
     *       它们明明已加载，却在「按模块看」的视角下消失。</li>
     * </ul>
     * <p>因此这里补一个「最小可用」的模块句柄。之后若
     * {@code PluginModuleLoader.loadModule} 用完整信息覆盖它，
     * 那正是我们想要的结果（它知道 jar 名与目录）。</p>
     *
     * @param moduleId    模块 ID；{@code null} 或空白时不登记
     * @param classLoader 类加载器；可为 {@code null}
     */
    private void ensureModuleHandle(String moduleId, ClassLoader classLoader) {
        if (moduleId == null || moduleId.isBlank()) {
            return;
        }
        synchronized (moduleHandles) {
            ModuleHandle existing =
                    fibers.module(moduleId).orElse(null);
            if (existing != null && existing.getJarFileName() != null) {
                // 已经有携带完整信息（jar 名）的句柄——它由 loadModule 建立，不要覆盖
                return;
            }

            /*
             * 收集本模块下的全部插件 ID。
             *
             * 句柄的 pluginIds 决定 admin.getModules() 能列出哪些插件
             * （见 FiberRegistry.ofModule），因此必须带上，不能留空——
             * 留空会让「按模块看」的视角漏掉该插件。
             *
             * 直接按模块 ID 过滤全部 Fiber：这一步不经过 moduleIdOf，
             * 因为 moduleIdOf 本身依赖模块句柄，此时可能还没建好（会死循环）。
             */
            List<String> pluginIds = new ArrayList<>();
            for (PluginFiber fiber : fibers.getAll()) {
                if (moduleId.equals(moduleIdOfRaw(fiber.getId()))) {
                    pluginIds.add(fiber.getId());
                }
            }

            fibers.addModule(new ModuleHandle(
                    moduleId,
                    // 显示名暂用模块 ID：真正的名字在 metadata.json 里，
                    // 由 loadModule 覆盖。这里不能瞎猜，只能如实填 ID
                    moduleId,
                    null,
                    null,
                    classLoader,
                    pluginIds));
            log.debug("为直接 load 的插件补建模块句柄: {}（{} 个插件）", moduleId, pluginIds.size());
        }
    }

    /**
     * 取插件所属模块 ID（不依赖模块句柄的版本）。
     *
     * <p>与公开的 {@link #moduleIdOf} 的区别：本方法只查「插件标识里是否以
     * {@code moduleId/} 开头」这一条路径，因此<b>可以在模块句柄尚未建立时使用</b>。
     * {@code ensureModuleHandle} 需要它，否则会形成
     * 「建句柄 → 查句柄 → 句柄还没有」的死循环。</p>
     *
     * @param pluginId 插件标识
     * @return 模块 ID；无法判定时返回 {@code null}
     */
    private String moduleIdOfRaw(String pluginId) {
        for (ModuleHandle handle : fibers.getAllModules()) {
            if (handle.getPluginIds().contains(pluginId)) {
                return handle.getModuleId();
            }
        }
        // 退化为「标识前缀」判定：标识格式为 moduleId/name，因此前缀即模块
        int slash = pluginId == null ? -1 : pluginId.indexOf('/');
        return slash > 0 ? pluginId.substring(0, slash) : null;
    }

    /**
     * 模块句柄补建用的锁对象。
     *
     * <p>只保护「检查是否存在 + 写入」这一段，不覆盖加载流程本身——
     * 后者是低频的重操作，用一把粗锁会拖长启动时间。</p>
     */
    private final Object moduleHandles = new Object();

    /**
     * 过滤器是否「不施加任何约束」——即默认开启且没有例外群。
     *
     * <p>这种过滤器不需要记为基线：它等价于「没有作者意图」，后续判定
     * 应直接回落到元数据 {@code defaultEnable}。</p>
     *
     * @param filter 过滤器
     * @return 不施加约束返回 {@code true}
     */
    private static boolean isNoConstraint(ScopeFilter filter) {
        return filter.isEnabledByDefault() && filter.getExceptionGroups().isEmpty();
    }

    /**
     * 执行 {@code apply(ctx)} 并处理成功 / 失败。
     *
     * @param fiber  目标 Fiber
     * @param dataDir 插件资源目录
     * @param filter  作用域过滤器；当前仅记录，P4 分发时使用
     */
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
     * @param filter  基线作用域过滤器；可为 {@code null}
     * @return 成功转为 ACTIVE 返回 {@code true}
     */
    private boolean applyTo(PluginFiber fiber, Path dataDir, ScopeFilter filter) {
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
         * 抢占成功后仍要复查封存标志：卸载可能恰好发生在「抢占成功」与「开始 apply」
         * 之间。此时登记表已封存，继续 apply 会让插件注册的一切被就地撤销——
         * 插件作者看到的是「代码跑了但什么也没生效」，很难排查，因此直接放弃。
         */
        if (fiber.getEffects().isSealed()) {
            return false;
        }

        PluginContext ctx = PluginContext.forFiber(
                fiber, services, registrations, taskManager, dataDir, jsonCodec);
        // 让子插件挂载能回调到运行时
        ctx.bindRuntime(this);

        try {
            fiber.getPlugin().apply(ctx);
            if (fiber.tryMarkActive()) {
                log.info("插件已加载: {} | 贡献能力 {}", fiber.getId(), registrations.kindsOf(fiber));
                return true;
            }

            /*
             * apply 期间 Fiber 被卸载：状态已不在 LOADING，CAS 拒绝把它回写成 ACTIVE。
             * 必须回滚已建立的 Effect，否则会留下「插件已卸载但监听器还在」的悬挂引用。
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
                log.error("插件 {} 加载失败，已回滚其 Effect", fiber.getId(), t);
            } else {
                log.warn("插件 {} 加载失败，但期间已被卸载，状态不再改为 FAILED", fiber.getId(), t);
            }

            // 回滚：撤销已建立的 Effect，避免「标记为失败但监听器仍在工作」。
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
        List<Class<?>> missing = missingDependencies(fiber.getPlugin());
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
        return applyTo(fiber, dataDir, ScopeFilter.ALL);
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
    public int activatePending(Function<String, Path> dataDirResolver) {
        int activated = 0;
        boolean progressed = true;
        while (progressed) {
            progressed = false;
            for (PluginFiber fiber : fibers.withState(FiberState.PENDING)) {
                Path dir = dataDirResolver == null ? null : dataDirResolver.apply(fiber.getId());
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
        /*
         * 移除基线过滤器。
         *
         * 基线来自「插件注册时代码里传的 filter」，生命周期与插件实例一致；
         * 卸载后必须清掉，否则热重载会留下旧类的配置、并且旧插件标识会持续
         * 占用这张表（{@link #scopeFilterOf} 依赖 Fiber 存在，但这些条目是泄漏）。
         *
         * 注意：用户配置的群例外表（{@link #pluginScopes}）<b>不</b>在这里清除
         * ——重载会先 unload 再 load，清掉就等于重载丢配置。
         * 需要真正清除用户配置时走 resetPluginScope。
         */
        baselines.remove(pluginId);

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
     * <p>从叶子往根卸载（{@link FiberRegistry#getRoots()} 的逆序），
     * 保证子插件先于父插件消失。</p>
     *
     * @return 清理失败的描述列表
     */
    public List<String> unloadAll() {
        List<String> failures = new ArrayList<>();
        List<PluginFiber> roots = fibers.getRoots();
        for (int i = roots.size() - 1; i >= 0; i--) {
            failures.addAll(unload(roots.get(i).getId()));
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
     * <h2>执行的六步</h2>
     * <ol>
     *   <li>取模块句柄（拿到 jar 文件名与所在目录）；</li>
     *   <li><b>先把作用域配置快照下来</b>——虽然
     *       {@link #unload} 现在不再清除例外表，但重新 {@code load} 时
     *       会按新实例的 {@code defaultEnable} 重算，因此仍以快照为准回填，
     *       保证「换实现不换配置」；</li>
     *   <li>逐个 {@link #unload} 该模块的插件（逆序撤销 Effect、注销服务）；</li>
     *   <li>移除模块索引，并关闭其 ClassLoader——这一步是 Windows 下能覆盖 jar
     *       的前提；</li>
     *   <li>交给 {@link com.yuier.yuni.plugin.runtime.load.PluginModuleLoader}
     *       重新扫描并按新 ClassLoader 加载；</li>
     *   <li>把第 2 步快照的作用域配置按插件名回填。</li>
     * </ol>
     *
     * <h2>调用时机约束（重要）</h2>
     * <p>本方法是<b>同步</b>的，且会在调用线程上卸载并重新加载目标模块的类。
     * 因此<b>不要从目标模块自己的处理器里调用它</b>——那会在一次命令处理尚未
     * 返回时就卸载掉正在执行的类。正确做法是由调用方在
     * {@code ctx.plugin(...)} 之外的独立插件、或由 webapi 的请求线程触发。</p>
     *
     * <p>框架选择「不做延迟调度」而非「静默地异步卸载」，是因为后者会让
     * 「重载成功了吗」变成一个无法回答的问题（调用返回时插件还没装好）。
     * 把约束显式写在这里，让调用方自己选择在哪里触发。</p>
     *
     * @param moduleId 模块 ID
     * @return 重载后该模块的插件 Fiber 列表
     * @throws IllegalStateException 模块不存在、jar 缺失，或运行时未装配模块加载器
     */
    public List<PluginFiber> reload(String moduleId) {
        if (moduleLoader == null) {
            throw new IllegalStateException(
                    "热重载需要 PluginModuleLoader，但本运行时未装配它"
                            + "（集成场景应调用 bindModuleLoader）。");
        }
        ModuleHandle handle = fibers.module(moduleId)
                .orElseThrow(() -> new IllegalStateException("模块不存在: " + moduleId));

        Path moduleDir = handle.getJarParentPath() == null ? null : Path.of(handle.getJarParentPath());
        java.io.File jarFile = moduleDir == null ? null : moduleDir.resolve(handle.getJarFileName()).toFile();
        if (jarFile == null || !jarFile.isFile()) {
            throw new IllegalStateException(
                    "找不到模块 " + moduleId + " 的 jar 文件"
                            + (jarFile == null ? "" : ": " + jarFile.getAbsolutePath()));
        }

        // 第 2 步：快照作用域例外。重启加载时新实例的 defaultEnable 可能与旧实例
        // 不同（用户改了插件），因此以快照为准回填，保证「换实现不换配置」。
        Map<String, Map<Long, Boolean>> scopeSnapshot = new LinkedHashMap<>();
        for (String pluginId : handle.getPluginIds()) {
            Map<Long, Boolean> values = pluginScopes.all(pluginId);
            if (!values.isEmpty()) {
                scopeSnapshot.put(pluginId, values);
            }
        }

        // 第 3 步：逐个卸载。逆序，与加载顺序对称（子插件先于父插件消失）
        List<String> pluginIds = new ArrayList<>(handle.getPluginIds());
        for (int i = pluginIds.size() - 1; i >= 0; i--) {
            unload(pluginIds.get(i));
        }

        // 第 4 步：移除模块索引并关闭类加载器。
        // 关闭是必须的：Windows 下 jar 文件句柄不释放就无法覆盖同名文件，
        // 而「换了 jar 再重载」正是热重载最常见的用法。
        fibers.removeModule(moduleId);
        closeQuietly(handle.getClassLoader());

        log.info("模块 {} 已卸载，开始重新加载 {}", moduleId, jarFile.getName());

        // 第 5 步：重新扫描加载
        List<PluginFiber> loaded = moduleLoader.loadModule(jarFile, moduleDir);

        // 第 6 步：回填作用域配置。
        // 按插件标识匹配而不是按位置——插件标识由「模块 + 插件显示名」构成，
        // 只要模块没有增删插件，标识就保持一致。
        int restored = 0;
        for (PluginFiber fiber : loaded) {
            Map<Long, Boolean> values = scopeSnapshot.get(fiber.getId());
            if (values != null) {
                pluginScopes.putAll(fiber.getId(), values);
                restored++;
            }
        }

        log.info("模块 {} 重载完成：{} 个插件，恢复 {} 项作用域配置",
                moduleId, loaded.size(), restored);
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
                        this::scopeFilterOf);
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
    public Optional<ScopeFilter> scopeFilterOf(String pluginId) {
        return fibers.byId(pluginId).map(fiber ->
                pluginScopes.toFilter(pluginId, effectiveDefaultEnabled(pluginId)));
    }

    /**
     * 取插件被显式开启的群（管理端展示用）。
     *
     * @param pluginId 插件标识
     * @return 显式开启的群号；无则为空集
     */
    public Set<Long> explicitlyEnabledGroups(String pluginId) {
        return pluginScopes.explicitlyEnabledGroups(pluginId);
    }

    /**
     * 判定插件是否在指定群生效（供主动插件经 {@code ctx.getScope()} 查询）。
     *
     * <p>判定链：用户显式例外 → 插件注册时的基线过滤器 → 元数据的
     * {@code defaultEnable}。</p>
     *
     * @param pluginId 插件标识
     * @param groupId  群号
     * @return 生效返回 {@code true}；<b>无任何配置信息时返回 {@code null}</b>，
     *         由调用方回落到插件的 {@code defaultEnable}
     */
    public Boolean isPluginEnabledIn(String pluginId, long groupId) {
        // ① 用户显式配置的群例外优先级最高
        Optional<Boolean> explicit = pluginScopes.explicitValue(pluginId, groupId);
        if (explicit.isPresent()) {
            return explicit.get();
        }
        if (!fiberExists(pluginId)) {
            return null;
        }
        /*
         * ② 插件注册时自带的基线例外。
         *
         * 例如插件自己写了 ctx.plugin(child, onlyGroups(200))，那 a=100/300
         * 就应当明确不生效——而不是回落到默认值（默认值为真时就会误判为生效）。
         */
        ScopeFilter base = baseScopeOf(pluginId);
        if (base != null && !base.getExceptionGroups().isEmpty()) {
            boolean listed = base.getExceptionGroups().contains(groupId);
            // 基线默认开启时，列出的群是「被关闭的例外」；反之列出的群是「被开启的例外」
            return base.isEnabledByDefault() ? !listed : listed;
        }
        // ③ 没有任何例外信息 → 按有效默认值（用户全局开关 > 基线 > 元数据）
        return effectiveDefaultEnabled(pluginId);
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
     * 取插件的有效默认启用值——基线优先，否则看元数据。
     *
     * @param pluginId 插件标识
     * @return 默认启用返回 {@code true}
     */
    private boolean effectiveDefaultEnabled(String pluginId) {
        // 用户显式设置过全局开关 → 以它为准
        Optional<Boolean> userGlobal = pluginScopes.explicitGlobalDefault(pluginId);
        if (userGlobal.isPresent()) {
            return userGlobal.get();
        }
        // 其次看插件注册时的基线
        ScopeFilter base = baseScopeOf(pluginId);
        if (base != null) {
            return base.isEnabledByDefault();
        }
        // 最后回落到元数据。插件不存在时保守返回 false：
        // 配置损坏时「不放行」比「误放行」安全
        return fibers.byId(pluginId)
                .map(fiber -> fiber.getMetadata().isDefaultEnable())
                .orElse(false);
    }

    /**
     * 取插件注册时的基线配置。
     *
     * @param pluginId 插件标识
     * @return 基线配置；没有时为 {@code null}
     */
    private ScopeFilter baseScopeOf(String pluginId) {
        return pluginId == null ? null : baselines.get(pluginId);
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
        /*
         * 优先描述基线：插件注册时传的 filter 是更强的意图表达
         * （插件作者明确说「只在某几个群生效」），而用户例外只是微调。
         */
        ScopeFilter base = baseScopeOf(pluginId);
        if (base != null && !base.getExceptionGroups().isEmpty()) {
            // 默认开启 → 列出的是被排除的群；默认关闭 → 列出的是被允许的群
            return base.isEnabledByDefault()
                    ? "排除群 " + base.getExceptionGroups()
                    : "仅限群 " + base.getExceptionGroups();
        }

        // 再看用户配置的群例外
        ScopeFilter persisted =
                pluginScopes.toFilter(pluginId, effectiveDefaultEnabled(pluginId));
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
     * <p>{@link com.yuier.yuni.plugin.runtime.PluginScopes} 是运行时的私有状态，
     * 而分发给插件看的 {@link #isPluginEnabledIn} 也从它读取。写入与读取必须在
     * 同一个类里，否则会出现「管理端改了 A、分发读的是 B」这类问题。</p>
     *
     * <h2>三态写入语义</h2>
     * <p>每个群有三个状态：<b>未设置</b>（按 {@code defaultEnable}）、
     * 显式开启、显式关闭。写入的值与默认值一致时<b>删除</b>该群的记录，
     * 使操作可逆——「关闭再开启」会回到「未设置」而不是留下一条无意义的记录。
     * 具体实现在 {@link com.yuier.yuni.plugin.runtime.PluginScopes#set}。</p>
     *
     * <h2>写库</h2>
     * <p>本方法在改完内存后立即调用 {@link com.yuier.yuni.plugin.runtime.PluginScopeStore}
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
        PluginFiber fiber = fibers.byId(pluginId).orElseThrow(() ->
                new IllegalArgumentException("插件不存在: " + pluginId));

        pluginScopes.set(pluginId, groupId, enabled, fiber.getMetadata().isDefaultEnable());
        log.info("插件 {} 在群 {} 的生效状态改为 {}（当前: {}）",
                pluginId, groupId, enabled, describeScope(pluginId));
        persistScope(pluginId);
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
        if (!fiberExists(pluginId)) {
            throw new IllegalArgumentException("插件不存在: " + pluginId);
        }
        pluginScopes.setGlobalDefault(pluginId, enabled);
        log.info("插件 {} 的全局启停改为 {}（当前: {}）", pluginId, enabled, describeScope(pluginId));
        persistScope(pluginId);
    }

    /**
     * 清除某插件的全部群例外，回到元数据声明的默认值。
     *
     * @param pluginId 插件标识
     * @return 本来是否有配置可清
     */
    public boolean resetPluginScope(String pluginId) {
        boolean hadExceptions = pluginScopes.clear(pluginId).isPresent();
        boolean hadGlobal = pluginScopes.clearGlobalDefault(pluginId);
        if (hadExceptions || hadGlobal) {
            persistScope(pluginId);
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
    public PluginRuntime bindScopeStore(
            PluginScopeStore store) {
        this.scopeStore = store;
        return this;
    }

    /**
     * 在插件加载<b>之后</b>从持久化存储恢复作用域配置。
     *
     * <h2>为什么必须在插件加载之后</h2>
     * <p>恢复时需要用每个插件的 {@code defaultEnable} 判断「这条记录算不算例外」，
     * 而 {@code defaultEnable} 在代码里（{@code PluginMetadata}），
     * 只有插件实例化后才能读到。因此本方法由
     * {@code PluginBootstrap.initialize()} 在全部模块加载完之后调用。</p>
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
        PluginScopeStore store = this.scopeStore;
        if (store == null) {
            return 0;
        }
        Map<String, PluginScopeRecord> stored;
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
            PluginScopeRecord record =
                    stored.get(fiber.getId());
            if (record == null || record.isEmpty()) {
                continue;
            }
            boolean pluginDefault = fiber.getMetadata().isDefaultEnable();
            // 全局开关：用户设置过就用它，否则用插件默认值。
            // 例外群的「相反值」判定以全局开关为基准，因此必须先定下它
            boolean global = record.getGlobalEnabled() != null ? record.getGlobalEnabled() : pluginDefault;
            pluginScopes.setGlobalDefault(fiber.getId(), global);
            pluginScopes.putAll(fiber.getId(),
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
        PluginScopeStore store = this.scopeStore;
        if (store == null) {
            return;
        }
        try {
            boolean pluginDefault = fibers.byId(pluginId)
                    .map(fiber -> fiber.getMetadata().isDefaultEnable())
                    .orElse(false);
            // 只持久化「用户显式设置过」的全局开关；
            // 未设置时存 null，让下次启动重新回落到插件元数据
            Boolean global = pluginScopes.explicitGlobalDefault(pluginId).orElse(null);
            boolean effectiveGlobal = global != null ? global : pluginDefault;
            // 例外群 = 与有效全局值相反的那些群
            Set<Long> exceptionGroups =
                    pluginScopes.oppositeGroups(pluginId, effectiveGlobal);
            store.save(pluginId,
                    new PluginScopeRecord(
                            global, exceptionGroups));
        } catch (Exception e) {
            // 不回滚内存：用户已经看到操作成功，回滚只会让内存与实际反馈不一致。
            // 下一次成功写入会覆盖这次失败的记录。
            log.error("保存插件 {} 的作用域配置失败（当前进程内配置已生效，重启后会丢失）",
                    pluginId, e);
        }
    }

    // ==================== 模块与插件的反向查询 ====================

    /**
     * 取某插件所属的模块 ID。
     *
     * @param pluginId 插件标识
     * @return 模块 ID；插件不属于任何模块或不存在时为空
     */
    public Optional<String> moduleIdOf(String pluginId) {
        for (ModuleHandle handle : fibers.getAllModules()) {
            if (handle.getPluginIds().contains(pluginId)) {
                return java.util.Optional.of(handle.getModuleId());
            }
        }
        return java.util.Optional.empty();
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
        return new PluginAdminRuntime(this, moduleLoader);
    }

    /**
     * 绑定模块加载器。
     *
     * <p>热重载需要重新读 jar，而读 jar 的逻辑在 {@code PluginModuleLoader} 里。
     * 该对象由 Spring 装配，晚于运行时的构造时机，因此用后置绑定而非构造器注入。</p>
     *
     * @param moduleLoader 模块加载器；可为 {@code null}（测试场景，则 reload 不可用）
     * @return {@code this}，便于链式配置
     */
    public PluginRuntime bindModuleLoader(
            PluginModuleLoader moduleLoader) {
        this.moduleLoader = moduleLoader;
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
                .flatMap(fiber -> fibers.getAllModules().stream()
                        .filter(m -> m.getPluginIds().contains(pluginId))
                        .findFirst())
                .map(m -> m.getJarParentPath() == null ? null : Path.of(m.getJarParentPath()))
                .filter(Objects::nonNull);
    }

    /**
     * 运行时诊断描述。
     *
     * @return 形如 {@code "modules=13, plugins=23, effects=41, states={...}"}
     */
    public String describe() {
        return fibers.describe() + ", registrations=" + registrations.getTotalSize();
    }

    // ==================== 内部 ====================

    /**
     * 安全读取插件元数据。
     *
     * <p>{@code getMetadata()} 是插件代码，可能抛异常。此处兜底为一个默认元数据，
     * 使「元数据写错」不至于让插件完全无法加载——真正的失败应发生在
     * {@code apply} 阶段并带上完整上下文。</p>
     *
     * @param plugin 插件实例
     * @return 元数据；读取失败时返回以类名命名的默认值
     */
    private static PluginMetadata safeMetadata(YuniPlugin plugin) {
        try {
            PluginMetadata metadata = plugin.getMetadata();
            return metadata == null
                    ? PluginMetadata.of(plugin.getClass().getSimpleName()).build()
                    : metadata;
        } catch (Throwable t) {
            log.warn("插件 {} 的 getMetadata() 执行失败，使用默认元数据", plugin.getClass().getName(), t);
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
        String name = metadata.getName();
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
        return load(child, null, dataDir, parent == null ? null : parent.getClassLoader(), parent, filter);
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
