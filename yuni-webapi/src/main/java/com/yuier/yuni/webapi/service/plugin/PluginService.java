package com.yuier.yuni.webapi.service.plugin;

import com.yuier.yuni.plugin.api.PluginAdmin;
import com.yuier.yuni.plugin.api.PluginAdminException;
import com.yuier.yuni.plugin.api.PluginView;
import com.yuier.yuni.plugin.runtime.PluginSystem;
import com.yuier.yuni.webapi.dto.plugin.PluginInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 插件管理服务（管理端 HTTP 接口）——插件列表查询、启用/禁用、重载。
 *
 * <h2>职责边界</h2>
 * <p>本类只把 {@link PluginAdmin} 的契约翻译成 HTTP 响应，不重复实现加载、
 * 启停或重载逻辑。这些能力全部在插件运行时里，webapi 与内置的
 * {@code plugin-manage} 共用同一份实现。</p>
 *
 * <h2>{@code groupId} 为空时的语义</h2>
 * <p>控制层允许 {@code groupId} 为空，表示「全局操作」。新系统用
 * {@link PluginAdmin#setGlobalEnabled} 承接，与「针对某个群」
 * （{@link PluginAdmin#setEnabled}）明确区分开。</p>
 *
 * <h2>权限</h2>
 * <p>{@code callerIsMaster} 传 {@code true}：管理端接口本身已有登录鉴权，
 * 能到达这里的调用者就是后台管理员，等价于「bot 拥有者」。</p>
 *
 * @see PluginAdmin
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PluginService {

    /**
     * 插件系统（super Bean）——运行时的唯一持有者。
     *
     * <p>本 Bean 装配时运行时还不存在（要等启动编排创建），因此这里持有
     * super Bean，在每次请求时经它的 {@code getAdmin()} 取值；
     * 未就绪时会抛出明确错误，而不是返回空列表。</p>
     */
    private final PluginSystem pluginSystem;

    /** 管理端接口的调用者视为 bot 拥有者。 */
    private static final boolean CALLER_IS_MASTER = true;

    // ==================== 查询 ====================

    /**
     * 获取插件列表（平铺）。
     *
     * @return 插件列表，顺序与加载顺序一致
     */
    public List<PluginInfo> listPlugins() {
        PluginAdmin admin = pluginSystem.getAdmin();
        List<PluginInfo> result = new ArrayList<>();
        for (PluginView plugin : admin.getPlugins()) {
            result.add(new PluginInfo(
                    plugin.getId(),
                    plugin.getName(),
                    plugin.getDescription(),
                    plugin.getTips(),
                    plugin.getVersion(),
                    plugin.getAuthor(),
                    plugin.getArtifactId(),
                    plugin.isBuildIn(),
                    plugin.getState().name(),
                    // 全局默认值。注意它<b>不是</b>「对所有群都生效」——
                    // 存在群例外时，个别群的结论由 enabledGroups 与按群接口给出。
                    admin.isGloballyEnabled(plugin.getId()),
                    new LinkedHashSet<>(admin.enabledGroups(plugin.getId())),
                    plugin.getFailureSummary().orElse(null),
                    plugin.getMissingServices()));
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
        PluginAdmin admin = pluginSystem.getAdmin();
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
     * <p>{@code pluginId} 为空时重载<b>全部 jar</b>；非空时重载该插件所属的 jar
     * （同 jar 的其它插件会一起重启）。</p>
     *
     * @param pluginId 目标插件标识；为空时重载全部
     * @throws IllegalArgumentException 插件不存在或找不到对应 jar
     */
    public void reloadPlugin(String pluginId) {
        PluginAdmin admin = pluginSystem.getAdmin();
        try {
            if (pluginId == null || pluginId.isBlank()) {
                reloadAll(admin);
            } else {
                admin.reload(pluginId);
                log.info("已重载插件: {}", pluginId);
            }
        } catch (PluginAdminException e) {
            // 与 applyEnabled 一样，把插件侧异常翻译为控制层已处理的类型
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    /**
     * 重载全部 jar。
     *
     * <p>收集全部失败而不是在第一个失败处中断——「重载 12 个 jar，其中 1 个失败」
     * 比「重载到第 7 个就停了，不知道后面怎么样」有用得多。</p>
     *
     * @param admin 插件管理服务
     */
    private void reloadAll(PluginAdmin admin) {
        List<String> failed = new ArrayList<>();
        int succeeded = 0;
        for (PluginView plugin : admin.getPlugins()) {
            try {
                admin.reload(plugin.getId());
                succeeded++;
            } catch (PluginAdminException e) {
                log.error("重载插件 {} 失败: {}", plugin.getId(), e.getMessage());
                failed.add(plugin.getId() + ": " + e.getMessage());
            }
        }
        log.info("已重载 {} 个插件", succeeded);
        if (!failed.isEmpty()) {
            throw new RuntimeException("有 " + failed.size() + " 个插件重载失败: " + String.join("; ", failed));
        }
    }
}
