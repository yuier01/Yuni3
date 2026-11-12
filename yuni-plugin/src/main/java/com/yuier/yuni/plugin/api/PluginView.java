package com.yuier.yuni.plugin.api;

import java.util.List;
import java.util.Optional;

/**
 * 一个已加载插件的只读视图。
 *
 * <h2>取代了什么</h2>
 * <p>现行系统里「插件列表」由 {@code PluginContainer} 维护，而那份数据结构
 * 只在框架内部可用——插件自身要展示插件列表，只能
 * {@code PluginUtils.getBean(PluginContainer.class)} 去捞框架内部类
 * （{@code PluginShow} 就是这么做的）。本接口把插件列表需要的全部信息
 * 收敛为一个稳定契约。</p>
 *
 * <h2>为什么不是「一个插件实例的包装」</h2>
 * <p>本接口<b>不</b>暴露 {@link YuniPlugin} 实例本身，理由有两条：</p>
 * <ol>
 *   <li><b>已卸载插件的实例引用会被泄漏</b>：视图对象可能被管理端缓存或在
 *       一次请求中持有，如果它带着插件实例，就等于让一个已 {@code dispose} 的
 *       ClassLoader 无法回收（设计文档 §9.4 的 P0 事故正是这类问题）；</li>
 *   <li><b>不需要</b>：消费方要的是「叫什么、是什么状态、在哪些群生效」，
 *       而不是「拿到实例去调用它」。需要跨插件调用时走服务
 *       （{@link YuniContext#findService(Class)}），而不是拿到插件实例直接调。</li>
 * </ol>
 *
 * <h2>与 {@link PluginMetadata} 的关系</h2>
 * <p>本接口把 {@code PluginMetadata} 的字段<b>平铺</b>出来，而不是返回一个
 * {@code getMetadata()}。原因是视图需要把元数据与运行时状态（{@link #getState()}、
 * {@link #getMissingServices()}）放在一起展示，而元数据本身不包含这些。
 * 平铺使消费方写模板时不必在两层对象间跳转。</p>
 *
 * @see PluginAdmin
 */
public interface PluginView {

    // ==================== 身份 ====================

    /**
     * 插件标识。
     *
     * <p>格式由运行时决定，形如 {@code "artifactId/PluginName"}。这是
     * 启停与重载操作使用的键——比「序号」稳定（序号会随加载顺序与热重载变化，
     * 现行系统的 index 偏移修正正是由此而来）。</p>
     *
     * @return 插件标识
     */
    String getId();

    /**
     * 所属加载单元标识。
     *
     * <p>对应 jar 包。一个 jar 可包含多个插件；重载以 jar 为单位。</p>
     *
     * @return 加载单元标识；不属于任何 jar 时为空串
     */
    String getArtifactId();

    // ==================== 元数据 ====================

    /**
     * 插件显示名。
     *
     * @return 显示名
     */
    String getName();

    /**
     * 版本。
     *
     * @return 版本字符串
     */
    String getVersion();

    /**
     * 描述。
     *
     * @return 描述文本
     */
    String getDescription();

    /**
     * 使用提示，每项一条。
     *
     * @return 不可变列表；无提示时为空列表
     */
    List<String> getTips();

    /**
     * 作者。
     *
     * @return 作者信息
     */
    String getAuthor();

    /**
     * 是否为系统内置插件。
     *
     * <p>内置插件在管理端受保护：只有 bot 拥有者可以启停。对应现行
     * {@code PluginEnable} 里那段 {@code isBuiltIn() && permission < MASTER}
     * 的判定——新系统中该判定收敛到 {@link PluginAdmin#setEnabled} 一处。</p>
     *
     * @return 内置返回 {@code true}
     */
    boolean isBuildIn();

    /**
     * 元数据声明的默认启用状态。
     *
     * <p>群粒度启停三层回落链的最后一层。管理端展示时应把它与
     * {@link PluginAdmin#isEnabledIn} 的<b>实际结果</b>区分开：前者是配置的
     * 默认值，后者是当前生效值。</p>
     *
     * @return 默认启用返回 {@code true}
     */
    boolean isDefaultEnable();

    // ==================== 运行状态 ====================

    /**
     * 当前运行状态。
     *
     * @return 状态枚举
     */
    PluginState getState();

    /**
     * 当前未满足的依赖服务类型名。
     *
     * <p>仅 {@link PluginState#PENDING} 时有意义。这是「我的插件为什么没启动」
     * 这个问题的直接答案——现行系统里它只能靠猜。</p>
     *
     * @return 不可变列表；无缺失依赖时为空列表
     */
    List<String> getMissingServices();

    /**
     * 失败原因摘要。
     *
     * <p>仅 {@link PluginState#FAILED} 时有值。只给出「异常类名 + 消息」，
     * 完整堆栈在日志里——管理端不需要、也不应该承载堆栈。</p>
     *
     * @return 失败摘要；非 FAILED 状态时为空
     */
    Optional<String> getFailureSummary();
}
