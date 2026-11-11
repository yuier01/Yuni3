package com.yuier.yuni.plugin.runtime.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuier.yuni.core.bot.JsonCodec;
import com.yuier.yuni.core.bot.YuniBot;
import com.yuier.yuni.core.model.bot.BotApp;
import com.yuier.yuni.core.task.DynamicTaskManager;
import com.yuier.yuni.plugin.api.service.Cache;
import com.yuier.yuni.plugin.api.service.Http;
import com.yuier.yuni.plugin.api.service.PermissionService;
import com.yuier.yuni.plugin.runtime.PluginRuntime;
import com.yuier.yuni.plugin.runtime.PluginScopeStore;
import com.yuier.yuni.plugin.runtime.load.PluginModuleLoader;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.function.Supplier;

/**
 * 插件系统核心组件的<b>手动组装器</b>。
 *
 * <h2>为什么不用 {@code @Bean} 让容器创建它们</h2>
 * <p>{@link PluginRuntime}、{@link PluginModuleLoader}、
 * {@link PluginSystemConfig.PluginBootstrap} 的<b>创建时机是有意义的</b>：
 * 它们必须等 {@code BotManager.startBot()} 建好机器人之后才能组装。
 * 交给容器的 eager 单例模型，就只能靠 {@code @Lazy} 代理与
 * {@code ObjectProvider} 去对抗初始化顺序——那正是
 * 「权限服务拿到 null bot」这类缺陷的来源。</p>
 *
 * <p>改为手动组装后，时机在
 * {@code SystemInitializeRunner} 里是一行可见的代码；组装完成后由调用方
 * 通过 {@code registerSingleton} 把运行时交回容器，供 webapi 与事件分发使用。</p>
 *
 * <h2>它自己为什么是 Bean</h2>
 * <p>组装器只持有<b>基础设施依赖</b>（任务管理器、编解码器、缓存、HTTP、
 * 权限、作用域存储、插件目录）——这些本来就是容器资产，且没有时机问题。
 * 让容器注入它们，比手工从容器里逐个 {@code getBean} 更干净。</p>
 */
@RequiredArgsConstructor
public final class PluginSystemAssembler {

    /** 定时任务管理器（提供调度能力）。 */
    private final DynamicTaskManager taskManager;

    /** JSON 编解码器（配置 / 缓存 / HTTP 能力）。 */
    private final JsonCodec jsonCodec;

    /** 模块元数据解析用的映射器。 */
    private final ObjectMapper objectMapper;

    /** 缓存门面；可为 {@code null}（未装配缓存时）。 */
    private final Cache cache;

    /** HTTP 门面；可为 {@code null}。 */
    private final Http http;

    /** 权限服务；可为 {@code null}。 */
    private final PermissionService permissionService;

    /** 作用域持久化端口。 */
    private final PluginScopeStore scopeStore;

    /** 插件目录。 */
    private final String pluginDirectory;

    /**
     * 组装插件系统核心。
     *
     * <p>调用方必须保证：{@code botSupplier} 在后续调用时能取到已就绪的机器人。
     * 组装本身不触发插件加载——加载由返回的 {@code PluginBootstrap} 负责。</p>
     *
     * @param botApp      机器人业务配置（提供命令前缀）；可为 {@code null}
     * @param botSupplier 机器人实例供应函数
     * @return 组装结果（运行时、加载器、启动器）
     */
    public AssembledPluginSystem assemble(BotApp botApp, Supplier<YuniBot> botSupplier) {
        PluginRuntime runtime = new PluginRuntime(taskManager, jsonCodec);
        // bot 与 BotApp 都在运行时创建之后才就绪，因此用供应函数
        runtime.bindBot(botSupplier, botApp);

        // 把内置服务登记进运行时：插件拿到的都是同一个共享实例
        if (cache != null) {
            runtime.registerService(Cache.class, cache);
        }
        if (http != null) {
            runtime.registerService(Http.class, http);
        }
        if (permissionService != null) {
            runtime.registerService(PermissionService.class, permissionService);
        }
        runtime.registerService(JsonCodec.class, jsonCodec);

        PluginModuleLoader loader = new PluginModuleLoader(objectMapper, runtime);
        // 后置绑定：热重载读 jar 的能力与作用域持久化都依赖这两个组件
        runtime.bindModuleLoader(loader);
        runtime.bindScopeStore(scopeStore);

        PluginSystemConfig.PluginBootstrap bootstrap =
                new PluginSystemConfig.PluginBootstrap(loader, runtime, pluginDirectory);
        return new AssembledPluginSystem(runtime, loader, bootstrap);
    }

    /**
     * 组装结果——三个核心组件的只读载体。
     */
    @Getter
    @RequiredArgsConstructor
    public static final class AssembledPluginSystem {

        /** 插件运行时。 */
        private final PluginRuntime runtime;

        /** 模块加载器。 */
        private final PluginModuleLoader loader;

        /** 启动器（负责扫描并加载插件目录）。 */
        private final PluginSystemConfig.PluginBootstrap bootstrap;
    }
}
