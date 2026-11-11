package com.yuier.yuni.plugin.runtime.load;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuier.yuni.plugin.api.YuniPlugin;
import com.yuier.yuni.plugin.runtime.FiberRegistry;
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
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * 插件模块加载器——扫描 jar、解析元数据、用 child-first 类加载器加载插件类。
 *
 * <h2>保留的现有资产</h2>
 * <p>本类复用现行系统已验证正确的三件事：</p>
 * <ol>
 *   <li><b>{@code PluginClassLoader} 的 child-first 策略</b>——原样沿用。
 *       父加载器取<b>工厂类自己的加载器</b>（fat jar 下为
 *       {@code LaunchedClassLoader}），这是 2026-08 P0 事故的修复成果，
 *       不能回退成 TCCL（见 {@code Notes/技术报告-插件重载静默失败.md}）。</li>
 *   <li><b>扫描阶段加载 jar 内全部类</b>——因此运行期不存在懒加载，
 *       且可以在 Windows 上关闭 jar 文件句柄以支持覆盖更新。</li>
 *   <li><b>元数据从 jar 父目录读取</b>（而非 jar 内）——运维可直接改配置而
 *       无需重新打包。</li>
 * </ol>
 *
 * <h2>与现行系统的差异</h2>
 * <table border="1">
 *   <caption>插件装配的差异</caption>
 *   <tr><th>环节</th><th>现行 {@code PluginLoadProcessor}</th><th>本类</th></tr>
 *   <tr><td>插件识别</td><td>类名等于 {@code metadata.json} 里的 {@code id}</td>
 *       <td>类实现 {@link YuniPlugin} 即被发现；元数据由<b>代码</b>提供</td></tr>
 *   <tr><td>实例化</td><td>反射无参构造</td><td>同</td></tr>
 *   <tr><td>类型判定</td><td>按 8 种基类分派</td><td><b>不存在</b>——统一为 {@code apply}</td></tr>
 *   <tr><td>元数据来源</td><td>json 与代码两处（必然漂移）</td><td>代码是唯一真相</td></tr>
 * </table>
 *
 * <h2>关于模块元数据</h2>
 * <p>{@code metadata.json} 只保留<b>模块级</b>信息：{@code module_id}、
 * {@code module_name}，以及可选的 {@code plugin_classes}（显式列出本模块的插件类，
 * 不填则全量扫描）。插件级的名称、版本、描述、作者由 {@code YuniPlugin.getMetadata()}
 * 提供——避免同一份信息两处维护。</p>
 *
 * @see PluginClassLoader
 */
@Slf4j
@RequiredArgsConstructor
public final class PluginModuleLoader {

    /** JSON 映射器，用于解析模块元数据。 */
    private final ObjectMapper objectMapper;

    /** 插件运行时。 */
    private final PluginRuntime runtime;

    /* 构造器由 @RequiredArgsConstructor 生成：
     * (ObjectMapper objectMapper, PluginRuntime runtime)。 */

    /**
     * 模块元数据模型。
     *
     * <p>只含模块级信息；插件级元数据由插件代码提供。</p>
     */
    @Data
    public static class ModuleMetadata {

        /** 模块 ID。 */
        private String moduleId;

        /** 模块显示名。 */
        private String moduleName;

        /**
         * 可选的插件类全限定名清单。
         *
         * <p>不填则扫描 jar 内全部实现 {@link YuniPlugin} 的类。显式填写的收益是
         * 加载更快（跳过无用类的加载），代价是新增插件时要同步维护。</p>
         */
        private List<String> pluginClasses;

        /*
         * getModuleId / setModuleId / getModuleName / setModuleName /
         * setPluginClasses 由 @Data 生成。
         *
         * getPluginClasses 不能生成——它带 null 兜底（未配置时返回空列表，
         * 避免调用方到处判空），因此保留下面这个手工方法，Lombok 会跳过同名生成。
         */

        /**
         * 取插件类清单。
         *
         * @return 插件类全限定名列表；未配置时为空
         */
        public List<String> getPluginClasses() {
            return pluginClasses == null ? List.of() : pluginClasses;
        }

        /** Jackson 反序列化需要无参构造；显式声明以消歧。 */
        public ModuleMetadata() {
            // 由 Jackson 填充字段
        }
    }

