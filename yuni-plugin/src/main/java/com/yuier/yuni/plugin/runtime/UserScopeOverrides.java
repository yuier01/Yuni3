package com.yuier.yuni.plugin.runtime;

import com.yuier.yuni.plugin.api.ScopeFilter;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 用户作用域覆盖表——只记录「用户显式改过」的部分，不含插件作者的基线。
 *
 * <p>它不是最终生效视图：最终值由 {@code PluginRuntime} 按
 * 「用户覆盖 &gt; 作者基线 &gt; 元数据 defaultEnable」现算。本类只负责
 * 用户层的三态（未设置 / 显式开启 / 显式关闭）与持久化形态的导出。</p>
 *
 * <h2>表示法：每个群一个三态</h2>
 * <p>用户对某个群的操作只有三种结果：未设置、显式开启、显式关闭。因此本表
 * 直接存「群号 → 显式值」，未出现的群即「未设置」：</p>
 * <pre>
 *   explicitGroups: pluginId → (groupId → true/false)
 * </pre>
 * <p>需要过滤器时，用「有效默认值 + 与默认值相反的群」合成
 * {@link ScopeFilter}（{@link #toFilter}）。白名单 / 黑名单不再是两套机制，
 * 只是同一个值对象的两种取值。</p>
 *
 * <h2>为什么还要单独存「显式全局默认值」</h2>
 * <p>「用户把全局开关从插件默认值改成相反值」这一操作，在没有群例外时
 * 无法由群表承载（群表是空的）。因此单独记一个可选布尔：
 * 用户从未改过时不出现，读取方回落到插件元数据。</p>
 *
 * <h2>绝对值与默认值</h2>
 * <p>群表里存的是<b>绝对</b>的布尔值（该群到底生效与否），不是「与默认值相反」。
 * 这样即使插件的 {@code defaultEnable} 在版本升级后改变，用户对某群的显式选择
 * 也不会被翻转——合成过滤器时才把绝对值和当前默认值比较。</p>
 *
 * <h2>线程安全</h2>
 * <p>外层 {@link ConcurrentHashMap} 提供插件级并发；单个插件的群表用
 * {@code synchronized} 在<b>该插件自己的 Map 实例</b>上保护。写入是低频操作。</p>
 *
 * @see ScopeFilter
 */
public final class UserScopeOverrides {

    /** 插件标识 → 该插件被显式设置过的群（群号 → 是否生效）。 */
    private final Map<String, Map<Long, Boolean>> explicitGroups = new ConcurrentHashMap<>();

    /**
     * 插件标识 → 用户显式设置的<b>全局默认启用值</b>。
     *
     * <p>只存「用户显式设置过」的项；未设置时不出现
     * （{@link #explicitGlobalDefault} 返回空）。优先级：
     * 用户显式值 &gt; 插件基线 &gt; 元数据 {@code defaultEnable}。</p>
     */
    private final Map<String, Boolean> explicitGlobalDefaults = new ConcurrentHashMap<>();

    /**
     * 覆盖一个插件的全部群级配置（用于启动时从数据库装载）。
     *
     * @param pluginId 插件标识
     * @param values   群号 → 是否生效；{@code null} 或空表示清除全部
     */
    public void putAll(String pluginId, Map<Long, Boolean> values) {
        if (pluginId == null) {
            return;
        }
        if (values == null || values.isEmpty()) {
            explicitGroups.remove(pluginId);
            return;
        }
        explicitGroups.put(pluginId, Map.copyOf(values));
    }

    /**
     * 取一个插件的群级配置快照。
     *
     * @param pluginId 插件标识
     * @return 群号 → 是否生效；无配置时为空 Map
     */
    public Map<Long, Boolean> all(String pluginId) {
        Map<Long, Boolean> values = explicitGroups.get(pluginId);
        return values == null ? Map.of() : Map.copyOf(values);
    }

    /**
     * 清除一个插件的全部群级配置。
     *
     * @param pluginId 插件标识
     * @return 被清除的配置；本来就没有时为空
     */
    public Optional<Map<Long, Boolean>> clear(String pluginId) {
        Map<Long, Boolean> removed = explicitGroups.remove(pluginId);
        return removed == null ? Optional.empty() : Optional.of(Map.copyOf(removed));
    }

    // ==================== 全局默认值 ====================

    /**
     * 读取用户显式设置的全局默认启用值。
     *
     * @param pluginId 插件标识
     * @return 显式设置的值；用户从未设置过时为空
     */
    public Optional<Boolean> explicitGlobalDefault(String pluginId) {
        return Optional.ofNullable(explicitGlobalDefaults.get(pluginId));
    }

    /**
     * 设置全局默认启用值。
     *
     * @param pluginId 插件标识
     * @param enabled  全局是否生效
     */
    public void setGlobalDefault(String pluginId, boolean enabled) {
        if (pluginId == null) {
            return;
        }
        explicitGlobalDefaults.put(pluginId, enabled);
    }

    /**
     * 清除用户设置的全局默认值（回到插件自身的默认）。
     *
     * @param pluginId 插件标识
     * @return 原本是否有显式设置
     */
    public boolean clearGlobalDefault(String pluginId) {
        return pluginId != null && explicitGlobalDefaults.remove(pluginId) != null;
    }

    // ==================== 判定与写入 ====================

    /**
     * 判定某个群是否被显式设置过，以及设置成什么。
     *
     * @param pluginId 插件标识
     * @param groupId  群号
     * @return 显式设置的值；未设置时为空
     */
    public Optional<Boolean> explicitValue(String pluginId, long groupId) {
        Map<Long, Boolean> values = explicitGroups.get(pluginId);
        return values == null ? Optional.empty() : Optional.ofNullable(values.get(groupId));
    }

    /**
     * 设置某个群的显式开关。
     *
     * <p>与插件默认值一致的写入会被<b>删除</b>，使操作可逆——「关闭再开启」
     * 会回到「未设置」，而不是留下一条与默认值相同的无意义记录。</p>
     *
     * @param pluginId             插件标识
     * @param groupId              群号
     * @param enabled              该群是否生效
     * @param pluginDefaultEnabled 该插件的默认启用值
     */
    public void set(String pluginId, long groupId, boolean enabled, boolean pluginDefaultEnabled) {
        if (pluginId == null) {
            return;
        }
        Map<Long, Boolean> values = explicitGroups.computeIfAbsent(
                pluginId, k -> new ConcurrentHashMap<>());
        synchronized (values) {
            if (enabled == pluginDefaultEnabled) {
                values.remove(groupId);
                if (values.isEmpty()) {
                    explicitGroups.remove(pluginId, values);
                }
            } else {
                values.put(groupId, enabled);
            }
        }
    }

    // ==================== 合成与导出 ====================

    /**
     * 合成过滤器：有效默认值 + 与它相反的例外群。
     *
     * @param pluginId       插件标识
     * @param defaultEnabled 有效默认启用值
     * @return 过滤器；无例外时返回 {@link ScopeFilter#ALL} 或 {@link ScopeFilter#NONE}
     */
    public ScopeFilter toFilter(String pluginId, boolean defaultEnabled) {
        Set<Long> opposite = oppositeGroups(pluginId, defaultEnabled);
        if (opposite.isEmpty()) {
            return defaultEnabled ? ScopeFilter.ALL : ScopeFilter.NONE;
        }
        return new ScopeFilter(defaultEnabled, opposite);
    }

    /**
     * 取「与默认值相反」的例外群集合——持久化与管理端展示的形态。
     *
     * @param pluginId       插件标识
     * @param defaultEnabled 有效默认启用值
     * @return 例外群集合；无例外时为空集
     */
    public Set<Long> oppositeGroups(String pluginId, boolean defaultEnabled) {
        Map<Long, Boolean> values = explicitGroups.get(pluginId);
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<Long> opposite = new LinkedHashSet<>();
        values.forEach((groupId, enabled) -> {
            if (groupId != null && enabled != null && enabled != defaultEnabled) {
                opposite.add(groupId);
            }
        });
        return opposite;
    }

    /**
     * 显式开启的群（管理端展示用）。
     *
     * <p>注意语义：只有「默认关闭、被用户单独打开」的群才会出现在这里；
     * 默认开启时不存在需要枚举的「显式开启群」（其余群是无限集）。</p>
     *
     * @param pluginId 插件标识
     * @return 显式开启的群号；无则为空集
     */
    public Set<Long> explicitlyEnabledGroups(String pluginId) {
        Map<Long, Boolean> values = explicitGroups.get(pluginId);
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        return values.entrySet().stream()
                .filter(entry -> Boolean.TRUE.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    // ==================== 生命周期 ====================

    /**
     * 从表中移除一个插件的群级配置（卸载时调用）。
     *
     * <p>全局默认值不在这里清除：卸载可能是重载的第一步，而重载必须保留
     * 用户的配置。需要清除时走 {@link #clearAll}。</p>
     *
     * @param pluginId 插件标识
     */
    public void remove(String pluginId) {
        explicitGroups.remove(pluginId);
    }

    /**
     * 彻底清除一个插件的全部配置（群级 + 全局默认值）。
     *
     * <p>仅用于「重置为插件默认」这类显式操作。</p>
     *
     * @param pluginId 插件标识
     */
    public void clearAll(String pluginId) {
        explicitGroups.remove(pluginId);
        explicitGlobalDefaults.remove(pluginId);
    }

    /**
     * 有群级配置的插件数量（诊断用）。
     *
     * @return 插件数
     */
    public int size() {
        return explicitGroups.size();
    }

    /**
     * 全部有群级配置的插件标识（诊断用）。
     *
     * @return 插件标识集合
     */
    public Set<String> getPluginIds() {
        return Set.copyOf(explicitGroups.keySet());
    }

    /**
     * 单行诊断描述。
     *
     * @return 形如 {@code "scopes={plugin=3}"} 的描述
     */
    public String describe() {
        Map<String, Integer> counts = explicitGroups.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().size()));
        return "scopes=" + counts;
    }
}
