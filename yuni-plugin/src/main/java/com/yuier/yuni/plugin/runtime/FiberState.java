package com.yuier.yuni.plugin.runtime;

import lombok.Getter;

/**
 * 插件实例（Fiber）的生命状态。
 *
 * <h2>状态迁移图</h2>
 * <pre>
 *                       依赖服务就绪
 *   PENDING ──────────────────────────────► LOADING ──► ACTIVE
 *      │                                       │
 *      │ 依赖永不满足                           │ apply() 抛异常
 *      │ （启动结束后仍为 PENDING → 报错）        ▼
 *      │                                    FAILED
 *      │
 *      └───────────────────────────────────────────────► UNLOADING ──► DISPOSED
 *                          （显式卸载 / 热重载 / 父插件卸载）
 * </pre>
 *
 * <h2>为什么需要显式的状态</h2>
 * <p>现行插件系统没有状态概念——插件加载失败时只是日志里少一行，
 * 管理端看到的是「插件不见了」或「插件在列表里但没反应」，
 * 运维无法区分以下三种情况：</p>
 * <ul>
 *   <li>插件依赖的服务还没就绪（{@link #PENDING}）；</li>
 *   <li>插件初始化时抛了异常（{@link #FAILED}）；</li>
 *   <li>插件正被重载（{@link #UNLOADING}）。</li>
 * </ul>
 * <p>这正是 2026-08 那次 P0 事故（插件重载静默失败）暴露出的可观测性缺口。
 * 有了显式状态，管理端可以如实展示，运维能直接看到「为什么没生效」。</p>
 *
 * @see PluginFiber
 */
@Getter
public enum FiberState {

    /**
     * 已声明但尚未加载——依赖的服务未就绪。
     *
     * <p>插件通过 {@code YuniPlugin.declareRequiredServices()} 声明依赖；框架在本状态等待。
     * 启动流程结束后若仍有插件停在本状态，框架会<b>显式报错</b>并列出
     * 未满足的依赖（含循环依赖），不会静默。</p>
     */
    PENDING("等待依赖"),

    /**
     * 正在执行 {@code apply(ctx)}。
     *
     * <p>本状态是瞬态：{@code apply} 返回即进入 {@link #ACTIVE}，
     * 抛异常则进入 {@link #FAILED}。管理端一般观察不到。</p>
     */
    LOADING("加载中"),

    /**
     * 已成功加载，能力已注册完毕，正在对外服务。
     */
    ACTIVE("运行中"),

    /**
     * {@code apply(ctx)} 抛出异常，插件不可用。
     *
     * <p>框架会：记录完整堆栈（含 {@code Error}）、回滚已建立的 PluginEffect、
     * 在管理端标记为失败。其他插件不受影响。</p>
     *
     * <p><b>为什么必须回滚</b>：若 {@code apply} 在前半段注册了监听器、
     * 后半段抛异常，不回滚就会出现「插件标记为失败但监听器仍在工作」的
     * 半初始化状态——比彻底失败更难排查。</p>
     */
    FAILED("加载失败"),

    /**
     * 正在卸载——PluginEffect 的 Disposer 正在逆序执行。
     *
     * <p>本状态是瞬态。此期间框架不再向该插件分发事件。</p>
     */
    UNLOADING("卸载中"),

    /**
     * 已完全卸载，全部 PluginEffect 已撤销，ClassLoader 可被回收。
     */
    DISPOSED("已卸载");

    /** 面向管理端的中文显示名；访问器由 @Getter + @Accessors(fluent) 生成。 */
    private final String displayName;

    FiberState(String displayName) {
        this.displayName = displayName;
    }

    /**
     * 本状态是否表示插件可以对事件作出响应。
     *
     * <p>仅 {@link #ACTIVE} 为真。分发器在调用处理器前会检查所属 Fiber 的状态，
     * 因此处于 FAILED / UNLOADING / DISPOSED 的插件不会收到事件。</p>
     *
     * @return 可服务返回 {@code true}
     */
    public boolean isServiceable() {
        return this == ACTIVE;
    }

    /**
     * 本状态是否为终态（不会再变化）。
     *
     * @return {@link #DISPOSED} 与 {@link #FAILED} 返回 {@code true}
     */
    public boolean isTerminal() {
        return this == DISPOSED || this == FAILED;
    }
}
