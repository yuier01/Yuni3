package com.yuier.yuni.plugin.api;

import java.util.List;

/**
 * 作用域查询服务——「某个插件在当前作用域下是否生效」。
 *
 * <h2>为什么需要它</h2>
 * <p>定时任务与主动插件有一个与被动插件不同的问题：<b>它们不由事件触发，
 * 因此没有「当前群号」这个上下文</b>。而被动的分发路径上，框架可以自动施加
 * {@link ScopeFilter} 过滤（见设计文档 §8.3）。</p>
 *
 * <p>于是像「疯狂星期四」这样的插件在广播文案时需要自己回答：
 * 「这个群是否启用了本插件？」现行做法是查
 * {@code PluginEnableProcessor.isPluginEnabled(groupId, CrazyThursday.class)}——
 * 那是框架内部 API，插件本不应该依赖它。</p>
 *
 * <p>本服务把该查询收敛为一个稳定的插件侧契约：</p>
 * <pre>{@code
 * // 向「启用了本插件的群」广播
 * ScopeQuery scope = ctx.scope();
 * for (BotGroupInfo group : groupList) {
 *     if (!scope.isEnabledIn(group.getGroupId())) continue;   // 尊重群粒度启停
 *     ctx.sender().group(group.getGroupId()).text(文案).send();
 * }
 * }</pre>
 *
 * @see YuniContext#scope()
 */
public interface ScopeQuery {

    /**
     * 判定当前插件是否在指定群生效。
     *
     * <p>实现应综合当前注册的 {@link ScopeFilter}、持久化的群例外配置
     * 与插件元数据的 {@code defaultEnable}——即现行系统的三层判定
     * （群例外 → 全局例外 → 默认值）。</p>
     *
     * @param groupId 群号
     * @return 生效返回 {@code true}
     */
    boolean isEnabledIn(long groupId);

    /**
     * 当前插件生效的全部群号。
     *
     * <p>用于「只向启用了本插件的群广播」。返回空列表表示当前没有任何群生效。</p>
     *
     * @return 群号列表，不可变
     */
    List<Long> enabledGroups();

    /**
     * 当前插件是否在全局作用域生效（即不限群）。
     *
     * @return 全局生效返回 {@code true}
     */
    boolean isGloballyEnabled();
}
