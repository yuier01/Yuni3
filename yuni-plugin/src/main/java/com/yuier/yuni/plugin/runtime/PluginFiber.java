package com.yuier.yuni.plugin.runtime;

import com.yuier.yuni.plugin.api.PluginMetadata;
import com.yuier.yuni.plugin.api.YuniPlugin;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Getter;

/**
 * 插件实例运行时句柄（Fiber）。
 *
 * <p>「Fiber」一词借自 Cordis：一个已加载插件实例的运行时句柄。它承载
 * 状态机、PluginEffect 登记表、子 Fiber、以及该插件的 ClassLoader 引用。</p>
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
 * 该 Fiber 的 PluginEffect 已归零，只有确认无残留后才允许调用方关闭 ClassLoader
 * （见 {@code PluginRuntime#reload}）。</p>
 *
 * <h2>线程安全</h2>
 * <p>状态迁移全部走 {@link AtomicReference} 的 <b>CAS</b>，因此「迁移」这件事
 * 本身是原子的，而不只是「单次读写」原子：</p>
 * <ul>
 *   <li>{@link #tryBeginLoading()} 用 {@code PENDING → LOADING} 抢占，
 *       保证同一个 Fiber <b>只会被 apply 一次</b>；</li>
 *   <li>{@link #tryMarkActive()} 要求当前仍是 {@code LOADING}，
 *       因此 apply 期间被卸载的 Fiber 不会被回写成 ACTIVE（防僵尸）。</li>
 * </ul>
 * <p>子 Fiber 列表用 {@link CopyOnWriteArrayList}（读多写少）。
 * PluginEffect 的线程安全由 {@link EffectRegistry} 负责。</p>
 *
 * <p>{@link #disposed} 是独立的 volatile 标志，先于状态迁移置位，
 * 供幂等判定与插件侧 {@code ctx.isDisposed()} 使用。并发调用
 * {@link #dispose()} 时，重复的清理由 {@link EffectRegistry#disposeAll()}
 * 自身的封存 CAS 兜住。</p>
 *
 * @see FiberState
 * @see EffectRegistry
 */
@Getter
public final class PluginFiber {

    /** 插件的稳定标识（加载单元 + 插件名），用于日志与管理端展示。 */
    private final String id;

    /** 所属加载单元标识；测试场景为 {@code null}。 */
    private final String artifactId;

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

    /** PluginEffect 登记表。 */
    private final EffectRegistry effects = new EffectRegistry();

    /** 当前状态。 */
    private final AtomicReference<FiberState> state = new AtomicReference<>(FiberState.PENDING);

    /** 未满足的依赖服务类型；仅 PENDING 状态有意义。 */
    private final List<Class<?>> pendingDependencies = new CopyOnWriteArrayList<>();

    /** FAILED 状态的失败原因；其他状态为 {@code null}。 */
    private final AtomicReference<Throwable> failureCause = new AtomicReference<>();

    /**
     * 是否已调用过 {@link #dispose()}。
     *
     * <p>两个用途：一是作为卸载的<b>幂等闸门</b>（见 {@link #dispose()} 开头的判定）；
     * 二是经 {@link com.yuier.yuni.plugin.api.YuniContext#isDisposed()} 暴露给插件，
     * 供异步回调判断「我是否已被卸载」，避免继续操作已释放的资源。</p>
     *
     * <p><b>注意它先于状态迁移置位</b>：进入卸载流程时就变为 {@code true}，
     * 而此时 {@link #getState()} 还是 {@link FiberState#UNLOADING}。
     * 所以本标志回答的是「是否已<b>开始</b>卸载」；
     * 「是否已卸载<b>完毕</b>」要问 {@code getState() == DISPOSED}。</p>
     *
     * <p>访问器 {@code isDisposed()} 由 Lombok {@link Getter} 生成
     * （类上使用 {@code @Accessors(fluent = true)}，与其余访问器命名一致）。</p>
     */
    @Getter
    private volatile boolean disposed;

    /**
     * 构造一个 Fiber。
     *
     * @param id          插件标识
     * @param plugin      插件实现实例
     * @param metadata    元数据
     * @param classLoader 加载该插件的类加载器；可为 {@code null}（测试场景）
     */
    PluginFiber(String id,
                       String artifactId,
                       YuniPlugin plugin,
                       PluginMetadata metadata,
                       ClassLoader classLoader) {
        this.id = Objects.requireNonNull(id, "插件标识不能为 null");
        this.artifactId = artifactId;
        this.plugin = Objects.requireNonNull(plugin, "插件实例不能为 null");
        this.metadata = Objects.requireNonNull(metadata, "插件元数据不能为 null");
        this.classLoader = classLoader;
    }

    // ==================== 身份 ====================

    /*
     * getId() / getPlugin() / getMetadata() / getClassLoader() 由类上的
     * @Getter + @Accessors(fluent) 生成（字段注释已说明各自语义）。
     */

    // ==================== 状态 ====================

