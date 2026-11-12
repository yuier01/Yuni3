package com.yuier.yuni.plugin.runtime;

import com.yuier.yuni.plugin.api.PluginDeclaration;
import com.yuier.yuni.plugin.api.PluginDescriptor;
import com.yuier.yuni.plugin.api.PluginMetadata;
import com.yuier.yuni.plugin.api.YuniPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PluginFiber} 状态迁移的并发不变量。
 *
 * <h2>为什么单独测这个类</h2>
 * <p>状态迁移原来用 {@code state.set(...)} 实现，只有「单次写原子」，
 * 没有「迁移原子」。两个并发 {@code tryActivate} 可能同时通过
 * 「状态是否为 PENDING」的检查，导致同一个插件被 apply 两次；
 * 而 apply 期间被卸载的 Fiber，也可能被回写成 ACTIVE（僵尸）。
 * 现在迁移全部走 CAS，本类钉住这两条不变量。</p>
 *
 * <p>这些用例都在 Fiber 层面直接验证——它们不依赖线程调度时序，
 * 因此不会出现「偶发通过」的 flaky 情况。</p>
 *
 * @see PluginFiber#tryBeginLoading()
 * @see PluginFiber#tryMarkActive()
 * @see PluginFiber#tryMarkFailed(Throwable)
 */
class PluginFiberTest {

    /**
     * 构造一个只用于状态迁移的 Fiber。
     *
     * @return 初始状态为 PENDING 的 Fiber
     */
    private static PluginFiber newFiber() {
        YuniPlugin plugin = YuniPlugin.of("状态测试插件", ctx -> {
        });
        return new PluginFiber(plugin, PluginMetadata.of("", PluginDeclaration.of("m/StateTest").build(), PluginDescriptor.empty(), List.of()), null);
    }

    // ==================== 抢占：只能有一个赢家 ====================

    @Test
    @DisplayName("初始状态为 PENDING")
    void initialStateIsPending() {
        assertEquals(FiberState.PENDING, newFiber().getState());
    }

    @Test
    @DisplayName("tryBeginLoading 只有第一次成功——这是「只 apply 一次」的保证")
    void beginLoadingSucceedsOnlyOnce() {
        PluginFiber fiber = newFiber();

        assertTrue(fiber.tryBeginLoading(), "PENDING → LOADING 应成功");
        assertEquals(FiberState.LOADING, fiber.getState());

        assertFalse(fiber.tryBeginLoading(), "已不在 PENDING，抢占必须失败");
        assertEquals(FiberState.LOADING, fiber.getState(), "失败不得改变状态");
    }

    @Test
    @DisplayName("多线程同时抢占：恰好一个成功")
    void concurrentBeginLoadingHasExactlyOneWinner() throws Exception {
        int threads = 8;
        PluginFiber fiber = newFiber();
        CyclicBarrier barrier = new CyclicBarrier(threads);
        AtomicInteger wins = new AtomicInteger();
        AtomicInteger losses = new AtomicInteger();

        Thread[] workers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            workers[i] = new Thread(() -> {
                try {
                    barrier.await();
                    if (fiber.tryBeginLoading()) {
                        wins.incrementAndGet();
                    } else {
                        losses.incrementAndGet();
                    }
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            workers[i].start();
        }
        for (Thread worker : workers) {
            worker.join(5_000);
        }

        assertEquals(1, wins.get(), "只能有一个线程抢到执行权");
        assertEquals(threads - 1, losses.get());
        assertEquals(FiberState.LOADING, fiber.getState());
    }

    // ==================== 激活 ====================

    @Test
    @DisplayName("LOADING → ACTIVE 成功，且只成功一次")
    void markActiveSucceedsFromLoading() {
        PluginFiber fiber = newFiber();

        assertFalse(fiber.tryMarkActive(), "PENDING 不能直接跳到 ACTIVE");

        assertTrue(fiber.tryBeginLoading());
        assertTrue(fiber.tryMarkActive());
        assertEquals(FiberState.ACTIVE, fiber.getState());

        assertFalse(fiber.tryMarkActive(), "已是 ACTIVE，重复迁移必须失败");
    }

    // ==================== 卸载与激活的竞争（僵尸回归）====================

    @Test
    @DisplayName("apply 期间被卸载：不会被回写成 ACTIVE（僵尸回归）")
    void disposedFiberCannotBecomeActive() {
        PluginFiber fiber = newFiber();

        // 模拟：apply 正在进行（状态已从 PENDING 抢占为 LOADING）
        assertTrue(fiber.tryBeginLoading());

        // 另一个线程此刻执行 unload
        fiber.dispose();
        assertEquals(FiberState.DISPOSED, fiber.getState());

        // apply 返回后试图标记 ACTIVE —— 必须被拒绝
        assertFalse(fiber.tryMarkActive(),
                "已卸载的 Fiber 不能被改回 ACTIVE，否则会留下不在索引里的僵尸");
        assertEquals(FiberState.DISPOSED, fiber.getState(), "状态必须仍是 DISPOSED");
    }

    @Test
    @DisplayName("已卸载的 Fiber 不能迁移为 FAILED")
    void disposedFiberCannotBecomeFailed() {
        PluginFiber fiber = newFiber();
        assertTrue(fiber.tryBeginLoading());
        fiber.dispose();

        assertFalse(fiber.tryMarkFailed(new IllegalStateException("迟到的失败")));
        assertEquals(FiberState.DISPOSED, fiber.getState());
        assertTrue(fiber.getFailureCause().isEmpty(), "失败原因不应被记到已卸载的 Fiber 上");
    }

    // ==================== 失败 ====================

    @Test
    @DisplayName("LOADING → FAILED：记录原因，且 cause 与状态一致")
    void markFailedRecordsCause() {
        PluginFiber fiber = newFiber();
        assertTrue(fiber.tryBeginLoading());

        IllegalStateException cause = new IllegalStateException("apply 炸了");
        assertTrue(fiber.tryMarkFailed(cause));

        assertEquals(FiberState.FAILED, fiber.getState());
        assertEquals(cause, fiber.getFailureCause().orElseThrow());
        assertEquals(FiberState.FAILED, fiber.getState(), "失败状态必须保持，便于 reportFailedPlugins 报告");
    }

    @Test
    @DisplayName("PENDING 不能直接迁移为 FAILED")
    void pendingCannotBecomeFailed() {
        PluginFiber fiber = newFiber();

        assertFalse(fiber.tryMarkFailed(new IllegalStateException("未开始就失败")));
        assertEquals(FiberState.PENDING, fiber.getState());
    }

    // ==================== 卸载幂等 ====================

    @Test
    @DisplayName("dispose 幂等：重复调用不报错，状态保持 DISPOSED")
    void disposeIsIdempotent() {
        PluginFiber fiber = newFiber();
        assertTrue(fiber.tryBeginLoading());

        assertTrue(fiber.dispose().isEmpty(), "首次卸载无清理失败");
        assertEquals(FiberState.DISPOSED, fiber.getState());

        assertTrue(fiber.dispose().isEmpty(), "重复卸载应直接返回空列表");
        assertEquals(FiberState.DISPOSED, fiber.getState());
        assertTrue(fiber.isDisposed());
    }
}
