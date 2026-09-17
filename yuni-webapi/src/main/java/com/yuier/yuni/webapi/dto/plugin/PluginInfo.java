package com.yuier.yuni.webapi.dto.plugin;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;
import java.util.Set;

/**
 * 插件列表响应条目。
 *
 * <h2>P6 迁移说明：两个字段的语义变了</h2>
 * <table border="1">
 *   <caption>字段语义变化</caption>
 *   <tr><th>字段</th><th>旧含义</th><th>新含义</th></tr>
 *   <tr><td>{@code fullId}</td>
 *       <td>{@code 模块ID-插件类全限定名}<br>如 {@code steam-steamstatus.MemberSteamStatus}</td>
 *       <td>{@code 模块ID/插件显示名}<br>如 {@code steam/群友 steam 游戏状态监控}</td></tr>
 *   <tr><td>{@code type}</td>
 *       <td>旧的八种插件类型之一：{@code COMMAND} / {@code PATTERN} / …</td>
 *       <td>运行状态：{@code ACTIVE} / {@code FAILED} / {@code PENDING} / …</td></tr>
 * </table>
 *
 * <p>{@code type} 的语义变化是设计决定的推论而非随意改动：新系统取消了
 * 「插件类型」这一<b>声明</b>——它现在是「插件在 {@code apply} 里注册了什么能力」
 * 的推论，无法作为元数据字段返回。而运行状态才是管理端真正需要的信息
 * （旧系统完全没有它，于是「插件在列表里但没反应」无法诊断）。</p>
 *
 * <p><b>字段名保持不变</b>以免破坏前端契约；前端需要把标签映射从旧的八种类型
 * 改为运行状态（已同步修改 {@code InstalledPlugins.vue} 与
 * {@code PluginDetailModal.vue}）。</p>
 */
@Data
@AllArgsConstructor
public class PluginInfo {

    /** 插件标识，形如 {@code steam/MemberSteamStatus}。 */
    private String fullId;

    /** 插件显示名。 */
    private String name;

    /** 描述。 */
    private String description;

    /** 使用提示。 */
    private List<String> tips;

    /** 版本。 */
    private String version;

    /** 作者。 */
    private String author;

    /** 所属模块 ID。 */
    private String moduleId;

    /** 是否内置插件（内置插件只有 bot 拥有者可启停）。 */
    private boolean builtIn;

    /**
     * 运行状态。
     *
     * <p>取值：{@code ACTIVE} / {@code FAILED} / {@code PENDING} /
     * {@code LOADING} / {@code UNLOADING} / {@code DISPOSED}。</p>
     */
    private String type;

    /**
     * 当前<b>全局</b>是否启用。
     *
     * <p>没有它时管理端无法渲染「全局开关」控件——而「整体打开一个默认关闭的
     * 插件」只能靠全局开关，逐个群开启等于枚举无限集。</p>
     *
     * <p>注意语义：这是「不限群时是否生效」，不是「当前是否对所有群生效」。
     * 某个群可能有例外，因此管理端应当把它与
     * {@code /admin/group/plugin-status} 的按群结果分别展示。</p>
     */
    private boolean globalEnabled;

    /**
     * 被显式<b>开启</b>的群（群例外）。
     *
     * <p>只包含白名单形态的例外。全局开启、个别群关闭的情形下，
     * 「哪些群被关闭」不在这里——那种信息的体量随群数增长，
     * 不适合塞进列表接口。需要精确知道的场景请查按群接口。</p>
     */
    private Set<Long> enabledGroups;

    /** 加载失败的原因摘要；仅 {@code type == FAILED} 时有值，否则为 {@code null}。 */
    private String failureSummary;

    /** 未满足的依赖服务类型名；仅 {@code type == PENDING} 时非空。 */
    private List<String> missingServices;
}