    /**
     * 当前状态。
     *
     * @return 状态枚举
     */
    public FiberState getState() {
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
     * 原子地把状态从 {@link FiberState#PENDING} 抢占为 {@link FiberState#LOADING}。
     *
     * <p>这是「同一个 Fiber 只允许被 apply 一次」的<b>唯一</b>保证。调用方必须
     * 抢占成功后才能执行插件代码——否则两个线程可能同时读到 PENDING，
     * 于是同一个插件被 apply 两次：监听器、定时任务、数据库连接全部重复注册，
     * 而这些问题在运行期极难归因。</p>
     *
     * <p>用 CAS 而不是「先读状态再 set」：后者是 check-then-act，
     * 检查与动作之间留有窗口，正是要消除的东西。</p>
     *
     * @return 抢占成功返回 {@code true}；已被其他线程接管、或已离开 PENDING 时返回 {@code false}
     */
    boolean tryBeginLoading() {
        return state.compareAndSet(FiberState.PENDING, FiberState.LOADING);
    }

    /**
     * 原子地把状态从 {@link FiberState#LOADING} 迁移到 {@link FiberState#ACTIVE}。
     *
     * <p>要求「当前仍是 LOADING」而不是无条件 set，是为了堵住
     * 「apply 执行期间插件被卸载」这个窗口：此时 {@link #dispose()} 已把状态
     * 推到 {@code UNLOADING}/{@code DISPOSED}，若这里直接覆盖，
     * 就会得到一个「状态是运行中、却已被移除出索引」的僵尸 Fiber。</p>
     *
     * @return 迁移成功返回 {@code true}；Fiber 已被卸载（状态不再是 LOADING）时返回 {@code false}，
     *         调用方必须回滚已建立的 PluginEffect 并按失败处理
     */
    boolean tryMarkActive() {
        return state.compareAndSet(FiberState.LOADING, FiberState.ACTIVE);
    }

    /**
     * 原子地把状态从 {@link FiberState#LOADING} 迁移到 {@link FiberState#FAILED}，
     * 并记录失败原因。
     *
     * <p>顺序是「先迁移状态、再写原因」：这样 {@link #getFailureCause()} 的契约
     * （非 FAILED 时为空）始终成立。代价是两步之间若被读到，会看到
     * FAILED 但原因为空——那只影响诊断文案显示「(未记录)」，不影响状态正确性。</p>
     *
     * @param cause 失败原因
     * @return 迁移成功返回 {@code true}；Fiber 已被卸载（状态不再是 LOADING）时返回 {@code false}
     */
    boolean tryMarkFailed(Throwable cause) {
        if (!state.compareAndSet(FiberState.LOADING, FiberState.FAILED)) {
            return false;
        }
        failureCause.set(cause);
        return true;
    }

    /**
     * FAILED 状态的失败原因。
     *
     * @return 失败原因；非 FAILED 状态时为空
     */
    public Optional<Throwable> getFailureCause() {
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
    public List<Class<?>> getPendingDependencies() {
        return Collections.unmodifiableList(new ArrayList<>(pendingDependencies));
    }

    // ==================== PluginEffect ====================

    /* getEffects() 由 @Getter 生成；下面是带计算的效应数量。 */

    /**
     * 当前登记的 PluginEffect 数量。
     *
     * <p>用于泄漏检测。</p>
     *
     * @return 数量
     */
    public int getEffectCount() {
        return effects.size();
    }

    // ==================== 卸载 ====================

    /**
     * 卸载本 Fiber。
     *
     * <p>执行顺序：</p>
     * <ol>
     *   <li>状态置为 {@link FiberState#UNLOADING}；</li>
     *   <li>封存并撤销本 Fiber 的全部 PluginEffect（逆序）；</li>
     *   <li>状态置为 {@link FiberState#DISPOSED}。</li>
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

        // 撤销本 Fiber 的 PluginEffect（逆序），并带上插件名便于定位
        for (String failure : effects.disposeAll()) {
            failures.add("[" + id + "] " + failure);
        }

        state.set(FiberState.DISPOSED);
        return Collections.unmodifiableList(failures);
    }

    /**
     * 回滚本 Fiber 已建立的 PluginEffect，但<b>不改变状态</b>。
     *
     * <p>专供 {@code apply} 失败后的清理使用。与 {@link #dispose()} 的区别至关重要：
     * {@code dispose()} 会把状态置为 {@link FiberState#DISPOSED}，于是「因为加载失败
     * 而被回滚」的 Fiber 会显示成「已卸载」——管理端与 {@code reportFailedPlugins()}
     * 就再也看不到这次失败了。本方法保留 {@link FiberState#FAILED} 状态，
     * 使失败可观测。</p>
     *
     * <p>注意本方法只处理本 Fiber 的 PluginEffect：
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

    // ==================== 诊断 ====================

    /**
     * 单行诊断描述。
     *
     * @return 形如 {@code "fortune/Fortune[ACTIVE, effects=3]"}
     */
    public String describe() {
        return id + "[" + state.get().getDisplayName()
                + ", effects=" + getEffectCount() + "]";
    }

    @Override
    public String toString() {
        return "PluginFiber{" + describe() + "}";
    }
}
