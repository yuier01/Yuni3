package com.yuier.yuni.plugin.api;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 作用域过滤器——决定「一个注册项在哪些群里生效」。
 *
 * <h2>为什么不用「每群一份插件实例」</h2>
 * <p>初版设计把群建模为 Context 子节点（{@code GroupScope(g).plugin(p)}），
 * 每个启用了该插件的群挂一份实例。经实证否决：</p>
 * <ul>
 *   <li>本仓库 23 个插件里<b>只有 1 个</b>（{@code PokeNotice}）真正持有群内实例状态，
 *       其余 20 个零实例字段，状态全部外置在 Redis / SQLite 并按 groupId/userId 分键；</li>
 *   <li>每群每插件的内存开销约 180 B（JVM 实测：{@code PluginFiber} ≈ 75 B、
 *       {@code EffectRegistry} ≈ 72 B、{@code ScopedContext} ≈ 32 B）；</li>
 *   <li>于是 1000 群 × 20 插件 = 约 3.6 MB，<b>而这份内存买到的隔离在当前代码库里
 *       等于零</b>——没有实例状态需要隔离。</li>
 * </ul>
 * <p>因此插件<b>全局只有一个实例</b>，群粒度启停通过注册项上的本过滤器表达，
 * 内存开销降为 O(1)。群内状态由 {@code ctx.state(Class)} 按作用域分键提供。</p>
 *
 * <h2>与现行三层判定的映射</h2>
 * <p>现行 {@code PluginEnableProcessor.isPluginEnabled} 的三层判定
 * （群例外 → 全局例外 → 默认值）映射为：</p>
 * <table border="1">
 *   <caption>三层判定映射</caption>
 *   <tr><th>现行层级</th><th>本过滤器的对应</th></tr>
 *   <tr><td>群例外（{@code groupId@pluginId} 有记录）</td><td>{@link #onlyGroups} / {@link #excludeGroups} 显式命中</td></tr>
 *   <tr><td>全局例外（{@code groupId = null} 的记录）</td><td>{@link #ALL} 或 {@link #excludeGroups}</td></tr>
 *   <tr><td>兜底 {@code defaultEnable}</td><td>{@link #matches} 的默认返回值</td></tr>
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
@FunctionalInterface
public interface ScopeFilter {

    /**
     * 判定本注册项是否在指定群生效。
     *
     * @param groupId 当前事件的群号；{@code null} 表示非群事件（私聊、元事件等）
     * @return 生效返回 {@code true}
     */
    boolean matches(Long groupId);

    // ==================== 预置实现 ====================

    /**
     * 全部作用域生效——不限群，含私聊与非群事件。
     *
     * <p>默认值。绝大多数插件（命令、定时、服务提供者）都应该用它。</p>
     */
    ScopeFilter ALL = groupId -> true;

    /**
     * 全部作用域都不生效。
     *
     * <p>用途：临时停用一个注册项而不撤销它（保留其位置与顺序），
     * 便于管理端「暂停」操作。</p>
     */
    ScopeFilter NONE = groupId -> false;

    /**
     * 只在指定群生效。
     *
     * <p>语义：白名单。<b>非群事件（{@code groupId == null}）不生效</b>——
     * 因为「只在某几个群生效」暗含「这是一个群内功能」。</p>
     *
     * @param groupIds 生效的群号
     * @return 白名单过滤器
     */
    static ScopeFilter onlyGroups(long... groupIds) {
        return onlyGroups(Arrays.stream(groupIds).boxed().collect(Collectors.toSet()));
    }

    /**
     * 只在指定群生效（集合重载）。
     *
     * @param groupIds 生效的群号集合；会被防御性拷贝
     * @return 白名单过滤器
     */
    static ScopeFilter onlyGroups(Collection<Long> groupIds) {
        return new OnlyGroups(new HashSet<>(groupIds));
    }

    /**
     * 除指定群外都生效。
     *
     * <p>语义：黑名单。对应「全局启用 + 个别群关闭」。</p>
     *
     * @param groupIds 不生效的群号
     * @return 黑名单过滤器
     */
    static ScopeFilter excludeGroups(long... groupIds) {
        return excludeGroups(Arrays.stream(groupIds).boxed().collect(Collectors.toSet()));
    }

    /**
     * 除指定群外都生效（集合重载）。
     *
     * @param groupIds 不生效的群号集合；会被防御性拷贝
     * @return 黑名单过滤器
     */
    static ScopeFilter excludeGroups(Collection<Long> groupIds) {
        return new ExcludeGroups(new HashSet<>(groupIds));
    }

    // ==================== 组合 ====================

    /**
     * 逻辑与组合：两个过滤器都通过才算通过。
     *
     * @param other 另一个过滤器；{@code null} 视为 {@link #ALL}
     * @return 组合后的过滤器
     */
    default ScopeFilter and(ScopeFilter other) {
        if (other == null) {
            return this;
        }
        return groupId -> this.matches(groupId) && other.matches(groupId);
    }

    /**
     * 逻辑或组合：任一过滤器通过即通过。
     *
     * @param other 另一个过滤器；{@code null} 时返回 {@code this}
     * @return 组合后的过滤器
     */
    default ScopeFilter or(ScopeFilter other) {
        if (other == null) {
            return this;
        }
        return groupId -> this.matches(groupId) || other.matches(groupId);
    }

    /**
     * 逻辑非：结果取反。
     *
     * @return 取反后的过滤器
     */
    default ScopeFilter negate() {
        return groupId -> !this.matches(groupId);
    }

    // ==================== 诊断 ====================

    /**
     * 尝试导出本过滤器为「显式群白名单」。
     *
     * <p>用于持久化与管理端展示。只有 {@link #onlyGroups} 产生的过滤器可以导出；
     * 其他实现（含 lambda 组合）返回空——它们无法被反向解析成数据。</p>
     *
     * @return 群白名单；不可导出时为空
     */
    default Optional<Set<Long>> getExplicitGroupWhitelist() {
        return Optional.empty();
    }

    /**
     * 尝试导出本过滤器为「显式群黑名单」。
     *
     * <p>与 {@link #getExplicitGroupWhitelist()} 对称，只有 {@link #excludeGroups}
     * 产生的过滤器可以导出。</p>
     *
     * @return 群黑名单；不可导出时为空
     */
    default Optional<Set<Long>> getExplicitGroupBlacklist() {
        return Optional.empty();
    }

    /**
     * 尝试导出本过滤器为「全局开关 + 例外群列表」。
     *
     * <p>这是持久化的目标形态，对应现行 {@code group_plugin_ability} 表的语义：
     * 一个全局布尔 + 一张按群的例外表。管理端据此渲染「全局启用 / 群例外」两个控件。</p>
     *
     * @return 可持久化描述；不可导出时为空
     */
    default Optional<PersistedScope> toPersisted() {
        if (this == ALL) {
            return Optional.of(new PersistedScope(true, Set.of()));
        }
        Optional<Set<Long>> white = getExplicitGroupWhitelist();
        if (white.isPresent()) {
            // 白名单语义：全局关闭，仅列出的群开启
            return Optional.of(new PersistedScope(false, white.get()));
        }
        Optional<Set<Long>> black = getExplicitGroupBlacklist();
        if (black.isPresent()) {
            // 黑名单语义：全局开启，列出的群关闭
            return Optional.of(new PersistedScope(true, black.get()));
        }
        return Optional.empty();
    }

    /**
     * 从持久化形态还原过滤器。
     *
     * @param persisted 持久化描述
     * @return 对应的过滤器
     */
    static ScopeFilter fromPersisted(PersistedScope persisted) {
        if (persisted == null) {
            return ALL;
        }
        if (persisted.isEnabledByDefault()) {
            // 全局开启 + 黑名单
            return persisted.getExceptionGroups().isEmpty()
                    ? ALL
                    : excludeGroups(persisted.getExceptionGroups());
        }
        // 全局关闭 + 白名单
        return onlyGroups(persisted.getExceptionGroups());
    }

    /**
     * 作用域过滤器的持久化形态。
     *
     * <p>原本是 record；为统一 JavaBean 访问器改为 {@code @Getter} 类。</p>
     */
    @Getter
    @ToString
    @EqualsAndHashCode
    final class PersistedScope {

        /** 默认（全局）是否启用。 */
        private final boolean enabledByDefault;

        /** 例外群列表：{@code enabledByDefault} 为真时是黑名单，为假时是白名单。 */
        private final Set<Long> exceptionGroups;

        /**
         * 构造持久化形态。
         *
         * @param enabledByDefault 默认是否启用
         * @param exceptionGroups  例外群；{@code null} 视为空集
         */
        public PersistedScope(boolean enabledByDefault, Set<Long> exceptionGroups) {
            this.enabledByDefault = enabledByDefault;
            // 防御性拷贝，保证不可变
            this.exceptionGroups = exceptionGroups == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new HashSet<>(exceptionGroups));
        }
    }

    /**
     * 白名单实现——具名类而非 lambda，以便被反向解析。
     */
    @Getter
    @ToString
    @EqualsAndHashCode
    final class OnlyGroups implements ScopeFilter {

        /** 生效的群集合（已不可变）。 */
        private final Set<Long> groups;

        /**
         * 构造白名单。
         *
         * @param groups 生效的群集合
         */
        public OnlyGroups(Set<Long> groups) {
            // 复制并冻结集合
            this.groups = Collections.unmodifiableSet(new HashSet<>(groups));
        }

        @Override
        public boolean matches(Long groupId) {
            // 非群事件不生效：白名单暗含「这是群内功能」
            return groupId != null && groups.contains(groupId);
        }

        @Override
        public Optional<Set<Long>> getExplicitGroupWhitelist() {
            return Optional.of(groups);
        }
    }

    /**
     * 黑名单实现——具名类而非 lambda，以便被反向解析。
     */
    @Getter
    @ToString
    @EqualsAndHashCode
    final class ExcludeGroups implements ScopeFilter {

        /** 不生效的群集合（已不可变）。 */
        private final Set<Long> groups;

        /**
         * 构造黑名单。
         *
         * @param groups 不生效的群集合
         */
        public ExcludeGroups(Set<Long> groups) {
            // 复制并冻结集合
            this.groups = Collections.unmodifiableSet(new HashSet<>(groups));
        }

        @Override
        public boolean matches(Long groupId) {
            // 非群事件照常生效：黑名单只排除列出的群
            return groupId == null || !groups.contains(groupId);
        }

        @Override
        public Optional<Set<Long>> getExplicitGroupBlacklist() {
            return Optional.of(groups);
        }
    }
}
