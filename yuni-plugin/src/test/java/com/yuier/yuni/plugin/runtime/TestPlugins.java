package com.yuier.yuni.plugin.runtime;

import com.yuier.yuni.plugin.api.PluginDescriptor;
import com.yuier.yuni.plugin.api.YuniPlugin;

/**
 * 测试专用的插件加载入口。
 *
 * <p>生产入口 {@link PluginRuntime#load(YuniPlugin, PluginArtifact, PluginDescriptor)}
 * 要求真实加载单元；测试用本工具补上占位单元与空描述。</p>
 */
public final class TestPlugins {

    private TestPlugins() {
    }

    /**
     * 无加载单元地加载插件。
     *
     * @param runtime 运行时
     * @param plugin  插件
     * @return Fiber
     */
    public static PluginFiber load(PluginRuntime runtime, YuniPlugin plugin) {
        PluginArtifact artifact = TestArtifacts.none();
        runtime.registerArtifact(artifact);
        return runtime.load(plugin, artifact, PluginDescriptor.empty());
    }

    /**
     * 在命名空间下加载插件。
     *
     * @param runtime    运行时
     * @param plugin     插件
     * @param artifactId 命名空间
     * @return Fiber
     */
    public static PluginFiber load(PluginRuntime runtime, YuniPlugin plugin, String artifactId) {
        PluginArtifact artifact = TestArtifacts.namespace(artifactId);
        runtime.registerArtifact(artifact);
        return runtime.load(plugin, artifact, PluginDescriptor.empty());
    }

    /**
     * 完整参数的测试入口。
     *
     * @param runtime    运行时
     * @param plugin     插件
     * @param artifact   加载单元
     * @param descriptor 描述
     * @return Fiber
     */
    public static PluginFiber load(PluginRuntime runtime,
                                   YuniPlugin plugin,
                                   PluginArtifact artifact,
                                   PluginDescriptor descriptor) {
        runtime.registerArtifact(artifact);
        return runtime.load(plugin, artifact, descriptor);
    }
}
