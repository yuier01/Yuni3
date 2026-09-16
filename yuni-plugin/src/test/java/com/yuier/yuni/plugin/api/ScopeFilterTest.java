package com.yuier.yuni.plugin.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ScopeFilter} 的单元测试。
 *
 * <p>这些断言覆盖设计文档 §8.4 的「三层判定映射」与 §14.7 要求的
 * <b>回归测试</b>——群粒度启停是本次重构中行为等价性最需要保护的部分。</p>
 *
 * <p>关键语义差异（测试固化）：</p>
 * <ul>
 *   <li><b>白名单</b>（{@code onlyGroups}）对非群事件<b>不</b>生效——
 *       「只在某几个群生效」暗含「这是群内功能」；</li>
 *   <li><b>黑名单</b>（{@code excludeGroups}）对非群事件<b>生效</b>——
 *       「除这些群外都生效」不排斥私聊。</li>
 * </ul>
 */
class ScopeFilterTest {

    @Test
    @DisplayName("ALL：所有群与非群事件都生效")
    void allMatchesEverything() {
        assertTrue(ScopeFilter.ALL.matches(123L));
        assertTrue(ScopeFilter.ALL.matches(null));
    }

    @Test
    @DisplayName("NONE：任何情况都不生效")
    void noneMatchesNothing() {
        assertFalse(ScopeFilter.NONE.matches(123L));
        assertFalse(ScopeFilter.NONE.matches(null));
    }

    @Test
    @DisplayName("onlyGroups：只在列出的群生效，非群事件不生效")
    void onlyGroupsIsWhitelist() {
        ScopeFilter filter = ScopeFilter.onlyGroups(1L, 2L, 3L);

        assertTrue(filter.matches(1L));
        assertTrue(filter.matches(2L));
        assertTrue(filter.matches(3L));
        assertFalse(filter.matches(4L));

        // 白名单暗含「这是群内功能」，因此私聊/元事件不生效
        assertFalse(filter.matches(null), "白名单不应匹配非群事件");
    }

    @Test
    @DisplayName("excludeGroups：除列出的群外都生效，含非群事件")
    void excludeGroupsIsBlacklist() {
        ScopeFilter filter = ScopeFilter.excludeGroups(1L, 2L);

        assertFalse(filter.matches(1L));
        assertFalse(filter.matches(2L));
        assertTrue(filter.matches(3L));

        // 黑名单只排除列出的群，不排斥私聊
        assertTrue(filter.matches(null), "黑名单应匹配非群事件");
    }

    @Test
    @DisplayName("集合重载与可变参数重载语义一致")
    void collectionOverloadsMatchVarargs() {
        ScopeFilter fromVarargs = ScopeFilter.onlyGroups(1L, 2L);
        ScopeFilter fromCollection = ScopeFilter.onlyGroups(List.of(1L, 2L));

        for (Long groupId : new Long[]{1L, 2L, 3L, null}) {
            assertEquals(fromVarargs.matches(groupId), fromCollection.matches(groupId),
                    "群 " + groupId + " 的判定应一致");
        }
    }

    @Test
    @DisplayName("构造后修改传入集合不影响过滤器（防御性拷贝）")
    void defensivelyCopiesInput() {
        java.util.Set<Long> mutable = new java.util.HashSet<>(Set.of(1L, 2L));
        ScopeFilter filter = ScopeFilter.onlyGroups(mutable);

        mutable.add(99L);

        assertFalse(filter.matches(99L), "过滤器不应受调用方后续修改影响");
        assertTrue(filter.matches(1L));
    }

    @Test
    @DisplayName("and / or / negate 组合语义正确")
    void combinatorsWork() {
        ScopeFilter white = ScopeFilter.onlyGroups(1L, 2L, 3L);
        ScopeFilter black = ScopeFilter.excludeGroups(3L, 4L);

        // and：既是白名单成员又不在黑名单里
        ScopeFilter and = white.and(black);
        assertTrue(and.matches(1L));
        assertTrue(and.matches(2L));
        assertFalse(and.matches(3L), "3 在白名单但在黑名单，and 应排除");
        assertFalse(and.matches(4L), "4 不在白名单");

        // or：任一命中
        ScopeFilter or = ScopeFilter.onlyGroups(1L).or(ScopeFilter.onlyGroups(9L));
        assertTrue(or.matches(1L));
        assertTrue(or.matches(9L));
        assertFalse(or.matches(2L));

        // negate
        assertFalse(ScopeFilter.ALL.negate().matches(1L));
        assertTrue(ScopeFilter.NONE.negate().matches(1L));
    }

