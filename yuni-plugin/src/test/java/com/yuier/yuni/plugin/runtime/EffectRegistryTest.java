package com.yuier.yuni.plugin.runtime;

import com.yuier.yuni.plugin.api.Disposable;
import com.yuier.yuni.plugin.api.Effect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EffectRegistry} 的单元测试。
 *
 * <p>覆盖设计文档中最关键的几条不变式：</p>
 * <ul>
 *   <li><b>逆序撤销</b>：后注册的先释放，保证「使用者先于被使用者」消失；</li>
 *   <li><b>幂等</b>：重复 dispose 不重复执行清理；</li>
 *   <li><b>异常隔离</b>：单个 Disposer 抛异常不阻断其余清理；</li>
 *   <li><b>封存后拒收</b>：卸载过程中新增的注册被就地撤销，不泄漏；</li>
 *   <li><b>归零</b>：disposeAll 后登记表为空（泄漏检测的依据）。</li>
 * </ul>
 */
class EffectRegistryTest {

    @Test
    @DisplayName("逆序撤销：后注册的 Effect 先被释放")
    void disposesInReverseOrder() {
        EffectRegistry registry = new EffectRegistry();
        List<Integer> order = new ArrayList<>();

        registry.register(() -> order.add(1));
        registry.register(() -> order.add(2));
        registry.register(() -> order.add(3));

        registry.disposeAll();

        // 后注册的先撤销，与前文「资源依赖关系」的语义一致
        assertEquals(List.of(3, 2, 1), order);
    }

    @Test
    @DisplayName("disposeAll 后登记表归零，且数量在撤销前可观测")
    void reportsSizeAndEmptiesAfterDispose() {
        EffectRegistry registry = new EffectRegistry();
        assertEquals(0, registry.size());
        assertTrue(registry.isEmpty());

        registry.register(() -> { });
        registry.register(() -> { });
        assertEquals(2, registry.size());

        registry.disposeAll();

        // 归零是热重载断言的基础：Effect 不为零就不允许关闭 ClassLoader
        assertEquals(0, registry.size());
        assertTrue(registry.isEmpty());
    }

    @Test
    @DisplayName("幂等：重复 disposeAll 不会重复执行清理")
    void disposeIsIdempotent() {
        EffectRegistry registry = new EffectRegistry();
        AtomicInteger count = new AtomicInteger();

        registry.register(count::incrementAndGet);

        registry.disposeAll();
        registry.disposeAll();
        registry.disposeAll();

        assertEquals(1, count.get());
    }

    @Test
    @DisplayName("异常隔离：单个 Disposer 抛异常不阻断其余清理")
    void singleFailureDoesNotAbortOthers() {
        EffectRegistry registry = new EffectRegistry();
        List<String> disposed = new ArrayList<>();

        registry.register(() -> disposed.add("first"));
        registry.register(() -> {
            throw new IllegalStateException("cleanup exploded");
        });
        registry.register(() -> disposed.add("third"));

        List<String> failures = registry.disposeAll();

        // 三个都被尝试过；失败的那个被记录下来而不是中断流程
        assertEquals(List.of("third", "first"), disposed);
        assertEquals(1, failures.size());
        assertTrue(failures.get(0).contains("cleanup exploded"), failures.toString());
        assertEquals(0, registry.size());
    }

    @Test
    @DisplayName("异常隔离：Error 同样被兜住（类加载失败场景）")
    void catchesErrorNotJustException() {
        EffectRegistry registry = new EffectRegistry();
        List<String> disposed = new ArrayList<>();

        registry.register(() -> disposed.add("before"));
        registry.register(() -> {
            // 模拟 ClassLoader 相关问题抛出的 Error
            throw new NoClassDefFoundError("simulated missing class");
        });
        registry.register(() -> disposed.add("after"));

        List<String> failures = registry.disposeAll();

        assertEquals(List.of("after", "before"), disposed);
        assertEquals(1, failures.size());
        assertTrue(failures.get(0).contains("NoClassDefFoundError"), failures.toString());
    }

    @Test
    @DisplayName("封存后拒收新注册，并就地撤销以免泄漏")
    void rejectsRegistrationAfterSeal() throws Exception {
        EffectRegistry registry = new EffectRegistry();
        AtomicInteger lateDisposeCount = new AtomicInteger();

        registry.disposeAll();
        assertTrue(registry.isSealed());

        boolean accepted = registry.register(lateDisposeCount::incrementAndGet);

        // 拒收的理由：卸载流程已经跑过，再登记就永远不会被撤销
        assertFalse(accepted);
        assertEquals(1, lateDisposeCount.get(), "被拒收的句柄必须立即撤销，否则泄漏");
        assertEquals(0, registry.size());
    }

    @Test
    @DisplayName("封存后拒收发生在 true 条件下的并发注册窗口")
    void rejectsConcurrentLateRegistration() throws Exception {
        EffectRegistry registry = new EffectRegistry();
        CountDownLatch cleanupStarted = new CountDownLatch(1);
        CountDownLatch releaseCleanup = new CountDownLatch(1);
        AtomicInteger lateDisposeCount = new AtomicInteger();

        // 第一个 Effect 的清理会阻塞，制造「卸载进行中」的窗口
        registry.register(() -> {
            cleanupStarted.countDown();
            try {
                releaseCleanup.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Thread disposer = new Thread(registry::disposeAll);
        disposer.start();
        assertTrue(cleanupStarted.await(5, TimeUnit.SECONDS), "清理应已开始");

        // 卸载进行中：新注册必须被拒收
        boolean accepted = registry.register(lateDisposeCount::incrementAndGet);
        assertFalse(accepted);
        assertEquals(1, lateDisposeCount.get());

        releaseCleanup.countDown();
        disposer.join(5000);
        assertFalse(disposer.isAlive(), "卸载线程应已结束");
    }

    @Test
    @DisplayName("NOOP 与 null 不占用登记位")
    void ignoresNoopAndNull() {
        EffectRegistry registry = new EffectRegistry();

        assertTrue(registry.register(Disposable.NOOP));
        assertTrue(registry.register(null));

        // 登记表保持空：这样「归零」断言才有意义，不会被无意义的占位干扰
        assertEquals(0, registry.size());
    }

    @Test
    @DisplayName("Effect 接口可声明资源获取与释放（ctx.effect 的底层形态）")
    void effectDeclaresAcquireAndRelease() throws Exception {
        EffectRegistry registry = new EffectRegistry();
        StringBuilder log = new StringBuilder();

        Effect effect = () -> {
            log.append("acquire;");
            return () -> log.append("release;");
        };

        // Effect.apply() 声明了 throws Exception（资源获取可能失败），
        // 因此测试方法需要声明抛出；运行时由 PluginContext.effect 统一处理
        Disposable disposer = effect.apply();
        assertNotNull(disposer);
        registry.register(disposer);

        assertEquals("acquire;", log.toString());
        registry.disposeAll();
        assertEquals("acquire;release;", log.toString());
    }
}
