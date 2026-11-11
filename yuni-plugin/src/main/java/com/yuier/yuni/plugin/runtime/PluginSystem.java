package com.yuier.yuni.plugin.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuier.yuni.core.bot.JsonCodec;
import com.yuier.yuni.core.bot.YuniBot;
import com.yuier.yuni.core.model.bot.Bot;
import com.yuier.yuni.core.model.bot.BotApp;
import com.yuier.yuni.core.task.DynamicTaskManager;
import com.yuier.yuni.permission.service.GroupUserPluginPermissionService;
import com.yuier.yuni.plugin.api.PluginAdmin;
import com.yuier.yuni.plugin.api.service.Cache;
import com.yuier.yuni.plugin.api.service.Http;
import com.yuier.yuni.plugin.api.service.PermissionService;
import com.yuier.yuni.plugin.runtime.config.PluginBootstrap;
import com.yuier.yuni.plugin.runtime.load.PluginModuleLoader;
import com.yuier.yuni.plugin.runtime.service.DefaultPermissionService;
import com.yuier.yuni.plugin.runtime.service.MybatisPluginScopeStore;
import com.yuier.yuni.plugin.runtime.service.RedisCache;
import com.yuier.yuni.plugin.runtime.service.RestTemplateHttp;
import com.yuier.yuni.plugin.service.GroupPluginAbilityService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 插件系统——<b>唯一的装配者与持有者</b>。
 *
 * <h2>为什么把所有组件收进一个 Bean</h2>
 * <p>之前是「一组 {@code @Bean} + 两个协调类 + 一条『运行时会被注册成
 * {@code pluginRuntime} 单例』的口头约定」。消费者要靠记忆去知道：
 * 运行时什么时候存在、该用 {@code ObjectProvider} 还是直接注入、
 * 注册的名字是什么。这些约定散落在多个文件里，容易漂移。</p>
 *
 * <p>现在改成：本类显式持有全部组件——</p>
 * <ul>
 *   <li><b>基础设施</b>：任务管理器、JSON 编解码器、缓存、HTTP、权限、作用域存储；</li>
 *   <li><b>核心组件</b>：{@link PluginRuntime}、{@link PluginModuleLoader}、
 *       {@link PluginBootstrap}——在 {@link #initialize} 里创建并持有；</li>
 *   <li><b>生命周期</b>：{@link #isReady()} / {@link #getRuntime()} /
 *       {@link #getAdmin()}。</li>
 * </ul>
 *
 * <p>消费者只依赖本类一个对象，不需要知道内部有几个组件、谁先创建、
 * 谁注册成什么名字。</p>
 *
 * <h2>为什么它自己是 Spring Bean，而内部组件不是</h2>
 * <p>本类在 refresh 期就能构造（依赖的只有基础设施），因此可以正常注入；
 * 而核心组件的创建时机必须晚于 {@code BotManager.startBot()}，所以它们
 * 不是 Bean，只由本类在 {@link #initialize} 里创建。这样「何时创建」
 * 是代码里的一行，而不是容器的隐式顺序。</p>
 */
@Slf4j
@Component
public final class PluginSystem {

    // ==================== 基础设施（构造注入） ====================

    /** 定时任务管理器（提供调度能力）。 */
    private final DynamicTaskManager taskManager;

    /** JSON 编解码器（配置 / 缓存 / HTTP 能力）。 */
    private final JsonCodec jsonCodec;

    /** 模块元数据解析用的映射器。 */
    private final ObjectMapper objectMapper;

    /** 机器人业务配置（提供命令前缀）。 */
    private final BotApp botApp;

    /** 插件目录。 */
    private final String pluginDirectory;

    // ==================== 框架内置服务（构造时创建并持有） ====================

    /** 缓存门面。 */
    private final Cache cache;

    /** HTTP 门面。 */
    private final Http http;

    /** 权限服务。 */
    private final PermissionService permissionService;

    /** 作用域持久化端口。 */
    private final PluginScopeStore scopeStore;

    // ==================== 核心组件（initialize 后持有） ====================

    /** 插件运行时；{@link #initialize} 之前为 {@code null}。 */
    private volatile PluginRuntime runtime;

    /** 模块加载器；{@link #initialize} 之前为 {@code null}。 */
    private volatile PluginModuleLoader moduleLoader;

    /** 插件启动器；{@link #initialize} 之前为 {@code null}。 */
    private volatile PluginBootstrap bootstrap;

    /**
     * 构造插件系统。
     *
     * <p>此时只组装基础设施；核心组件要等机器人就绪后由
     * {@link #initialize} 创建。{@code YuniBot} 用 {@link ObjectProvider}
     * 注入，因为它在启动编排里才被 {@code BotManager} 动态注册。</p>
     *
     * @param taskManager     定时任务管理器
     * @param jsonCodec       JSON 编解码器
     * @param objectMapper    模块元数据解析
     * @param botProvider     机器人实例提供者（延迟解析）
     * @param botConfig       机器人配置（权限服务的 masterId）
     * @param botApp          机器人业务配置
     * @param permissionStore 权限例外数据访问
     * @param abilityService  群插件启停表数据访问
     * @param pluginDirectory 插件目录
     */
    public PluginSystem(DynamicTaskManager taskManager,
                        JsonCodec jsonCodec,
                        ObjectMapper objectMapper,
                        ObjectProvider<YuniBot> botProvider,
                        ObjectProvider<Bot> botConfig,
                        BotApp botApp,
                        GroupUserPluginPermissionService permissionStore,
                        GroupPluginAbilityService abilityService,
                        @Value("${bot.app.plugin.directory:plugins}") String pluginDirectory) {
        this.taskManager = taskManager;
        this.jsonCodec = jsonCodec;
        this.objectMapper = objectMapper;
        this.botApp = botApp;
        this.pluginDirectory = pluginDirectory;

        this.cache = new RedisCache(jsonCodec);
        this.http = new RestTemplateHttp(jsonCodec);
        // bot 在 refresh 期尚不存在，因此传供应函数而不是实例
        this.permissionService = new DefaultPermissionService(
                permissionStore,
                botConfig.getIfAvailable(),
                () -> botProvider.getIfAvailable());
        this.scopeStore = new MybatisPluginScopeStore(abilityService);
    }

    /**
     * 初始化插件系统：创建核心组件 → 扫描加载插件 → 输出诊断。
     *
     * <p>必须在 {@code BotManager.startBot()} 之后调用。</p>
     *
     * @param botSupplier 机器人实例供应函数
     * @throws IllegalStateException 重复初始化
     */
    public synchronized void initialize(Supplier<YuniBot> botSupplier) {
        if (runtime != null) {
            throw new IllegalStateException("插件系统已初始化，不允许重复调用 initialize");
        }

        PluginRuntime newRuntime = new PluginRuntime(taskManager, jsonCodec);
        newRuntime.bindBot(botSupplier, botApp);

        // 内置服务登记进运行时：插件拿到的都是同一份共享实例
        newRuntime.registerService(Cache.class, cache);
        newRuntime.registerService(Http.class, http);
        newRuntime.registerService(PermissionService.class, permissionService);
        newRuntime.registerService(JsonCodec.class, jsonCodec);

        PluginModuleLoader newLoader = new PluginModuleLoader(objectMapper, newRuntime);
        newRuntime.bindModuleLoader(newLoader);
        newRuntime.bindScopeStore(scopeStore);

        PluginBootstrap newBootstrap = new PluginBootstrap(newLoader, newRuntime, pluginDirectory);

        // 先持有再加载：加载期间插件通过 ctx 反查运行时
        this.runtime = newRuntime;
        this.moduleLoader = newLoader;
        this.bootstrap = newBootstrap;

        loadModules(newBootstrap);
        report(newRuntime);
    }

    /**
     * 插件系统是否已完成初始化。
     *
     * @return 已初始化返回 {@code true}
     */
    public boolean isReady() {
        return runtime != null;
    }

    /**
     * 取插件运行时；未初始化时抛出明确错误。
     *
     * @return 插件运行时
     * @throws IllegalStateException 尚未初始化
     */
    public PluginRuntime getRuntime() {
        PluginRuntime current = runtime;
        if (current == null) {
            throw new IllegalStateException("插件系统尚未完成初始化，请等待启动流程结束");
        }
        return current;
    }

    /**
     * 取插件运行时；未初始化时返回空。
     *
     * <p>供事件分发使用——启动早期还没有插件在监听，跳过即可。</p>
     *
     * @return 运行时，未初始化时为空
     */
    public Optional<PluginRuntime> runtimeIfReady() {
        return Optional.ofNullable(runtime);
    }

    /**
     * 取插件管理端接口。
     *
     * @return 管理端接口
     * @throws IllegalStateException 尚未初始化
     */
    public PluginAdmin getAdmin() {
        return getRuntime().getAdmin();
    }

    // ==================== 内部 ====================

    /**
     * 扫描并加载插件目录下的全部模块。
     *
     * <p>失败时不让启动流程崩掉：插件是可选组件，一个 jar 坏掉不应该让 bot
     * 完全起不来。逐模块的异常已由 {@link PluginBootstrap} 内部捕获，
     * 这里只兜住「插件目录本身不可读」这类更外层的问题。</p>
     *
     * @param pluginBootstrap 启动器
     */
    private void loadModules(PluginBootstrap pluginBootstrap) {
        try {
            List<PluginFiber> loaded = pluginBootstrap.initialize();
            log.info("插件加载完成，共 {} 个插件实例", loaded.size());
        } catch (Throwable t) {
            // 必须捕获 Throwable：类加载失败抛的是 Error（2026-08 P0 事故的教训）
            log.error("插件系统初始化失败，bot 将以无插件状态继续运行", t);
        }
    }

    /**
     * 输出插件健康度诊断。
     *
     * <p>此时全部模块已加载、依赖推进已跑到不动点，因此「还有谁停在 PENDING」
     * 与「谁失败了」是确定的答案。用 warn 而非 error：单个插件失败不应让整个
     * 启动流程看起来失败，但它需要被人看到。</p>
     *
     * @param pluginRuntime 插件运行时
     */
    private void report(PluginRuntime pluginRuntime) {
        List<String> pending = pluginRuntime.reportPendingPlugins();
        if (!pending.isEmpty()) {
            log.warn("有 {} 个插件因依赖未满足而未加载（可能是循环依赖或配置缺失）：", pending.size());
            pending.forEach(item -> log.warn("  - {}", item));
        }
        List<String> failed = pluginRuntime.reportFailedPlugins();
        if (!failed.isEmpty()) {
            log.warn("有 {} 个插件加载失败：", failed.size());
            failed.forEach(item -> log.warn("  - {}", item));
        }
        log.info("插件系统就绪：{}", pluginRuntime.describe());
    }
}
