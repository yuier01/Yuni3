package com.yuier.yuni.plugin.runtime;

import com.yuier.yuni.core.task.DynamicTaskManager;
import com.yuier.yuni.plugin.api.PluginMetadata;
import com.yuier.yuni.plugin.api.YuniContext;
import com.yuier.yuni.plugin.api.YuniPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 调度类 Effect 的单元测试——覆盖 {@code ctx.schedule} / {@code ctx.every} /
 * {@code ctx.runNow} 的注册与<b>卸载时自动取消</b>。
 *
 * <p>这里使用真实的 {@link ThreadPoolTaskScheduler} 与真实的
 * {@link DynamicTaskManager}（它们的依赖只是一个 {@code TaskScheduler}），
 * 因此测的是端到端的调度行为，而不是打桩替代。</p>
 *
 * <p>核心断言是设计文档 §1.6 想解决的问题：<b>插件不需要自己写取消代码</b>。
 * 现行系统里定时任务由 {@code PluginReloadProcessor} 手工调用
 * {@code dynamicTaskManager.cancelTask(fullId)} 取消；新系统中
 * 卸载 Fiber 即自动取消。</p>
 */
class PluginSchedulerTest {

    private ThreadPoolTaskScheduler scheduler;
    private DynamicTaskManager taskManager;
    private PluginRuntime runtime;

