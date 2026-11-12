package com.yuier.yuni.plugin.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PluginArtifact} 的三种形态与边界校验。
 *
 * <p>真实 jar 的字段必须齐全；测试用的命名空间允许没有 jar；占位实例两者都没有。
 * 把「什么时候可以为 null」固定在工厂方法里，调用方不需要再散落判空。</p>
 */
class PluginArtifactTest {

    @Test
    @DisplayName("none 没有标识与文件系统位置")
    void noneHasNoIdentityOrPath() {
        PluginArtifact artifact = PluginArtifact.none();

        assertNull(artifact.getId());
        assertNull(artifact.getName());
        assertNull(artifact.getDataDir());
        assertNull(artifact.getJarPath());
        assertFalse(artifact.isJarBacked());
    }

    @Test
    @DisplayName("namespace 只有标识，没有 jar")
    void namespaceHasIdentityOnly() {
        PluginArtifact artifact = PluginArtifact.namespace("test-artifact");

        assertEquals("test-artifact", artifact.getId());
        assertEquals("test-artifact", artifact.getName());
        assertNull(artifact.getDataDir());
        assertNull(artifact.getJarPath());
        assertFalse(artifact.isJarBacked());
    }

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
        assertTrue(artifact.isJarBacked());
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
                () -> PluginArtifact.namespace(" "));
    }
}
