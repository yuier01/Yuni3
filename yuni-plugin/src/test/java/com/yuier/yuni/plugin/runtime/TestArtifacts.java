package com.yuier.yuni.plugin.runtime;

/**
 * 测试专用的加载单元构造工具。
 *
 * <p>业务代码只允许真实 jar 形态的 {@link PluginArtifact}；测试需要在没有 jar
 * 的情况下加载插件，因此通过包内构造器直接构造。这些方法<b>不在</b>生产源码里，
 * 避免业务 API 出现「测试专用形态」。</p>
 */
public final class TestArtifacts {

    private TestArtifacts() {
    }

    /**
     * 无命名空间、无 jar 的占位。
     *
     * @return 占位
     */
    public static PluginArtifact none() {
        return new PluginArtifact(null, null, null, null, null);
    }

    /**
     * 只有命名空间、没有 jar 的加载单元。
     *
     * @param id 命名空间
     * @return 加载单元
     */
    public static PluginArtifact namespace(String id) {
        return new PluginArtifact(id, id, null, null, null);
    }
}
