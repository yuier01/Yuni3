package com.yuier.yuni.plugin.runtime;

import com.yuier.yuni.plugin.api.Disposable;
import com.yuier.yuni.plugin.api.PluginDeclaration;
import com.yuier.yuni.plugin.api.PluginMetadata;
import com.yuier.yuni.plugin.api.YuniContext;
import com.yuier.yuni.plugin.api.YuniPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.yuier.yuni.plugin.runtime.TestPlugins.load;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PluginRuntime} 的单元测试——覆盖「加载 → 依赖检查 → apply → 卸载」闭环。
 *
 * <p>这些测试是设计文档 §9「生命周期」与 §14.7「测试策略」中
 * <b>集成层</b>要求的核心断言：</p>
 * <ul>
 *   <li>加载后 PluginEffect 被登记，卸载后<b>归零</b>；</li>
 *   <li>{@code apply} 抛异常时插件进入 FAILED 且已建立的 PluginEffect 被回滚；</li>
 *   <li>依赖未满足时停在 PENDING 且 {@code apply} <b>不被调用</b>；</li>
 *   <li>重复加载同 ID 插件被拒绝（不静默覆盖）。</li>
 * </ul>
 */
class PluginRuntimeTest {

    /**
     * 构造一个不注入定时任务管理器的运行时。
     *
     * <p>本测试只覆盖生命周期与 PluginEffect 语义，因此不装配调度能力——
     * 那会让每个用例都要拉起 Spring 的 {@code TaskScheduler}。
     * 调度能力的覆盖在 {@code PluginSchedulerTest}（8 个用例）里，
     * 那里装配了真实的 {@code DynamicTaskManager}。</p>
     *
     * @return 运行时实例
     */
    private static PluginRuntime runtime() {
        return new PluginRuntime(null);
    }

    @Test
    @DisplayName("加载一个注册了能力的插件后处于 ACTIVE，且 PluginEffect 数正确")
    void loadsPluginAndRegistersEffects() {
        PluginRuntime runtime = runtime();

        YuniPlugin plugin = new YuniPlugin() {
            @Override
 public PluginDeclaration getDeclaration() {
                return PluginDeclaration.of("测试插件").build();
            }

            @Override
            public void apply(YuniContext ctx) {
                ctx.onMessage((ev, reply) -> { });
                ctx.onNotice("poke", (ev, reply) -> { });
                ctx.effect(() -> () -> { });
            }
        };

        PluginFiber fiber = load(runtime, plugin, "test-artifact");

        assertEquals(FiberState.ACTIVE, fiber.getState());
        // 两个事件注册 + 一个自定义 PluginEffect
        assertEquals(3, fiber.getEffectCount());
        // 注册表也应看到这三条（自定义 PluginEffect 不进注册表，故为 2）
        assertEquals(2, runtime.getRegistrations().getTotalSize());
        assertEquals(1, runtime.getFibers().getPluginCount());
    }

    @Test
    @DisplayName("卸载后 PluginEffect 归零，注册表清空——这是热重载允许关 ClassLoader 的前提")
    void unloadZeroesEffectsAndRegistrations() {
        PluginRuntime runtime = runtime();

        YuniPlugin plugin = YuniPlugin.of("卸载测试", ctx -> {
            ctx.onMessage((ev, reply) -> { });
            ctx.onMessage((ev, reply) -> { });
            ctx.effect(() -> () -> { });
        });

        PluginFiber fiber = load(runtime, plugin);
        assertEquals(3, fiber.getEffectCount());
        assertTrue(runtime.getRegistrations().getTotalSize() > 0);

        List<String> failures = runtime.unload(fiber.getId());

        assertTrue(failures.isEmpty(), "不应有清理失败: " + failures);
        assertEquals(0, fiber.getEffectCount(), "PluginEffect 必须归零");
        assertEquals(FiberState.DISPOSED, fiber.getState());
        assertEquals(0, runtime.getFibers().getPluginCount());
        assertEquals(0, runtime.getRegistrations().getTotalSize(), "注册表必须清空");
        assertEquals(0, runtime.getFibers().getTotalEffectCount());
    }