    /**
     * 收集插件目录下全部 jar 文件。
     *
     * @param directory 插件目录
     * @return jar 文件列表；目录不存在时为空列表
     */
    public List<File> collectJarFiles(String directory) {
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
     * 加载一个插件模块（对应一个 jar）。
     *
     * <p>流程：读模块元数据 → 建 child-first 类加载器 → 扫描插件类 → 逐个
     * 实例化并交给运行时加载。任一插件失败不影响同模块的其他插件。</p>
     *
     * @param jarFile    jar 文件
     * @param moduleDir  模块资源目录（jar 的父目录）
     * @return 本模块成功建成的 Fiber 列表
     */
    public List<PluginFiber> loadModule(File jarFile, Path moduleDir) {
        List<PluginFiber> loaded = new ArrayList<>();
        ModuleMetadata metadata = readModuleMetadata(moduleDir, jarFile.getName());
        String moduleId = metadata.getModuleId();

        try (PluginClassLoader classLoader = PluginClassLoaderFactory.create(jarFile)) {
            List<Class<?>> pluginClasses = discoverPluginClasses(jarFile, classLoader, metadata);
            if (pluginClasses.isEmpty()) {
                log.warn("模块 {} 未发现任何插件类（jar: {}）", moduleId, jarFile.getName());
                return loaded;
            }

            for (Class<?> pluginClass : pluginClasses) {
                YuniPlugin instance = instantiate(pluginClass);
                if (instance == null) {
                    continue;
                }
                try {
                    PluginFiber fiber = runtime.load(instance, moduleId, moduleDir, classLoader);
                    loaded.add(fiber);
                } catch (Throwable t) {
                    // 必须捕获 Throwable：类加载失败抛的是 Error。
                    // 只 catch Exception 会让插件静默消失（2026-08 P0 事故的教训）
                    log.error("加载插件 {} 失败", pluginClass.getName(), t);
                }
            }

            // 登记模块句柄，供管理端分组展示与热重载定位 jar
            List<String> pluginIds = loaded.stream().map(PluginFiber::getId).toList();
            runtime.getFibers().addModule(new FiberRegistry.ModuleHandle(
                    moduleId,
                    metadata.getModuleName(),
                    jarFile.getName(),
                    moduleDir == null ? null : moduleDir.toString(),
                    classLoader,
                    pluginIds));

            log.info("模块 {} 加载完成，共 {} 个插件", moduleId, loaded.size());
        } catch (Throwable t) {
            log.error("加载插件模块失败: {}", jarFile.getName(), t);
        }
        return loaded;
    }

    /**
     * 扫描 jar 内实现 {@link YuniPlugin} 的具体类。
     *
     * @param jarFile     jar 文件
     * @param classLoader 类加载器
     * @param metadata    模块元数据（可能显式列出插件类）
     * @return 插件类列表
     * @throws IOException 读取 jar 失败
     */
    private List<Class<?>> discoverPluginClasses(File jarFile,
                                                  PluginClassLoader classLoader,
                                                  ModuleMetadata metadata) throws IOException {
        List<String> declared = metadata.getPluginClasses();

        // 显式声明了插件类：只加载这些，跳过全量扫描（更快）
        if (!declared.isEmpty()) {
            List<Class<?>> classes = new ArrayList<>(declared.size());
            for (String className : declared) {
                try {
                    Class<?> clazz = classLoader.loadClass(className);
                    if (isConcretePlugin(clazz)) {
                        classes.add(clazz);
                    } else {
                        log.warn("元数据声明的类不是具体插件实现: {}", className);
                    }
                } catch (Throwable t) {
                    log.error("加载元数据声明的插件类失败: {}", className, t);
                }
            }
            return classes;
        }

        // 否则遍历 jar 内全部 class 条目
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
                    // 单个类加载失败（缺依赖等）不应中断整模块扫描
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
     * 读取模块元数据。
     *
     * <p>{@code metadata.json} 位于 jar 的<b>父目录</b>。缺失时不视为错误——
     * 模块级信息是可选的组织信息，插件本身仍可加载（此时用 jar 文件名作模块 ID）。</p>
     *
     * @param moduleDir    模块目录
     * @param jarFileName  jar 文件名（用作兜底模块 ID）
     * @return 模块元数据，永不为 {@code null}
     */
    private ModuleMetadata readModuleMetadata(Path moduleDir, String jarFileName) {
        ModuleMetadata fallback = new ModuleMetadata();
        fallback.setModuleId(stripExtension(jarFileName));
        fallback.setModuleName(stripExtension(jarFileName));

        if (moduleDir == null) {
            return fallback;
        }
        Path metadataFile = moduleDir.resolve("metadata.json");
        if (!Files.isRegularFile(metadataFile)) {
            log.warn("模块目录缺少 metadata.json，使用 jar 文件名作为模块标识: {}", moduleDir);
            return fallback;
        }
        try {
            ModuleMetadata metadata = objectMapper.readValue(metadataFile.toFile(), ModuleMetadata.class);
            if (metadata == null) {
                return fallback;
            }
            if (metadata.getModuleId() == null || metadata.getModuleId().isBlank()) {
                metadata.setModuleId(fallback.getModuleId());
            }
            if (metadata.getModuleName() == null || metadata.getModuleName().isBlank()) {
                metadata.setModuleName(metadata.getModuleId());
            }
            return metadata;
        } catch (Exception e) {
            log.error("解析模块元数据失败，使用兜底标识: {}", metadataFile, e);
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
