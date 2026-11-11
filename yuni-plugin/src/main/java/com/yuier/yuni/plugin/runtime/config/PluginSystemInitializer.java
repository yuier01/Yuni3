package com.yuier.yuni.plugin.runtime.config;

import com.yuier.yuni.core.bot.YuniBot;
import com.yuier.yuni.core.model.bot.BotApp;
import com.yuier.yuni.plugin.runtime.PluginFiber;
import com.yuier.yuni.plugin.runtime.PluginRuntime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;

/**
 * 插件子系统的初始化入口。
 *
 * <h2>为什么需要这一层</h2>
 * <p>{@code SystemInitializeRunner} 应当只负责「按顺序调用各子系统的初始化方法」，
 * 而不该知道插件系统内部的组装顺序。本类把那段过程收在子系统内部：</p>
 * <ol>
 *   <li>用 {@link PluginSystemAssembler} 组装运行时 / 加载器 / 启动器；</li>
 *   <li>把运行时注册进容器（供 webapi 与事件分发按需解析）；</li>
 *   <li>调 {@link PluginBootstrap#initialize()} 扫描并加载插件；</li>
 *   <li>输出 PENDING / FAILED 诊断。</li>
 * </ol>
 *
 * <p>这样启动器里只剩一行
 * {@code pluginSystemInitializer.initialize(botManager::getBot)}，
 * 与 {@code userPermissionManager.initUserPermission()}、
 * {@code eventManager.init()} 等保持一致的粒度。</p>
 */
@Slf4j
@Component
public final class PluginSystemInitializer {

    /** 核心组件组装器。 */
    private final PluginSystemAssembler assembler;

    /** 用于把组装好的运行时注册为单例。 */
    private final ConfigurableApplicationContext applicationContext;

    /** 机器人业务配置（提供命令前缀）。 */
    private final BotApp botApp;

    /**
     * 构造初始化入口。
     *
     * @param assembler          核心组件组装器
     * @param applicationContext 容器上下文（注册运行时单例用）
     * @param botApp             机器人业务配置
     */
    public PluginSystemInitializer(PluginSystemAssembler assembler,
                                   ConfigurableApplicationContext applicationContext,
                                   BotApp botApp) {
        this.assembler = assembler;
        this.applicationContext = applicationContext;
        this.botApp = botApp;
    }

    /**
     * 初始化插件系统。
     *
     * <p>必须在 {@code BotManager.startBot()} 之后调用：组装出的流水线需要
     * 已就绪的机器人实例。</p>
     *
     * @param botSupplier 机器人实例供应函数
     */
    public void initialize(Supplier<YuniBot> botSupplier) {
        PluginSystemAssembler.AssembledPluginSystem system =
                assembler.assemble(botApp, botSupplier);

        registerRuntime(system.getRuntime());
        loadModules(system.getBootstrap());
        report(system.getRuntime());
    }

    /**
     * 把组装好的运行时注册进容器。
     *
     * <p>手工创建的对象要让 {@code ObjectProvider<PluginRuntime>} 在后续查得到，
     * 必须显式注册为单例——与 {@code BotManager} 注册 {@code yuniBot} 是同一手法。</p>
     *
     * @param runtime 插件运行时
     */
    private void registerRuntime(PluginRuntime runtime) {
        ConfigurableListableBeanFactory beanFactory = applicationContext.getBeanFactory();
        if (beanFactory.containsBean("pluginRuntime")) {
            log.warn("容器中已存在 pluginRuntime Bean，跳过注册（视为外部覆盖）");
            return;
        }
        beanFactory.registerSingleton("pluginRuntime", runtime);
    }

    /**
     * 扫描并加载插件目录下的全部模块。
     *
     * <p>失败时不让启动流程崩掉：插件是可选组件，一个 jar 坏掉不应该让 bot
     * 完全起不来。逐模块的异常已由 {@link PluginBootstrap} 内部捕获，
     * 这里只兜住「插件目录本身不可读」这类更外层的问题。</p>
     *
     * @param bootstrap 插件启动器
     */
    private void loadModules(PluginBootstrap bootstrap) {
        try {
            List<PluginFiber> loaded = bootstrap.initialize();
            log.info("插件加载完成，共 {} 个插件实例", loaded.size());
        } catch (Throwable t) {
            // 必须捕获 Throwable：类加载失败抛的是 Error（2026-08 P0 事故的教训）
            log.error("插件系统初始化失败，bot 将以无插件状态继续运行", t);
        }
    }

    /**
     * 输出插件健康度诊断。
     *
     * <p>此时全部模块已加载、依赖推进已跑到不动点，因此「还有谁停在 PENDING」
     * 与「谁失败了」是确定的答案。用 warn 而非 error：单个插件失败不应让整个
     * 启动流程看起来失败，但它需要被人看到。</p>
     *
     * @param runtime 插件运行时
     */
    private void report(PluginRuntime runtime) {
        List<String> pending = runtime.reportPendingPlugins();
        if (!pending.isEmpty()) {
            log.warn("有 {} 个插件因依赖未满足而未加载（可能是循环依赖或配置缺失）：", pending.size());
            pending.forEach(item -> log.warn("  - {}", item));
        }
        List<String> failed = runtime.reportFailedPlugins();
        if (!failed.isEmpty()) {
            log.warn("有 {} 个插件加载失败：", failed.size());
            failed.forEach(item -> log.warn("  - {}", item));
        }
        log.info("插件系统就绪：{}", runtime.describe());
    }
}
