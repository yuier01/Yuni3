package com.yuier.yuni.plugin.runtime;

import com.yuier.yuni.plugin.api.ScopeFilter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件作用域配置表——「每个插件在哪些群被显式开启 / 关闭」。
 *
 * <h2>为什么需要它，而不是一个 {@code Map<pluginId, ScopeFilter>}</h2>
 * <p>P5 阶段 {@code PluginRuntime} 直接持有 {@code Map<String, ScopeFilter>}。
 * 那个表示法有一个致命的表达力缺口：<b>它无法同时表达「全局关闭 + 若干群开启」
 * 与「全局开启 + 若干群关闭」</b>。</p>
 *
 * <ul>
 *   <li>{@code onlyGroups}（白名单）：非列出的群全部关闭；</li>
 *   <li>{@code excludeGroups}（黑名单）：非列出的群全部开启。</li>
 * </ul>
 *
 * <p>真实配置需要的是「一小部分群被显式设置过，其余按默认值」——
 * 也就是每个群一个<b>三态</b>（未设置 / 开启 / 关闭）。旧的
 * {@code group_plugin_ability} 表正是这个三态（{@code ability = true/false}）。</p>
 *
 * <p>若强行把三态压成单个过滤器，就会遇到「全局默认关闭、但 A 群开启、
 * 同时又要保留默认开启的 B 群」这类配置<b>无法表示</b>的问题——而
 * 「默认关闭 + 逐个群开启」恰恰是管理端最常见的用法。</p>
 *
 * <h2>采用的表示法</h2>
 * <p>每个插件持有一个<b>显式例外列表</b> {@code List<ScopeFilter>}，每项都是
 * 可反向解析的具名过滤器（{@code OnlyGroups} / {@code ExcludeGroups}）。
 * 判定单个群时按列表顺序取第一个命中的结果：</p>
 * <pre>
 *   某个例外返回 true  → 该群开启
 *   某个例外返回 false → 该群关闭
 *   没有例外命中       → 按插件元数据的 defaultEnable
 * </pre>
 *
 * <p>写入时按「需要新增的群属于哪一侧」选择把例外追加到白名单还是黑名单：
 * 新增一个<b>开启</b>的群时，若已存在黑名单则追加进黑名单；否则新建白名单。
 * 反之亦然。这样既保持三态语义完整，又能随时导出为
 * {@link ScopeFilter.PersistedScope}（默认值 + 例外群集合）。</p>
 *
 * <h2>为什么不用一把锁保护整张表</h2>
 * <p>外层 {@link ConcurrentHashMap} 提供插件级并发；每个插件的例外列表用
 * {@code synchronized} 在<b>该插件自己的 Monitor</b> 上保护。写入是低频操作
 * （用户敲一条命令），读取远多于写入，因此插件级隔离足够且不会有全局争用。</p>
 *
 * @see ScopeFilter
 */
public final class PluginScopes {

    /** 插件标识 → 该插件的显式例外列表（按加入顺序）。 */
    private final Map<String, List<ScopeFilter>> exceptions = new ConcurrentHashMap<>();

    /**
     * 插件标识 → 用户显式设置的<b>全局默认启用值</b>。
     *
     * <h2>为什么需要它</h2>
     * <p>没有这一层时，「全局开关」与「默认值」是同一个东西，于是
     * 「把插件从默认关闭改为全局开启」这个操作无法表达——它是
     * {@code PersistedScope} 里的 {@code enabledByDefault} 位，
     * 而那个位在没有例外群时<b>没有任何载体</b>（例外列表是空的）。</p>
     *
     * <p>具体后果：一个 {@code defaultEnable = false} 的插件，用户想全局开启，
     * 若只存例外列表则必须写成「白名单 = 全部群」，而群是无限集、无法枚举。</p>
     *
     * <h2>优先级</h2>
     * <p>用户显式全局默认值 &gt; 插件注册时的基线默认值 &gt; 元数据
     * {@code defaultEnable}。因此本表只存「用户显式设置过」的项，
     * 未设置时不出现（{@link #explicitGlobalDefault} 返回空）。</p>
     */
    private final Map<String, Boolean> explicitGlobalDefaults = new ConcurrentHashMap<>();

