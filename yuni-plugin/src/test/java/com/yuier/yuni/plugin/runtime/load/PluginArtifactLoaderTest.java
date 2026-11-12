package com.yuier.yuni.plugin.runtime.load;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * metadata.json 结构的解析测试。
 *
 * <p>这些字段不参与运行行为，但配置写错会让管理端展示为空——本测试固定住
 * 「文件形状」与「字段名」，避免改名后静默失效。</p>
 */
class PluginArtifactLoaderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("解析 jar 与插件展示信息")
    void parsesArtifactAndPluginDescriptors() throws Exception {
        String json = """
                {
                  "id": "steam",
                  "name": "steam 相关功能",
                  "plugins": {
                    "Steam": {
                      "name": "Steam 状态监控与绑定",
                      "version": "1.0.0",
                      "description": "监控与绑定",
                      "tips": ["提示一", "提示二"],
                      "author": "Yuier",
                      "homepage": "https://example.com",
                      "repository": "https://github.com/example/steam",
                      "icon": "https://example.com/icon.png"
                    }
                  }
                }
                """;

        PluginArtifactLoader.ArtifactMetadata metadata =
                MAPPER.readValue(json, PluginArtifactLoader.ArtifactMetadata.class);

        assertEquals("steam", metadata.getId());
        assertEquals("steam 相关功能", metadata.getName());
        assertTrue(metadata.getPlugins().containsKey("Steam"));

        var descriptor = metadata.getPlugins().get("Steam").toDescriptor();
        assertEquals("Steam 状态监控与绑定", descriptor.getName());
        assertEquals("1.0.0", descriptor.getVersion());
        assertEquals("监控与绑定", descriptor.getDescription());
        assertEquals(2, descriptor.getTips().size());
        assertEquals("Yuier", descriptor.getAuthor());
        assertEquals("https://example.com", descriptor.getHomepage());
        assertEquals("https://github.com/example/steam", descriptor.getRepository());
        assertEquals("https://example.com/icon.png", descriptor.getIcon());
    }

    @Test
    @DisplayName("缺少 plugins 节点时为空映射，不报错")
    void missingPluginsIsEmpty() throws Exception {
        PluginArtifactLoader.ArtifactMetadata metadata =
                MAPPER.readValue("{\"id\":\"x\",\"name\":\"X\"}",
                        PluginArtifactLoader.ArtifactMetadata.class);

        assertEquals("x", metadata.getId());
        assertTrue(metadata.getPlugins().isEmpty());
    }
}
