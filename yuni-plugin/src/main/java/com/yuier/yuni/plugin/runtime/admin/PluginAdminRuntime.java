package com.yuier.yuni.plugin.runtime.admin;

import com.yuier.yuni.plugin.api.PluginAdmin;
import com.yuier.yuni.plugin.api.PluginAdminException;
import com.yuier.yuni.plugin.api.PluginModuleView;
import com.yuier.yuni.plugin.api.PluginState;
import com.yuier.yuni.plugin.api.PluginView;
import com.yuier.yuni.plugin.runtime.FiberRegistry;
import com.yuier.yuni.plugin.runtime.PluginFiber;
import com.yuier.yuni.plugin.runtime.PluginRuntime;
import com.yuier.yuni.plugin.runtime.load.PluginModuleLoader;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;

/**
 * {@link PluginAdmin} 的默认实现——把 {@link PluginRuntime} 的内部状态投影为
 * 公共契约，并把写操作翻译回运行时调用。
 *
 * <h2>本类不持有状态</h2>
 * <p>全部状态都在 {@link PluginRuntime} 里（Fiber 容器、服务表、作用域过滤器）。
 * 本类是<b>无状态的适配器</b>，因此可以由运行时按需新建（见
 * {@code PluginRuntime#admin()}），不需要缓存，也不会出现「两个管理视图不一致」。</p>
 *
 * <h2>本类不做权限校验</h2>
 * <p>唯一的例外是内置插件保护——它需要一个 {@code callerIsMaster} 标志。
 * 该标志由调用方传入（见 {@link #setEnabled} 的说明），本类只负责在
 * 「目标内置」且「调用方不是拥有者」时拒绝。</p>
 *
 * @see PluginAdmin
 * @see FiberPluginView
 */
@Slf4j
public final class PluginAdminRuntime implements PluginAdmin {

    /** 运行时。 */
    private final PluginRuntime runtime;

    /**
     * 模块加载器。
     *
     * <p>本类不直接用它——重载逻辑在
     * {@link PluginRuntime#reload(String)} 内。保留引用只是为了在
     * {@link #reload} 里给出更准确的错误信息（区分「运行时没装配加载器」
     * 与「模块不存在」）。</p>
     */
    @SuppressWarnings("unused")
    private final PluginModuleLoader moduleLoader;

    /**
     * 构造适配器。
     *
     * @param runtime      插件运行时
     * @param moduleLoader 模块加载器；可为 {@code null}
     */
    public PluginAdminRuntime(PluginRuntime runtime, PluginModuleLoader moduleLoader) {
        this.runtime = runtime;
        this.moduleLoader = moduleLoader;
    }

    // ==================== 查询 ====================

    @Override
    public List<PluginModuleView> modules() {
        FiberRegistry fibers = runtime.fibers();
        return fibers.allModules().stream()
                .map(handle -> (PluginModuleView) new FiberModuleView(handle, fibers))
                .toList();
    }

    @Override
    public List<PluginView> plugins() {
        return runtime.fibers().all().stream()
                .filter(fiber -> fiber.parent() == null)
                .map(this::viewOf)
                .toList();
    }

    @Override
    public Optional<PluginView> plugin(String pluginId) {
        return runtime.fibers().byId(pluginId).map(this::viewOf);
    }

    @Override
    public List<PluginView> pluginsOfModule(String moduleId) {
        return runtime.fibers().ofModule(moduleId).stream()
                .filter(fiber -> fiber.parent() == null)
                .map(this::viewOf)
                .toList();
    }

    /**
     * 把 Fiber 投影为公共视图，并补上它的模块 ID。
     *
     * @param fiber 插件 Fiber
     * @return 视图
     */
    private PluginView viewOf(PluginFiber fiber) {
        return new FiberPluginView(fiber, runtime.moduleIdOf(fiber.id()).orElse(null));
    }

    // ==================== 群粒度启停 ====================

    @Override
    public boolean isGloballyEnabled(String pluginId) {
        // 插件不存在时返回 false：与 isEnabledIn 的「可以问出一个否定的答案」一致
        if (runtime.fibers().byId(pluginId).isEmpty()) {
            return false;
        }
        return runtime.isGloballyEnabled(pluginId);
    }

    @Override
    public boolean isEnabledIn(String pluginId, long groupId) {
        Optional<PluginFiber> fiber = runtime.fibers().byId(pluginId);
        if (fiber.isEmpty()) {
            // 插件不存在是一个可以被问出的普通问题（用户输入错 id），
            // 不应该变成一次异常——返回 false 即可
            return false;
        }
        Boolean explicit = runtime.isPluginEnabledIn(pluginId, groupId);
        // 无显式配置时回落到元数据的默认值。判定链的唯一实现在这里，
        // 管理端与 ctx.scope() 走同一条路径，因此不会给出矛盾答案
        return explicit != null ? explicit : fiber.get().metadata().defaultEnable();
    }

    @Override
    public List<Long> enabledGroups(String pluginId) {
        /*
         * 语义选择：只返回「被显式**开启**的群」。
         *
         * 为什么不返回「全部生效群」：那需要向机器人查询群列表（一次网络调用），
         * 而本方法在管理端列表渲染时会被每个插件调用一次——N 个插件就是 N 次
         * 群列表查询。需要全部生效群时应当用 ctx.scope().enabledGroups()，
         * 那个方法与「本插件」绑定，调用次数天然受控。
         *
         * 三态模型下「显式开启」有两种来源：白名单里的群（默认关闭 + 该群开启），
         * 以及黑名单<b>之外</b>的群——后者无法枚举（是无限集），
         * 因此这里只返回白名单里的群。这与旧 group_plugin_ability 表
         * 「ability = true 的行」语义一致。
         */
        java.util.LinkedHashSet<Long> enabled = new java.util.LinkedHashSet<>();
        for (com.yuier.yuni.plugin.api.ScopeFilter filter : runtime.scopeExceptionsOf(pluginId)) {
            filter.explicitGroupWhitelist().ifPresent(enabled::addAll);
        }
        return List.copyOf(enabled);
    }

