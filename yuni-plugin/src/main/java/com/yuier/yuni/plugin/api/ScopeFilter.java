package com.yuier.yuni.plugin.api;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 作用域过滤器——决定「一个注册项在哪些群里生效」。
 *
 * <h2>模型：默认值 + 例外群</h2>
 * <p>整个作用域只有两个维度：</p>
 * <ul>
 *   <li><b>默认值</b>：{@link #isEnabledByDefault()}，未列出的群（含私聊等非群事件）
 *       是否生效；</li>
 *   <li><b>例外群</b>：{@link #getExceptionGroups()}，取值与默认值<b>相反</b>的群。</li>
 * </ul>
 * <p>因此「白名单」与「黑名单」不是两套机制，而是同一模型的两个取值：</p>
 * <ul>
 *   <li>{@code onlyGroups({100, 200})} = 默认关闭 + 例外群 {100, 200}；</li>
 *   <li>{@code excludeGroups({100, 200})} = 默认开启 + 例外群 {100, 200}。</li>
 * </ul>
 * <p>持久化、管理端展示、数据库表（{@code group_plugin_ability}）都直接使用
 * 这两个字段，不再需要单独的「持久化类型」或反向解析。</p>
 *
 * <h2>为什么不用「每群一份插件实例」</h2>
 * <p>初版设计把群建模为 Context 子节点（{@code GroupScope(g).plugin(p)}），
 * 每个启用了该插件的群挂一份实例。经实证否决：本仓库 23 个插件里只有 1 个
 * 真正持有群内实例状态，其余状态全部外置在 Redis / SQLite 并按
 * {@code groupId}/{@code userId} 分键；每群每插件的内存开销约 180 B，
 * 1000 群 × 20 插件约 3.6 MB，而这份内存买到的隔离等于零。</p>
 *
 * <p>因此插件<b>全局只有一个实例</b>，群粒度启停通过注册项上的本过滤器表达，
 * 内存开销降为 O(1)。群内状态由 {@code ctx.state(Class)} 按作用域分键提供。</p>
 *
 * <h2>与现行三层判定的映射</h2>
 * <table border="1">
 *   <caption>三层判定映射</caption>
 *   <tr><th>现行层级</th><th>本过滤器的对应</th></tr>
 *   <tr><td>群例外（{@code groupId@pluginId} 有记录）</td><td>{@link #getExceptionGroups()} 命中</td></tr>
 *   <tr><td>全局例外（{@code groupId = null} 的记录）</td><td>{@link #isEnabledByDefault()}</td></tr>
 *   <tr><td>兜底 {@code defaultEnable}</td><td>{@link #isEnabledByDefault()}</td></tr>
 * </table>
 *
 * <h2>一个附带的行为修正</h2>
 * <p>现行系统的启停判定只被消息/通知两条路径调用，
 * {@code matchRequestEvent} / {@code matchMetaEvent} / {@code matchMessageSentEvent}
 * 三条路径<b>完全不检查启停</b>（审计报告已记录该不一致）。本过滤器由分发器统一
 * 施加，任何事件类型都无法绕过——启停语义因此变得一致。</p>
 *
 * @see YuniContext#plugin
 */
@Getter
@ToString
@EqualsAndHashCode
public final class ScopeFilter {

    /** 全部作用域生效——不限群，含私聊与非群事件。 */
    public static final ScopeFilter ALL = new ScopeFilter(true, Set.of());

    /** 全部作用域都不生效。 */
    public static final ScopeFilter NONE = new ScopeFilter(false, Set.of());

    /** 默认（未列出的群）是否生效。 */
    private final boolean enabledByDefault;

    /** 例外群集合：取值与 {@link #enabledByDefault} 相反。 */
    private final Set<Long> exceptionGroups;

    /**
     * 构造过滤器。
     *
     * @param enabledByDefault 默认是否生效
     * @param exceptionGroups  例外群；{@code null} 视为空集，会被防御性拷贝
     */
    public ScopeFilter(boolean enabledByDefault, Collection<Long> exceptionGroups) {
        this.enabledByDefault = enabledByDefault;
        this.exceptionGroups = exceptionGroups == null
                ? Set.of()
                : Collections.unmodifiableSet(new HashSet<>(exceptionGroups));
    }

    /**
     * 判定本注册项是否在指定群生效。
     *
     * <p>非群事件（{@code groupId == null}）按默认值处理——这既符合
     * {@link #ALL}（true）与 {@link #NONE}（false）的直觉，也让
     * {@code onlyGroups} 的「非群事件不生效」与 {@code excludeGroups} 的
     * 「非群事件照常生效」成为同一公式的两个特例。</p>
     *
     * @param groupId 当前事件的群号；{@code null} 表示非群事件
     * @return 生效返回 {@code true}
     */
    public boolean matches(Long groupId) {
        if (groupId == null) {
            return enabledByDefault;
        }
        return exceptionGroups.contains(groupId) != enabledByDefault;
    }

    /**
     * 只在指定群生效（默认关闭 + 例外群）。
     *
     * @param groupIds 生效的群号
     * @return 过滤器
     */
    public static ScopeFilter onlyGroups(long... groupIds) {
        return onlyGroups(Arrays.stream(groupIds).boxed().collect(Collectors.toSet()));
    }

    /**
     * 只在指定群生效（集合重载）。
     *
     * @param groupIds 生效的群号集合；会被防御性拷贝
     * @return 过滤器
     */
    public static ScopeFilter onlyGroups(Collection<Long> groupIds) {
        return new ScopeFilter(false, groupIds);
    }

    /**
     * 除指定群外都生效（默认开启 + 例外群）。
     *
     * @param groupIds 不生效的群号
     * @return 过滤器
     */
    public static ScopeFilter excludeGroups(long... groupIds) {
        return excludeGroups(Arrays.stream(groupIds).boxed().collect(Collectors.toSet()));
    }

    /**
     * 除指定群外都生效（集合重载）。
     *
     * @param groupIds 不生效的群号集合；会被防御性拷贝
     * @return 过滤器
     */
    public static ScopeFilter excludeGroups(Collection<Long> groupIds) {
        return new ScopeFilter(true, groupIds);
    }
}
