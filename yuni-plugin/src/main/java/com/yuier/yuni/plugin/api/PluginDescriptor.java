package com.yuier.yuni.plugin.api;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.List;

/**
 * 插件描述——由<b>配置文件</b>（metadata.json）提供、与运行行为无关的展示信息。
 *
 * <h2>为什么单独一个类</h2>
 * <p>这些字段改了不影响插件的加载、启停或依赖，只影响管理端与帮助信息里
 * 「长什么样」。把它们从代码里拆出来，运维改版本号 / 描述 / 提示时不需要重新
 * 构建 jar；配置缺失时字段空着即可，插件照样加载。</p>
 *
 * <p>身份与行为字段（{@link PluginDeclaration}）不在这里——那些必须由代码
 * 决定。两者组装为 {@link PluginMetadata}。</p>
 *
 * @see PluginDeclaration
 * @see PluginMetadata
 */
@Getter
@ToString
@EqualsAndHashCode
public final class PluginDescriptor {

    /** 插件显示名；为空时回落到插件 ID。 */
    private final String name;

    /** 版本号；未配置时为空串。 */
    private final String version;

    /** 描述；未配置时为空串。 */
    private final String description;

    /** 使用提示；未配置时为空列表。 */
    private final List<String> tips;

    /** 作者 / 版权声明；未配置时为空串。 */
    private final String author;

    /** 项目主页；未配置时为空串。 */
    private final String homepage;

    /** 代码仓库；未配置时为空串。 */
    private final String repository;

    /** 图标（URL 或资源路径）；未配置时为空串。 */
    private final String icon;

    private PluginDescriptor(Builder builder) {
        this.name = builder.name;
        this.version = builder.version;
        this.description = builder.description;
        this.tips = List.copyOf(builder.tips);
        this.author = builder.author;
        this.homepage = builder.homepage;
        this.repository = builder.repository;
        this.icon = builder.icon;
    }

    /**
     * 空描述——配置缺失时使用。
     *
     * @return 所有字段为空
     */
    public static PluginDescriptor empty() {
        return new Builder().build();
    }

    /**
     * 创建描述构建器。
     *
     * @return 构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 描述构建器。
     */
    public static final class Builder {

        private String name = "";
        private String version = "";
        private String description = "";
        private List<String> tips = List.of();
        private String author = "";
        private String homepage = "";
        private String repository = "";
        private String icon = "";

        private Builder() {
        }

        /**
         * 设置显示名。
         *
         * @param name 显示名
         * @return {@code this}
         */
        public Builder name(String name) {
            this.name = nullToEmpty(name);
            return this;
        }

        /**
         * 设置版本。
         *
         * @param version 版本
         * @return {@code this}
         */
        public Builder version(String version) {
            this.version = nullToEmpty(version);
            return this;
        }

        /**
         * 设置描述。
         *
         * @param description 描述
         * @return {@code this}
         */
        public Builder description(String description) {
            this.description = nullToEmpty(description);
            return this;
        }

        /**
         * 设置提示列表。
         *
         * @param tips 提示
         * @return {@code this}
         */
        public Builder tips(List<String> tips) {
            this.tips = tips == null ? List.of() : List.copyOf(tips);
            return this;
        }

        /**
         * 设置作者 / 版权声明。
         *
         * @param author 作者
         * @return {@code this}
         */
        public Builder author(String author) {
            this.author = nullToEmpty(author);
            return this;
        }

        /**
         * 设置项目主页。
         *
         * @param homepage 主页
         * @return {@code this}
         */
        public Builder homepage(String homepage) {
            this.homepage = nullToEmpty(homepage);
            return this;
        }

        /**
         * 设置代码仓库。
         *
         * @param repository 仓库
         * @return {@code this}
         */
        public Builder repository(String repository) {
            this.repository = nullToEmpty(repository);
            return this;
        }

        /**
         * 设置图标。
         *
         * @param icon 图标
         * @return {@code this}
         */
        public Builder icon(String icon) {
            this.icon = nullToEmpty(icon);
            return this;
        }

        /**
         * 构造描述。
         *
         * @return 描述
         */
        public PluginDescriptor build() {
            return new PluginDescriptor(this);
        }

        private static String nullToEmpty(String value) {
            return value == null ? "" : value;
        }
    }
}