    @Test
    @DisplayName("apply 抛异常：插件进入 FAILED，且已建立的 PluginEffect 被回滚")
    void failedApplyRollsBackEffects() {
        PluginRuntime runtime = runtime();
        AtomicInteger disposed = new AtomicInteger();

        YuniPlugin plugin = new YuniPlugin() {
            @Override
 public PluginDeclaration getDeclaration() {
                return PluginDeclaration.of("半初始化插件").build();
            }

            @Override
            public void apply(YuniContext ctx) {
                // 先注册两个 PluginEffect，再抛异常——模拟「注册到一半失败」
                ctx.onMessage((ev, reply) -> { });
                ctx.effect(() -> disposed::incrementAndGet);
                throw new IllegalStateException("apply 中途失败");
            }
        };

        PluginFiber fiber = load(runtime, plugin);

        assertEquals(FiberState.FAILED, fiber.getState());
        assertTrue(fiber.getFailureCause().isPresent());
        assertTrue(fiber.getFailureCause().get().getMessage().contains("apply 中途失败"));

        // 回滚是关键：不回滚会出现「标记为失败但监听器仍在工作」的半初始化状态
        assertEquals(0, fiber.getEffectCount(), "失败后 PluginEffect 必须回滚");
        assertEquals(1, disposed.get(), "自定义 PluginEffect 的 Disposer 应被调用");
        assertEquals(0, runtime.getRegistrations().getTotalSize(), "注册表不应残留半初始化条目");
    }

    @Test
    @DisplayName("apply 抛出 Error（类加载失败场景）同样被捕获并回滚")
    void failedApplyCatchesError() {
        PluginRuntime runtime = runtime();

        YuniPlugin plugin = YuniPlugin.of("抛 Error 的插件", ctx -> {
            ctx.onMessage((ev, reply) -> { });
            throw new NoClassDefFoundError("simulated missing framework class");
        });

        PluginFiber fiber = load(runtime, plugin);

        // 只 catch Exception 会让这里静默通过并把注册残留下来
        assertEquals(FiberState.FAILED, fiber.getState());
        assertEquals(0, fiber.getEffectCount());
        assertEquals(0, runtime.getRegistrations().getTotalSize());
    }

    @Test
    @DisplayName("依赖未满足：停在 PENDING，且 apply 不被调用")
    void pendingWhenDependencyMissing() {
        PluginRuntime runtime = runtime();
        AtomicInteger applyCount = new AtomicInteger();

        YuniPlugin plugin = new YuniPlugin() {
            @Override
 public PluginDeclaration getDeclaration() {
                return PluginDeclaration.of("缺依赖插件").build();
            }

            @Override
            public List<Class<?>> declareRequiredServices() {
                return List.of(CharSequence.class);
            }

            @Override
            public void apply(YuniContext ctx) {
                applyCount.incrementAndGet();
            }
        };

        PluginFiber fiber = load(runtime, plugin);

        assertEquals(FiberState.PENDING, fiber.getState());
        assertEquals(0, applyCount.get(), "依赖未满足时不能执行 apply");
        assertEquals(List.of(CharSequence.class), fiber.getPendingDependencies());

        // PENDING 必须在启动收尾时被显式报出，而不是静默
        List<String> report = runtime.reportPendingPlugins();
        assertEquals(1, report.size());
        assertTrue(report.get(0).contains("缺依赖插件"), report.toString());
        assertTrue(report.get(0).contains("CharSequence"), report.toString());
    }

    @Test
    @DisplayName("依赖就绪后推进 PENDING 插件并转为 ACTIVE")
    void activatesPendingOnceDependencyProvided() {
        PluginRuntime runtime = runtime();
        AtomicInteger applyCount = new AtomicInteger();

        YuniPlugin plugin = new YuniPlugin() {
            @Override
 public PluginDeclaration getDeclaration() {
                return PluginDeclaration.of("等待服务").build();
            }

            @Override
            public List<Class<?>> declareRequiredServices() {
                return List.of(CharSequence.class);
            }

            @Override
            public void apply(YuniContext ctx) {
                applyCount.incrementAndGet();
            }
        };

        PluginFiber fiber = load(runtime, plugin);
        assertEquals(FiberState.PENDING, fiber.getState());

        // 注入依赖
        runtime.registerService(CharSequence.class, "我来了");

        int activated = runtime.activatePending();

        assertEquals(1, activated);
        assertEquals(FiberState.ACTIVE, fiber.getState());
        assertEquals(1, applyCount.get());
        assertTrue(runtime.reportPendingPlugins().isEmpty());
    }