    @BeforeEach
    void setUp() {
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("yuni-test-scheduler-");
        scheduler.initialize();

        taskManager = new DynamicTaskManager();
        // 字段注入在测试中手工完成——生产环境由 Spring 注入
        setField(taskManager, "taskScheduler", scheduler);

        runtime = new PluginRuntime(taskManager);
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    /**
     * 通过反射给 {@link DynamicTaskManager} 的私有 {@code taskScheduler} 字段赋值。
     *
     * @param target 目标对象
     * @param name   字段名
     * @param value  字段值
     */
    private static void setField(Object target, String name, Object value) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法注入测试依赖: " + name, e);
        }
    }

    @Test
    @DisplayName("ctx.schedule 注册 cron 任务，卸载插件时任务自动取消")
    void scheduleRegistersAndUnloadCancels() throws Exception {
        AtomicInteger ticks = new AtomicInteger();
        CountDownLatch firstTick = new CountDownLatch(1);

        // 每秒触发一次，便于在测试窗口内观察到执行
        YuniPlugin plugin = YuniPlugin.of("定时插件", ctx ->
                ctx.schedule("*/1 * * * * ?", () -> {
                    ticks.incrementAndGet();
                    firstTick.countDown();
                }));

        PluginFiber fiber = runtime.load(plugin, null, null, null);

        assertEquals(FiberState.ACTIVE, fiber.state());
        // 定时任务也是一个 Effect，因此会被登记
        assertEquals(1, fiber.effectCount());
        assertEquals(1, taskManager.taskCount());

        assertTrue(firstTick.await(5, TimeUnit.SECONDS), "任务应至少执行一次");

        runtime.unload(fiber.id());

        // 卸载即取消：不需要插件作者写 deleteJob 之类的清理代码
        assertEquals(0, taskManager.taskCount(), "卸载后定时任务必须被取消");
        assertEquals(0, fiber.effectCount());

        // 再等一段时间，确认任务确实不再触发
        int ticksAfterUnload = ticks.get();
        Thread.sleep(1500);
        assertEquals(ticksAfterUnload, ticks.get(), "卸载后任务不应再执行");
    }

    @Test
    @DisplayName("一个插件可注册多个定时任务（现行系统用 pluginFullId 作 ID 时做不到）")
    void multipleSchedulesPerPlugin() {
        YuniPlugin plugin = YuniPlugin.of("多任务插件", ctx -> {
            ctx.schedule("0 0 8 * * ?", () -> { });
            ctx.schedule("0 30 8 * * ?", () -> { });
            ctx.schedule("0 0 12 * * ?", () -> { });
        });

        PluginFiber fiber = runtime.load(plugin, null, null, null);

        assertEquals(FiberState.ACTIVE, fiber.state());
        assertEquals(3, fiber.effectCount());
        assertEquals(3, taskManager.taskCount(), "三个独立任务都应注册成功");
    }

    @Test
    @DisplayName("ctx.every 按间隔折算为 cron，亚秒级间隔被明确拒绝")
    void everyConvertsIntervalAndRejectsSubSecond() {
        YuniPlugin plugin = YuniPlugin.of("周期插件", ctx ->
                ctx.every(Duration.ofSeconds(30), () -> { }));

        PluginFiber fiber = runtime.load(plugin, null, null, null);
        assertEquals(FiberState.ACTIVE, fiber.state());
        assertEquals(1, taskManager.taskCount());

        // 亚秒级无法用 cron 表达：明确报错而不是静默降级成 1 秒
        YuniPlugin tooFast = YuniPlugin.of("过快插件", ctx ->
                ctx.every(Duration.ofMillis(200), () -> { }));

        PluginFiber fastFiber = runtime.load(tooFast, null, null, null);
        assertEquals(FiberState.FAILED, fastFiber.state());
        assertTrue(fastFiber.failureCause().orElseThrow().getMessage().contains("至少为 1 秒"),
                fastFiber.failureCause().orElseThrow().getMessage());
    }

    @Test
    @DisplayName("非法 cron 表达式使插件进入 FAILED，且不留下已注册的任务")
    void invalidCronFailsPlugin() {
        YuniPlugin plugin = YuniPlugin.of("坏 cron 插件", ctx -> {
            ctx.schedule("这不是 cron", () -> { });
        });

        PluginFiber fiber = runtime.load(plugin, null, null, null);

        assertEquals(FiberState.FAILED, fiber.state());
        assertEquals(0, taskManager.taskCount(), "注册失败的任务不应残留");
        assertEquals(0, fiber.effectCount());
    }

    @Test
    @DisplayName("ctx.runNow 异步执行一次，对应现行 ImmediatePlugin 的全部语义")
    void runNowExecutesOnceAsynchronously() throws Exception {
        CountDownLatch ran = new CountDownLatch(1);
        AtomicInteger count = new AtomicInteger();

        YuniPlugin plugin = YuniPlugin.of("即时插件", ctx ->
                ctx.runNow(() -> {
                    count.incrementAndGet();
                    ran.countDown();
                }));

        PluginFiber fiber = runtime.load(plugin, null, null, null);

        assertEquals(FiberState.ACTIVE, fiber.state());
        assertTrue(ran.await(5, TimeUnit.SECONDS), "runNow 任务应被执行");
        assertEquals(1, count.get());

        // 等一会确认只执行一次（不是周期任务）
        Thread.sleep(300);
        assertEquals(1, count.get(), "runNow 应只执行一次");
        // 任务已提交，没有可撤销的资源
        assertEquals(0, fiber.effectCount());
    }

    @Test
    @DisplayName("runNow 任务抛异常被兜住，不影响插件状态")
    void runNowSwallowsThrowable() throws Exception {
        CountDownLatch attempted = new CountDownLatch(1);

        YuniPlugin plugin = YuniPlugin.of("抛异常的即时插件", ctx ->
                ctx.runNow(() -> {
                    attempted.countDown();
                    // 模拟类加载失败之类的 Error
                    throw new NoClassDefFoundError("simulated");
                }));

        PluginFiber fiber = runtime.load(plugin, null, null, null);

        assertTrue(attempted.await(5, TimeUnit.SECONDS));
        // 异步任务的异常不得影响插件本身的加载结果
        assertEquals(FiberState.ACTIVE, fiber.state());
    }

    @Test
    @DisplayName("未注入任务管理器时，调度 API 给出明确错误而非 NPE")
    void scheduleWithoutTaskManagerFailsClearly() {
        PluginRuntime noScheduler = new PluginRuntime(null);

        YuniPlugin plugin = new YuniPlugin() {
            @Override
            public PluginMetadata metadata() {
                return PluginMetadata.of("无调度器").build();
            }

            @Override
            public void apply(YuniContext ctx) {
                ctx.schedule("0 0 8 * * ?", () -> { });
            }
        };

        PluginFiber fiber = noScheduler.load(plugin, null, null, null);

        assertEquals(FiberState.FAILED, fiber.state());
        assertTrue(fiber.failureCause().orElseThrow().getMessage().contains("未注入"),
                fiber.failureCause().orElseThrow().getMessage());
    }

    @Test
    @DisplayName("卸载全部插件后调度任务表清空")
    void unloadAllClearsScheduledTasks() {
        for (int i = 0; i < 4; i++) {
            final int n = i;
            runtime.load(YuniPlugin.of("定时插件" + n, ctx ->
                    ctx.schedule("0 0 " + (n + 1) + " * * ?", () -> { })), "m" + n, null, null);
        }
        assertEquals(4, taskManager.taskCount());

        List<String> failures = runtime.unloadAll();

        assertTrue(failures.isEmpty(), failures.toString());
        assertEquals(0, taskManager.taskCount(), "全量卸载后调度任务必须全部取消");
    }
}
