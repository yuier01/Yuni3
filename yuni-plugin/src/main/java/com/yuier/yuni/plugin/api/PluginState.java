package com.yuier.yuni.plugin.api;

import lombok.Getter;

/**
 * 插件对外的运行状态。
 *
 * <h2>为什么不在 API 层直接用 {@code FiberState}</h2>
 * <p>运行时内部的 {@code FiberState} 有六个值，其中 {@code LOADING} 与
 * {@code UNLOADING} 是<b>瞬态</b>——它们存在的时间只有一次 {@code apply} 调用
 * 或一次 PluginEffect 撤销的时长，管理端无论如何轮询都几乎观察不到。
 * 把它们放进公共契约，等于让所有消费方（管理端、webapi、插件）
 * 都为一个永远不出现的分支写代码。</p>
 *
 * <p>因此 API 层收敛为四个值，并把两个瞬态归并到它们的目标状态：
 * {@code LOADING} → {@link #LOADING}（保留，因为「正在加载」对运维有意义：
 * 长时间停在这里说明 {@code apply} 阻塞了），
 * {@code UNLOADING} → 仍单独保留，便于诊断「卸载卡住」。</p>
 *
 * <h2>判定「插件是否在服务」应当用哪个值</h2>
 * <p>用 {@link #ACTIVE}。但要区分两件容易混淆的事：</p>
 * <ul>
 *   <li>{@link #ACTIVE}：插件<b>已成功装配</b>，能力已注册；</li>
 *   <li>{@link PluginAdmin#isEnabledIn}：插件在<b>某个群</b>是否生效。</li>
 * </ul>
 * <p>一个插件可以同时是 ACTIVE 且在某个群里不生效（群粒度启停）。反过来，
 * 非 ACTIVE 的插件在任何群里都不生效。</p>
 *
 * @see PluginView#getState()
 */
@Getter
public enum PluginState {

    /**
     * 等待依赖——{@code inject()} 声明的服务尚未就绪。
     *
     * <p>启动结束时仍停在本状态即为异常，框架会在启动日志中列出
     * 「谁因为缺什么而没加载」。这是现行系统完全缺失的可观测性。</p>
     */
    PENDING("等待依赖"),

    /**
     * 正在执行 {@code apply(ctx)}。
     */
    LOADING("加载中"),

    /**
     * 已成功加载，正在对外服务。
     */
    ACTIVE("运行中"),

    /**
     * {@code apply(ctx)} 抛出异常，插件不可用。
     *
     * <p>已建立的 PluginEffect 已被回滚，因此不会出现「标记为失败但监听器仍在工作」
     * 的半初始化状态。失败原因见 {@link PluginView#getFailureSummary()}。</p>
     */
    FAILED("加载失败"),

    /**
     * 正在卸载。
     */
    UNLOADING("卸载中"),

    /**
     * 已完全卸载，全部 PluginEffect 已撤销。
     */
    DISPOSED("已卸载");

    /** 面向管理端的中文显示名；访问器由 @Getter + @Accessors(fluent) 生成。 */
    private final String displayName;

    PluginState(String displayName) {
        this.displayName = displayName;
    }

    /**
     * 本状态是否可对事件作出响应。
     *
     * @return 仅 {@link #ACTIVE} 返回 {@code true}
     */
    public boolean isServiceable() {
        return this == ACTIVE;
    }

    /**
     * 本状态是否为终态。
     *
     * @return {@link #DISPOSED} 与 {@link #FAILED} 返回 {@code true}
     */
    public boolean isTerminal() {
        return this == DISPOSED || this == FAILED;
    }
}
