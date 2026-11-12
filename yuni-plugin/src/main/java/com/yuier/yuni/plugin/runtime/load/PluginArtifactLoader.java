package com.yuier.yuni.plugin.runtime.load;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuier.yuni.plugin.api.YuniPlugin;
import com.yuier.yuni.plugin.api.PluginDescriptor;
import com.yuier.yuni.plugin.runtime.PluginArtifact;
import com.yuier.yuni.plugin.runtime.PluginFiber;
import com.yuier.yuni.plugin.runtime.PluginRuntime;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * 插件加载单元加载器——扫描 jar、解析描述文件、用 child-first 类加载器加载插件类。
 *
 * <h2>加载单元是什么</h2>
 * <p>一个 {@link PluginArtifact} 对应一个 jar，只承担两件事：定位 jar 文件、
 * 提供 ClassLoader。它<b>不表达插件分组</b>——jar 内的多个插件彼此平级。</p>
 *
 * <h2>保留的现有资产</h2>
 * <ol>
 *   <li><b>{@code PluginClassLoader} 的 child-first 策略</b>——原样沿用。
 *       父加载器取工厂类自己的加载器（fat jar 下为 {@code LaunchedClassLoader}），
 *       这是 2026-08 P0 事故的修复成果，不能回退成 TCCL。</li>
 *   <li><b>扫描阶段加载 jar 内全部类</b>——因此运行期不存在懒加载，
 *       且可以在 Windows 上关闭 jar 文件句柄以支持覆盖更新。</li>
 *   <li><b>元数据从 jar 父目录读取</b>（而非 jar 内）——运维可直接改配置而
 *       无需重新打包。</li>
 * </ol>
 *
 * <h2>jar 描述文件</h2>
 * <p>{@code metadata.json} 只描述 jar 本身：{@code id} 与 {@code name}。
 * 插件级的名称、版本、描述、作者由同文件 {@code plugins} 节点提供。</p>
 *
 * @see PluginClassLoader
 * @see PluginArtifact
 */
@Slf4j
@RequiredArgsConstructor
public final class PluginArtifactLoader {

    /** JSON 映射器，用于解析 jar 描述文件。 */
    private final ObjectMapper objectMapper;

    /** 插件运行时。 */
    private final PluginRuntime runtime;

    /**
     * jar 描述模型。
     */
    @Data
    public static class ArtifactMetadata {

        /** 加载单元标识；缺省时用 jar 文件名。 */
        private String id;

        /** 展示名；缺省时用 {@link #id}。 */
        private String name;

        /** 插件简单类名 → 展示信息。 */
        private Map<String, PluginDescriptorData> plugins = new LinkedHashMap<>();

        /** Jackson 反序列化需要无参构造。 */
        public ArtifactMetadata() {
            // 由 Jackson 填充字段
        }
    }

    /**
     * metadata.json 中单个插件的展示信息。
     */
    @Data
    public static class PluginDescriptorData {

        /** 显示名。 */
        private String name;

        /** 版本。 */
        private String version;

        /** 描述。 */
        private String description;

        /** 提示。 */
        private List<String> tips;

        /** 作者 / 版权声明。 */
        private String author;

        /** 项目主页。 */
        private String homepage;

        /** 代码仓库。 */
        private String repository;

        /** 图标。 */
        private String icon;

        /** Jackson 反序列化需要无参构造。 */
        public PluginDescriptorData() {
            // 由 Jackson 填充字段
        }

        /**
         * 转为不可变描述。
         *
         * @return 描述
         */
        public PluginDescriptor toDescriptor() {
            return PluginDescriptor.builder()
                    .name(name)
                    .version(version)
                    .description(description)
                    .tips(tips)
                    .author(author)
                    .homepage(homepage)
                    .repository(repository)
                    .icon(icon)
                    .build();
        }
    }

