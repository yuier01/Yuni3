package com.yuier.yuni.plugin.runtime;

import com.yuier.yuni.plugin.api.ScopeFilter;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.Map;
import java.util.Set;

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
 *   <li><b>运行时可以脱离数据库测试</b>：P5 的用例里绝大多数
 *       {@code new PluginRuntime(null, null)} 就能跑，靠的正是这类端口；
 *       若运行时直接注入 {@code GroupPluginAbilityService}，
 *       每个用例都得启 Spring 容器；</li>
 *   <li><b>数据访问方式的迁移不会波及运行时</b>：现在的实现是 MyBatis 适配器，
 *       若将来框架表也迁到自研 ORM，只需换实现。</li>
 * </ol>
 *
 * <h2>存储形状：一条记录表达一个插件的全部配置</h2>
 * <p>接口按<b>插件</b>而不是按「数据库行」组织数据。这是刻意的：</p>
 * <ul>
 *   <li>「一行一个群」是<u>某一种</u>存储选择，不是语义的一部分。将来若改成
 *       「一行一个插件 + JSON 列存例外群」，本接口不需要变；</li>
 *   <li>全局开关与群例外是两个正交维度（前者是「默认值是什么」，
 *       后者是「哪些群与默认值相反」）。把它们拆在两种记录形状里会迫使
 *       每个调用方自己拼装。</li>
 * </ul>
 *
 * <h2>与旧 {@code group_plugin_ability} 表的映射</h2>
 * <p>该表的 {@code (groupId, pluginId, ability)} 三元组可以无损承载本接口的形状：</p>
 * <ul>
 *   <li>{@code groupId = null} 的行 → 全局开关（{@link PluginScopeRecord#getGlobalEnabled()}）；</li>
 *   <li>{@code groupId = 具体群号} 的行 → 群例外集合中的一项。</li>
 * </ul>
 * <p>这恰好也是旧系统的用法——{@code PluginEnableProcessor} 在
 * {@code groupId} 为 {@code null} 时就是查全局例外。因此<b>历史数据可直接复用</b>。</p>
 *
 * @see PluginRuntime#bindScopeStore
 * @see PluginRuntime#restoreScopes
 */
public interface PluginScopeStore {

    /**
     * 一个插件的完整作用域配置。
     *
     * @param globalEnabled   全局（不限群）是否生效；{@code null} 表示用户未设置过，
     *                        此时由插件的元数据 {@code defaultEnable} 决定
     * @param exceptionGroups 群例外集合——每个群的值与全局开关<b>相反</b>。
     *                        例如全局开启时，集合里的群是「被关闭的群」；
     *                        全局关闭时，集合里的群是「被开启的群」
     */
    @Getter
    @ToString
    @EqualsAndHashCode
    final class PluginScopeRecord {

        /** 全局（不限群）是否生效；{@code null} 表示用户未设置过。 */
        private final Boolean globalEnabled;

        /** 群例外集合——每个群的值与全局开关相反（构造时冻结）。 */
        private final Set<Long> exceptionGroups;

        /**
         * 构造记录。
         *
         * @param globalEnabled   全局开关；{@code null} 表示未设置
         * @param exceptionGroups 群例外集合；{@code null} 视为空集
         */
        public PluginScopeRecord(Boolean globalEnabled, Set<Long> exceptionGroups) {
            this.globalEnabled = globalEnabled;
            // 冻结例外集合，避免调用方修改影响存储层
            this.exceptionGroups = exceptionGroups == null
                    ? Set.of()
                    : Set.copyOf(exceptionGroups);
        }

        /**
         * 是否为空配置（既无全局设置也无群例外）。
         *
         * @return 空返回 {@code true}
         */
        public boolean isEmpty() {
            return globalEnabled == null && exceptionGroups.isEmpty();
        }
    }

    /**
     * 读取全部插件的作用域配置。
     *
     * <p><b>只包含有显式设置的内容</b>——与插件默认值一致的项不必出现，
     * 调用方也无法区分（这本就是三态模型里「未设置」的含义）。</p>
     *
     * @return 插件标识 → 配置；无数据时返回空 Map
     */
    Map<String, PluginScopeRecord> loadAll();

    /**
     * 覆盖保存一个插件的完整作用域配置。
     *
     * <p><b>覆盖</b>语义：实现必须清除该插件的全部旧记录再写入，
     * 否则「用户把某群拨回默认值」之后旧记录仍会残留并在下次启动时复活。
     * 这正是三态模型要避免的一类 bug。</p>
     *
     * <p>配置为空（无全局设置且无群例外）时也应当被调用并清空旧记录。</p>
     *
     * @param pluginId 插件标识
     * @param record   该插件的完整配置
     */
    void save(String pluginId, PluginScopeRecord record);

    /**
     * 便于实现的便捷方法：由 {@link ScopeFilter.PersistedScope} 构造记录。
     *
     * @param persisted 持久化形态
     * @return 对应的记录
     */
    static PluginScopeRecord of(ScopeFilter.PersistedScope persisted) {
        if (persisted == null) {
            return new PluginScopeRecord(null, Set.of());
        }
        return new PluginScopeRecord(persisted.isEnabledByDefault(), persisted.getExceptionGroups());
    }
}