    /**
     * 记录一个插件的完整例外列表（用于启动时从数据库装载）。
     *
     * <p>会<b>覆盖</b>已有配置。装载发生在插件加载之前，因此不会有并发问题；
     * 但仍按插件加锁，以免与运行期的写入交错。</p>
     *
     * @param pluginId 插件标识
     * @param filters  例外列表；{@code null} 或空表示清除全部例外
     */
    public void putAll(String pluginId, List<ScopeFilter> filters) {
        if (pluginId == null) {
            return;
        }
        if (filters == null || filters.isEmpty()) {
            exceptions.remove(pluginId);
            return;
        }
        List<ScopeFilter> copy = new ArrayList<>(filters);
        exceptions.put(pluginId, copy);
    }

    /**
     * 取一个插件的例外列表快照。
     *
     * @param pluginId 插件标识
     * @return 不可变副本；无例外时为空列表
     */
    public List<ScopeFilter> all(String pluginId) {
        List<ScopeFilter> filters = exceptions.get(pluginId);
        if (filters == null) {
            return List.of();
        }
        synchronized (filters) {
            return List.copyOf(filters);
        }
    }

    /**
     * 清除一个插件的全部例外。
     *
     * @param pluginId 插件标识
     * @return 被清除的例外列表；本来就没有时为空
     */
    public Optional<List<ScopeFilter>> clear(String pluginId) {
        List<ScopeFilter> removed = exceptions.remove(pluginId);
        return removed == null ? Optional.empty() : Optional.of(List.copyOf(removed));
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

    /**
     * 从表中移除一个插件（卸载时调用）。
     *
     * @param pluginId 插件标识
     */
    public void remove(String pluginId) {
        exceptions.remove(pluginId);
        // 全局默认值不在这里清除：卸载可能是重载的第一步，
        // 而重载必须保留用户的配置。需要清除时走 clearAll。
    }

    /**
     * 彻底清除一个插件的全部配置（例外 + 全局默认值）。
     *
     * <p>仅用于「重置为插件默认」这类显式操作。</p>
     *
     * @param pluginId 插件标识
     */
    public void clearAll(String pluginId) {
        exceptions.remove(pluginId);
        explicitGlobalDefaults.remove(pluginId);
    }

    /**
     * 判定某个群是否被显式设置过，以及设置成什么。
     *
     * @param pluginId 插件标识
     * @param groupId  群号
     * @return 显式设置的值；未被任何例外命中时为空
     */
    public Optional<Boolean> explicitValue(String pluginId, long groupId) {
        for (ScopeFilter filter : all(pluginId)) {
            // 例外列表里的每一项都只针对「自己关心的那群」。
            // 用 matches(groupId) 判定即可：白名单对不在其中的群返回 false，
            // 因此必须用一个只问「这个群在不在我的集合里」的方式区分。
            Boolean hit = hitOf(filter, groupId);
            if (hit != null) {
                return Optional.of(hit);
            }
        }
        return Optional.empty();
    }

    /**
     * 判定一个过滤器是否「命中」某个群，命中时给出它的结论。
     *
     * <p>关键区别：{@code matches} 回答的是「这个群是否生效」，
     * 而这里要回答的是「这个过滤器<b>是否关心</b>这个群」。
     * 白名单 {@code onlyGroups({A})} 对 B 群返回 {@code false}，
     * 但它并不关心 B——B 应当继续问下一个例外或回落到默认值。</p>
     *
     * @param filter  过滤器
     * @param groupId 群号
     * @return 命中且结论为开启返回 {@code true}；命中且结论为关闭返回 {@code false}；
     *         未命中返回 {@code null}
     */
    private static Boolean hitOf(ScopeFilter filter, long groupId) {
        Optional<Set<Long>> whitelist = filter.explicitGroupWhitelist();
        if (whitelist.isPresent()) {
            return whitelist.get().contains(groupId) ? Boolean.TRUE : null;
        }
        Optional<Set<Long>> blacklist = filter.explicitGroupBlacklist();
        if (blacklist.isPresent()) {
            return blacklist.get().contains(groupId) ? Boolean.FALSE : null;
        }
        // 无法反向解析的过滤器（lambda 组合）：只能问它是否生效，
        // 并把它当作「关心所有群」。这种过滤器不会由本类产生，
        // 但插件可以通过 ctx.plugin(child, filter) 传入
        return filter.matches(groupId);
    }

    /**
     * 设置某个群的显式开关。
     *
     * @param pluginId             插件标识
     * @param groupId              群号
     * @param enabled              该群是否生效
     * @param pluginDefaultEnabled 该插件的默认启用值（用于决定合并到哪一侧）
     */
    public void set(String pluginId, long groupId, boolean enabled, boolean pluginDefaultEnabled) {
        List<ScopeFilter> filters = exceptions.computeIfAbsent(pluginId, k -> new ArrayList<>());
        synchronized (filters) {
            // 先移除该群在别处的旧设置，避免同一群出现两条互相矛盾的例外
            removeGroupLocked(filters, groupId);

            if (enabled == pluginDefaultEnabled) {
                /*
                 * 目标值与默认值一致 → 该群不需要例外。
                 * 这是「用户把开关拨回默认」的情形，必须真的删掉记录，
                 * 否则配置会随时间无限增长且无法回到「无例外」状态。
                 */
                if (filters.isEmpty()) {
                    exceptions.remove(pluginId);
                }
                return;
            }

            // 需要一个与默认值相反的例外：优先并入已存在的同类过滤器
            if (enabled) {
                // 该群要开启，而默认是关闭 → 并入白名单（或新建）
                if (!mergeInto(filters, groupId, true)) {
                    filters.add(ScopeFilter.onlyGroups(Set.of(groupId)));
                }
            } else {
                // 该群要关闭，而默认是开启 → 并入黑名单（或新建）
                if (!mergeInto(filters, groupId, false)) {
                    filters.add(ScopeFilter.excludeGroups(Set.of(groupId)));
                }
            }
        }
    }

    /**
     * 尝试把一个群并入已有的同类过滤器。
     *
     * <p>由于 {@link ScopeFilter} 的具名实现是不可变的 record，合并意味着
     * 「用新集合替换列表中的那一项」。</p>
     *
     * @param filters  例外列表（调用方已加锁）
     * @param groupId  群号
     * @param whitelist {@code true} 并入白名单，{@code false} 并入黑名单
     * @return 成功并入返回 {@code true}；没有同类过滤器可并入时返回 {@code false}
     */
    private static boolean mergeInto(List<ScopeFilter> filters, long groupId, boolean whitelist) {
        for (int i = 0; i < filters.size(); i++) {
            ScopeFilter filter = filters.get(i);
            Optional<Set<Long>> existing = whitelist
                    ? filter.explicitGroupWhitelist()
                    : filter.explicitGroupBlacklist();
            if (existing.isEmpty()) {
                continue;
            }
            Set<Long> merged = new java.util.LinkedHashSet<>(existing.get());
            merged.add(groupId);
            filters.set(i, whitelist
                    ? ScopeFilter.onlyGroups(merged)
                    : ScopeFilter.excludeGroups(merged));
            return true;
        }
        return false;
    }

    /**
     * 从例外列表中移除某个群的全部设置。
     *
     * @param filters 例外列表（调用方已加锁）
     * @param groupId 群号
     */
    private static void removeGroupLocked(List<ScopeFilter> filters, long groupId) {
        for (int i = filters.size() - 1; i >= 0; i--) {
            ScopeFilter filter = filters.get(i);
            Optional<Set<Long>> whitelist = filter.explicitGroupWhitelist();
            Optional<Set<Long>> blacklist = filter.explicitGroupBlacklist();
            Set<Long> groups = whitelist.orElseGet(() -> blacklist.orElse(null));
            if (groups == null || !groups.contains(groupId)) {
                continue;
            }
            Set<Long> remaining = new java.util.LinkedHashSet<>(groups);
            remaining.remove(groupId);
            if (remaining.isEmpty()) {
                filters.remove(i);
            } else {
                filters.set(i, whitelist.isPresent()
                        ? ScopeFilter.onlyGroups(remaining)
                        : ScopeFilter.excludeGroups(remaining));
            }
        }
    }

    /**
     * 把例外列表合成为一个过滤器，供事件分发使用。
     *
     * <p>分发路径只需要回答「这个群是否生效」，因此把多个例外折叠成一个
     * 谓词即可。{@code defaultEnabled} 作为未命中任何例外时的返回值。</p>
     *
     * @param pluginId       插件标识
     * @param defaultEnabled 插件元数据的默认启用值
     * @return 合成过滤器；无例外时返回表示「按默认值」的常量过滤器
     */
    public ScopeFilter composite(String pluginId, boolean defaultEnabled) {
        List<ScopeFilter> filters = all(pluginId);
        if (filters.isEmpty()) {
            return defaultEnabled ? ScopeFilter.ALL : ScopeFilter.NONE;
        }
        /*
         * 逐项判定，取第一个「命中」的结论。
         *
         * 这里不能用 filter.matches 的「或」组合：白名单对不在其中的群返回
         * false，若直接 or 起来，会把「未命中」误当成「显式关闭」。
         * 因此必须用 hitOf 区分「命中且关闭」与「未命中」。
         */
        return groupId -> {
            if (groupId == null) {
                /*
                 * 非群事件（私聊、元事件）：三态模型里没有它的位置。
                 * 沿用旧系统的行为——全局默认开启则生效，否则不生效。
                 * （旧 PluginEnableProcessor 对私聊也走 defaultEnable 兜底。）
                 */
                return defaultEnabled;
            }
            for (ScopeFilter filter : filters) {
                Boolean hit = hitOf(filter, groupId);
                if (hit != null) {
                    return hit;
                }
            }
            return defaultEnabled;
        };
    }

    /**
     * 导出为可持久化的形态。
     *
     * <p>只导出「默认值 + 与默认值相反的例外群集合」。注意这<b>会丢失</b>
     * 「与默认值相同的显式设置」——但那种设置本来就不携带信息
     * （{@link #set} 已经把与默认值一致的项删掉了），因此无损。</p>
     *
     * @param pluginId       插件标识
     * @param defaultEnabled 插件元数据的默认启用值
     * @return 持久化描述
     */
    public ScopeFilter.PersistedScope toPersisted(String pluginId, boolean defaultEnabled) {
        Set<Long> opposite = new java.util.LinkedHashSet<>();
        for (ScopeFilter filter : all(pluginId)) {
            filter.explicitGroupWhitelist().ifPresent(opposite::addAll);
            filter.explicitGroupBlacklist().ifPresent(opposite::addAll);
        }
        return new ScopeFilter.PersistedScope(defaultEnabled, opposite);
    }

    /**
     * 从持久化记录构造例外列表。
     *
     * <p>用于启动时把数据库里的三态还原成运行时表示。
     * 每一项 {@code (pluginId, groupId, ability)} 都转成一个单群例外。</p>
     *
     * @param records 记录列表：群号 → 该群是否生效
     * @param defaultEnabled 该插件的默认启用值（与默认一致的项会被忽略）
     * @return 例外列表
     */
    public static List<ScopeFilter> fromRecords(Map<Long, Boolean> records, boolean defaultEnabled) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        Set<Long> enabled = new java.util.LinkedHashSet<>();
        Set<Long> disabled = new java.util.LinkedHashSet<>();
        records.forEach((groupId, value) -> {
            if (groupId == null || value == null || value == defaultEnabled) {
                // 与默认值一致的记录不产生例外
                return;
            }
            if (value) {
                enabled.add(groupId);
            } else {
                disabled.add(groupId);
            }
        });
        List<ScopeFilter> filters = new ArrayList<>(2);
        if (!enabled.isEmpty()) {
            filters.add(ScopeFilter.onlyGroups(enabled));
        }
        if (!disabled.isEmpty()) {
            filters.add(ScopeFilter.excludeGroups(disabled));
        }
        return filters;
    }

    /**
     * 表中记录的插件数量（诊断用）。
     *
     * @return 有显式例外的插件数
     */
    public int size() {
        return exceptions.size();
    }

    /**
     * 全部有显式例外的插件标识（诊断用）。
     *
     * @return 插件标识集合
     */
    public Set<String> pluginIds() {
        return Set.copyOf(exceptions.keySet());
    }

    /**
     * 单行诊断描述。
     *
     * @return 形如 {@code "scopes=3"} 的描述
     */
    public String describe() {
        Map<String, Integer> counts = new HashMap<>();
        exceptions.forEach((id, filters) -> counts.put(id, all(id).size()));
        return "scopes=" + exceptions.size() + counts;
    }
}
