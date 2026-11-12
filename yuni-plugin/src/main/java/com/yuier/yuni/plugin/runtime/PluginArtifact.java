package com.yuier.yuni.plugin.runtime;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 插件加载单元——一个 jar 的文件与类加载信息。
 *
 * <h2>三种形态</h2>
 * <ul>
 *   <li>{@link #jar(String, String, String, String, ClassLoader)}：真实 jar，
 *       所有字段非空；</li>
 *   <li>{@link #namespace(String)}：只有命名空间、没有 jar，用于测试与代码内
 *       直接加载插件；</li>
 *   <li>{@link #none()}：连命名空间都没有，插件标识退化为插件 ID。</li>
 * </ul>
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

    /** 无 jar、无命名空间的占位实例。 */
    private static final PluginArtifact NONE =
            new PluginArtifact(null, null, null, null, null);

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

    private PluginArtifact(String id,
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
     * 无 jar、无命名空间的占位。
     *
     * @return 占位实例
     */
    public static PluginArtifact none() {
        return NONE;
    }

    /**
     * 只有命名空间、没有 jar 的加载单元，供测试与代码内直接加载使用。
     *
     * @param id 命名空间标识；不能为空
     * @return 加载单元
     */
    public static PluginArtifact namespace(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("加载单元标识不能为空");
        }
        return new PluginArtifact(id, id, null, null, null);
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
     * 是否来自真实 jar。
     *
     * @return 有 jar 文件返回 {@code true}
     */
    public boolean isJarBacked() {
        return jarFileName != null;
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