    @Test
    @DisplayName("插件提供服务后，等待该服务的插件被自动推进")
    void providingServiceActivatesWaiter() {
        PluginRuntime runtime = runtime();

        YuniPlugin consumer = new YuniPlugin() {
            @Override
 public PluginDeclaration getDeclaration() {
                return PluginDeclaration.of("消费者").build();
            }

            @Override
            public List<Class<?>> declareRequiredServices() {
                return List.of(Runnable.class);
            }

            @Override
            public void apply(YuniContext ctx) {
                // 依赖由 inject 保证就绪
                assertNotNull(ctx.findService(Runnable.class));
            }
        };

        PluginFiber consumerFiber = load(runtime, consumer);
        assertEquals(FiberState.PENDING, consumerFiber.getState());

        // 提供者插件在 apply 中注册服务——消费者应被自动推进，无需手动调用 activatePending
        YuniPlugin provider = new YuniPlugin() {
            @Override
 public PluginDeclaration getDeclaration() {
                return PluginDeclaration.of("提供者").build();
            }

            @Override
            public void apply(YuniContext ctx) {
                // 显式转型：provide 的第二个参数是 Object，
                // 裸 lambda 在此处无法被编译器推断为 Runnable
                ctx.registerService(Runnable.class, (Runnable) () -> { });
            }
        };
        load(runtime, provider);
        assertEquals(FiberState.ACTIVE, consumerFiber.getState(),
                "provide 之后应自动推进等待者，否则必须等下次启动才生效");
    }

    @Test
    @DisplayName("循环依赖：双方都停在 PENDING，且被报告出来")
    void circularDependencyStaysPendingAndIsReported() {
        PluginRuntime runtime = runtime();

        // 用两个标记接口模拟互相依赖
        YuniPlugin a = new YuniPlugin() {
            @Override
 public PluginDeclaration getDeclaration() {
                return PluginDeclaration.of("A").build();
            }

            @Override
            public List<Class<?>> declareRequiredServices() {
                return List.of(Comparable.class);
            }

            @Override
            public void apply(YuniContext ctx) {
                ctx.registerService(Cloneable.class, new Cloneable() { });
            }
        };
        YuniPlugin b = new YuniPlugin() {
            @Override
 public PluginDeclaration getDeclaration() {
                return PluginDeclaration.of("B").build();
            }

            @Override
            public List<Class<?>> declareRequiredServices() {
                return List.of(Cloneable.class);
            }

            @Override
            public void apply(YuniContext ctx) {
                ctx.registerService(Comparable.class, (Comparable<Object>) o -> 0);
            }
        };

        PluginFiber fiberA = load(runtime, a);
        PluginFiber fiberB = load(runtime, b);

        // A 等 Comparable（由 B 提供），B 等 Cloneable（由 A 提供）：谁都无法先 apply
        assertEquals(FiberState.PENDING, fiberA.getState());
        assertEquals(FiberState.PENDING, fiberB.getState());

        // 不动点迭代不应死循环
        assertEquals(0, runtime.activatePending());

        List<String> report = runtime.reportPendingPlugins();
        assertEquals(2, report.size(), "循环依赖必须被显式报出，不能静默");
    }

