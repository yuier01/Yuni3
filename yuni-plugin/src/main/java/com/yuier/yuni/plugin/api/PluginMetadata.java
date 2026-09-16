package com.yuier.yuni.plugin.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 插件元数据——插件的身份与展示信息。
 *
 * <h2>来源</h2>
 * <p>本对象由插件自己在 {@code YuniPlugin.metadata()} 中返回，<b>代码即唯一真相</b>。
 * 现行系统把插件级元数据放在 jar 同级的 {@code metadata.json} 里，于是同一份信息
 * 有两处来源（代码里的类名 + json 里的 id），必然漂移。</p>
 *
 * <p>新方案：{@code metadata.json} 只保留<b>模块级</b>信息
 * （{@code module_id} / {@code module_name} / 插件类全限定名清单），
 * 插件级的名称、版本、描述、作者等一律以本对象为准。</p>
 *
 * <h2>不变性</h2>
 * <p>本类不可变。{@code Builder} 用于构造，{@link #build()} 后即冻结。</p>
 *
 * @see YuniPlugin#metadata()
 */
public final class PluginMetadata {

    private final String name;
    private final String version;
    private final String description;
    private final List<String> tips;
    private final String author;
    private final List<String> dependencies;
    private final boolean defaultEnable;
    private final boolean buildIn;

    private PluginMetadata(Builder b) {
        this.name = b.name;
        this.version = b.version;
        this.description = b.description;
        this.tips = List.copyOf(b.tips);
        this.author = b.author;
        this.dependencies = List.copyOf(b.dependencies);
        this.defaultEnable = b.defaultEnable;
        this.buildIn = b.buildIn;
    }

    /**
     * 创建元数据构建器。
     *
     * @param name 插件显示名，用于日志与管理端展示；不应为空
     * @return 构建器
     */
    public static Builder of(String name) {
        return new Builder(name);
    }

    /**
     * 插件显示名。
     *
     * @return 显示名
     */
    public String name() {
        return name;
    }

    /**
     * 插件版本。
     *
     * @return 版本字符串；未设置时为 {@code "0.0.1"}
     */
    public String version() {
        return version;
    }

    /**
     * 插件描述，会展示给用户。
     *
     * @return 描述；未设置时为空串
     */
    public String description() {
        return description;
    }

    /**
     * 使用提示，每项一条。
     *
     * <p>为空时框架回退为「只含描述的单元素列表」，与现行
     * {@code PluginLoadProcessor.pluginMetadataPreProcess} 的兜底行为一致。</p>
     *
     * @return 不可变的提示列表
     */
    public List<String> tips() {
        return tips.isEmpty() && !description.isEmpty()
                ? List.of(description)
                : tips;
    }

    /**
     * 作者信息。
     *
     * @return 作者；未设置时为空串
     */
    public String author() {
        return author;
    }

    /**
     * 依赖的其他插件名。
     *
     * <p><b>注意</b>：现行系统的该字段是纯声明，从未被检查
     * （{@code PluginMetadata} 的注释自承「实际上目前并没有实现依赖检查」）。
     * 新系统中插件间依赖由 {@code inject()} 表达服务依赖，本字段仅作展示用途。</p>
     *
     * @return 不可变的依赖名列表
     */
    public List<String> dependencies() {
        return dependencies;
    }

    /**
     * 默认是否启用。
     *
     * <p>决定插件首次加载时 {@code ScopeFilter} 的初始值。
     * 对应现行 {@code metadata.json} 的 {@code default_enable}。</p>
     *
     * @return 默认启用返回 {@code true}
     */
    public boolean defaultEnable() {
        return defaultEnable;
    }

    /**
     * 是否为系统内置插件。
     *
     * <p>内置插件在管理端与命令界面中受保护：只有 bot 拥有者可以启停，
     * 对应现行 {@code PluginEnable} 里的 {@code isBuiltIn()} 判定。</p>
     *
     * @return 内置返回 {@code true}
     */
    public boolean buildIn() {
        return buildIn;
    }

    @Override
    public String toString() {
        return "PluginMetadata{name='" + name + "', version='" + version + "'}";
    }

    /**
     * {@link PluginMetadata} 的构建器。
     *
     * <p>全部 setter 风格方法返回 {@code this}，最后调 {@link #build()}。</p>
     */
    public static final class Builder {

        private final String name;
        private String version = "0.0.1";
        private String description = "";
        private List<String> tips = new ArrayList<>();
        private String author = "";
        private List<String> dependencies = new ArrayList<>();
        private boolean defaultEnable = true;
        private boolean buildIn = false;

        private Builder(String name) {
            this.name = Objects.requireNonNull(name, "插件名不能为 null");
        }

        /**
         * 设置版本。
         *
         * @param version 版本字符串
         * @return {@code this}
         */
        public Builder version(String version) {
            this.version = version == null ? "" : version;
            return this;
        }

        /**
         * 设置描述。
         *
         * @param description 描述文本
         * @return {@code this}
         */
        public Builder description(String description) {
            this.description = description == null ? "" : description;
            return this;
        }

        /**
         * 设置使用提示。
         *
         * @param tips 提示项
         * @return {@code this}
         */
        public Builder tips(List<String> tips) {
            this.tips = tips == null ? new ArrayList<>() : new ArrayList<>(tips);
            return this;
        }

        /**
         * 追加一条使用提示。
         *
         * @param tip 提示文本
         * @return {@code this}
         */
        public Builder tip(String tip) {
            this.tips.add(tip);
            return this;
        }

        /**
         * 设置作者。
         *
         * @param author 作者信息
         * @return {@code this}
         */
        public Builder author(String author) {
            this.author = author == null ? "" : author;
            return this;
        }

        /**
         * 设置依赖列表（仅展示用，参见 {@link PluginMetadata#dependencies()}）。
         *
         * @param dependencies 依赖的插件名
         * @return {@code this}
         */
        public Builder dependencies(List<String> dependencies) {
            this.dependencies = dependencies == null ? new ArrayList<>() : new ArrayList<>(dependencies);
            return this;
        }

        /**
         * 设置默认启用状态。
         *
         * @param defaultEnable 默认启用传 {@code true}
         * @return {@code this}
         */
        public Builder defaultEnable(boolean defaultEnable) {
            this.defaultEnable = defaultEnable;
            return this;
        }

        /**
         * 标记为内置插件。
         *
         * @param buildIn 内置传 {@code true}
         * @return {@code this}
         */
        public Builder buildIn(boolean buildIn) {
            this.buildIn = buildIn;
            return this;
        }

        /**
         * 构建不可变元数据。
         *
         * @return 元数据实例
         */
        public PluginMetadata build() {
            return new PluginMetadata(this);
        }
    }
}
