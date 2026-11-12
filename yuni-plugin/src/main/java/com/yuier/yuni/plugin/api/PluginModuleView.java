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

 *
 * @see PluginAdmin#getModules()
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
    String getId();

    /**
     * 模块显示名。
     *
     * <p>来自 {@code metadata.json} 的 {@code module_name}。缺失时回落为模块 ID。</p>
     *
     * @return 显示名
     */
    String getName();

    /**
     * jar 文件名。
     *
     * <p>热重载需要据此定位磁盘上的文件；管理端展示它有助于运维确认
     * 「我换的 jar 到底是不是这个」。</p>
     *
     * @return jar 文件名
     */
    String getJarFileName();

    /**
     * 本模块的顶层插件。
     *
     * @return 不可变列表；本模块无成功加载的插件时为空列表
     */
    List<PluginView> getPlugins();
}
