package com.yuier.yuni.plugin.runtime;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 插件加载单元——一个 jar 的文件与类加载信息。
 *
 * <p>所有字段非空：加载单元必然对应一个 jar 文件（测试可以指向一个不存在的
 * 文件，但仍然是结构完整的 Artifact）。Artifact 不表达插件分组——插件之间
 * 平级。</p>
 *
 * @see PluginRuntime#reload(String)
 * @see PluginArtifactLoader
 */
@Getter
@ToString
@EqualsAndHashCode
public final class PluginArtifact {

    /** 加载单元标识；来自 metadata.json 的 {@code id}，缺省时为 jar 文件名。 */
    private final String id;

    /** 展示名；来自 metadata.json 的 {@code name}。 */
    private final String name;

    /** jar 文件名。 */
    private final String jarFileName;

    /** jar 所在目录，同时是插件资源目录。 */
    private final String jarParentPath;

    /** 加载本单元插件类的 ClassLoader。 */
    private final ClassLoader classLoader;

    /*
     * 包内可见：仅供测试源码构造「指向不存在 jar」的 Artifact。
     * 生产代码请使用 jar(...) 工厂。
     */
    PluginArtifact(String id,
                   String name,
                   String jarFileName,
                   String jarParentPath,
                   ClassLoader classLoader) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = name;
        this.jarFileName = Objects.requireNonNull(jarFileName, "jarFileName");
        this.jarParentPath = Objects.requireNonNull(jarParentPath, "jarParentPath");
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
    }

    /**
     * 真实 jar 对应的加载单元。
     *
     * @param id            标识
     * @param name          展示名；为空时回落到 {@code id}
     * @param jarFileName   jar 文件名
     * @param jarParentPath jar 所在目录
     * @param classLoader   类加载器
     * @return 加载单元
     */
    public static PluginArtifact jar(String id,
                                     String name,
                                     String jarFileName,
                                     String jarParentPath,
                                     ClassLoader classLoader) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("加载单元标识不能为空");
        }
        return new PluginArtifact(
                id,
                name == null || name.isBlank() ? id : name,
                jarFileName,
                jarParentPath,
                classLoader);
    }

    /**
     * 资源目录。
     *
     * @return 目录
     */
    public Path getDataDir() {
        return Path.of(jarParentPath);
    }

    /**
     * jar 文件位置。
     *
     * @return 文件
     */
    public Path getJarPath() {
        return Path.of(jarParentPath, jarFileName);
    }
}
