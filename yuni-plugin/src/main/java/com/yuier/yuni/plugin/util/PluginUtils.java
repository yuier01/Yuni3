package com.yuier.yuni.plugin.util;

import com.yuier.yuni.core.model.bot.BotApp;
import com.yuier.yuni.core.util.SpringContextUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 插件编写静态工具类的<b>遗留入口</b>——已被 {@code YuniContext} 取代。
 *
 * <h2>本类为什么还留着一个方法</h2>
 * <p>P6 把旧的静态门面整体删除了：本类原有的 33 个静态方法（{@code getYuniBot}、
 * {@code getBean}、{@code simpleGet}、{@code sendGroupMessage}、{@code repo}、
 * {@code registerEntity} …）现在<b>一个都不存在</b>。保留本类只为承载两件事：</p>
 * <ol>
 *   <li>下面这张<b>迁移对照表</b>——它是「旧写法现在该怎么写」的唯一出处，
 *       删掉本类会让这张表无处可放，而它正是排查「迁移后我的插件不编译了」时
 *       最需要的东西；</li>
 *   <li>{@link #getAppDatabaseUrl()}——框架自身的
 *       {@code PluginDataService} 仍需要应用数据库 URL。那属于框架内部装配，
 *       不是插件 API，因此不适合塞进 {@code YuniContext}。</li>
 * </ol>
 *
 * <h2>迁移对照表</h2>
 * <table border="1">
 *   <caption>旧静态方法 → 新写法</caption>
 *   <tr><th>旧：{@code PluginUtils.xxx}</th><th>新</th></tr>
 *   <tr><td>{@code getYuniBot()} / {@code getBotId()} / {@code getBotNickName()}</td>
 *       <td>{@code ctx.getBot()}</td></tr>
 *   <tr><td>{@code getBean(Class)} / {@code getBean(String)}</td>
 *       <td>{@code ctx.findService(Class)} / {@code ctx.tryFindService(Class)}</td></tr>
 *   <tr><td>{@code getBotAppCommandFlag()}</td>
 *       <td>框架侧已处理，插件无需读取命令前缀</td></tr>
 *   <tr><td>{@code loadJsonConfigFromPlugin(f, C, cls)}</td>
 *       <td>{@code ctx.getConfigLoader().load(f, C)}</td></tr>
 *   <tr><td>{@code loadTextFromPluginFolder(f, cls)}</td>
 *       <td>{@code ctx.getConfigLoader().loadText(f)}</td></tr>
 *   <tr><td>{@code loadFontFromPlugin(f, size, cls)}</td>
 *       <td>{@code ctx.getConfigLoader().open(f)} + {@code Font.createFont(...)}</td></tr>
 *   <tr><td>{@code getPluginRootPath(cls)}</td>
 *       <td>{@code ctx.getDataDir()} / {@code ctx.getConfigLoader().getDataDir()}</td></tr>
 *   <tr><td>{@code sendGroupMessage(g, chain)} / {@code sendPrivateMessage(u, chain)}</td>
 *       <td>{@code ctx.getSender().group(g).chain(view).send()}</td></tr>
 *   <tr><td>{@code simpleGet(url, C)} / {@code simplePost(url, params, C)}</td>
 *       <td>{@code ctx.getHttp().get(url).execute(C)}</td></tr>
 *   <tr><td>{@code getGroupMemberName(g, u)}</td>
 *       <td>{@code ctx.getBot().getGroupMemberInfo(g, u)}</td></tr>
 *   <tr><td>{@code findUserGroups(u)}</td>
 *       <td>{@code ctx.getBot().getGroupList()}（自行筛选）</td></tr>
 *   <tr><td>{@code registerEntity(C)} / {@code repo(C)} / {@code query(C)} /
 *       {@code delete(C)} / {@code update(C)} / {@code db()}</td>
 *       <td>{@code ctx.getDb().registerEntity(C)} / {@code ctx.getDb().getRepository(C)} 等</td></tr>
 *   <tr><td>{@code serialize(obj)} / {@code deserialize(json, C)}</td>
 *       <td>不会再用到——{@code ctx.getCache()} 直接读写目标类型</td></tr>
 *   <tr><td>{@code getRandomElement(list)} / {@code checkHitProbability(r)}</td>
 *       <td>纯工具函数，插件自行实现（框架不提供）</td></tr>
 *   <tr><td>{@code getPluginManager()}</td>
 *       <td>{@code ctx.getPlugins()}（{@code PluginAdmin}）</td></tr>
 * </table>
 *
 * <p>设计文档 §1.1 记录了这个静态门面的三条病灶（无法测试、隐式全局状态、
 * 生命周期不可追踪），{@code YuniContext} 是它们的解药。因此本类<b>不应再增加
 * 任何方法</b>——它是一个墓碑，不是待办清单。</p>
 *
 * @deprecated 插件请使用 {@code YuniContext}（{@code apply(ctx)} 的参数）。
 *             本类不再提供任何插件 API。
 */
@Deprecated(since = "P6", forRemoval = true)
@Slf4j
public final class PluginUtils {

    /** 工具类不可实例化。 */
    private PluginUtils() {
    }

    /**
     * 取应用数据库的连接 URL。
     *
     * <p><b>仅供框架内部装配使用</b>（目前只有 {@code PluginDataService} 在建
     * 自研 ORM 的 Jdbi 时调用）。插件侧请用 {@code ctx.getDb()}——
     * 它已经把连接管理封在 {@code PluginDataService} 里，插件不需要知道 URL。</p>
     *
     * @return {@code jdbc:sqlite:./<配置的库文件名>}
     * @throws IllegalStateException Spring 容器未就绪时，或配置项缺失时
     */
    public static String getAppDatabaseUrl() {
        BotApp botApp = SpringContextUtil.getBean(BotApp.class);
        if (botApp == null || botApp.getSqliteDbFile() == null || botApp.getSqliteDbFile().isBlank()) {
            // 旧实现直接解引用，配置缺失时抛的是与病因无关的 NPE
            throw new IllegalStateException(
                    "缺少配置 bot.app.sqlite-db-file，无法确定插件数据库位置。"
                            + "请检查 application.yml。");
        }
        return "jdbc:sqlite:./" + botApp.getSqliteDbFile();
    }
}
