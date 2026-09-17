package com.yuier.yuni.plugin.api;

import java.util.List;

/**
 * 一个插件模块的只读视图。
 *
 * <h2>「模块」是什么</h2>
 * <p>一个<b>模块</b>对应一个 jar 包，可包含多个插件。这是 Maven 构建与运维
 * 部署的天然单位（{@code plugin-repo} 下每个子模块产出一个 jar），
 * 也是<b>热重载的最小单位</b>——重载必须整包换 ClassLoader，无法只重载包内一个类。</p>
 *
 * <p>管理端分组展示插件列表时也按模块分组：这与现行 {@code PluginShow}
 * 的 {@code PluginListModule} 语义一致，只是数据来源从
 * {@code PluginContainer} 换成了本接口。</p>
 *
 * <h2>为什么模块视图里的插件是平铺列表而不是树</h2>
 * <p>新系统支持 {@code ctx.plugin(child)} 挂载子插件，形成父子关系。但现行系统的
 * 管理端<b>没有</b>树的展示需求，而子插件在管理端单独列出来会造成困惑
 * （用户会以为它能独立启停，实际上它随父插件卸载）。因此本视图只包含
 * <b>顶层插件</b>——子插件是父插件的实现细节，不是运维对象。</p>
 *
 * @see PluginAdmin#modules()
 */
public interface PluginModuleView {

    /**
     * 模块 ID。
     *
     * <p>来自 {@code metadata.json} 的 {@code module_id}。启停与重载操作使用
     * 它作为键。</p>
     *
     * @return 模块 ID
     */
    String id();

    /**
     * 模块显示名。
     *
     * <p>来自 {@code metadata.json} 的 {@code module_name}。缺失时回落为模块 ID。</p>
     *
     * @return 显示名
     */
    String name();

    /**
     * jar 文件名。
     *
     * <p>热重载需要据此定位磁盘上的文件；管理端展示它有助于运维确认
     * 「我换的 jar 到底是不是这个」。</p>
     *
     * @return jar 文件名
     */
    String jarFileName();

    /**
     * 本模块的顶层插件。
     *
     * @return 不可变列表；本模块无成功加载的插件时为空列表
     */
    List<PluginView> plugins();
}
