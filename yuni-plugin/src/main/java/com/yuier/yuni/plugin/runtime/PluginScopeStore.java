package com.yuier.yuni.plugin.runtime;

import com.yuier.yuni.plugin.api.ScopeFilter;

import java.util.Map;

/**
 * 插件作用域配置的持久化端口。
 *
 * <h2>为什么是「端口」而不是直接依赖 MyBatis</h2>
 * <p>{@code yuni-plugin} 里有两种数据访问方式并存：</p>
 * <ul>
 *   <li>插件自己的数据走自研 ORM（{@code PluginDataService} + JDBI）；</li>
 *   <li>框架自身的数据走 MyBatis-Plus（{@code group_plugin_ability} 等）。</li>
 * </ul>
 * <p>运行时不应当知道其中任何一种。把持久化抽象成一个只有一个实现的接口看似
 * 多余，但它带来两件具体收益：</p>
 * <ol>
 *   <li><b>运行时可以脱离数据库测试</b>：P5 的 147 个用例里绝大多数
 *       {@code new PluginRuntime(null, null)} 就能跑，靠的正是这类端口；
 *       若运行时直接注入 {@code GroupPluginAbilityService}，
 *       每个用例都得启 Spring 容器；</li>
 *   <li><b>数据访问方式的迁移不会波及运行时</b>：现在这一实现是
 *       MyBatis 适配器，若将来框架表也迁到自研 ORM，只需换实现。</li>
 * </ol>
 *
 * <h2>与 {@link ScopeFilter.PersistedScope} 的关系</h2>
 * <p>接口的入参出参都用 {@code PersistedScope}（默认值 + 例外群集合），
 * 而不是数据库的行结构。理由：那是<b>语义</b>层的最简表达，
 * 而「一行一个群」是<u>某一种</u>存储选择。将来若改成「一行一个插件 +
 * JSON 列存例外群」，本接口不需要变。</p>
 *
 * @see PluginRuntime#bindScopeStore
 * @see PluginRuntime#restoreScopes
 */
public interface PluginScopeStore {

    /**
     * 读取全部插件的群粒度启停配置。
     *
     * <p>返回值按插件标识分组，每组是「群号 → 该群是否生效」。
     * 只包含<b>有显式设置</b>的群——与默认值一致的项不必出现，
     * 调用方也无法区分（这本就是三态模型里「未设置」的含义）。</p>
     *
     * @return 插件标识 → （群号 → 是否生效）；无数据时返回空 Map
     */
    Map<String, Map<Long, Boolean>> loadAll();

    /**
     * 覆盖保存一个插件的群粒度启停配置。
     *
     * <p><b>覆盖</b>语义：实现应当先清除该插件的全部旧记录再写入，
     * 否则「用户把某群拨回默认值」之后旧记录仍会残留并在下次启动时复活。</p>
     *
     * @param pluginId  插件标识
     * @param persisted 该插件当前的完整配置
     */
    void save(String pluginId, ScopeFilter.PersistedScope persisted);
}
