package com.yuier.yuni.plugin.runtime;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 插件加载单元——一个 jar 的文件与类加载信息。
 *
 * <p>正常业务中 Artifact 一定来自真实 jar，因此所有字段非空。测试场景由
 * 测试源码里的构造工具直接调用包内构造器，不经过本类的公开 API——业务代码
 * 不提供「没有 jar 的 Artifact」这种形态。</p>
 *
 * <p>Artifact 不表达插件分组——插件之间平级。它只回答两个问题：这些类从哪来、
 * 重载时换哪个 ClassLoader。</p>
 *
 * @see PluginRuntime#reload(String)
 * @see PluginArtifactLoader
 */
@Getter
@ToString
@EqualsAndHashCode
public final class PluginArtifact {


    /** 加载单元标识；无命名空间时为 {@code null}。 */
    private final String id;

    /** 展示名；无命名空间时为 {@code null}。 */
    private final String name;

    /** jar 文件名；无 jar 时为 {@code null}。 */
    private final String jarFileName;

    /** jar 所在目录，同时是插件资源目录；无 jar 时为 {@code null}。 */
    private final String jarParentPath;

    /** 加载本单元插件类的 ClassLoader；无 jar 时为 {@code null}。 */
    private final ClassLoader classLoader;

    PluginArtifact(String id,
                           String name,
                           String jarFileName,
                           String jarParentPath,
                           ClassLoader classLoader) {
        this.id = id;
        this.name = name;
        this.jarFileName = jarFileName;
        this.jarParentPath = jarParentPath;
        this.classLoader = classLoader;
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
        Objects.requireNonNull(jarFileName, "jarFileName");
        Objects.requireNonNull(jarParentPath, "jarParentPath");
        Objects.requireNonNull(classLoader, "classLoader");
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
     * @return 目录；没有文件系统位置时返回 {@code null}
     */
    public Path getDataDir() {
        return jarParentPath == null ? null : Path.of(jarParentPath);
    }

    /**
     * jar 文件位置。
     *
     * @return 文件；没有文件系统位置时返回 {@code null}
     */
    public Path getJarPath() {
        if (jarParentPath == null || jarFileName == null) {
            return null;
        }
        return Path.of(jarParentPath, jarFileName);
    }
}
