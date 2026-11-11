package com.yuier.yuni.plugin.runtime;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fiber 容器——「哪个插件正跑在哪里」的树形索引。
 *
 * <h2>取代了什么</h2>
 * <p>现行 {@code PluginContainer} 维护了 8 条类型 ID 列表 + 3 张 Map，
 * 且每张 Map 都要在热重载时被手工增删（{@code PluginReloadProcessor} 与
 * {@code PluginService} 各实现了一遍同样的维护逻辑，是 bug 高发区）。</p>
 *
 * <p>本容器维护一棵<b>树</b>（父子关系由 {@link PluginFiber#getParent()} 表达）
 * 加上按 ID / 模块 / 类名索引的三张 Map。<b>没有 index 字段，没有类型分桶，
 * 没有偏移修正</b>——热重载时「位置」就是「父 Fiber + 在父 Fiber 下的挂载顺序」。</p>
 *
 * <h2>模块与插件的关系</h2>
 * <p>一个 <b>模块</b>（module）对应一个 jar 包，可包含多个插件；每个插件是一个
 * Fiber。模块元数据（{@code module_id} / {@code module_name}）在
 * {@link FiberRegistry.ModuleHandle} 中保存，供管理端分组展示与热重载定位 jar 使用。</p>
 *
 * <h2>线程安全</h2>
 * <p>加载 / 卸载 / 查询可能并发。所有索引用 {@link ConcurrentHashMap}；
 * 顶层顺序用同步列表保护（写入是低频操作，读取时做快照）。</p>
 *
 * @see PluginFiber
 * @see PluginRuntime
 */
public final class FiberRegistry {

    /** 插件标识 → Fiber。 */
    private final Map<String, PluginFiber> fibersById = new ConcurrentHashMap<>();

    /** 模块 ID → 模块句柄。 */
    private final Map<String, ModuleHandle> modulesById = new ConcurrentHashMap<>();

    /** 插件实现类 → 插件标识；供「按类反查插件」使用（现行 {@code PluginUtils} 的用法）。 */
    private final Map<Class<?>, String> pluginIdByClass = new ConcurrentHashMap<>();

    /** 顶层 Fiber 的挂载顺序；用于管理端稳定展示与重载时的原位还原。 */
    private final List<String> rootOrder = Collections.synchronizedList(new ArrayList<>());

    /**
     * 模块句柄——一个 jar 包对应的元数据与 ClassLoader。
     *
     * @param moduleId      模块 ID（来自 metadata.json 的 {@code module_id}）
     * @param moduleName    模块显示名
     * @param jarFileName   jar 文件名，用于热重载时定位文件
     * @param jarParentPath jar 所在目录，同时是插件资源目录
     * @param classLoader   加载该模块插件类的类加载器
     * @param pluginIds     该模块包含的插件标识列表（保持 jar 内声明顺序）
     */
    /**
     * 一个模块的元数据与资源位置。
     *
     * <p>原本是 record；为统一 JavaBean 访问器（{@code getXxx()}）改为
     * {@code @Getter} 类。字段语义与 record 组件一一对应。</p>
     */
    @Getter
    @ToString
    @EqualsAndHashCode
    public static final class ModuleHandle {

        /** 模块 ID（来自 {@code metadata.json} 的 {@code module_id}）。 */
        private final String moduleId;

        /** 模块显示名。 */
        private final String moduleName;

        /** jar 文件名；用于日志与热重载定位。 */
        private final String jarFileName;

        /** jar 所在目录。 */
        private final String jarParentPath;

        /** 加载该模块的类加载器。 */
        private final ClassLoader classLoader;

        /** 该模块下的插件标识（构造时冻结）。 */
        private final List<String> pluginIds;

        /**
         * 构造模块句柄。
         *
         * @param moduleId      模块 ID
         * @param moduleName    模块显示名
         * @param jarFileName   jar 文件名
         * @param jarParentPath jar 所在目录
         * @param classLoader   类加载器
         * @param pluginIds     插件标识列表（会被复制并冻结）
         */
        public ModuleHandle(String moduleId,
                            String moduleName,
                            String jarFileName,
                            String jarParentPath,
                            ClassLoader classLoader,
                            List<String> pluginIds) {
            this.moduleId = moduleId;
            this.moduleName = moduleName;
            this.jarFileName = jarFileName;
            this.jarParentPath = jarParentPath;
            this.classLoader = classLoader;
            // 冻结可变的插件列表
            this.pluginIds = List.copyOf(pluginIds);
        }
    }

    // ==================== 注册 ====================

    /**
     * 登记一个已加载的插件 Fiber。
     *
     * @param fiber     Fiber
     * @param moduleId  所属模块 ID；为 {@code null} 表示不属于任何模块（测试场景）
     * @param topLevel  是否为顶层插件（{@code false} 表示是某个插件的子插件）
     */
    public void add(PluginFiber fiber, String moduleId, boolean topLevel) {
        if (fiber == null) {
            return;
        }
        fibersById.put(fiber.getId(), fiber);
        pluginIdByClass.put(fiber.getPlugin().getClass(), fiber.getId());
        if (topLevel) {
            synchronized (rootOrder) {
                if (!rootOrder.contains(fiber.getId())) {
                    rootOrder.add(fiber.getId());
                }
            }
        }
    }

    /**
     * 登记模块句柄。
     *
     * @param handle 模块句柄
     */
    public void addModule(ModuleHandle handle) {
        if (handle != null && handle.getModuleId() != null) {
            modulesById.put(handle.getModuleId(), handle);
        }
    }

    // ==================== 查询 ====================

    /**
     * 按插件标识取 Fiber。
     *
     * @param pluginId 插件标识
     * @return Fiber；不存在时为空
     */
    public Optional<PluginFiber> byId(String pluginId) {
        return pluginId == null ? Optional.empty() : Optional.ofNullable(fibersById.get(pluginId));
    }

    /**
     * 按插件实现类反查 Fiber。
     *
     * <p>供「插件内部想拿到自己的 Fiber」与「按类启停」两类场景使用。
     * 注意：热重载后旧 Fiber 已被移除，本方法返回的是<b>当前</b>生效的那一个。</p>
     *
     * @param pluginClass 插件实现类
     * @return Fiber；不存在时为空
     */
    public Optional<PluginFiber> byClass(Class<?> pluginClass) {
        String id = pluginIdByClass.get(pluginClass);
        return id == null ? Optional.empty() : byId(id);
    }

    /**
     * 按模块 ID 取模块句柄。
     *
     * @param moduleId 模块 ID
     * @return 模块句柄；不存在时为空
     */
    public Optional<ModuleHandle> module(String moduleId) {
        return moduleId == null ? Optional.empty() : Optional.ofNullable(modulesById.get(moduleId));
    }

    /**
     * 全部已加载的 Fiber（无序）。
     *
     * @return 不可变快照
     */
    public List<PluginFiber> getAll() {
        return List.copyOf(fibersById.values());
    }

    /**
     * 顶层 Fiber，按挂载顺序。
     *
     * <p>用于管理端稳定展示——顺序与加载顺序一致，不随重载抖动。</p>
     *
     * @return 不可变快照
     */
    public List<PluginFiber> getRoots() {
        List<PluginFiber> result = new ArrayList<>();
        synchronized (rootOrder) {
            for (String id : rootOrder) {
                PluginFiber fiber = fibersById.get(id);
                if (fiber != null) {
                    result.add(fiber);
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 某模块包含的 Fiber。
     *
     * @param moduleId 模块 ID
     * @return 该模块的插件列表；模块不存在时为空列表
     */
    public List<PluginFiber> ofModule(String moduleId) {
        ModuleHandle handle = modulesById.get(moduleId);
        if (handle == null) {
            return List.of();
        }
        List<PluginFiber> result = new ArrayList<>();
        for (String pluginId : handle.getPluginIds()) {
            PluginFiber fiber = fibersById.get(pluginId);
            if (fiber != null) {
                result.add(fiber);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 已加载的插件数量。
     *
     * @return 数量
     */
    public int getPluginCount() {
        return fibersById.size();
    }

    /**
     * 已加载的模块数量。
     *
     * @return 数量
     */
    public int getModuleCount() {
        return modulesById.size();
    }

    /**
     * 全部已加载模块，按模块 ID 排序。
     *
     * @return 不可变快照
     */
    public List<ModuleHandle> getAllModules() {
        List<ModuleHandle> result = new ArrayList<>(modulesById.values());
        result.sort(java.util.Comparator.comparing(ModuleHandle::getModuleId));
        return Collections.unmodifiableList(result);
    }

    /**
     * 按状态汇总插件数量。
     *
     * <p>用于管理端看板与启动结束后的 PENDING / FAILED 报告。</p>
     *
     * @return 状态 → 数量（全部状态都有键，无插件时为 0）
     */
    public Map<FiberState, Integer> countByState() {
        Map<FiberState, Integer> counts = new LinkedHashMap<>();
        for (FiberState state : FiberState.values()) {
            counts.put(state, 0);
        }
        for (PluginFiber fiber : fibersById.values()) {
            counts.merge(fiber.getState(), 1, Integer::sum);
        }
        return Collections.unmodifiableMap(counts);
    }

    /**
     * 处于指定状态的插件。
     *
     * @param state 目标状态
     * @return 匹配的 Fiber 列表
     */
    public List<PluginFiber> withState(FiberState state) {
        List<PluginFiber> result = new ArrayList<>();
        for (PluginFiber fiber : fibersById.values()) {
            if (fiber.getState() == state) {
                result.add(fiber);
            }
        }
        return Collections.unmodifiableList(result);
    }

    // ==================== 移除 ====================

    /**
     * 移除一个 Fiber 的全部索引（不执行卸载）。
     *
     * <p>调用方应<b>先</b>调用 {@link PluginFiber#dispose()}，本方法只负责清索引。
     * 分开的理由：卸载可能失败（Disposer 抛异常），但索引必须清掉，
     * 否则会出现「已卸载却仍在分发表里」的悬挂引用。</p>
     *
     * @param fiber 目标 Fiber
     */
    public void remove(PluginFiber fiber) {
        if (fiber == null) {
            return;
        }
        fibersById.remove(fiber.getId());
        pluginIdByClass.remove(fiber.getPlugin().getClass());
        synchronized (rootOrder) {
            rootOrder.remove(fiber.getId());
        }
    }

    /**
     * 移除某个模块的全部索引（不执行卸载）。
     *
     * @param moduleId 模块 ID
     * @return 被移除的 Fiber 列表
     */
    public List<PluginFiber> removeModule(String moduleId) {
        List<PluginFiber> removed = ofModule(moduleId);
        for (PluginFiber fiber : removed) {
            remove(fiber);
        }
        modulesById.remove(moduleId);
        return removed;
    }

    /**
     * 清空全部索引（不执行卸载）。
     *
     * <p>仅用于测试与全量重载前的兜底。</p>
     */
    public void clear() {
        fibersById.clear();
        modulesById.clear();
        pluginIdByClass.clear();
        synchronized (rootOrder) {
            rootOrder.clear();
        }
    }

    /**
     * 全部 Fiber 及其后代的 Effect 总数。
     *
     * <p>用于全局泄漏检测：全量卸载后本方法应返回 {@code 0}。</p>
     *
     * @return 总数
     */
    public int getTotalEffectCount() {
        int total = 0;
        for (PluginFiber fiber : fibersById.values()) {
            // 只统计顶层，避免子树被重复计数
            if (fiber.getParent() == null) {
                total += fiber.getTotalEffectCount();
            }
        }
        return total;
    }

    /**
     * 单行诊断描述。
     *
     * @return 形如 {@code "modules=13, plugins=23, effects=41, states={ACTIVE=21, ...}"}
     */
    public String describe() {
        return "modules=" + getModuleCount()
                + ", plugins=" + getPluginCount()
                + ", effects=" + getTotalEffectCount()
                + ", states=" + countByState();
    }
}
