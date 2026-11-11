package com.yuier.yuni.plugin.runtime.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuier.yuni.core.bot.JsonCodec;
import com.yuier.yuni.core.bot.YuniBot;
import com.yuier.yuni.core.model.bot.Bot;
import com.yuier.yuni.core.task.DynamicTaskManager;
import com.yuier.yuni.plugin.api.service.Cache;
import com.yuier.yuni.plugin.api.service.Http;
import com.yuier.yuni.plugin.api.service.PermissionService;
import com.yuier.yuni.plugin.runtime.service.DefaultPermissionService;
import com.yuier.yuni.plugin.runtime.service.RedisCache;
import com.yuier.yuni.plugin.runtime.service.RestTemplateHttp;
import com.yuier.yuni.permission.service.GroupUserPluginPermissionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 插件系统自动配置——只负责<b>基础设施 Bean</b> 与组装器。
 *
 * <h2>核心组件为什么不在这里声明</h2>
 * <p>{@code PluginRuntime} / {@code PluginModuleLoader} / {@link PluginBootstrap}
 * 的创建时机必须晚于机器人就绪，因此<b>不由容器创建</b>：</p>
 * <ol>
 *   <li>{@link PluginSystemAssembler} 持有本类提供的基础设施依赖，
 *       在启动编排里被调用；</li>
 *   <li>{@link PluginSystemInitializer} 负责在 {@code startBot()} 之后组装、
 *       并把运行时注册回容器。</li>
 * </ol>
 *
 * <h2>关于 {@code YuniBot} 的延迟解析</h2>
 * <p>{@code YuniBot} 由 engine 的 {@code BotManager} 在启动流程中<b>动态创建</b>
 * （它需要先选工厂、再建连接），因此权限服务注入的是供应函数而非实例——
 * 调用时才解析，避免「Spring 装配期 bot 还不存在」导致永久为 null。</p>
 *
 * <h2>插件目录</h2>
 * <p>沿用现行配置项 {@code bot.app.plugin.directory}（默认 {@code plugins}），
 * 因此运维无需改动既有部署结构。</p>
 *
 * @see PluginSystemAssembler
 * @see PluginSystemInitializer
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

}