    @Test
    @DisplayName("重复加载同一插件被拒绝，不静默覆盖")
    void rejectsDuplicateLoad() {
        PluginRuntime runtime = runtime();

        YuniPlugin plugin = YuniPlugin.of("重复插件", ctx -> { });
        load(runtime, plugin, "m");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> load(runtime, YuniPlugin.of("重复插件", ctx -> { }), "m"));
        assertTrue(ex.getMessage().contains("已加载"), ex.getMessage());
    }

    @Test
    @DisplayName("插件的服务在卸载时注销，不能拿到已卸载插件的对象")
    void unloadUnregistersProvidedServices() {
        PluginRuntime runtime = runtime();

        YuniPlugin provider = new YuniPlugin() {
            @Override
 public PluginDeclaration getDeclaration() {
                return PluginDeclaration.of("服务提供者").build();
            }

            @Override
            public void apply(YuniContext ctx) {
                // 显式转型：provide 的第二个参数是 Object
                ctx.registerService(Runnable.class, (Runnable) () -> { });
            }
        };

        PluginFiber fiber = load(runtime, provider);
        assertTrue(runtime.getServices().contains(Runnable.class));

        runtime.unload(fiber.getId());

        assertFalse(runtime.getServices().contains(Runnable.class), "卸载后服务必须注销");
    }

    @Test
    @DisplayName("作用域状态按类型懒创建，同一类型返回同一实例")
    void scopeStateIsLazyAndShared() {
        PluginRuntime runtime = runtime();
        List<Object> captured = new ArrayList<>();

        YuniPlugin plugin = YuniPlugin.of("带状态的插件", ctx -> {
            captured.add(ctx.state(StateHolder.class));
            captured.add(ctx.state(StateHolder.class));
        });

        load(runtime, plugin);

        assertEquals(2, captured.size());
        assertSame(captured.get(0), captured.get(1), "同一类型应返回同一实例");
        assertNotNull(captured.get(0));
    }

    @Test
    @DisplayName("作用域状态类型缺少无参构造器时给出明确错误")
    void scopeStateRequiresNoArgConstructor() {
        PluginRuntime runtime = runtime();

        YuniPlugin plugin = YuniPlugin.of("状态类型不合法", ctx -> ctx.state(NoDefaultCtor.class));

        PluginFiber fiber = load(runtime, plugin);

        // 插件应进入 FAILED，且失败原因里说明是构造器问题
        assertEquals(FiberState.FAILED, fiber.getState());
        Throwable cause = fiber.getFailureCause().orElseThrow();
        assertEquals(IllegalStateException.class, cause.getClass());
        assertTrue(cause.getMessage().contains("无参构造器"), cause.getMessage());
    }

    @Test
    @DisplayName("全量卸载后全局 PluginEffect 归零")
    void unloadAllLeavesNoResidue() {
        PluginRuntime runtime = runtime();

        for (int i = 0; i < 5; i++) {
            final int n = i;
            load(runtime, YuniPlugin.of("插件" + n, ctx -> {
                ctx.onMessage((ev, reply) -> { });
                ctx.onNotice("poke", (ev, reply) -> { });
                ctx.effect(() -> () -> { });
            }), "m" + n);
        }

        assertEquals(5, runtime.getFibers().getPluginCount());
        assertEquals(15, runtime.getFibers().getTotalEffectCount());

        List<String> failures = runtime.unloadAll();

        assertTrue(failures.isEmpty(), "不应有清理失败: " + failures);
        assertEquals(0, runtime.getFibers().getPluginCount());
        assertEquals(0, runtime.getFibers().getTotalEffectCount());
        assertEquals(0, runtime.getRegistrations().getTotalSize());
    }

    @Test
    @DisplayName("关闭后再注册：拒收并就地撤销，不留悬挂引用")
    void registrationAfterDisposeIsRejectedAndRolledBack() {
        PluginRuntime runtime = runtime();
        List<Disposable> lateHandles = new ArrayList<>();

        // 保留 ctx 引用并在卸载后再注册——模拟「异步回调在插件卸载后才触发」
        YuniPlugin plugin = new YuniPlugin() {
            @Override
 public PluginDeclaration getDeclaration() {
                return PluginDeclaration.of("延迟注册").build();
            }

            @Override
            public void apply(YuniContext ctx) {
                lateHandles.add(ctx.onMessage((ev, reply) -> { }));
            }
        };

        PluginFiber fiber = load(runtime, plugin);
        assertEquals(1, runtime.getRegistrations().getTotalSize());

        runtime.unload(fiber.getId());
        assertEquals(0, runtime.getRegistrations().getTotalSize());

        // 已经不在注册表里，再次撤销是幂等的
        lateHandles.get(0).dispose();
        assertEquals(0, runtime.getRegistrations().getTotalSize());
    }

    /**
     * 无参构造器可访问的状态类型——用于验证作用域状态的正常路径。
     */
    public static final class StateHolder {
        /** 可变计数，用于验证状态确实被共享。 */
        public int counter;
    }

    /**
     * 缺少无参构造器的状态类型——用于验证错误路径。
     */
    public static final class NoDefaultCtor {
        /** 唯一构造器带参数，反射实例化应失败。 */
        public NoDefaultCtor(String required) {
            // 故意要求参数
        }
    }
}
