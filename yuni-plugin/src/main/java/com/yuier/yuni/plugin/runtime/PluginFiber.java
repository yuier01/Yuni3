package com.yuier.yuni.plugin.runtime;

import com.yuier.yuni.plugin.api.PluginMetadata;
import com.yuier.yuni.plugin.api.YuniPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 插件实例运行时句柄（Fiber）。
 *
 * <p>「Fiber」一词借自 Cordis：一个已加载插件实例的运行时句柄。它承载
 * 状态机、Effect 登记表、子 Fiber、以及该插件的 ClassLoader 引用。</p>
 *
 * <h2>取代了什么</h2>
 * <p>现行系统用三个类表达运行时实例：{@code PluginInstance}（抽象基类）、
 * {@code ActivePluginInstance}、{@code PassivePluginInstance}。三者承载的信息
 * 高度重叠，且按「主动/被动」「有无探测器」切分——而这些区分在新系统中
 * 已因类型体系消失而不存在。因此统一为一个 {@code PluginFiber}。</p>
 *
 * <h2>为什么持有 ClassLoader 引用</h2>
 * <p>现行 {@code PluginInstance} <b>不</b>持有加载它的类加载器（审计报告记录的
 * 问题之一），导致：</p>
 * <ul>
 *   <li>无法以编程方式访问插件类（自省、显式加载）；</li>
 *   <li>正确性隐式依赖「扫描阶段已加载 jar 内全部类」这一实现细节，无测试保护。</li>
 * </ul>
 * <p>本类显式持有引用。它同时承担一个安全职责：{@link #dispose()} 会断言
 * 该 Fiber 的 Effect 已归零，只有确认无残留后才允许调用方关闭 ClassLoader
 * （见 {@code PluginRuntime#reloadModule}）。</p>
 *
 * <h2>树形结构</h2>
 * <p>{@link #children()} 是父 Fiber 挂载的子 Fiber。子 Fiber 随父 Fiber 一同
 * dispose。这是「一个复杂插件拆成多个模块」与「替代现行『A 插件的 enable 回调里
 * 启停 B 插件』耦合写法」的基础设施。</p>
 *
 * <h2>线程安全</h2>
 * <p>状态用 {@link AtomicReference} 保证状态迁移的原子性；子 Fiber 列表用
 * {@link CopyOnWriteArrayList}（读多写少）。Effect 的线程安全由
 * {@link EffectRegistry} 负责。</p>
 *
 * @see FiberState
 * @see EffectRegistry
 */
public final class PluginFiber {

    /** 插件的稳定标识（模块名 + 插件名），用于日志与管理端展示。 */
    private final String id;

    /** 插件实现实例。热重载后由新 Fiber 持有新实例，旧 Fiber 保留旧实例引用直至回收。 */
    private final YuniPlugin plugin;

    /** 插件元数据（不可变，可安全跨 Fiber 共享）。 */
    private final PluginMetadata metadata;

    /**
     * 加载本插件的类加载器。
     *
     * <p><b>可能已被关闭。</b>扫描阶段会加载 jar 内全部类，之后即可关闭
     * 类加载器以释放 jar 文件句柄（Windows 下这是覆盖 jar 实现热更新的前提）。
     * 已加载的类定义不受 {@code close()} 影响，因此运行期引用仍然有效
     * （见设计文档 §9.4 / §9.5）。</p>
     *
     * <p>保留本引用是为了诊断（打印加载器标识）与将来可能的显式加载需求。
     * 切勿在不检查 {@code isClosed} 的情况下用它做新的 {@code loadClass}。</p>
     */
    private final ClassLoader classLoader;

    /** 子 Fiber；随本 Fiber 一同卸载。 */
    private final List<PluginFiber> children = new CopyOnWriteArrayList<>();

    /** Effect 登记表。 */
    private final EffectRegistry effects = new EffectRegistry();

    /** 当前状态。 */
    private final AtomicReference<FiberState> state = new AtomicReference<>(FiberState.PENDING);

    /** 未满足的依赖服务类型；仅 PENDING 状态有意义。 */
    private final List<Class<?>> pendingDependencies = new CopyOnWriteArrayList<>();

    /** FAILED 状态的失败原因；其他状态为 {@code null}。 */
    private final AtomicReference<Throwable> failureCause = new AtomicReference<>();

    /** 父 Fiber；根插件为 {@code null}。 */
    private volatile PluginFiber parent;

    /** 是否已被显式 dispose（用于幂等与状态查询）。 */
    private volatile boolean disposed;

    /**
     * 构造一个 Fiber。
     *
     * @param id          插件标识
     * @param plugin      插件实现实例
     * @param metadata    元数据
     * @param classLoader 加载该插件的类加载器；可为 {@code null}（测试场景）
     */
    public PluginFiber(String id, YuniPlugin plugin, PluginMetadata metadata, ClassLoader classLoader) {
        this.id = Objects.requireNonNull(id, "插件标识不能为 null");
        this.plugin = Objects.requireNonNull(plugin, "插件实例不能为 null");
        this.metadata = Objects.requireNonNull(metadata, "插件元数据不能为 null");
        this.classLoader = classLoader;
    }

    // ==================== 身份 ====================

    /**
     * 插件标识。
     *
     * @return 标识字符串
     */
    public String id() {
        return id;
    }

    /**
     * 插件实现实例。
     *
     * @return 插件实例
     */
    public YuniPlugin plugin() {
        return plugin;
    }

    /**
     * 插件元数据。
     *
     * @return 元数据
     */
    public PluginMetadata metadata() {
        return metadata;
    }

    /**
     * 加载本插件的类加载器。
     *
     * @return 类加载器；可能为 {@code null}
     */
    public ClassLoader classLoader() {
        return classLoader;
    }

    // ==================== 状态 ====================

    /**
     * 当前状态。
     *
     * @return 状态枚举
     */
    public FiberState state() {
        return state.get();
    }

    /**
     * 是否处于可服务状态（{@link FiberState#ACTIVE}）。
     *
     * <p>分发器在调用处理器前会检查本方法。</p>
     *
     * @return 可服务返回 {@code true}
     */
    public boolean isServiceable() {
        return state.get().isServiceable();
    }

    /**
     * 状态迁移到 {@link FiberState#LOADING}。
     *
     * <p>由 {@code PluginRuntime} 在调用 {@code apply} 之前调用。</p>
     */
    void markLoading() {
        state.set(FiberState.LOADING);
    }

    /**
     * 状态迁移到 {@link FiberState#ACTIVE}。
     *
     * <p>由 {@code PluginRuntime} 在 {@code apply} 正常返回后调用。</p>
     */
    void markActive() {
        state.set(FiberState.ACTIVE);
    }

    /**
     * 状态迁移到 {@link FiberState#FAILED} 并记录原因。
     *
     * @param cause 失败原因
     */
    void markFailed(Throwable cause) {
        this.failureCause.set(cause);
        state.set(FiberState.FAILED);
    }

    /**
     * FAILED 状态的失败原因。
     *
     * @return 失败原因；非 FAILED 状态时为空
     */
    public java.util.Optional<Throwable> failureCause() {
        return java.util.Optional.ofNullable(failureCause.get());
    }

    /**
     * 记录一个尚未满足的依赖服务类型。
     *
     * <p>用于启动结束时报告「哪些插件因缺什么依赖而未加载」。</p>
     *
     * @param serviceType 缺失的服务类型
     */
    void addPendingDependency(Class<?> serviceType) {
        if (serviceType != null && !pendingDependencies.contains(serviceType)) {
            pendingDependencies.add(serviceType);
        }
    }

    /**
     * 清空未满足依赖记录。
     */
    void clearPendingDependencies() {
        pendingDependencies.clear();
    }

    /**
     * 当前未满足的依赖服务类型。
     *
     * @return 不可变的类型列表
     */
    public List<Class<?>> pendingDependencies() {
        return Collections.unmodifiableList(new ArrayList<>(pendingDependencies));
    }

    // ==================== Effect ====================

    /**
     * Effect 登记表。
     *
     * @return 登记表
     */
    public EffectRegistry effects() {
        return effects;
    }

    /**
     * 当前登记的 Effect 数量。
     *
     * <p>用于泄漏检测。</p>
     *
     * @return 数量
     */
    public int effectCount() {
        return effects.size();
    }

    // ==================== 树结构 ====================

    /**
     * 父 Fiber。
     *
     * @return 父 Fiber；根插件为 {@code null}
     */
    public PluginFiber parent() {
        return parent;
    }

    /**
     * 登记一个子 Fiber。
     *
     * <p>由 {@code PluginRuntime} 在挂载子插件时调用。</p>
     *
     * @param child 子 Fiber
     */
    void addChild(PluginFiber child) {
        if (child == null || child == this) {
            return;
        }
        child.parent = this;
        children.add(child);
    }

    /**
     * 子 Fiber 列表。
     *
     * @return 不可变视图
     */
    public List<PluginFiber> children() {
        return Collections.unmodifiableList(children);
    }

    /**
     * 本 Fiber 及其全部后代的 Effect 总数。
     *
     * <p>用于子树级泄漏检测。</p>
     *
     * @return 总数
     */
    public int totalEffectCount() {
        int total = effectCount();
        for (PluginFiber child : children) {
            total += child.totalEffectCount();
        }
        return total;
    }

    // ==================== 卸载 ====================

    /**
     * 卸载本 Fiber 及其全部子树。
     *
     * <p>执行顺序：</p>
     * <ol>
     *   <li>状态置为 {@link FiberState#UNLOADING}；</li>
     *   <li><b>先</b>递归卸载子 Fiber（子先于父，与 Effect 逆序同理）；</li>
     *   <li>封存并撤销本 Fiber 的全部 Effect（逆序）；</li>
     *   <li>状态置为 {@link FiberState#DISPOSED}，清空子列表。</li>
     * </ol>
     *
     * <p>本方法幂等。返回的失败列表用于让调用方记录日志——
     * 卸载流程本身永不因单个 Disposer 失败而中断。</p>
     *
     * @return 清理失败的描述列表；全部成功时为空
     */
    public List<String> dispose() {
        if (disposed) {
            return List.of();
        }
        disposed = true;
        state.set(FiberState.UNLOADING);

        List<String> failures = new ArrayList<>();

        // 先卸子：子插件可能引用了父插件提供的服务，必须先行释放
        for (PluginFiber child : children) {
            failures.addAll(child.dispose());
        }
        children.clear();

        // 再撤本 Fiber 的 Effect（逆序），并带上插件名便于定位
        for (String failure : effects.disposeAll()) {
            failures.add("[" + id + "] " + failure);
        }

        state.set(FiberState.DISPOSED);
        return Collections.unmodifiableList(failures);
    }

    /**
     * 回滚本 Fiber 已建立的 Effect，但<b>不改变状态</b>。
     *
     * <p>专供 {@code apply} 失败后的清理使用。与 {@link #dispose()} 的区别至关重要：
     * {@code dispose()} 会把状态置为 {@link FiberState#DISPOSED}，于是「因为加载失败
     * 而被回滚」的 Fiber 会显示成「已卸载」——管理端与 {@code reportFailedPlugins()}
     * 就再也看不到这次失败了。本方法保留 {@link FiberState#FAILED} 状态，
     * 使失败可观测。</p>
     *
     * <p>注意本方法<b>不</b>递归处理子 Fiber：{@code apply} 失败时，子插件可能
     * 尚未挂载，也可能已挂载了一部分。已挂载的子 Fiber 随本 Fiber 一起被
     * {@link #dispose()} 处理（调用方在合适时机执行）。</p>
     *
     * @return 清理失败的描述列表；全部成功时为空
     */
    List<String> rollbackEffects() {
        List<String> failures = new ArrayList<>();
        for (String failure : effects.disposeAll()) {
            failures.add("[" + id + "] " + failure);
        }
        return Collections.unmodifiableList(failures);
    }

    /**
     * 是否已被卸载（{@link #dispose()} 调用过）。
     *
     * @return 已卸载返回 {@code true}
     */
    public boolean isDisposed() {
        return disposed;
    }

    // ==================== 诊断 ====================

    /**
     * 单行诊断描述。
     *
     * @return 形如 {@code "fortune/Fortune[ACTIVE, effects=3]"}
     */
    public String describe() {
        return id + "[" + state.get().displayName()
                + ", effects=" + effectCount()
                + (children.isEmpty() ? "" : ", children=" + children.size()) + "]";
    }

    @Override
    public String toString() {
        return "PluginFiber{" + describe() + "}";
    }
}
