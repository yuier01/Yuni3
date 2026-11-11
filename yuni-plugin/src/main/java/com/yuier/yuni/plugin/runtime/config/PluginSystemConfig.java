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
@Configuration
public class PluginSystemConfig {

    /** 插件目录，与现行系统共用同一配置项。 */
    @Value("${bot.app.plugin.directory:plugins}")
    private String pluginDirectory;

    /**
     * 构造插件系统组装器。
     *
     * <p><b>不再直接声明 {@code PluginRuntime} / {@code PluginModuleLoader} /
     * {@code PluginBootstrap} 三个 Bean</b>：它们的创建时机必须晚于
     * {@code BotManager.startBot()}，因此改由 {@code SystemInitializeRunner}
     * 在启动编排里显式调用 {@link PluginSystemAssembler#assemble} 组装，
     * 组装完成后再 {@code registerSingleton} 交回容器。</p>
     *
     * <p>本 Bean 只持有基础设施依赖——它们没有时机问题，交给容器注入最省事。</p>
     *
     * @param taskManager   定时任务管理器
     * @param jsonCodec     JSON 编解码器
     * @param objectMapper  模块元数据解析
     * @param cache         缓存门面（可选）
     * @param http          HTTP 门面（可选）
     * @param permission    权限服务（可选）
     * @param scopeStore    作用域持久化端口
     * @return 组装器
     */
    @Bean
    @ConditionalOnMissingBean
    public PluginSystemAssembler pluginSystemAssembler(DynamicTaskManager taskManager,
                                                       JsonCodec jsonCodec,
                                                       ObjectMapper objectMapper,
                                                       ObjectProvider<Cache> cache,
                                                       ObjectProvider<Http> http,
                                                       ObjectProvider<PermissionService> permission,
                                                       com.yuier.yuni.plugin.runtime.PluginScopeStore scopeStore) {
        return new PluginSystemAssembler(
                taskManager, jsonCodec, objectMapper,
                cache.getIfAvailable(),
                http.getIfAvailable(),
                permission.getIfAvailable(),
                scopeStore,
                pluginDirectory);
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
                // bot 在 refresh 期尚不存在（要到启动编排的 startBot 才注册），
                // 因此传供应函数而不是实例——否则它会永久为 null，群角色映射失效
                () -> botProvider.getIfAvailable());
    }

    /**
     * 插件作用域配置的持久化实现。
     *
     * <p>复用旧的 {@code group_plugin_ability} 表——它的
     * {@code (groupId, pluginId, ability)} 结构正是群粒度启停所需的完整三态数据，
     * 且已在实际部署中积累配置。见实现类的「升级影响」说明。</p>
     *
     * @param abilityService 群插件启停表的数据访问
     * @return 持久化端口
     */
    @Bean
    @ConditionalOnMissingBean
    public com.yuier.yuni.plugin.runtime.PluginScopeStore pluginScopeStore(
            com.yuier.yuni.plugin.service.GroupPluginAbilityService abilityService) {
        return new com.yuier.yuni.plugin.runtime.service.MybatisPluginScopeStore(abilityService);
    }

    /*
     * 这里原本还有一个 @Lazy 的 pluginBootstrap Bean。
     *
     * 去掉它的原因：手动组装（PluginSystemAssembler）已经把「何时创建启动器」
     * 变成 SystemInitializeRunner 里的一行代码，不再需要靠 @Lazy 代理去拖延
     * 单例的实例化——那正是「与容器 eager 模型对抗」的来源。
     */

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

            /*
             * 恢复持久化的群粒度启停配置。
             *
             * 必须在全部模块加载之后：判断「数据库里的一条记录算不算例外」
             * 需要该插件的 defaultEnable，而它只有插件实例化后才读得到。
             *
             * 恢复的配置会覆盖插件代码里声明的基线过滤器——持久化的是用户的
             * 显式选择，而基线是插件作者写的默认建议，用户选择应当胜出
             * （与旧系统一致：group_plugin_ability 优先于 metadata.defaultEnable）。
             */
            runtime.restoreScopes();

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
        public Path getPluginDirectory() {
            return Paths.get(pluginDirectory);
        }
    }
}