    @Override
    public void setEnabled(String pluginId, long groupId, boolean enabled, boolean callerIsMaster) {
        PluginFiber fiber = runtime.fibers().byId(pluginId).orElseThrow(() ->
                new PluginAdminException(pluginId, "没有标识为 " + pluginId + " 的插件。"));
        requireMasterForBuiltIn(pluginId, fiber, callerIsMaster);
        runtime.setPluginEnabledIn(pluginId, groupId, enabled);
    }

    @Override
    public void setGlobalEnabled(String pluginId, boolean enabled, boolean callerIsMaster) {
        PluginFiber fiber = runtime.fibers().byId(pluginId).orElseThrow(() ->
                new PluginAdminException(pluginId, "没有标识为 " + pluginId + " 的插件。"));
        requireMasterForBuiltIn(pluginId, fiber, callerIsMaster);
        runtime.setPluginGlobalEnabled(pluginId, enabled);
    }

    /**
     * 校验「内置插件只能由 bot 拥有者操作」。
     *
     * <p>抽成方法而不是两处各写一遍：这条规则同时适用于群粒度启停与全局启停，
     * 而它恰好是旧 {@code PluginEnable} 里那段
     * {@code isBuiltIn() && permission < MASTER} 的新家——只此一处。</p>
     *
     * @param pluginId       插件标识
     * @param fiber          目标 Fiber
     * @param callerIsMaster 调用者是否为 bot 拥有者
     * @throws PluginAdminException 内置插件被非拥有者操作
     */
    private static void requireMasterForBuiltIn(String pluginId, PluginFiber fiber, boolean callerIsMaster) {
        if (fiber.metadata().buildIn() && !callerIsMaster) {
            // 结构性保护：内置插件随框架发布，误关会让管理端自己失效
            // （plugin-manage 与 help 都是内置插件）
            throw new PluginAdminException(pluginId,
                    "插件「" + fiber.metadata().name() + "」为内置插件，只有 bot 拥有者有权开启或关闭。");
        }
    }

    @Override
    public void resetScope(String pluginId) {
        if (runtime.fibers().byId(pluginId).isEmpty()) {
            throw new PluginAdminException(pluginId, "没有标识为 " + pluginId + " 的插件。");
        }
        if (!runtime.resetPluginScope(pluginId)) {
            log.debug("插件 {} 本来就没有作用域配置", pluginId);
        }
    }

    // ==================== 生命周期 ====================

    @Override
    public void reload(String moduleId) {
        if (runtime.fibers().module(moduleId).isEmpty()) {
            throw new PluginAdminException(null, "没有标识为 " + moduleId + " 的模块。");
        }
        try {
            runtime.reload(moduleId);
        } catch (IllegalStateException e) {
            // 运行时的 IllegalStateException 描述的是「这次重载为什么做不了」
            // （jar 找不到 / 未装配加载器），属于用户可理解的失败
            throw new PluginAdminException(null, e.getMessage(), e);
        }
    }

    @Override
    public void unload(String pluginId) {
        PluginFiber fiber = runtime.fibers().byId(pluginId).orElseThrow(() ->
                new PluginAdminException(pluginId, "没有标识为 " + pluginId + " 的插件。"));
        if (fiber.metadata().buildIn()) {
            /*
             * 内置插件不允许单独卸载。
             *
             * 为什么这里不看 callerIsMaster：卸载内置插件不是「权限不够」的问题，
             * 而是「卸载之后没人能再把它装回来」——插件管理命令本身就是内置插件，
             * 而重新加载的入口（webapi / 启动流程）不在插件手上。
             * 需要替换内置插件请走 reload，那里有完整的卸载 + 重装流程。
             */
            throw new PluginAdminException(pluginId,
                    "插件「" + fiber.metadata().name() + "」为内置插件，不能单独卸载。"
                            + "如需更新请使用重载（它会卸载并重新加载整个模块）。");
        }
        runtime.unload(pluginId);
    }

    @Override
    public String summary() {
        FiberRegistry fibers = runtime.fibers();
        var counts = fibers.countByState();
        return "模块 " + fibers.moduleCount()
                + " / 插件 " + fibers.pluginCount()
                + " / 运行中 " + counts.getOrDefault(com.yuier.yuni.plugin.runtime.FiberState.ACTIVE, 0)
                + " / 失败 " + counts.getOrDefault(com.yuier.yuni.plugin.runtime.FiberState.FAILED, 0)
                + " / 等待依赖 " + counts.getOrDefault(com.yuier.yuni.plugin.runtime.FiberState.PENDING, 0);
    }

    /**
     * 状态汇总，供管理端看板使用。
     *
     * <p>返回 {@link PluginState} → 数量的映射，键覆盖全部状态（无插件时为 0），
     * 因此消费方不需要处理缺键。</p>
     *
     * @return 不可变映射
     */
    public java.util.Map<PluginState, Integer> countByState() {
        java.util.Map<PluginState, Integer> result = new java.util.LinkedHashMap<>();
        for (PluginState state : PluginState.values()) {
            result.put(state, 0);
        }
        for (PluginFiber fiber : runtime.fibers().all()) {
            PluginState state = new FiberPluginView(fiber, null).state();
            result.merge(state, 1, Integer::sum);
        }
        return java.util.Collections.unmodifiableMap(result);
    }
}
