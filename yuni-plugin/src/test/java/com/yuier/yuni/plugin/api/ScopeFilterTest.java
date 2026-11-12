package com.yuier.yuni.plugin.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ScopeFilter} 的模型测试。
 *
 * <p>作用域只有一个模型：<b>默认值 + 与默认值相反的例外群</b>。
 * 白名单 / 黑名单只是这一模型的两个取值，不再有独立实现。</p>
 */
class ScopeFilterTest {

    @Test
    @DisplayName("ALL：所有群与非群事件都生效")
    void allMatchesEverything() {
        assertTrue(ScopeFilter.ALL.matches(1L));
        assertTrue(ScopeFilter.ALL.matches(null));
        assertTrue(ScopeFilter.ALL.isEnabledByDefault());
        assertTrue(ScopeFilter.ALL.getExceptionGroups().isEmpty());
    }

    @Test
    @DisplayName("NONE：任何情况都不生效")
    void noneMatchesNothing() {
        assertFalse(ScopeFilter.NONE.matches(1L));
        assertFalse(ScopeFilter.NONE.matches(null));
        assertFalse(ScopeFilter.NONE.isEnabledByDefault());
        assertTrue(ScopeFilter.NONE.getExceptionGroups().isEmpty());
    }

    @Test
    @DisplayName("默认关闭 + 例外群：只有列出的群生效，非群事件不生效")
    void defaultOffWithExceptions() {
        ScopeFilter filter = new ScopeFilter(false, Set.of(100L, 200L));

        assertTrue(filter.matches(100L));
        assertTrue(filter.matches(200L));
        assertFalse(filter.matches(300L));
        assertFalse(filter.matches(null));
        assertFalse(filter.isEnabledByDefault());
        assertEquals(Set.of(100L, 200L), filter.getExceptionGroups());
    }

    @Test
    @DisplayName("默认开启 + 例外群：只有列出的群不生效，非群事件照常生效")
    void defaultOnWithExceptions() {
        ScopeFilter filter = new ScopeFilter(true, Set.of(100L));

        assertFalse(filter.matches(100L));
        assertTrue(filter.matches(200L));
        assertTrue(filter.matches(null));
        assertTrue(filter.isEnabledByDefault());
        assertEquals(Set.of(100L), filter.getExceptionGroups());
    }

    @Test
    @DisplayName("构造后修改传入集合不影响过滤器（防御性拷贝）")
    void defensiveCopy() {
        Set<Long> source = new HashSet<>(Set.of(1L));
        ScopeFilter filter = new ScopeFilter(false, source);

        source.add(2L);

        assertEquals(Set.of(1L), filter.getExceptionGroups());
        assertTrue(filter.matches(1L));
        assertFalse(filter.matches(2L), "构造后外部修改不应影响过滤器");
    }

    @Test
    @DisplayName("null 例外集合视为空集")
    void nullExceptionGroups() {
        ScopeFilter filter = new ScopeFilter(true, null);
        assertEquals(ScopeFilter.ALL, filter);
        assertTrue(filter.getExceptionGroups().isEmpty());
    }

    @Test
    @DisplayName("值语义：字段相同即相等")
    void valueEquality() {
        assertEquals(new ScopeFilter(false, Set.of(1L)), new ScopeFilter(false, Set.of(1L)));
        assertNotEquals(new ScopeFilter(false, Set.of(1L)), new ScopeFilter(true, Set.of(1L)));
        assertNotEquals(new ScopeFilter(false, Set.of(1L)), new ScopeFilter(false, Set.of(2L)));
    }
}
