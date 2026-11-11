package com.yuier.yuni.plugin.runtime.config;

import com.yuier.yuni.plugin.runtime.PluginFiber;
import com.yuier.yuni.plugin.runtime.PluginRuntime;
import com.yuier.yuni.plugin.runtime.load.PluginModuleLoader;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 插件系统启动器——负责扫描插件目录并加载全部模块。
 *
 * <h2>为什么与 {@link PluginRuntime} 分离</h2>
 * <p>运行时是长期存在的系统组件，而「扫描目录、加载全部模块」是
 * <b>一次性的启动动作</b>。分开后可以单独测试加载流程，
 * 也便于热重载时复用同一运行时。</p>
 *
 * <h2>调用时机</h2>
 * <p>本类由 {@link PluginSystemAssembler} 在机器人就绪后创建，
 * 随后由 {@link PluginSystemInitializer} 调用 {@link #initialize()}。
 * 它不是 Spring Bean——创建时机必须晚于容器 refresh。</p>
 */
@Slf4j
public final class PluginBootstrap {

    /** 模块加载器。 */
    private final PluginModuleLoader loader;

    /** 插件运行时。 */
    private final PluginRuntime runtime;

    /** 插件目录。 */
    private final String pluginDirectory;

    /**
     * 构造启动器。
     *
     * @param loader          模块加载器
     * @param runtime         插件运行时
     * @param pluginDirectory 插件目录
     */
    public PluginBootstrap(PluginModuleLoader loader, PluginRuntime runtime, String pluginDirectory) {
        this.loader = loader;
        this.runtime = runtime;
        this.pluginDirectory = pluginDirectory;
    }

    /**
     * 扫描插件目录并加载全部模块。
     *
     * @return 成功加载的插件 Fiber 列表
     */
    public List<PluginFiber> initialize() {
        List<File> jarFiles = loader.collectJarFiles(pluginDirectory);
        log.info("扫描插件目录 {}，发现 {} 个 jar 包", pluginDirectory, jarFiles.size());

        List<PluginFiber> all = new ArrayList<>();
        for (File jarFile : jarFiles) {
            Path moduleDir = jarFile.getParentFile() == null
                    ? null
                    : jarFile.getParentFile().toPath();
            all.addAll(loader.loadModule(jarFile, moduleDir));
        }

        // 依赖驱动的推进：某个插件提供的服务可能让先前停在 PENDING 的插件就绪。
        // 反复扫描到不动点，循环依赖会被留在 PENDING 并在下面报出。
        int activated = runtime.activatePending();
        if (activated > 0) {
            log.info("依赖推进使 {} 个插件从 PENDING 转为 ACTIVE", activated);
        }

        /*
         * 恢复持久化的群粒度启停配置。
         *
         * 必须在全部模块加载之后：判断「数据库里的一条记录算不算例外」
         * 需要该插件的 defaultEnable，而它只有插件实例化后才读得到。
         *
         * 恢复的配置会覆盖插件代码里声明的基线过滤器——持久化的是用户的
         * 显式选择，而基线是插件作者写的默认建议，用户选择应当胜出
         * （与旧系统一致：group_plugin_ability 优先于 metadata.defaultEnable）。
         */
        runtime.restoreScopes();

        log.info("插件系统启动完成: {}", runtime.describe());
        return all;
    }

    /**
     * 插件目录（供管理端展示）。
     *
     * @return 目录路径
     */
    public Path getPluginDirectory() {
        return Paths.get(pluginDirectory);
    }
}
