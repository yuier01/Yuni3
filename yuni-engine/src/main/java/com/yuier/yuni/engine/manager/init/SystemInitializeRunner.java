package com.yuier.yuni.engine.manager.init;

import com.yuier.yuni.contact.manage.YuniContactManager;
import com.yuier.yuni.event.init.EventManager;
import com.yuier.yuni.permission.manage.UserPermissionManager;
import com.yuier.yuni.plugin.runtime.PluginSystem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 系统启动初始化进程。
 *
 * <h2>本类的职责边界：只编排，不实现</h2>
 * <p>它按顺序调用各子系统的初始化入口，<b>不关心任何一个子系统内部的
 * 装配与加载细节</b>。每个子系统对外只暴露一个简洁方法：</p>
 * <ul>
 *   <li>{@code systemInitializeProcessor.checkDatabaseFile()}</li>
 *   <li>{@code userPermissionManager.initUserPermission()}</li>
 *   <li>{@code eventManager.init()}</li>
 *   <li>{@code botManager.startBot()}</li>
 *   <li>{@code yuniContactManager.init()}</li>
 *   <li>{@code pluginSystem.initialize()}</li>
 * </ul>
 *
 * <h2>为什么插件系统排在最后</h2>
 * <p>这个顺序是必要的：</p>
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
 * <h2>插件子系统的内部流程</h2>
 * <p>组装运行时、注册单例、扫描插件目录、推进依赖、恢复作用域配置、
 * 报告 PENDING / FAILED——全部收在 {@link PluginSystem} 内部。
 * 本类只负责在正确的时机调它一次。</p>
 */
@Component
@Slf4j
public class SystemInitializeRunner implements ApplicationRunner {

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
    @Autowired
    private PluginSystem pluginSystem;

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
        log.info("初始化插件系统...");
        pluginSystem.initialize();

        log.info("""
                Yuni 启动完毕 Ciallo～(∠・ω< )⌒★!""");
    }
}
