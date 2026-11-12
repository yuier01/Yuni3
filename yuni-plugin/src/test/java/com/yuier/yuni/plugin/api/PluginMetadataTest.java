package com.yuier.yuni.plugin.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PluginMetadata} 的组装与回退规则。
 *
 * <p>核心约束：配置缺失不能影响加载；名称回落到插件 ID；行为字段只来自代码声明。</p>
 */
class PluginMetadataTest {

    @Test
    @DisplayName("配置缺失时名称回落到插件 ID，其余字段为空")
    void emptyDescriptorFallsBackToId() {
        PluginMetadata metadata = PluginMetadata.of(
                "", PluginDeclaration.of("steam").build(), PluginDescriptor.empty(), List.of());

        assertEquals("steam", metadata.getId());
        assertEquals("steam", metadata.getName());
        assertEquals("", metadata.getVersion());
        assertEquals("", metadata.getDescription());
        assertTrue(metadata.getTips().isEmpty());
        assertTrue(metadata.isDefaultEnable());
        assertFalse(metadata.isBuildIn());
        assertTrue(metadata.getRequiredServices().isEmpty());
    }

    @Test
    @DisplayName("描述非空且未配置 tips 时，tips 回退为单条描述")
    void tipsFallBackToDescription() {
        PluginDescriptor descriptor = PluginDescriptor.builder()
                .description("一句描述")
                .build();
        PluginMetadata metadata = PluginMetadata.of(
                "", PluginDeclaration.of("x").build(), descriptor, List.of());

        assertEquals(List.of("一句描述"), metadata.getTips());
    }

    @Test
    @DisplayName("配置提供展示信息时覆盖空值")
    void descriptorOverridesDisplayFields() {
        PluginDescriptor descriptor = PluginDescriptor.builder()
                .name("显示名")
                .version("1.2.3")
                .description("描述")
                .tips(List.of("提示"))
                .author("作者")
                .homepage("home")
                .repository("repo")
                .icon("icon")
                .build();
        PluginMetadata metadata = PluginMetadata.of(
                "", PluginDeclaration.of("x").build(), descriptor, List.of());

        assertEquals("x", metadata.getId());
        assertEquals("显示名", metadata.getName());
        assertEquals("1.2.3", metadata.getVersion());
        assertEquals("描述", metadata.getDescription());
        assertEquals(List.of("提示"), metadata.getTips());
        assertEquals("作者", metadata.getAuthor());
        assertEquals("home", metadata.getHomepage());
        assertEquals("repo", metadata.getRepository());
        assertEquals("icon", metadata.getIcon());
    }

    @Test
    @DisplayName("行为字段只来自代码声明")
    void behaviourComesFromDeclaration() {
        PluginMetadata metadata = PluginMetadata.of(
                "mod",
                PluginDeclaration.of("help").defaultEnable(false).buildIn(true).build(),
                PluginDescriptor.builder().name("帮助").build(),
                List.of(Runnable.class));

        assertEquals("mod/help", metadata.getId());
        assertEquals("mod", metadata.getArtifactId());
        assertEquals("help", metadata.getKey());
        assertFalse(metadata.isDefaultEnable());
        assertTrue(metadata.isBuildIn());
        assertEquals(List.of(Runnable.class), metadata.getRequiredServices());
    }
}
