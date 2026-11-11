package com.yuier.yuni.plugin.api;

import java.util.List;
import java.util.Optional;

/**
 * 插件管理服务——由 {@link YuniContext#getPlugins()} 提供。
 *
 * <h2>为什么插件 API 里要有「管理插件」的能力</h2>
 * <p>Yuni 自身有一个内置的 {@code plugin-manage} 模块，它的职责就是在聊天里
 * 管理插件（{@code /插件 查看}、{@code /插件 开启 3}、{@code /插件 重载}）。
 * 既然它是<b>用插件系统写的插件</b>，它就需要一个插件侧的稳定契约去操作插件系统。</p>
 *
 * <p>现行系统的做法是让那个插件直接依赖框架内部类型：</p>
 * <pre>{@code
 * // PluginShow.java:91-92 旧
 * PluginContainer container = PluginUtils.getBean(PluginContainer.class);
 * PluginEnableProcessor processor = PluginUtils.getBean(PluginEnableProcessor.class);
 *
 * // PluginReload.java:60-61 旧
 * PluginReloadProcessor pluginReloadProcessor = new PluginReloadProcessor();
 * pluginReloadProcessor.reloadPlugin(jarFile);
 * }</pre>
 * <p>{@code PluginContainer} / {@code PluginEnableProcessor} / {@code PluginReloadProcessor}
 * 都是 {@code yuni-plugin} 的内部实现类。插件依赖它们意味着：</p>
 * <ul>
 *   <li>框架内部重构会直接打断内置插件（而内置插件与框架<b>同版本发布</b>，
 *       所以这个耦合在开发期不会被发现，只在插件独立升级时爆炸）；</li>
 *   <li>「热重载」逻辑在 {@code PluginReloadProcessor} 与 webapi 的
 *       {@code PluginService} 里<b>各写了一遍</b>——同一个能力两个实现，
 *       必然漂移；</li>
 *   <li>插件可以通过这些内部类做框架没有承诺过的事（例如直接改索引）。</li>
 * </ul>
 *
 * <p>本接口是那个缺失的契约：把「插件管理」需要的能力收拢为一组有文档、
 * 有语义保证的方法，{@code plugin-manage} 与 webapi 都通过它操作。
 * 热重载实现因此只有一份。</p>
 *
 * <h2>与 {@link ScopeQuery} 的分工</h2>
 * <table border="1">
 *   <caption>两个作用域相关接口</caption>
 *   <tr><th></th><th>{@link ScopeQuery}</th><th>PluginAdmin</th></tr>
 *   <tr><td>回答的问题</td><td>「<b>我</b>在某个群生效吗」</td>
 *       <td>「<b>任意</b>插件在某个群生效吗 / 怎么改」</td></tr>
 *   <tr><td>身份</td><td>绑定到调用方自己的插件</td><td>与调用方无关，按 id 指定目标</td></tr>
 *   <tr><td>是否有写操作</td><td>无（纯查询）</td><td>有（启停、重载）</td></tr>
 *   <tr><td>典型使用方</td><td>定时任务类插件</td><td>管理端、webapi、plugin-manage</td></tr>
 * </table>
 * <p>两者共享同一份作用域数据，因此不会出现「{@code ScopeQuery} 说启用、
 * {@code PluginAdmin} 说没启用」的不一致。</p>
 *
 * <h2>权限由调用方负责</h2>
 * <p>本接口<b>不</b>做权限校验——它不知道自己被谁调用。调用方（{@code plugin-manage}
 * 的命令处理器、webapi 的鉴权层）必须先完成鉴权。唯一的例外是
 * {@link #setEnabled} 对内置插件的保护，那是<b>结构性</b>约束而非权限约束：
 * 它要求调用方显式传入「调用者是否为 bot 拥有者」，把判断依据摆在签名里，
 * 而不是让每个调用方各自记着要判。</p>
 *
 * @see PluginView
 * @see YuniContext#getPlugins()
 */
public interface PluginAdmin {

    // ==================== 查询 ====================

    /**
     * 全部插件模块，按模块 ID 排序。
     *
     * <p>返回的是<b>已加载的模块</b>（jar 已被扫描并尝试加载）。jar 存在但
     * 加载失败的模块仍会在列表里出现——它的插件状态为
     * {@link PluginState#FAILED}，这正是管理端需要展示的情况。</p>
     *
     * @return 不可变列表；无模块时为空列表
     */
    List<PluginModuleView> getModules();

    /**
     * 全部插件（跨模块），按模块 ID、再按加载顺序。
     *
     * @return 不可变列表；无插件时为空列表
     */
    List<PluginView> getPlugins();

