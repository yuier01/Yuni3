package com.yuier.yuni.engine.manager.init;

import com.yuier.yuni.contact.manage.YuniContactManager;
import com.yuier.yuni.core.model.bot.BotApp;
import com.yuier.yuni.event.init.EventManager;
import com.yuier.yuni.permission.manage.UserPermissionManager;
import com.yuier.yuni.plugin.runtime.PluginFiber;
import com.yuier.yuni.plugin.runtime.PluginRuntime;
import com.yuier.yuni.plugin.runtime.config.PluginSystemAssembler;
import com.yuier.yuni.plugin.runtime.config.PluginSystemConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 系统启动初始化进程。
 *
 * <h2>P6 迁移说明：插件系统的初始化入口换了</h2>
 * <p>旧实现调 {@code pluginManager.initializePlugins()}——那是旧插件系统的
 * 加载入口，它会反射扫描 jar、按八种基类分派、把实例塞进
 * {@code PluginContainer} 的八条类型列表。</p>
 *
 * <p>新实现调 {@link PluginSystemConfig.PluginBootstrap#initialize()}——
 * 它是新系统的加载入口：读 {@code metadata.json}、用 child-first 类加载器
 * 扫描实现 {@code YuniPlugin} 的类、逐个 {@code apply(ctx)}。</p>
 *
 * <h2>为什么这一步在 {@code botManager.startBot()} 之后</h2>
 * <p>顺序与旧实现完全一致，且这个顺序是必要的：</p>
 * <ol>
 *   <li>插件在 {@code apply} 里通过 {@code ctx.getBot()} 拿机器人实例。
 *       {@code BotManager} 在 {@code startBot()} 里创建并注册它，
 *       因此插件加载必须在之后；</li>
 *   <li>插件在 {@code apply} 里通过 {@code ctx.getPermission()} 做权限判定，
 *       而权限系统在本方法更早处初始化；</li>
 *   <li>插件在 {@code apply} 里通过 {@code ctx.getDb()} 建表，
 *       数据库文件在更早处检查。</li>
 * </ol>
 *
 * <h2>依赖就绪的显式报告</h2>
 * <p>新系统的 {@code PluginBootstrap} 会在加载结束后报告两类异常：
 * 停在 {@code PENDING} 的插件（依赖未满足，含循环依赖）与
 * 加载失败的插件。本方法把它们升级为启动日志里的 warning——
 * 这正是现行系统完全缺失的可观测性：「我的插件为什么没反应」在旧系统里
 * 只能靠翻日志猜。</p>
 */
@Component
@Slf4j
public class SystemInitializeRunner implements ApplicationRunner {

    @Autowired
    private PluginSystemAssembler pluginSystemAssembler;
    @Autowired
    private ApplicationContext applicationContext;
    @Autowired
    private BotApp botApp;
    @Autowired
    private SystemInitializeProcessor systemInitializeProcessor;
    @Autowired
    private UserPermissionManager userPermissionManager;
    @Autowired
    private EventManager eventManager;
    @Autowired
    private BotManager botManager;
    @Autowired
    private YuniContactManager yuniContactManager;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        log.info("检查数据库文件...");
        systemInitializeProcessor.checkDatabaseFile();
        log.info("初始化权限系统...");
        userPermissionManager.initUserPermission();
        log.info("初始化事件系统...");
        eventManager.init();
        log.info("拉起适配器...");
        botManager.startBot();
        log.info("初始化联系人系统...");
        yuniContactManager.init();

        /*
         * 插件系统在这里才组装——而不是由容器在 refresh 期创建。
         *
         * 原因：运行时的流水线需要 bot 与权限服务，而 bot 刚刚才在
         * startBot() 里创建。若交给容器的 eager 单例，就只能靠 @Lazy 与
         * ObjectProvider 去对抗初始化顺序，正是「权限服务拿到 null bot」
         * 这类缺陷的来源。
         */
        log.info("初始化插件系统...");
        PluginSystemAssembler.AssembledPluginSystem pluginSystem =
                pluginSystemAssembler.assemble(botApp, botManager::getBot);
        registerPluginRuntime(pluginSystem.getRuntime());
        initializePlugins(pluginSystem.getBootstrap());

        /*
         * 启动完成后的诊断输出。
         *
         * 顺序刻意放在最后：此时全部模块都已加载、依赖推进已跑到不动点，
         * 因此「还有谁停在 PENDING」与「谁失败了」是确定的答案。
         *
         * 用 warn 而非 error：一个插件加载失败不应该让整个启动流程看起来失败，
         * 但它确实需要被人看到。
         */
        PluginRuntime runtime = pluginSystem.getRuntime();
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

        log.info("""
                Yuni 启动完毕 Ciallo～(∠・ω< )⌒★!""");
    }

    /**
     * 把组装好的插件运行时注册进容器。
     *
     * <p>手工组装的对象要让 {@code ObjectProvider<PluginRuntime>} 在后续能查得到，
     * 必须显式注册为单例——与 {@code BotManager} 注册 {@code yuniBot}
     * 是同一手法（{@code startBot()} 里 {@code registerSingleton("yuniBot", bot)}）。</p>
     *
     * @param runtime 插件运行时
     */
    private void registerPluginRuntime(PluginRuntime runtime) {
        ConfigurableListableBeanFactory beanFactory =
                ((org.springframework.context.ConfigurableApplicationContext) applicationContext)
                        .getBeanFactory();
        if (beanFactory.containsBean("pluginRuntime")) {
            log.warn("容器中已存在 pluginRuntime Bean，跳过注册（视为外部覆盖）");
            return;
        }
        beanFactory.registerSingleton("pluginRuntime", runtime);
    }

    /**
     * 扫描插件目录并加载全部插件模块。
     *
     * <p>失败时不让启动流程崩掉：插件是可选组件，一个 jar 坏掉不应该让 bot
     * 完全起不来。{@code PluginBootstrap} 内部已对每个模块单独捕获
     * {@code Throwable}（含类加载失败的 {@code Error}），因此这里只兜住
     * 「插件目录本身不可读」这类更外层的问题。</p>
     * @param bootstrap 已组装好的插件启动器
     */
    private void initializePlugins(PluginSystemConfig.PluginBootstrap bootstrap) {
        try {
            List<PluginFiber> loaded = bootstrap.initialize();
            log.info("插件加载完成，共 {} 个插件实例", loaded.size());
        } catch (Throwable t) {
            // 必须捕获 Throwable：类加载失败抛的是 Error（2026-08 P0 事故的教训）
            log.error("插件系统初始化失败，bot 将以无插件状态继续运行", t);
        }
    }
}
