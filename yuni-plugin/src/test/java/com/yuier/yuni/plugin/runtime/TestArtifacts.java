package com.yuier.yuni.plugin.runtime;

/**
 * 测试专用的加载单元构造工具。
 *
 * <p>生产代码只承认真实 jar 形态的 {@link PluginArtifact}；测试通过包内构造器
 * 造一个结构完整、但文件不存在的 Artifact。业务代码因此不需要支持
 * 「没有 jar / 文件路径为 null」的形态。</p>
 */
public final class TestArtifacts {

    /** 测试用 jar 所在目录；不需要真实存在。 */
    private static final String TEST_DIR = System.getProperty("java.io.tmpdir");

    private TestArtifacts() {
    }

    /**
     * 不带命名空间的测试加载单元。
     *
     * @return 加载单元
     */
    public static PluginArtifact none() {
        return new PluginArtifact(
                "", "", "test.jar", TEST_DIR, TestArtifacts.class.getClassLoader());
    }

    /**
     * 带命名空间的测试加载单元。
     *
     * @param id 命名空间
     * @return 加载单元
     */
    public static PluginArtifact namespace(String id) {
        return new PluginArtifact(
                id, id, "test.jar", TEST_DIR, TestArtifacts.class.getClassLoader());
    }
}