    /**
     * 按标识取单个插件。
     *
     * @param pluginId 插件标识
     * @return 视图；不存在时为空
     */
    Optional<PluginView> plugin(String pluginId);

    /**
     * 某模块下的插件。
     *
     * @param moduleId 模块 ID
     * @return 不可变列表；模块不存在时为空列表
     */
    List<PluginView> pluginsOfModule(String moduleId);

    // ==================== 群粒度启停 ====================

    /**
     * 判定某插件的<b>全局开关</b>是否处于开启状态。
     *
     * <h2>它回答的确切问题</h2>
     * <p>「这个插件的<b>有效全局默认值</b>是否生效」——也就是
     * <b>不考虑群例外时</b>它是否开启。群例外由 {@link #isEnabledIn} 回答。</p>
     *
     * <p>与 {@link #setGlobalEnabled} 是一对读写操作，两者<b>对偶</b>：</p>
     * <pre>{@code
     * admin.setGlobalEnabled(p, V);
     * admin.isGloballyEnabled(p) == V;      // 恒成立
     * }</pre>
     *
     * <h2>群例外不影响本方法</h2>
     * <table border="1">
     *   <caption>判定规则</caption>
     *   <tr><th>情形</th><th>返回</th><th>说明</th></tr>
     *   <tr><td>默认生效，无群例外</td><td>{@code true}</td><td>对所有群生效</td></tr>
     *   <tr><td>默认生效，有群例外</td><td>{@code true}</td>
     *       <td>整体是开的，只是有几个群被单独覆盖——
     *           管理端应展示为「全局开 + N 个群例外」</td></tr>
     *   <tr><td>默认不生效，无群例外</td><td>{@code false}</td>
     *       <td>任何群都不生效</td></tr>
     *   <tr><td>默认不生效，有群例外（白名单）</td><td>{@code false}</td>
     *       <td>整体是关的，只在列出的群生效——
     *           管理端应展示为「全局关 + N 个群例外」</td></tr>
     * </table>
     *
     * <p>换句话说：<b>本方法只看「底座」，不看覆盖</b>。
     * 判定依据是「有效默认值」——依次取「用户显式设置的全局开关」→
     * 「插件基线自带的默认值」→「元数据的 {@code defaultEnable}」。</p>
     *
     * <h3>为什么不是「是否每个群都生效」</h3>
     * <p>那个读法在「默认开 + 个别群关」时返回 {@code false}，
     * 于是 setter 写 {@code true} 而 getter 读回 {@code false}——
     * 管理端的开关保存后会立刻显示成相反状态，且「全局关闭」无法被幂等表达。
     * 管理端要展示「是不是每个群都生效」，应当把它与 {@link #enabledGroups}
     * 一起渲染，而不是指望本方法承载那个含义。</p>
     *
     * <p>插件不存在时返回 {@code false}。</p>
     *
     * @param pluginId 插件标识
     * @return 全局默认生效时返回 {@code true}
     * @see #setGlobalEnabled
     * @see #isEnabledIn
     */
    boolean isGloballyEnabled(String pluginId);

    /**
     * 判定某插件在某个群是否生效。
     *
     * <p>判定链与分发路径完全一致（三层回落）：群例外 → 全局例外 →
     * 元数据的 {@link PluginView#isDefaultEnable()}。<b>这是权威答案</b>——
     * 管理端展示、插件自查都应当以本方法为准，不要自己重算。</p>
     *
     * <p>插件不存在时返回 {@code false}（而非抛异常）：这是一个可以被问出的
     * 普通问题，用户输入错 id 不应该变成一次异常。</p>
     *
     * @param pluginId 插件标识
     * @param groupId  群号
     * @return 生效返回 {@code true}
     */
    boolean isEnabledIn(String pluginId, long groupId);

    /**
     * 某插件生效的全部群号。
     *
     * <p>注意语义：返回的是「有<b>显式</b>群例外的群」还是「全部生效群」取决于
     * 实现。对「只向启用了本插件的群广播」这个用途，调用方通常还知道自己要遍历
     * 哪些候选群，因此更常用的是 {@link #isEnabledIn}。</p>
     *
     * @param pluginId 插件标识
     * @return 群号列表；插件不存在或全局生效（无群例外）时为空列表
     */
    List<Long> enabledGroups(String pluginId);

