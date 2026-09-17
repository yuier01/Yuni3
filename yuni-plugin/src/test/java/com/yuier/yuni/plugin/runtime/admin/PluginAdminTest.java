package com.yuier.yuni.plugin.runtime.admin;

import com.yuier.yuni.plugin.api.PluginAdmin;
import com.yuier.yuni.plugin.api.PluginAdminException;
import com.yuier.yuni.plugin.api.PluginMetadata;
import com.yuier.yuni.plugin.api.PluginModuleView;
import com.yuier.yuni.plugin.api.PluginState;
import com.yuier.yuni.plugin.api.PluginView;
import com.yuier.yuni.plugin.api.ScopeFilter;
import com.yuier.yuni.plugin.api.YuniContext;
import com.yuier.yuni.plugin.api.YuniPlugin;
import com.yuier.yuni.plugin.runtime.PluginFiber;
import com.yuier.yuni.plugin.runtime.PluginRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PluginAdmin} 的单元测试。
 *
 * <h2>本测试验证的核心主张</h2>
 * <p>设计文档 §14.5 声称内置插件 {@code plugin-manage} 迁移后
 * 「{@code PluginEnable} 的旧判定逻辑整体消失」。这个主张成立的前提是
 * {@code PluginAdmin} 把那些逻辑<b>收拢到了一处并做对</b>。旧
 * {@code PluginEnable} 里有三件事，本测试逐条覆盖：</p>
 * <ol>
 *   <li><b>越界判定</b>（{@code pluginSeq > container.getPluginCount()}）——
 *       新系统用插件标识定位，不存在「越界」这个概念，改为
 *       「插件不存在」；</li>
 *   <li><b>内置插件保护</b>（{@code isBuiltIn() && permission < MASTER}）——
 *       收敛为 {@link PluginAdmin#setEnabled} 的 {@code callerIsMaster} 参数；</li>
 *   <li><b>启停本身</b>——收敛为运行时的作用域过滤器写入。</li>
 * </ol>
 *
 * <h2>不测什么</h2>
 * <p>不测渲染（Playwright 截屏）与 jar 热重载——前者依赖浏览器二进制，
 * 后者依赖真实 jar 文件。热重载的「找不到 jar」分支会测，因为那是
 * 用户最容易遇到的失败（jar 删了但进程还在跑）。</p>
 */
class PluginAdminTest {

    private PluginRuntime runtime;
    private PluginAdmin admin;

    @BeforeEach
    void setUp() {
        runtime = new PluginRuntime(null, null);
        admin = runtime.admin();
    }

    /**
     * 加载一个测试插件。
     *
     * <p>注意插件标识的格式是 {@code 模块ID/插件显示名}（见
     * {@code PluginRuntime#buildPluginId}），因此本方法的两个参数都要与
     * 期望的标识对上——这本身就是一条值得固化下来的约定：
     * <b>标识来自元数据的名字，不是类名</b>。</p>
     *
     * @param moduleId      模块 ID
     * @param pluginName    插件显示名
     * @param buildIn       是否内置
     * @param defaultEnable 元数据默认启用值
     * @param filter        作用域过滤器；可为 {@code null}
     * @return 建好的 Fiber
     */
    private PluginFiber load(String moduleId,
                             String pluginName,
                             boolean buildIn,
                             boolean defaultEnable,
                             ScopeFilter filter) {
        YuniPlugin plugin = new YuniPlugin() {
            @Override
            public PluginMetadata metadata() {
                return PluginMetadata.of(pluginName)
                        .description(pluginName + " 的描述")
                        .author("测试")
                        .version("1.2.3")
                        .tip("提示一")
                        .buildIn(buildIn)
                        .defaultEnable(defaultEnable)
                        .build();
            }

            @Override
            public void apply(YuniContext ctx) {
                // 无能力：本测试只关心管理视图
            }
        };
        return runtime.load(plugin, moduleId, null, null, null, filter);
    }

    /**
     * 加载一个 {@code apply} 会抛异常的插件，用于验证失败可观测性。
     *
     * @param moduleId   模块 ID
     * @param pluginName 插件显示名
     * @param message    异常消息
     */
    private void loadFailing(String moduleId, String pluginName, String message) {
        runtime.load(new YuniPlugin() {
            @Override
            public PluginMetadata metadata() {
                return PluginMetadata.of(pluginName).build();
            }

            @Override
            public void apply(YuniContext ctx) {
                throw new IllegalStateException(message);
            }
        }, moduleId, null, null, null, null);
    }

    // ==================== 查询 ====================

    @Test
    @DisplayName("modules/plugins 把 Fiber 投影为公共视图，元数据字段平铺且完整")
    void projectsMetadataIntoViews() {
        load("fortune", "Fortune", false, true, ScopeFilter.ALL);

        List<PluginModuleView> modules = admin.modules();
        assertEquals(1, modules.size());
        assertEquals("fortune", modules.get(0).id());

        List<PluginView> plugins = admin.plugins();
        assertEquals(1, plugins.size());
        PluginView view = plugins.get(0);
        assertEquals("fortune/Fortune", view.id());
        assertEquals("fortune", view.moduleId());
        assertEquals("Fortune 的描述", view.description());
        assertEquals("测试", view.author());
        assertEquals("1.2.3", view.version());
        assertEquals(List.of("提示一"), view.tips());
        assertEquals(PluginState.ACTIVE, view.state());
        assertTrue(view.defaultEnable());
        assertFalse(view.buildIn());
        // ACTIVE 的插件没有失败原因与缺失依赖
        assertTrue(view.failureSummary().isEmpty());
        assertTrue(view.missingServices().isEmpty());
    }

    @Test
    @DisplayName("apply 抛异常时视图给出 FAILED 与失败摘要——取代旧系统「插件静默消失」")
    void failedPluginIsVisible() {
        loadFailing("bad", "Bad", "初始化时炸了");

        PluginView view = admin.plugin("bad/Bad").orElseThrow();
        assertEquals(PluginState.FAILED, view.state());
        assertTrue(view.failureSummary().orElseThrow().contains("初始化时炸了"),
                "失败摘要应包含异常消息，实际: " + view.failureSummary());
    }

    @Test
    @DisplayName("声明了未满足的依赖时视图给出 PENDING 与缺失服务名")
    void pendingPluginReportsMissingService() {
        runtime.load(new YuniPlugin() {
            @Override
            public PluginMetadata metadata() {
                return PluginMetadata.of("Needs").build();
            }

            @Override
            public List<Class<?>> inject() {
                return List.of(CharSequence.class);
            }

            @Override
            public void apply(YuniContext ctx) {
                throw new AssertionError("依赖未满足时不应调用 apply");
            }
        }, "pending", null, null, null, null);

        PluginView view = admin.plugin("pending/Needs").orElseThrow();
        assertEquals(PluginState.PENDING, view.state());
        assertEquals(List.of("java.lang.CharSequence"), view.missingServices());
    }

    @Test
    @DisplayName("查不存在的插件返回空，不抛异常")
    void unknownPluginIsEmpty() {
        assertTrue(admin.plugin("nope/Nope").isEmpty());
        // 「插件在不在某个群生效」也是一个可以被问出的普通问题，答案是否
        assertFalse(admin.isEnabledIn("nope/Nope", 100L));
    }

    // ==================== 群粒度启停 ====================

    @Test
    @DisplayName("setEnabled 关闭某群后 isEnabledIn 返回 false，重开又回到 true")
    void setEnabledIsReversible() {
        load("p", "P", false, true, ScopeFilter.ALL);
        assertTrue(admin.isEnabledIn("p/P", 100L));

        admin.setEnabled("p/P", 100L, false, false);
        assertFalse(admin.isEnabledIn("p/P", 100L));
        // 其他群不受影响
        assertTrue(admin.isEnabledIn("p/P", 200L));
        assertEquals(List.of(100L), admin.enabledGroups("p/P"));

        admin.setEnabled("p/P", 100L, true, false);
        assertTrue(admin.isEnabledIn("p/P", 100L));
        assertTrue(admin.enabledGroups("p/P").isEmpty(),
                "关闭再开启同一群应当回到「无例外」状态");
    }

    @Test
    @DisplayName("默认关闭的插件可以逐个群开启")
    void enableByIdForDefaultDisabledPlugin() {
        load("p", "P", false, false, null);

        // 无过滤器记录 → 回落到 defaultEnable(false)
        assertFalse(admin.isEnabledIn("p/P", 100L));

        admin.setEnabled("p/P", 100L, true, false);
        assertTrue(admin.isEnabledIn("p/P", 100L));
        assertFalse(admin.isEnabledIn("p/P", 200L));
        assertEquals(List.of(100L), admin.enabledGroups("p/P"));
    }

    @Test
    @DisplayName("resetScope 清空全部群例外，回到 defaultEnable")
    void resetScopeRestoresDefault() {
        load("p", "P", false, true, ScopeFilter.ALL);
        admin.setEnabled("p/P", 100L, false, false);
        assertFalse(admin.isEnabledIn("p/P", 100L));

        admin.resetScope("p/P");
        assertTrue(admin.isEnabledIn("p/P", 100L));
        assertTrue(admin.enabledGroups("p/P").isEmpty());
    }

    @Test
    @DisplayName("内置插件只有 bot 拥有者能启停——旧 PluginEnable 的判定逻辑收拢到这里")
    void builtInPluginRequiresMaster() {
        load("help", "HelpInfo", true, true, ScopeFilter.ALL);

        PluginAdminException e = assertThrows(PluginAdminException.class,
                () -> admin.setEnabled("help/HelpInfo", 100L, false, false));
        assertTrue(e.getMessage().contains("内置插件"), "异常消息应说明原因: " + e.getMessage());
        // 被拒绝后状态未变
        assertTrue(admin.isEnabledIn("help/HelpInfo", 100L));

        // 拥有者可以操作
        admin.setEnabled("help/HelpInfo", 100L, false, true);
        assertFalse(admin.isEnabledIn("help/HelpInfo", 100L));
    }

    @Test
    @DisplayName("操作不存在的插件抛 PluginAdminException，且带上插件标识")
    void setEnabledOnUnknownPluginFails() {
        PluginAdminException e = assertThrows(PluginAdminException.class,
                () -> admin.setEnabled("nope/Nope", 100L, true, true));
        assertEquals("nope/Nope", e.pluginId());
    }

    // ==================== 卸载与重载 ====================

    @Test
    @DisplayName("内置插件不能单独卸载——卸载后没有任何入口能把它装回来")
    void builtInPluginCannotBeUnloaded() {
        load("help", "HelpInfo", true, true, ScopeFilter.ALL);

        PluginAdminException e = assertThrows(PluginAdminException.class,
                () -> admin.unload("help/HelpInfo"));
        assertTrue(e.getMessage().contains("重载"), "应指引用户改用重载: " + e.getMessage());
        // 仍在使用中
        assertTrue(admin.plugin("help/HelpInfo").isPresent());
    }

    @Test
    @DisplayName("普通插件可以被卸载，卸载后从视图中消失")
    void pluginCanBeUnloaded() {
        load("p", "P", false, true, ScopeFilter.ALL);
        assertNotNull(admin.plugin("p/P").orElse(null));

        admin.unload("p/P");

        assertTrue(admin.plugin("p/P").isEmpty());
        assertFalse(admin.isEnabledIn("p/P", 100L));
    }

    @Test
    @DisplayName("重载不存在的模块抛 PluginAdminException，消息可读")
    void reloadUnknownModuleFails() {
        PluginAdminException e = assertThrows(PluginAdminException.class,
                () -> admin.reload("nope"));
        assertTrue(e.getMessage().contains("nope"), "消息应含模块 ID: " + e.getMessage());
    }

    @Test
    @DisplayName("重载已存在的模块但缺少 jar 时抛可读错误，而不是 NPE")
    void reloadModuleWithoutJarFailsReadably() {
        load("p", "P", false, true, ScopeFilter.ALL);

        PluginAdminException e = assertThrows(PluginAdminException.class,
                () -> admin.reload("p"));
        // 要么报「未装配加载器」，要么报「找不到 jar」——都不应是一个裸 NPE
        assertNotNull(e.getMessage());
        assertFalse(e.getMessage().isBlank());
    }

    // ==================== 汇总 ====================

    @Test
    @DisplayName("summary 与 countByState 给出管理端看板需要的数字")
    void summaryAndCounts() {
        load("a", "A", false, true, ScopeFilter.ALL);
        load("b", "B", false, true, ScopeFilter.ALL);
        loadFailing("c", "C", "boom");

        String summary = admin.summary();
        assertTrue(summary.contains("模块 3"), summary);
        assertTrue(summary.contains("插件 3"), summary);
        assertTrue(summary.contains("运行中 2"), summary);
        assertTrue(summary.contains("失败 1"), summary);

        var counts = ((PluginAdminRuntime) admin).countByState();
        assertEquals(2, counts.get(PluginState.ACTIVE));
        assertEquals(1, counts.get(PluginState.FAILED));
        // 全部状态都有键，消费方不需要处理缺键
        for (PluginState state : PluginState.values()) {
            assertTrue(counts.containsKey(state), "缺少状态键: " + state);
        }
    }

    @Test
    @DisplayName("视图是快照：卸载后已取出的视图仍可读，但状态不自动更新")
    void viewIsSnapshot() {
        load("p", "P", false, true, ScopeFilter.ALL);
        PluginView view = admin.plugin("p/P").orElseThrow();

        admin.unload("p/P");

        // 快照仍在，字段可读——这使管理端可以安全地缓存与跨线程传递
        assertEquals("p/P", view.id());
        assertEquals(PluginState.ACTIVE, view.state());
        // 但重新查询才是权威答案
        assertTrue(admin.plugin("p/P").isEmpty());
    }

    @Test
    @DisplayName("子插件不出现在管理视图中——它随父插件卸载，不是独立的运维对象")
    void childPluginsAreHidden() {
        YuniPlugin parent = new YuniPlugin() {
            @Override
            public PluginMetadata metadata() {
                return PluginMetadata.of("Parent").build();
            }

            @Override
            public void apply(YuniContext ctx) {
                ctx.plugin(YuniPlugin.of("子插件", c -> { }));
            }
        };
        runtime.load(parent, "m", null, null, null, null);

        List<PluginView> views = admin.plugins();
        assertEquals(1, views.size(), "只应看到父插件: " + views);
        assertEquals("Parent", views.get(0).name());
        // 但 Fiber 总数包含子插件
        assertEquals(2, runtime.fibers().pluginCount());
    }

    @Test
    @DisplayName("插件可按模块分组取出")
    void pluginsOfModule() {
        load("m1", "A", false, true, ScopeFilter.ALL);
        load("m2", "B", false, true, ScopeFilter.ALL);
        load("m2", "C", false, true, ScopeFilter.ALL);

        assertEquals(1, admin.pluginsOfModule("m1").size());
        assertEquals(2, admin.pluginsOfModule("m2").size());
        assertTrue(admin.pluginsOfModule("nope").isEmpty());
    }

    @Test
    @DisplayName("moduleIdOf 能反查插件所属模块，供视图补全 moduleId")
    void moduleIdOfPlugin() {
        load("steam", "MemberSteamStatus", false, true, ScopeFilter.ALL);
        Optional<String> moduleId = runtime.moduleIdOf("steam/MemberSteamStatus");
        assertTrue(moduleId.isPresent());
        assertEquals("steam", moduleId.get());
    }
}
