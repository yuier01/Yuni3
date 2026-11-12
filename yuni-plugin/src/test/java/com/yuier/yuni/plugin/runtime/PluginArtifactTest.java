package com.yuier.yuni.plugin.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link PluginArtifact} 真实 jar 形态的字段与校验。
 *
 * <p>「没有 jar」只存在于测试工具里，不在生产 API 中，因此这里不测试那种形态。</p>
 */
class PluginArtifactTest {

    @Test
    @DisplayName("jar 形态提供文件位置与类加载器")
    void jarHasFileLocation() {
        PluginArtifact artifact = PluginArtifact.jar(
                "steam", "steam 相关功能", "steam.jar", "plugins/steam",
                PluginArtifactTest.class.getClassLoader());

        assertEquals("steam", artifact.getId());
        assertEquals("steam 相关功能", artifact.getName());
        assertEquals(Path.of("plugins/steam"), artifact.getDataDir());
        assertEquals(Path.of("plugins/steam/steam.jar"), artifact.getJarPath());
    }

    @Test
    @DisplayName("jar 形态缺少文件信息时立即失败，而不是留下半状态")
    void jarRequiresCompleteFileInformation() {
        assertThrows(NullPointerException.class,
                () -> PluginArtifact.jar("x", "X", null, "plugins/x", getClass().getClassLoader()));
        assertThrows(NullPointerException.class,
                () -> PluginArtifact.jar("x", "X", "x.jar", null, getClass().getClassLoader()));
        assertThrows(NullPointerException.class,
                () -> PluginArtifact.jar("x", "X", "x.jar", "plugins/x", null));
        assertThrows(IllegalArgumentException.class,
                () -> PluginArtifact.jar(" ", "X", "x.jar", "plugins/x", getClass().getClassLoader()));
    }
}