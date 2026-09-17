package com.yuier.yuni.webapi.service.plugin;

import com.yuier.yuni.plugin.api.PluginAdmin;
import com.yuier.yuni.plugin.api.PluginAdminException;
import com.yuier.yuni.plugin.api.PluginModuleView;
import com.yuier.yuni.plugin.api.PluginView;
import com.yuier.yuni.plugin.runtime.PluginRuntime;
import com.yuier.yuni.webapi.dto.plugin.PluginInfo;
import com.yuier.yuni.webapi.dto.plugin.PluginModuleInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 插件管理服务（管理端 HTTP 接口）——插件列表查询、启用/禁用、重载。
 *
 * <h2>P6 迁移说明：本类是 webapi 侧「新系统接管」的开关</h2>
 * <p>旧实现直接依赖四个框架内部类：{@code PluginContainer}、
 * {@code PluginEnableProcessor}、{@code PluginManager}、{@code PluginLoadProcessor}，
 * 并且<b>把热重载逻辑又实现了一遍</b>（{@code reloadAllPlugins} /
 * {@code reloadSinglePlugin} / {@code registerActivePlugin} /
 * {@code registerScheduledPlugin} / {@code registerImmediatePlugin} /
 * {@code findJarFileForPlugin}，约 170 行）。</p>
 *
 * <p>那 170 行与内置插件 {@code plugin-manage} 的 {@code PluginReloadProcessor}
 * 是同一个能力的两个实现——必然漂移。迁移后两边都走
 * {@link PluginAdmin#reload}，本类缩到列表与启停三类操作。</p>
 *
 * <table border="1">
 *   <caption>旧 → 新</caption>
 *   <tr><th>旧实现</th><th>新实现</th></tr>
 *   <tr><td>{@code controller.listPlugins()} 遍历 {@code PluginContainer} 的八条类型列表</td>
 *       <td>{@code admin.modules()}</td></tr>
 *   <tr><td>{@code pluginEnableProcessor.enablePlugin(...)}（且先调插件的 enable 回调）</td>
 *       <td>{@code admin.setGlobalEnabled(...)} / {@code admin.setEnabled(...)}</td></tr>
 *   <tr><td>170 行手工重载（清容器、取消定时任务、逐个重建索引）</td>
 *       <td>{@code admin.reload(moduleId)}</td></tr>
 * </table>
 *
 * <h2>{@code groupId} 为空时的语义</h2>
 * <p>控制层允许 {@code groupId} 为空，旧语义是「全局操作」。新系统用
 * {@link PluginAdmin#setGlobalEnabled} 承接，与「针对某个群」
 * （{@link PluginAdmin#setEnabled}）明确区分开。</p>
 *
 * <h2>权限</h2>
 * <p>{@code callerIsMaster} 传 {@code true}：管理端接口本身已有登录鉴权
 * （见 webapi 的鉴权层），能到达这里的调用者就是后台管理员，
 * 等价于「bot 拥有者」。因此内置插件的保护在这里不会误拦。</p>
 *
 * @see PluginAdmin
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PluginService {

    /**
     * 插件运行时。
     *
     * <p>注入运行时而不是 {@link PluginAdmin}：后者由运行时按需提供
     * （{@code runtime.admin()}），不是 Spring Bean——它的生命周期与插件系统
     * 一致，而插件系统在启动流程的后段才就绪。这样也保证了本类拿到的
     * 一定是「当前生效」的那一份视图。</p>
     */
    private final PluginRuntime pluginRuntime;

    /** 管理端接口的调用者视为 bot 拥有者。 */
    private static final boolean CALLER_IS_MASTER = true;

    // ==================== 查询 ====================

    /**
     * 获取插件列表，以模块为单位分组。
     *
     * <p>响应结构保持与旧接口一致（{@code moduleId} / {@code moduleName} /
     * {@code plugins}），以免破坏前端契约。其中两处字段语义有变：
     * {@code fullId} 现在是新格式的插件标识，{@code type} 现在是运行状态
     * （见 {@link PluginInfo} 的说明）。</p>
     *
     * @return 插件模块列表，顺序与加载顺序一致
     */
    public List<PluginModuleInfo> listPlugins() {
        PluginAdmin admin = pluginRuntime.admin();
        List<PluginModuleInfo> result = new ArrayList<>();
        for (PluginModuleView module : admin.modules()) {
            List<PluginInfo> plugins = new ArrayList<>();
            for (PluginView plugin : module.plugins()) {
                plugins.add(new PluginInfo(
                        plugin.id(),
                        plugin.name(),
                        plugin.description(),
                        plugin.tips(),
                        plugin.version(),
                        plugin.author(),
                        plugin.moduleId(),
                        plugin.buildIn(),
                        plugin.state().name(),
                        admin.isGloballyEnabled(plugin.id()),
                        new LinkedHashSet<>(admin.enabledGroups(plugin.id())),
                        plugin.failureSummary().orElse(null),
                        plugin.missingServices()));
            }
            result.add(new PluginModuleInfo(module.id(), module.name(), plugins));
        }
        return result;
    }

    // ==================== 启用 / 禁用 ====================

    /**
     * 启用插件。
     *
     * @param pluginId 插件标识（形如 {@code steam/MemberSteamStatus}）
     * @param groupId  目标群号；为 {@code null} 时全局启用
     * @throws IllegalArgumentException 插件不存在
     */
    public void enablePlugin(String pluginId, Long groupId) {
        applyEnabled(pluginId, groupId, true);
    }

    /**
     * 禁用插件。
     *
     * @param pluginId 插件标识
     * @param groupId  目标群号；为 {@code null} 时全局禁用
     * @throws IllegalArgumentException 插件不存在
     */
    public void disablePlugin(String pluginId, Long groupId) {
        applyEnabled(pluginId, groupId, false);
    }

    /**
     * 执行启用 / 禁用。
     *
     * @param pluginId 插件标识
     * @param groupId  群号；{@code null} 表示全局
     * @param enabled  目标状态
     * @throws IllegalArgumentException 插件不存在或为受保护的内置插件
     */
    private void applyEnabled(String pluginId, Long groupId, boolean enabled) {
        PluginAdmin admin = pluginRuntime.admin();
        try {
            if (groupId == null) {
                admin.setGlobalEnabled(pluginId, enabled, CALLER_IS_MASTER);
            } else {
                admin.setEnabled(pluginId, groupId, enabled, CALLER_IS_MASTER);
            }
        } catch (PluginAdminException e) {
            /*
             * 把可预期的业务拒绝翻译成 IllegalArgumentException。
             *
             * 理由：控制层已有的异常映射按 IllegalArgumentException 处理
             * （404 / 400），保持它不变可以避免同时改动控制器与前端错误处理。
             * PluginAdminException 是插件侧的契约，不应泄漏到 HTTP 层。
             */
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        log.info("已{}插件: {} (groupId={})", enabled ? "启用" : "禁用", pluginId, groupId);
    }

    // ==================== 重载 ====================

    /**
     * 重载插件。
     *
     * <p>{@code pluginId} 为空时重载<b>全部模块</b>；非空时重载该插件所在的模块
     * （即一个 jar 包）。</p>
     *
     * <p>与旧实现的关键差别：旧实现「重载全部」是先销毁全部实例再重新扫描，
     * 中途失败会让 bot 处于「零插件」状态。新实现的 {@code reload} 逐个模块
     * 处理，单个模块失败不影响其他模块，且失败原因会回给调用方。</p>
     *
     * @param pluginId 目标插件标识；为空时重载全部
     * @throws IllegalArgumentException 插件不存在或找不到对应模块
     * @throws RuntimeException         重载过程中发生异常
     */
    public void reloadPlugin(String pluginId) {
        PluginAdmin admin = pluginRuntime.admin();
        if (pluginId == null || pluginId.isBlank()) {
            reloadAll(admin);
            return;
        }

        PluginView plugin = admin.plugin(pluginId).orElseThrow(() ->
                new IllegalArgumentException("未找到插件: " + pluginId));
        String moduleId = plugin.moduleId();
        if (moduleId == null || moduleId.isBlank()) {
            throw new IllegalArgumentException(
                    "插件 " + pluginId + " 不属于任何模块，无法重载（它可能是通过代码直接加载的）");
        }
        reloadModule(admin, moduleId);
    }

    /**
     * 重载全部模块。
     *
     * <p>收集全部失败而不是在第一个失败处中断——「重载 12 个模块，其中 1 个失败」
     * 比「重载到第 7 个就停了，不知道后面怎么样」有用得多。</p>
     *
     * @param admin 插件管理服务
     */
    private void reloadAll(PluginAdmin admin) {
        List<String> failed = new ArrayList<>();
        int succeeded = 0;
        for (PluginModuleView module : admin.modules()) {
            try {
                admin.reload(module.id());
                succeeded++;
            } catch (PluginAdminException e) {
                log.error("重载模块 {} 失败: {}", module.id(), e.getMessage());
                failed.add(module.id() + ": " + e.getMessage());
            }
        }
        log.info("已重载 {} 个插件模块", succeeded);
        if (!failed.isEmpty()) {
            throw new RuntimeException("有 " + failed.size() + " 个模块重载失败: " + String.join("; ", failed));
        }
    }

    /**
     * 重载单个模块。
     *
     * @param admin    插件管理服务
     * @param moduleId 模块 ID
     * @throws IllegalArgumentException 模块不存在或 jar 缺失
     */
    private void reloadModule(PluginAdmin admin, String moduleId) {
        try {
            admin.reload(moduleId);
            log.info("已重载插件模块: {}", moduleId);
        } catch (PluginAdminException e) {
            // 与 applyEnabled 一样，把插件侧异常翻译为控制层已处理的类型
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }
}
