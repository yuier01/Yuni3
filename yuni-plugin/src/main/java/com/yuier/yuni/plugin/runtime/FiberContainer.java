package com.yuier.yuni.plugin.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fiber 容器——「哪个插件正跑在哪里」的索引。
 *
 * <h2>取代了什么</h2>
 * <p>现行 {@code PluginContainer} 维护了 8 条类型 ID 列表 + 3 张 Map，
 * 且每张 Map 都要在热重载时被手工增删（{@code PluginReloadProcessor} 与
 * {@code PluginService} 各实现了一遍同样的维护逻辑，是 bug 高发区）。</p>
 *
 * <p>本容器维护按 ID / 类名 / 加载单元的三张 Map。<b>没有 index 字段，没有类型分桶，
 * 没有偏移修正</b>——插件之间是平级关系。</p>
 *
 * <h2>加载单元（Artifact）</h2>
 * <p>一个 {@link PluginArtifact} 对应一个 jar，可包含多个插件。它只用于
 * 定位 jar 与 ClassLoader（热重载边界），<b>不承担分组展示职责</b>。</p>
 *
 * <h2>线程安全</h2>
 * <p>加载 / 卸载 / 查询可能并发。所有索引用 {@link ConcurrentHashMap}。</p>
 *
 * @see PluginFiber
 * @see PluginRuntime
 */
public final class FiberContainer {

    /** 插件标识 → Fiber。 */
    private final Map<String, PluginFiber> fibersById = new ConcurrentHashMap<>();

    /** 加载单元标识 → 加载单元。 */
    private final Map<String, PluginArtifact> artifactsById = new ConcurrentHashMap<>();

    /** 插件实现类 → 插件标识；供「按类反查插件」使用（现行 {@code PluginUtils} 的用法）。 */
    private final Map<Class<?>, String> pluginIdByClass = new ConcurrentHashMap<>();

    // ==================== 注册 ====================

    /**
     * 登记一个已加载的插件 Fiber。
     *
     * @param fiber Fiber
     */
    public void add(PluginFiber fiber) {
        if (fiber == null) {
            return;
        }
        fibersById.put(fiber.getId(), fiber);
        pluginIdByClass.put(fiber.getPlugin().getClass(), fiber.getId());
    }

    /**
     * 登记一个加载单元。
     *
     * @param artifact 加载单元
     */
    public void addArtifact(PluginArtifact artifact) {
        java.util.Objects.requireNonNull(artifact, "artifact");
        if (artifact.getId() != null) {
            artifactsById.put(artifact.getId(), artifact);
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
     * 按加载单元标识取加载单元。
     *
     * @param artifactId 加载单元标识
     * @return 加载单元；不存在时为空
     */
    public Optional<PluginArtifact> artifact(String artifactId) {
        return artifactId == null
                ? Optional.empty()
                : Optional.ofNullable(artifactsById.get(artifactId));
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
     * 某加载单元包含的 Fiber。
     *
     * @param artifactId 加载单元标识
     * @return 该单元的插件列表；单元不存在时为空列表
     */
    public List<PluginFiber> pluginsOfArtifact(String artifactId) {
        if (artifactId == null) {
            return List.of();
        }
        List<PluginFiber> result = new ArrayList<>();
        for (PluginFiber fiber : fibersById.values()) {
            if (artifactId.equals(fiber.getArtifactId())) {
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
     * 已登记的加载单元数量。
     *
     * @return 数量
     */
    public int getArtifactCount() {
        return artifactsById.size();
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
    }

    /**
     * 移除加载单元索引（不执行插件卸载）。
     *
     * @param artifactId 加载单元标识
     */
    public void removeArtifact(String artifactId) {
        if (artifactId != null) {
            artifactsById.remove(artifactId);
        }
    }

    /**
     * 清空全部索引（不执行卸载）。
     *
     * <p>仅用于测试与全量重载前的兜底。</p>
     */
    public void clear() {
        fibersById.clear();
        artifactsById.clear();
        pluginIdByClass.clear();
    }

    /**
     * 全部 Fiber 的 PluginEffect 总数。
     *
     * <p>用于全局泄漏检测：全量卸载后本方法应返回 {@code 0}。</p>
     *
     * @return 总数
     */
    public int getTotalEffectCount() {
        int total = 0;
        for (PluginFiber fiber : fibersById.values()) {
            total += fiber.getEffectCount();
        }
        return total;
    }

    /**
     * 单行诊断描述。
     *
     * @return 形如 {@code "artifacts=13, plugins=23, effects=41, states={ACTIVE=21, ...}"}
     */
    public String describe() {
        return "artifacts=" + getArtifactCount()
                + ", plugins=" + getPluginCount()
                + ", effects=" + getTotalEffectCount()
                + ", states=" + countByState();
    }
}