    @Test
    @DisplayName("and(null) / or(null) 按 ALL 语义处理，不抛 NPE")
    void combinatorsTolerateNull() {
        ScopeFilter white = ScopeFilter.onlyGroups(1L);

        assertTrue(white.and(null).matches(1L));
        assertFalse(white.and(null).matches(2L));

        assertTrue(white.or(null).matches(1L));
        assertFalse(white.or(null).matches(2L));
    }

    @Test
    @DisplayName("只有具名实现可被反向解析为持久化形态")
    void onlyNamedImplementationsArePersistable() {
        // 白名单可导出
        ScopeFilter white = ScopeFilter.onlyGroups(1L, 2L);
        assertEquals(Set.of(1L, 2L), white.explicitGroupWhitelist().orElseThrow());
        assertTrue(white.explicitGroupBlacklist().isEmpty());

        // 黑名单可导出
        ScopeFilter black = ScopeFilter.excludeGroups(3L);
        assertEquals(Set.of(3L), black.explicitGroupBlacklist().orElseThrow());
        assertTrue(black.explicitGroupWhitelist().isEmpty());

        // lambda 组合不可导出——它们无法被反向解析成数据
        ScopeFilter lambda = groupId -> true;
        assertTrue(lambda.explicitGroupWhitelist().isEmpty());
        assertTrue(lambda.explicitGroupBlacklist().isEmpty());
        assertTrue(lambda.toPersisted().isEmpty());
    }

    @Test
    @DisplayName("持久化往返：白名单 → PersistedScope → 过滤器，语义不变")
    void persistedRoundTripForWhitelist() {
        ScopeFilter original = ScopeFilter.onlyGroups(1L, 2L);

        ScopeFilter.PersistedScope persisted = original.toPersisted().orElseThrow();
        // 白名单语义 = 全局关闭 + 例外群开启
        assertFalse(persisted.enabledByDefault());
        assertEquals(Set.of(1L, 2L), persisted.exceptionGroups());

        ScopeFilter restored = ScopeFilter.fromPersisted(persisted);
        assertEquals(original.matches(1L), restored.matches(1L));
        assertEquals(original.matches(2L), restored.matches(2L));
        assertEquals(original.matches(3L), restored.matches(3L));
        assertEquals(original.matches(null), restored.matches(null));
    }

    @Test
    @DisplayName("持久化往返：黑名单 → PersistedScope → 过滤器，语义不变")
    void persistedRoundTripForBlacklist() {
        ScopeFilter original = ScopeFilter.excludeGroups(1L);

        ScopeFilter.PersistedScope persisted = original.toPersisted().orElseThrow();
        // 黑名单语义 = 全局开启 + 例外群关闭
        assertTrue(persisted.enabledByDefault());
        assertEquals(Set.of(1L), persisted.exceptionGroups());

        ScopeFilter restored = ScopeFilter.fromPersisted(persisted);
        assertFalse(restored.matches(1L));
        assertTrue(restored.matches(2L));
        assertTrue(restored.matches(null));
    }

    @Test
    @DisplayName("ALL 的持久化形态是「全局开启 + 空例外」")
    void allIsPersistedAsGloballyEnabled() {
        ScopeFilter.PersistedScope persisted = ScopeFilter.ALL.toPersisted().orElseThrow();
        assertTrue(persisted.enabledByDefault());
        assertTrue(persisted.exceptionGroups().isEmpty());
    }

    @Test
    @DisplayName("PersistedScope 防御性拷贝且不可变")
    void persistedScopeIsImmutable() {
        java.util.Set<Long> mutable = new java.util.HashSet<>(Set.of(1L));
        ScopeFilter.PersistedScope persisted = new ScopeFilter.PersistedScope(true, mutable);

        mutable.add(2L);

        assertEquals(Set.of(1L), persisted.exceptionGroups());
    }
}
