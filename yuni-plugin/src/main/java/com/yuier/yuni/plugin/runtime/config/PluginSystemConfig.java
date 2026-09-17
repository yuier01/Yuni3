package com.yuier.yuni.plugin.runtime.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuier.yuni.core.bot.JsonCodec;
import com.yuier.yuni.core.bot.YuniBot;
import com.yuier.yuni.core.model.bot.Bot;
import com.yuier.yuni.core.model.bot.BotApp;
import com.yuier.yuni.core.task.DynamicTaskManager;
import com.yuier.yuni.plugin.api.service.Cache;
import com.yuier.yuni.plugin.api.service.Http;
import com.yuier.yuni.plugin.api.service.PermissionService;
import com.yuier.yuni.plugin.runtime.PluginFiber;
import com.yuier.yuni.plugin.runtime.PluginRuntime;
import com.yuier.yuni.plugin.runtime.load.PluginModuleLoader;
import com.yuier.yuni.plugin.runtime.service.DefaultPermissionService;
import com.yuier.yuni.plugin.runtime.service.RedisCache;
import com.yuier.yuni.plugin.runtime.service.RestTemplateHttp;
import com.yuier.yuni.permission.service.GroupUserPluginPermissionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 插件系统自动配置——把新插件系统装配为一个可用的 Spring 组件。
 *
 * <h2>装配的三件东西</h2>
 * <ol>
 *   <li>{@link PluginRuntime}——加载、依赖推进、卸载的总入口；</li>
 *   <li>{@link PluginModuleLoader}——jar 扫描与类加载；</li>
 *   <li>框架内置服务——{@code Cache} / {@code Http} / {@code PermissionService}。</li>
 * </ol>
 *
 * <h2>关于 {@code YuniBot} 的延迟解析</h2>
 * <p>{@code YuniBot} 由 engine 的 {@code BotManager} 在启动流程中<b>动态创建</b>
 * （它需要先选工厂、再建连接），因此本配置注入的是
 * {@link ObjectProvider} 而非实例——每次需要时才解析，避免
 * 「Spring 装配期 bot 还不存在」导致启动失败。</p>
 *
 * <p>{@code BotManager} 创建完 bot 后会调用
 * {@link PluginRuntime#bindBot} 把实例回填。</p>
 *
 * <h2>插件目录</h2>
 * <p>沿用现行配置项 {@code bot.app.plugin.directory}（默认 {@code plugins}），
 * 因此运维无需改动既有部署结构。</p>
 *
 * @see PluginRuntime
 */
@Slf4j
@Configuration
public class PluginSystemConfig {

    /** 插件目录，与现行系统共用同一配置项。 */
    @Value("${bot.app.plugin.directory:plugins}")
    private String pluginDirectory;

    /**
     * 构造插件运行时。
     *
     * @param taskManager   定时任务管理器（提供调度能力）
     * @param jsonCodec     JSON 编解码器（提供配置/缓存/HTTP 能力）
     * @param botProvider   机器人实例提供者（延迟解析）
     * @param botApp        机器人业务配置（提供命令前缀）
     * @return 插件运行时
     */
    @Bean
    @ConditionalOnMissingBean
    public PluginRuntime pluginRuntime(DynamicTaskManager taskManager,
                                       JsonCodec jsonCodec,
                                       ObjectProvider<YuniBot> botProvider,
                                       ObjectProvider<BotApp> botApp,
                                       ObjectProvider<Cache> cache,
                                       ObjectProvider<Http> http,
                                       ObjectProvider<PermissionService> permission) {
        PluginRuntime runtime = new PluginRuntime(taskManager, jsonCodec);
        // bot 与 BotApp 都可能晚于本 Bean 就绪，因此用供应函数而不是直接传实例
        runtime.bindBot(
                () -> botProvider.getIfAvailable(),
                botApp.getIfAvailable());

        // 把内置服务登记进运行时：插件通过 ctx.cache() / ctx.http() / ctx.permission()
        // 拿到的是同一个共享实例，连接池与权限缓存因此不会被割裂
        Cache cacheBean = cache.getIfAvailable();
        if (cacheBean != null) {
            runtime.registerService(Cache.class, cacheBean);
        }
        Http httpBean = http.getIfAvailable();
        if (httpBean != null) {
            runtime.registerService(Http.class, httpBean);
        }
        PermissionService permissionBean = permission.getIfAvailable();
        if (permissionBean != null) {
            runtime.registerService(PermissionService.class, permissionBean);
        }

        /*
         * JSON 编解码器也登记为内置服务。
         *
         * 为什么需要：协议适配器类插件（maimai）必须处理「框架不理解的结构化数据」——
         * 它接收 OneBot 客户端发来的任意 action 请求（形如 Map），并返回任意响应。
         * 这类插件用一个中间层（yuni-plugin）无法预知的 JSON 结构，
         * 因此需要一个明确的 JSON 编解码入口。
         *
         * 为何不放进 YuniContext 的快捷方法：那会让 JsonCodec（core 的类型）
         * 出现在插件 API 的显式契约里，而只有极少数协议适配器需要它。
         * 经 ctx.service(JsonCodec.class) 解析既满足需求，又不扩大 API 面。
         * 这也修掉了旧实现里 PluginUtils.getBean(ObjectMapper.class) 那种
         * 「从 Spring 容器里随手捞一个工具类」的隐式依赖。
         */
        runtime.registerService(JsonCodec.class, jsonCodec);

        log.info("插件运行时已创建，插件目录: {}", pluginDirectory);
        return runtime;
    }

    /**
     * 构造插件模块加载器。
     *
     * @param objectMapper JSON 映射器（解析模块元数据）
     * @param runtime      插件运行时
     * @return 模块加载器
     */
    @Bean
    @ConditionalOnMissingBean
    public PluginModuleLoader pluginModuleLoader(ObjectMapper objectMapper, PluginRuntime runtime) {
        return new PluginModuleLoader(objectMapper, runtime);
    }

    /**
     * 注册框架内置服务：缓存。
     *
     * @param jsonCodec JSON 编解码器
     * @return 缓存门面
     */
    @Bean
    @ConditionalOnMissingBean
    public Cache pluginCache(JsonCodec jsonCodec) {
        return new RedisCache(jsonCodec);
    }

    /**
     * 注册框架内置服务：HTTP 客户端。
     *
     * @param jsonCodec JSON 编解码器
     * @return HTTP 门面
     */
    @Bean
    @ConditionalOnMissingBean
    public Http pluginHttp(JsonCodec jsonCodec) {
        return new RestTemplateHttp(jsonCodec);
    }

    /**
     * 注册框架内置服务：权限判定。
     *
     * <p>委托给现行的 {@code GroupUserPluginPermissionService} 做例外记录查询，
     * 默认权限回落逻辑在本实现内——同时修掉了现行
     * {@code UserPermissionManager} 的两个缺陷（缓存不刷新、HashMap 非线程安全），
     * 见 {@link DefaultPermissionService} 的类注释。</p>
     *
     * @param permissionStore 权限例外数据访问
     * @param botConfig       机器人配置（提供 masterId）
     * @param botProvider     机器人实例提供者（查询群成员角色）
     * @return 权限服务
     */
    @Bean
    @ConditionalOnMissingBean
    public PermissionService pluginPermissionService(GroupUserPluginPermissionService permissionStore,
                                                     ObjectProvider<Bot> botConfig,
                                                     ObjectProvider<YuniBot> botProvider) {
        return new DefaultPermissionService(
                permissionStore,
                botConfig.getIfAvailable(),
                botProvider.getIfAvailable());
    }

    /**
     * 插件系统启动器——在 Spring 容器就绪后加载插件目录下的全部模块。
     *
     * <p>用 {@link Lazy} 标注是为了确保它<b>不</b>在 Bean 装配期就被实例化：
     * 加载插件需要权限服务、缓存等全部就绪。真正的触发点是
     * {@code SystemInitializeRunner} 调用 {@link PluginBootstrap#initialize()}——
     * 这样插件加载时机由启动编排显式控制，而不是靠 Bean 初始化顺序的隐式约定。</p>
     *
     * @param loader  模块加载器
     * @param runtime 插件运行时
     * @return 启动器
     */
    @Bean
    @Lazy
    @ConditionalOnMissingBean
    public PluginBootstrap pluginBootstrap(PluginModuleLoader loader, PluginRuntime runtime) {
        /*
         * 顺手把加载器绑到运行时上。
         *
         * 热重载需要「重新读 jar」，而读 jar 的逻辑在 PluginModuleLoader 里；
         * 该对象与本 Bean 同时装配（都晚于 PluginRuntime 的构造），
         * 因此用后置绑定而不是在 pluginRuntime(...) 里加参数——
         * 那会让运行时 Bean 与加载器 Bean 形成循环依赖
         * （加载器依赖运行时，运行时又依赖加载器）。
         */
        runtime.bindModuleLoader(loader);
        return new PluginBootstrap(loader, runtime, pluginDirectory);
    }

    /**
     * 插件系统启动器——负责扫描并加载插件目录。
     *
     * <p>与 {@code PluginRuntime} 分离的理由：运行时是长期存在的系统组件，
     * 而「扫描目录、加载全部模块」是<b>一次性的启动动作</b>。分开后可以
     * 单独测试加载流程，也便于热重载时复用同一运行时。</p>
     */
    @Slf4j
    public static class PluginBootstrap {

        /** 模块加载器。 */
        private final PluginModuleLoader loader;

        /** 插件运行时。 */
        private final PluginRuntime runtime;

        /** 插件目录。 */
        private final String pluginDirectory;

        /**
         * 构造启动器。
         *
         * @param loader          模块加载器
         * @param runtime         插件运行时
         * @param pluginDirectory 插件目录
         */
        public PluginBootstrap(PluginModuleLoader loader, PluginRuntime runtime, String pluginDirectory) {
            this.loader = loader;
            this.runtime = runtime;
            this.pluginDirectory = pluginDirectory;
        }

        /**
         * 扫描插件目录并加载全部模块。
         *
         * @return 成功加载的插件 Fiber 列表
         */
        public List<PluginFiber> initialize() {
            List<File> jarFiles = loader.collectJarFiles(pluginDirectory);
            log.info("扫描插件目录 {}，发现 {} 个 jar 包", pluginDirectory, jarFiles.size());

            List<PluginFiber> all = new ArrayList<>();
            for (File jarFile : jarFiles) {
                Path moduleDir = jarFile.getParentFile() == null
                        ? null
                        : jarFile.getParentFile().toPath();
                all.addAll(loader.loadModule(jarFile, moduleDir));
            }

            // 依赖驱动的推进：某个插件提供的服务可能让先前停在 PENDING 的插件就绪。
            // 反复扫描到不动点，循环依赖会被留在 PENDING 并在下面报出。
            int activated = runtime.activatePending();
            if (activated > 0) {
                log.info("依赖推进使 {} 个插件从 PENDING 转为 ACTIVE", activated);
            }

            reportUnhealthy();

            log.info("插件系统启动完成: {}", runtime.describe());
            return all;
        }

        /**
         * 报告不健康的插件（PENDING / FAILED）。
         *
         * <p>这是现行系统完全缺失的可观测性——「我的插件为什么没生效」
         * 在现行系统里只能靠翻日志猜。这里显式报出缺失的依赖与失败原因。</p>
         */
        private void reportUnhealthy() {
            List<String> pending = runtime.reportPendingPlugins();
            if (!pending.isEmpty()) {
                log.warn("以下插件因依赖未满足而停在 PENDING（检查是否存在循环依赖）：");
                pending.forEach(item -> log.warn("  - {}", item));
            }
            List<String> failed = runtime.reportFailedPlugins();
            if (!failed.isEmpty()) {
                log.error("以下插件加载失败：");
                failed.forEach(item -> log.error("  - {}", item));
            }
        }

        /**
         * 插件目录（供管理端展示）。
         *
         * @return 目录路径
         */
        public Path pluginDirectory() {
            return Paths.get(pluginDirectory);
        }
    }
}
