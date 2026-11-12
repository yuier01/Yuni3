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
 * <p>统一后的模型只有「默认值 + 例外群」两个字段，白名单 / 黑名单只是它的
 * 两个取值。因此本类验证的是同一个公式的边界，而不是两套实现。</p>
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
    @DisplayName("onlyGroups：只在列出的群生效，非群事件不生效")
    void onlyGroups() {
        ScopeFilter filter = ScopeFilter.onlyGroups(100L, 200L);

        assertTrue(filter.matches(100L));
        assertTrue(filter.matches(200L));
        assertFalse(filter.matches(300L));
        assertFalse(filter.matches(null), "白名单暗含「群内功能」，非群事件不生效");
        assertFalse(filter.isEnabledByDefault());
        assertEquals(Set.of(100L, 200L), filter.getExceptionGroups());
    }

    @Test
    @DisplayName("excludeGroups：除列出的群外都生效，含非群事件")
    void excludeGroups() {
        ScopeFilter filter = ScopeFilter.excludeGroups(100L);

        assertFalse(filter.matches(100L));
        assertTrue(filter.matches(200L));
        assertTrue(filter.matches(null), "黑名单只排除列出的群，非群事件照常生效");
        assertTrue(filter.isEnabledByDefault());
        assertEquals(Set.of(100L), filter.getExceptionGroups());
    }

    @Test
    @DisplayName("构造器直接表达同一模型：默认值 + 例外群")
    void constructorIsEquivalentToFactories() {
        assertEquals(ScopeFilter.onlyGroups(100L),
                new ScopeFilter(false, Set.of(100L)));
        assertEquals(ScopeFilter.excludeGroups(100L),
                new ScopeFilter(true, Set.of(100L)));
        assertEquals(ScopeFilter.ALL, new ScopeFilter(true, Set.of()));
        assertEquals(ScopeFilter.NONE, new ScopeFilter(false, Set.of()));
    }

    @Test
    @DisplayName("可变参数重载与集合重载语义一致")
    void overloadsAgree() {
        assertEquals(ScopeFilter.onlyGroups(1L, 2L),
                ScopeFilter.onlyGroups(Set.of(1L, 2L)));
        assertEquals(ScopeFilter.excludeGroups(1L, 2L),
                ScopeFilter.excludeGroups(Set.of(1L, 2L)));
    }

    @Test
    @DisplayName("构造后修改传入集合不影响过滤器（防御性拷贝）")
    void defensiveCopy() {
        Set<Long> source = new HashSet<>(Set.of(1L));
        ScopeFilter filter = ScopeFilter.onlyGroups(source);

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
    @DisplayName("值语义：字段相同即相等，与构造方式无关")
    void valueEquality() {
        assertEquals(ScopeFilter.onlyGroups(1L), ScopeFilter.onlyGroups(1L));
        assertNotEquals(ScopeFilter.onlyGroups(1L), ScopeFilter.excludeGroups(1L));
        assertNotEquals(ScopeFilter.onlyGroups(1L), ScopeFilter.onlyGroups(2L));
    }
}
