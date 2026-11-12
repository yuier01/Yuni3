package com.yuier.yuni.plugin.runtime;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.yuier.yuni.core.bot.JsonCodec;
import com.yuier.yuni.core.event.YuniMessageEvent;
import com.yuier.yuni.core.model.bot.BotApp;
import com.yuier.yuni.core.model.message.MessageChain;
import com.yuier.yuni.core.model.user.MessageSender;
import com.yuier.yuni.core.task.DynamicTaskManager;
import com.yuier.yuni.core.util.PolymorphicRegistrationProcessor;
import com.yuier.yuni.plugin.api.PluginMetadata;
import com.yuier.yuni.plugin.api.YuniContext;
import com.yuier.yuni.plugin.api.YuniPlugin;
import com.yuier.yuni.plugin.runtime.adapter.RecordingYuniBot;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 插件生命周期端到端集成测试。
 *
 * <h2>它验证的设计主张</h2>
 * <p>本测试模拟「一个真实插件从加载到卸载的全过程」，用于验证设计文档里
 * 几条最核心的主张：</p>
 * <ol>
 *   <li><b>卸载即自动清理</b>：插件作者不需要写任何清理代码，
 *       卸载 Fiber 后监听器、定时任务、提供的服务全部消失（§3.3）；</li>
 *   <li><b>能力即注册</b>：一个插件同时贡献多种能力（命令 + 定时 + 事件 + 服务），
 *       不需要选择「插件类型」（§1.1）；</li>
 *   <li><b>可观测</b>：加载后能查到它贡献了哪些能力、有多少 PluginEffect（§15.1 的
 *       「类型从声明变推导」）。</li>
 * </ol>
 *
 * <p>本测试的插件刻意设计成「什么都做一点」，以覆盖多种 PluginEffect 的注册与撤销。</p>
 */
class PluginLifecycleIntegrationTest {

    private RecordingYuniBot bot;
    private JsonCodec jsonCodec;
    private PluginRuntime runtime;
    private PluginEventAdapterHolder holder;
    private ThreadPoolTaskScheduler scheduler;

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        new PolymorphicRegistrationProcessor().applyTo(mapper);
        jsonCodec = new JsonCodec(mapper);

        bot = new RecordingYuniBot();

        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.initialize();
        DynamicTaskManager taskManager = new DynamicTaskManager();
        setField(taskManager, "taskScheduler", scheduler);

        runtime = new PluginRuntime(taskManager, jsonCodec);

        BotApp botApp = new BotApp();
        botApp.setCommandFlag("/");
        runtime.bindBot(() -> bot, botApp);

        holder = new PluginEventAdapterHolder();
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    /**
     * 通过反射给 {@link DynamicTaskManager} 的私有字段赋值。
     *
     * @param target 目标对象
     * @param name   字段名
     * @param value  值
     * @throws Exception 反射失败
     */
    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /**
     * 构造群消息事件。
     *
     * @param text 文本
     * @return 事件
     */
    private static YuniMessageEvent groupMessage(String text) {
        YuniMessageEvent event = new YuniMessageEvent();
        event.setTime(1700000000L);
        event.setSelfId(10000L);
        event.setMessageType("group");
        event.setSubType("normal");
        event.setMessageId(1L);
        event.setUserId(10001L);
        event.setGroupId(123L);
        MessageChain chain = new MessageChain();
        chain.addTextSegment(text);
        event.setMessage(chain.getContent());
        event.setMessageChain(chain);
        MessageSender sender = new MessageSender();
        sender.setUserId(10001L);
        sender.setNickname("测试用户");
        sender.setRole("member");
        event.setSender(sender);
        return event;
    }