    /**
     * 收集插件目录下全部 jar 文件。
     *
     * @param directory 插件目录
     * @return jar 文件列表；目录不存在时为空列表
     */
    public List<File> collectJarFiles(String directory) {
        if (directory == null || directory.isBlank()) {
            // 空目录配置等于「扫描当前工作目录」，会意外加载无关 jar；
            // 也避免 Files.walk 返回裸文件名（无父目录）
            log.warn("插件目录为空，跳过扫描");
            return List.of();
        }
        Path rootPath = Paths.get(directory);
        if (!Files.isDirectory(rootPath)) {
            log.warn("插件目录不存在或不是目录: {}", directory);
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(rootPath)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().toLowerCase().endsWith(".jar"))
                    .map(Path::toFile)
                    .toList();
        } catch (IOException e) {
            log.error("遍历插件目录失败: {}", directory, e);
            return List.of();
        }
    }

    /**
     * 加载一个 jar。
     *
     * <p>流程：读描述文件 → 建 child-first 类加载器 → 扫描插件类 → 逐个实例化并
     * 交给运行时。任一插件失败不影响同 jar 的其他插件。</p>
     *
     * @param jarFile     jar 文件
     * @param artifactDir jar 所在目录（资源目录）
     * @return 本次成功建成的 Fiber 列表
     */
    public List<PluginFiber> loadArtifact(File jarFile, Path artifactDir) {
        List<PluginFiber> loaded = new ArrayList<>();
        // 从 metadata.json 中解析元数据
        ArtifactMetadata metadata = readArtifactMetadata(artifactDir, jarFile.getName());
        String artifactId = metadata.getId();

        // 为每个插件 jar 包创建类加载器，其父加载器为 PluginClassLoaderFactory 自身的父加载器
        // 确保能看见框架类，这样在重载插件的场景下，可以确保解析框架类，成功重载
        try (PluginClassLoader classLoader = PluginClassLoaderFactory.create(jarFile)) {
            PluginArtifact artifact = new PluginArtifact(
                    artifactId,
                    metadata.getName(),
                    jarFile.getName(),
                    artifactDir == null ? null : artifactDir.toString(),
                    classLoader);
            // 先登记：即使 jar 里没有插件，管理端与重载流程也能看到这个加载单元
            runtime.registerArtifact(artifact);

            List<Class<?>> pluginClasses = discoverPluginClasses(jarFile, classLoader);
            if (pluginClasses.isEmpty()) {
                log.warn("加载单元 {} 未发现任何插件类（jar: {}）", artifactId, jarFile.getName());
                return loaded;
            }

            for (Class<?> pluginClass : pluginClasses) {
                YuniPlugin instance = instantiate(pluginClass);
                if (instance == null) {
                    continue;
                }
                try {
                    loaded.add(runtime.load(instance, artifact,
                            descriptorFor(metadata, pluginClass)));
                } catch (Throwable t) {
                    // 必须捕获 Throwable：类加载失败抛的是 Error。
                    // 只 catch Exception 会让插件静默消失（2026-08 P0 事故的教训）
                    log.error("加载插件 {} 失败", pluginClass.getName(), t);
                }
            }

            log.info("加载单元 {} 加载完成，共 {} 个插件", artifactId, loaded.size());
        } catch (Throwable t) {
            log.error("加载插件 jar 失败: {}", jarFile.getName(), t);
        }
        return loaded;
    }

    /**
     * 取某个插件在配置里的展示信息。
     *
     * <p>先按简单类名查，再按全限定名查；都没有时返回空描述。</p>
     *
     * @param metadata    jar 描述
     * @param pluginClass 插件类
     * @return 描述，永不为 {@code null}
     */
    private static PluginDescriptor descriptorFor(ArtifactMetadata metadata, Class<?> pluginClass) {
        if (metadata.getPlugins() == null || metadata.getPlugins().isEmpty()) {
            return PluginDescriptor.empty();
        }
        PluginDescriptorData data = metadata.getPlugins().get(pluginClass.getSimpleName());
        if (data == null) {
            data = metadata.getPlugins().get(pluginClass.getName());
        }
        return data == null ? PluginDescriptor.empty() : data.toDescriptor();
    }

    /**
     * 扫描 jar 内实现 {@link YuniPlugin} 的具体类。
     *
     * @param jarFile     jar 文件
     * @param classLoader 类加载器
     * @return 插件类列表
     * @throws IOException 读取 jar 失败
     */
    private List<Class<?>> discoverPluginClasses(File jarFile,
                                                  PluginClassLoader classLoader) throws IOException {
        List<Class<?>> classes = new ArrayList<>();
        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.endsWith(".class")) {
                    continue;
                }
                String className = name.substring(0, name.length() - 6).replace('/', '.');
                try {
                    Class<?> clazz = classLoader.loadClass(className);
                    if (isConcretePlugin(clazz)) {
                        classes.add(clazz);
                    }
                } catch (Throwable t) {
                    // 单个类加载失败（缺依赖等）不应中断整包扫描
                    log.debug("跳过无法加载的类 {}: {}", className, t.getMessage());
                }
            }
        }
        return classes;
    }

    /**
     * 判定类是否为可实例化的插件实现。
     *
     * @param clazz 类
     * @return 是具体插件实现时返回 {@code true}
     */
    private static boolean isConcretePlugin(Class<?> clazz) {
        return YuniPlugin.class.isAssignableFrom(clazz)
                && !clazz.isInterface()
                && !Modifier.isAbstract(clazz.getModifiers());
    }

    /**
     * 实例化插件类。
     *
     * @param pluginClass 插件类
     * @return 插件实例；失败时返回 {@code null}
     */
    private static YuniPlugin instantiate(Class<?> pluginClass) {
        try {
            return (YuniPlugin) pluginClass.getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            log.error("插件类 {} 实例化失败（需要公开无参构造器）", pluginClass.getName(), t);
            return null;
        }
    }

    /**
     * 读取 jar 描述文件。
     *
     * <p>{@code metadata.json} 位于 jar 的父目录。缺失时不视为错误：此时用
     * jar 文件名作为标识与展示名。</p>
     *
     * @param artifactDir jar 所在目录
     * @param jarFileName jar 文件名（兜底标识）
     * @return 描述信息，永不为 {@code null}
     */
    private ArtifactMetadata readArtifactMetadata(Path artifactDir, String jarFileName) {
        ArtifactMetadata fallback = new ArtifactMetadata();
        fallback.setId(stripExtension(jarFileName));
        fallback.setName(stripExtension(jarFileName));

        if (artifactDir == null) {
            return fallback;
        }
        Path metadataFile = artifactDir.resolve("metadata.json");
        if (!Files.isRegularFile(metadataFile)) {
            log.warn("jar 目录缺少 metadata.json，使用 jar 文件名作为标识: {}", artifactDir);
            return fallback;
        }
        try {
            ArtifactMetadata metadata =
                    objectMapper.readValue(metadataFile.toFile(), ArtifactMetadata.class);
            if (metadata == null) {
                return fallback;
            }
            if (metadata.getId() == null || metadata.getId().isBlank()) {
                metadata.setId(fallback.getId());
            }
            if (metadata.getName() == null || metadata.getName().isBlank()) {
                metadata.setName(metadata.getId());
            }
            return metadata;
        } catch (Exception e) {
            log.error("解析 jar 描述文件失败，使用兜底标识: {}", metadataFile, e);
            return fallback;
        }
    }

    /**
     * 去掉文件名扩展名。
     *
     * @param fileName 文件名
     * @return 去扩展名后的名字
     */
    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