    /**
     * 开启 / 关闭某插件在某个群的生效状态。
     *
     * <p>这是<b>唯一的写操作</b>，也是新系统相对现行系统最重要的简化：
     * 现行 {@code PluginEnable} 做四件事——判越界、判内置插件与权限、调插件自身的
     * {@code enable()}/{@code disable()} 回调、调 {@code PluginManager} 改状态。
     * 新系统里第三件事（插件回调）已随基类一起消失，第四件事就是本方法。</p>
     *
     * <h3>内置插件保护</h3>
     * <p>当目标为 {@link PluginView#isBuildIn()} 且 {@code callerIsMaster} 为
     * {@code false} 时抛 {@link PluginAdminException}。把该标志放进签名是刻意的：
     * 它强制调用方在编译期就面对「你有没有验过这个人是不是拥有者」这个问题。</p>
     *
     * @param pluginId       插件标识
     * @param groupId        群号
     * @param enabled        {@code true} 开启，{@code false} 关闭
     * @param callerIsMaster 调用者是否为 bot 拥有者
     * @throws PluginAdminException 插件不存在，或内置插件被非拥有者操作
     */
    void setEnabled(String pluginId, long groupId, boolean enabled, boolean callerIsMaster);

    /**
     * 设置插件的<b>全局</b>启停（不限群），并保留已有的群例外。
     *
     * <h3>与 {@link #setEnabled} 的分工</h3>
     * <p>这是三态模型里两个正交的维度：</p>
     * <ul>
     *   <li>{@link #setEnabled} 改的是「<b>这个群</b>与默认值相反」——
     *       即群例外；</li>
     *   <li>本方法改的是「<b>默认值本身</b>」——即全局开关。</li>
     * </ul>
     * <p>两者不可互相替代。特别是「整体打开一个 {@code defaultEnable = false}
     * 的插件」只能靠本方法：若用 {@code setEnabled} 逐个群开启，等于枚举全部群，
     * 而群是无限集。</p>
     *
     * <p>本方法<b>不改动</b>已有群例外。因此「全局关闭 + 某群开启」这类配置在
     * 全局开关被改回开启后，那个「某群开启」的例外会自动变成「与全局一致」
     * 并被 {@link PluginRuntime} 侧的三态写入规范化清除。</p>
     *
     * <h3>内置插件保护</h3>
     * <p>与 {@link #setEnabled} 相同。</p>
     *
     * @param pluginId       插件标识
     * @param enabled        全局是否生效
     * @param callerIsMaster 调用者是否为 bot 拥有者
     * @throws PluginAdminException 插件不存在，或内置插件被非拥有者操作
     */
    void setGlobalEnabled(String pluginId, boolean enabled, boolean callerIsMaster);

    /**
     * 清除某插件的全部群例外，回到元数据声明的 {@link PluginView#isDefaultEnable()}。
     *
     * <p>与 {@link #setEnabled} 的分工：后者调整个别群的开关，本方法是
     * 「重置为默认」——管理端通常把它渲染成一个「恢复默认」按钮。</p>
     *
     * @param pluginId 插件标识
     * @throws PluginAdminException 插件不存在
     */
    void resetScope(String pluginId);

    // ==================== 生命周期 ====================

    /**
     * 重载某插件所在的模块。
     *
     * <p>语义：卸载该模块的全部插件 → 关闭其 ClassLoader → 重新读取 jar →
     * 以新的 ClassLoader 原位重挂。执行期间该模块的插件对外不可服务
     * （状态为 {@link PluginState#UNLOADING}，分发器不会向它们投递事件）。</p>
     *
     * <h3>作用域配置保留</h3>
     * <p><b>重载不重置群粒度启停配置。</b>一次重载不应该让运维丢掉全部群例外——
     * 那是「重新安装」的语义，不是「重载」的语义。需要恢复默认请显式调用
     * {@link #setEnabled}。</p>
     *
     * <h3>重载自身</h3>
     * <p>{@code plugin-manage} 重载自己所在的模块时，其调用栈上的类即将被新
     * ClassLoader 替换。实现必须保证<b>调用方返回后再执行卸载</b>，否则会在
     * 执行途中卸载掉正在运行的代码。见实现说明。</p>
     *
     * @param moduleId 模块 ID
     * @throws PluginAdminException 模块不存在，或其 jar 文件/资源目录缺失
     */
    void reload(String moduleId);

    /**
     * 卸载某插件（不重新加载）。
     *
     * <p>用于「插件写坏了、暂时摘掉」的场景。与 {@link #reload} 的区别是
     * 它不重新读 jar。模块的全部插件都卸载后，模块从列表中消失。</p>
     *
     * @param pluginId 插件标识
     * @throws PluginAdminException 插件不存在
     */
    void unload(String pluginId);

    /**
     * 插件运行统计摘要，用于管理端看板。
     *
     * @return 形如 {@code "模块 13 / 插件 23 / 生效 21 / 失败 1 / 等待依赖 1"}
     */
    String getSummary();
}