    /**
     * 等待条件成立。
     *
     * @param condition 条件
     * @param timeoutMs 超时
     */
    private static void await(BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 仅用于持有类型引用，避免测试类字段过多。 */
    private static final class PluginEventAdapterHolder {
    }

    /**
     * 一个「什么都做一点」的插件——用于覆盖多种 PluginEffect 的注册与撤销。
     */
    public static final class MultiAbilityPlugin implements YuniPlugin {

        /** 消息处理器被调用的次数。 */
        final AtomicInteger messageHits = new AtomicInteger();

        /** 定时任务被调用的次数。 */
        final AtomicInteger scheduleHits = new AtomicInteger();

        /** 对外提供的服务。 */
        public interface Greeter {
            /**
             * 打招呼。
             *
             * @return 问候语
             */
            String greet();
        }

        @Override
        public PluginMetadata getMetadata() {
            return PluginMetadata.of("多能力插件")
                    .version("0.0.1")
                    .description("同时贡献消息监听、定时任务与服务的测试插件")
                    .author("test")
                    .build();
        }

        @Override
        public void apply(YuniContext ctx) {
            // 能力 1：消息监听
            ctx.onMessage(ev -> ev.getChain().containsText("ping"),
                    (ev, reply) -> messageHits.incrementAndGet());

            // 能力 2：定时任务
            ctx.schedule("*/1 * * * * ?", () -> scheduleHits.incrementAndGet());

            // 能力 3：对外提供服务（用工厂形式，使函数式契约无需转型）
            ctx.registerService(Greeter.class, t -> () -> "hello");

            // 能力 4：自定义资源
            ctx.effect(() -> () -> { });
        }
    }

    @Test
    @DisplayName("端到端：加载 → 多能力生效 → 卸载 → 全部清理归零")
    void fullLifecycleCleansEverything() {
        MultiAbilityPlugin plugin = new MultiAbilityPlugin();
        PluginFiber fiber = runtime.load(plugin, "test-module", null, null);

        // 加载成功，贡献了 4 类能力
        assertEquals(FiberState.ACTIVE, fiber.getState());
        assertEquals(4, fiber.getEffectCount(), "应有 4 个 PluginEffect：消息 + 定时 + 服务 + 自定义");
        // 注册表：消息 1 条；服务不进注册表（在 ServiceRegistry 里）
        assertEquals(1, runtime.getRegistrations().getTotalSize());
        assertTrue(runtime.getServices().contains(MultiAbilityPlugin.Greeter.class));

        // 能力 1 生效：符合条件时处理器被调用
        runtime.getPipeline().onMessage(groupMessage("ping 一下"));
        await(() -> plugin.messageHits.get() > 0, 3000);
        assertEquals(1, plugin.messageHits.get());

        // 条件不满足时不调用
        runtime.getPipeline().onMessage(groupMessage("无关内容"));
        await(() -> plugin.messageHits.get() > 1, 300);
        assertEquals(1, plugin.messageHits.get(), "条件不满足不应调用");

        // 能力 2 生效：定时任务被调度
        await(() -> plugin.scheduleHits.get() > 0, 3000);
        assertTrue(plugin.scheduleHits.get() > 0, "定时任务应被执行");

        // 卸载：插件作者没有写任何清理代码
        List<String> failures = runtime.unload(fiber.getId());

        assertTrue(failures.isEmpty(), "清理不应失败: " + failures);
        assertEquals(0, fiber.getEffectCount(), "PluginEffect 必须归零");
        assertEquals(0, runtime.getRegistrations().getTotalSize(), "注册表必须清空");
        assertTrue(!runtime.getServices().contains(MultiAbilityPlugin.Greeter.class),
                "提供的服务必须注销");
        assertEquals(FiberState.DISPOSED, fiber.getState());
        assertEquals(0, runtime.getFibers().getPluginCount());
    }

    @Test
    @DisplayName("能力推导：能查出插件贡献了哪些能力（取代「插件类型」标签）")
    void abilitiesAreDerivableFromRegistrations() {
        PluginFiber fiber = runtime.load(new MultiAbilityPlugin(), null, null, null);

        Set<RegistrationTable.Kind> kinds = runtime.getRegistrations().kindsOf(fiber);

        // 「这个插件是什么类型」由它注册了什么推导得出，而不是由它 extends 了什么声明
        assertTrue(kinds.contains(RegistrationTable.Kind.MESSAGE));
        assertEquals(1, kinds.size(), "本插件只贡献了 MESSAGE 类注册项：定时与服务不走注册表");
    }

    @Test
    @DisplayName("重复加载同一插件被拒绝，卸载后可重新加载")
    void reloadAfterUnloadIsAllowed() {
        PluginFiber first = runtime.load(new MultiAbilityPlugin(), "m", null, null);
        assertTrue(runtime.getFibers().byId(first.getId()).isPresent());

        // 加载前未卸载 → 拒绝
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> runtime.load(new MultiAbilityPlugin(), "m", null, null));

        runtime.unload(first.getId());

        // 卸载后可以重新加载（热重载的基础）
        PluginFiber second = runtime.load(new MultiAbilityPlugin(), "m", null, null);
        assertEquals(FiberState.ACTIVE, second.getState());
        assertEquals(1, runtime.getFibers().getPluginCount());
    }

    @Test
    @DisplayName("运行时可报告不健康插件")
    void runtimeReportsUnhealthyPlugins() {
        // 一个必失败的插件
        runtime.load(new YuniPlugin() {
            @Override
            public PluginMetadata getMetadata() {
                return PluginMetadata.of("必失败插件").build();
            }

            @Override
            public void apply(YuniContext ctx) {
                throw new IllegalStateException("故意失败");
            }
        }, null, null, null);

        // 一个缺依赖的插件
        runtime.load(new YuniPlugin() {
            @Override
            public PluginMetadata getMetadata() {
                return PluginMetadata.of("缺依赖插件").build();
            }

            @Override
            public List<Class<?>> requiredServices() {
                return java.util.List.of(Comparable.class);
            }

            @Override
            public void apply(YuniContext ctx) {
                // 不应执行
            }
        }, null, null, null);

        List<String> failed = runtime.reportFailedPlugins();
        List<String> pending = runtime.reportPendingPlugins();

        assertEquals(1, failed.size());
        assertTrue(failed.get(0).contains("必失败插件"));
        assertTrue(failed.get(0).contains("故意失败"));

        assertEquals(1, pending.size());
        assertTrue(pending.get(0).contains("缺依赖插件"));
        assertTrue(pending.get(0).contains("Comparable"));
    }
}
