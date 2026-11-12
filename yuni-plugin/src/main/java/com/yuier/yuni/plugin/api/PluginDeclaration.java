package com.yuier.yuni.plugin.api;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/**
 * 插件声明——由<b>代码</b>提供、与运行行为相关的元数据。
 *
 * <h2>为什么只有这三个字段</h2>
 * <ul>
 *   <li>{@link #getKey()}：声明键，只在所属加载单元内唯一。全局标识由
 *       {@link PluginMetadata#getId()} 拼上加载单元 ID 得到，用户配置按全局
 *       标识持久化，因此它<b>不能</b>由配置文件决定——改配置不应该让用户的
 *       群启停记录失效。</li>
 *   <li>{@link #isDefaultEnable()}：没有任何用户配置时的默认生效值。
 *       它是产品行为，不是展示信息；放在配置里会让运维改一个 json 就悄悄
 *       改变所有群的默认行为。</li>
 *   <li>{@link #isBuildIn()}：安全边界。内置插件只有 bot 拥有者能启停；
 *       如果它可配置，任何能改文件的人都能绕过保护。</li>
 * </ul>
 *
 * <p>名称、版本、描述、提示、作者等<b>展示信息</b>不在这里，而在
 * {@link PluginDescriptor}（来自 metadata.json）。两者组装为
 * {@link PluginMetadata}。</p>
 *
 * @see PluginDescriptor
 * @see PluginMetadata
 */
@Getter
@ToString
@EqualsAndHashCode
public final class PluginDeclaration {

    /** 插件键——在所属加载单元内唯一；全局 ID 由 {@link PluginMetadata#getId()} 拼出。 */
    private final String key;

    /** 无用户配置时默认是否生效。 */
    private final boolean defaultEnable;

    /** 是否内置插件（只有 bot 拥有者能启停）。 */
    private final boolean buildIn;

    private PluginDeclaration(Builder builder) {
        this.key = builder.key;
        this.defaultEnable = builder.defaultEnable;
        this.buildIn = builder.buildIn;
    }

    /**
     * 创建声明构建器。
     *
     * @param key 插件键；不能为空
     * @return 构建器
     */
    public static Builder of(String key) {
        return new Builder(key);
    }

    /**
     * 声明构建器。
     */
    public static final class Builder {

        private final String key;
        private boolean defaultEnable = true;
        private boolean buildIn;

        private Builder(String key) {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("插件键不能为空");
            }
            this.key = key;
        }

        /**
         * 设置默认是否生效。
         *
         * @param defaultEnable 默认值
         * @return {@code this}
         */
        public Builder defaultEnable(boolean defaultEnable) {
            this.defaultEnable = defaultEnable;
            return this;
        }

        /**
         * 设置是否为内置插件。
         *
         * @param buildIn 是否内置
         * @return {@code this}
         */
        public Builder buildIn(boolean buildIn) {
            this.buildIn = buildIn;
            return this;
        }

        /**
         * 构造声明。
         *
         * @return 声明
         */
        public PluginDeclaration build() {
            return new PluginDeclaration(this);
        }
    }
}
