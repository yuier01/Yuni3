package com.yuier.yuni.plugin.runtime;

import com.yuier.yuni.plugin.api.Disposable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PluginEffect 登记表——一个插件实例持有的全部可撤销注册。
 *
 * <h2>职责</h2>
 * <p>{@code ctx} 上的每一次注册（监听器、命令、定时任务、服务、自定义资源）
 * 都会把一个 {@link Disposable} 交给所属 Fiber 的 {@code EffectRegistry}。
 * 插件卸载时，登记表按<b>注册顺序的逆序</b>逐个撤销。</p>
 *
 * <h2>为什么是逆序</h2>
 * <p>资源之间常有依赖关系：先注册的服务可能被后注册的监听器使用。
 * 逆序撤销保证「后来者先走」——先摘掉使用者，再释放被使用者。
 * 这与 Cordis 的 effect 语义一致。</p>
 *
 * <h2>异常隔离</h2>
 * <p>单个 Disposer 抛异常<b>不会</b>阻断其余 Disposer 的执行。若一个清理失败
 * 就中断整个卸载流程，会导致后续资源全部泄漏——比单点失败严重得多。
 * 失败会被收集起来，在全部撤销完成后统一记录。</p>
 *
 * <h2>线程安全</h2>
 * <p>注册可能发生在任意线程（{@code apply} 期间通常单线程，但插件可能在
 * 异步回调里动态注册）；卸载可能与注册并发。因此内部用
 * {@code synchronized} 保护列表操作。登记表本身不是热点，锁竞争可忽略。</p>
 *
 * <h2>泄漏检测</h2>
 * <p>{@link #size()} 用于验证「卸载后 PluginEffect 归零」这一不变式——
 * 热重载流程会断言它，见 {@code PluginRuntime#reloadModule}。
 * 这是 2026-08 P0 事故的直接产物：当时容器被清空而插件的注册未被撤销，
 * 且无任何指标可观测。</p>
 *
 * @see PluginFiber
 * @see Disposable
 */
public final class EffectRegistry {

    /** 登记的撤销句柄，按注册顺序存放；卸载时逆序执行。 */
    private final List<Disposable> effects = new ArrayList<>();

    /** 是否已进入卸载流程；置位后拒绝新注册。 */
    private final AtomicBoolean sealed = new AtomicBoolean(false);

    /**
     * 登记一个 PluginEffect。
     *
     * <p>若登记表已封存（插件正在卸载或已卸载），传入的句柄会被<b>立即撤销</b>
     * 并返回 {@code false}。这防止「卸载过程中有异步回调又注册了东西」
     * 造成的泄漏——那种情况下新注册永远不会被执行到。</p>
     *
     * @param disposable 撤销句柄；{@code null} 或 {@link Disposable#NOOP} 会被忽略
     * @return 登记成功返回 {@code true}；因已封存而被立即撤销返回 {@code false}
     */
    public boolean register(Disposable disposable) {
        if (disposable == null || disposable == Disposable.NOOP) {
            return true;
        }
        synchronized (this) {
            if (sealed.get()) {
                // 已封存：不能登记，只能就地撤销，否则该注册会永久泄漏
                safeDispose(disposable, "late-registration");
                return false;
            }
            effects.add(disposable);
            return true;
        }
    }

    /**
     * 当前登记的 PluginEffect 数量。
     *
     * <p>用于泄漏检测：插件卸载完成后本方法应返回 {@code 0}。</p>
     *
     * @return PluginEffect 数量
     */
    public synchronized int size() {
        return effects.size();
    }

    /**
     * 登记表是否为空。
     *
     * @return 无登记时返回 {@code true}
     */
    public synchronized boolean isEmpty() {
        return effects.isEmpty();
    }

    /**
     * 封存登记表并撤销全部 PluginEffect。
     *
     * <p>本方法幂等：重复调用直接返回空列表。执行流程：</p>
     * <ol>
     *   <li>置位封存标志，后续 {@link #register(Disposable)} 一律就地撤销；</li>
     *   <li>取出并清空列表（先清空，保证撤销过程中即使有重入也不会重复执行）；</li>
     *   <li>逆序逐个撤销，单个失败不影响其余；</li>
     *   <li>返回失败信息，供调用方记录日志。</li>
     * </ol>
     *
     * @return 撤销失败的描述列表；全部成功时为空列表
     */
    public List<String> disposeAll() {
        List<Disposable> snapshot;
        synchronized (this) {
            if (!sealed.compareAndSet(false, true)) {
                // 已经卸载过：幂等返回
                return List.of();
            }
            if (effects.isEmpty()) {
                return List.of();
            }
            // 先拷贝再清空：撤销过程中若发生重入，不会读到已处理的元素
            snapshot = new ArrayList<>(effects);
            effects.clear();
        }

        List<String> failures = new ArrayList<>();
        // 逆序撤销：后注册的先释放，保证「使用者先于被使用者」消失
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            Disposable d = snapshot.get(i);
            String failure = safeDispose(d, "index=" + i);
            if (failure != null) {
                failures.add(failure);
            }
        }
        return Collections.unmodifiableList(failures);
    }

    /**
     * 撤销单个句柄并吞掉异常。
     *
     * @param disposable 目标句柄
     * @param context    失败信息里携带的上下文标识
     * @return 失败描述；成功返回 {@code null}
     */
    private static String safeDispose(Disposable disposable, String context) {
        try {
            disposable.dispose();
            return null;
        } catch (Throwable t) {
            // 刻意捕获 Throwable：清理逻辑同样可能抛 Error（如类加载问题），
            // 而卸载流程必须走完，否则后续资源全部泄漏
            return context + " -> " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /**
     * 登记表是否已封存。
     *
     * @return 已封存返回 {@code true}
     */
    public boolean isSealed() {
        return sealed.get();
    }
}
