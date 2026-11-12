package com.yuier.yuni.plugin.runtime;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.nio.file.Path;

/**
 * 插件加载单元——一个 jar（或测试中的等价物）的文件与类加载信息。
 *
 * <h2>它不是什么</h2>
 * <p>Artifact <b>不表达插件分组</b>。插件之间是平级关系；管理端看到的也是
 * 平铺列表。Artifact 只回答两个问题：这些类从哪个文件来、重载时该换哪个
 * ClassLoader。</p>
 *
 * <h2>为什么需要它</h2>
 * <p>Java 的类加载器以 jar 为单位，无法只重载 jar 中的一个类。因此热重载
 * 的最小单位天然是「一个 jar」，而不是「一个插件」。Artifact 就是这个边界的
 * 显式表达——它取代了旧系统中的 module 概念，但不再承担分组展示职责。</p>
 *
 * <h2>测试场景</h2>
 * <p>单元测试通常直接 {@code new} 插件实例，没有 jar。此时使用
 * {@link #none()}：{@code id} 为 {@code null}，插件标识退化为插件名。</p>
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

    /** jar 文件名；测试场景为 {@code null}。 */
    private final String jarFileName;

    /** jar 所在目录，同时是插件资源目录；测试场景为 {@code null}。 */
    private final String jarParentPath;

    /** 加载本单元插件类的 ClassLoader；测试场景可为 {@code null}。 */
    private final ClassLoader classLoader;

    /**
     * 构造加载单元。
     *
     * @param id           标识
     * @param name         展示名
     * @param jarFileName  jar 文件名
     * @param jarParentPath jar 所在目录
     * @param classLoader  类加载器
     */
    public PluginArtifact(String id,
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
     * 无文件系统的加载单元，供测试使用。
     *
     * @return 一个 {@code id} 为 {@code null} 的 Artifact
     */
    public static PluginArtifact none() {
        return new PluginArtifact(null, null, null, null, null);
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
