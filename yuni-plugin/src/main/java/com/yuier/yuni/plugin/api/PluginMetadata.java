package com.yuier.yuni.plugin.api;

import lombok.Getter;

import java.util.List;
import java.util.Objects;

/**
 * 插件元数据——加载时组装完成的扁平视图。
 *
 * <h2>三个模型的关系</h2>
 * <ul>
 *   <li>{@link PluginDeclaration}：代码侧输入（声明键 / defaultEnable / buildIn）；</li>
 *   <li>{@link PluginDescriptor}：配置侧输入（展示信息）；</li>
 *   <li>{@link PluginMetadata}：两者与加载单元拼装后的<b>单层</b>结果，
 *       不再持有前两者。</li>
 * </ul>
 *
 * <p>唯一 id 由这里持有：{@code 加载单元ID/本地ID}。{@code PluginFiber}
 * 不再单独保存 id，直接委托本对象。</p>
 *
 * @see PluginDeclaration
 * @see PluginDescriptor
 */
@Getter
public final class PluginMetadata {

    /** 全局唯一标识：{@code 加载单元ID/本地ID}。 */
    private final String id;

    /** 所属加载单元标识。 */
    private final String artifactId;


    /** 插件显示名；配置未提供时回落到本地 ID。 */
    private final String name;

    /** 版本号；未配置时为空串。 */
    private final String version;

    /** 描述；未配置时为空串。 */
    private final String description;

    /** 使用提示。 */
    private final List<String> tips;

    /** 作者 / 版权声明；未配置时为空串。 */
    private final String author;

    /** 项目主页；未配置时为空串。 */
    private final String homepage;

    /** 代码仓库；未配置时为空串。 */
    private final String repository;

    /** 图标；未配置时为空串。 */
    private final String icon;

    /** 无用户配置时默认是否生效。 */
    private final boolean defaultEnable;

    /** 是否内置插件。 */
    private final boolean buildIn;

    /** 插件声明的必需服务类型。 */
    private final List<Class<?>> requiredServices;

    private PluginMetadata(String id,
                           String artifactId,
                           String name,
                           String version,
                           String description,
                           List<String> tips,
                           String author,
                           String homepage,
                           String repository,
                           String icon,
                           boolean defaultEnable,
                           boolean buildIn,
                           List<Class<?>> requiredServices) {
        this.id = id;
        this.artifactId = Objects.requireNonNull(artifactId, "artifactId");
        this.name = name == null || name.isBlank() ? keyOf(id) : name;
        this.version = nullToEmpty(version);
        this.description = nullToEmpty(description);
        this.tips = tips == null ? List.of() : List.copyOf(tips);
        this.author = nullToEmpty(author);
        this.homepage = nullToEmpty(homepage);
        this.repository = nullToEmpty(repository);
        this.icon = nullToEmpty(icon);
        this.defaultEnable = defaultEnable;
        this.buildIn = buildIn;
        this.requiredServices = requiredServices == null ? List.of() : List.copyOf(requiredServices);
    }

    /**
     * 组装元数据。
     *
     * @param artifactId       加载单元标识；可为 {@code null}
     * @param declaration      代码侧声明
     * @param descriptor       配置侧描述；可为 {@code null}
     * @param requiredServices 必需服务类型
     * @return 扁平元数据
     */
    public static PluginMetadata of(String artifactId,
                                    PluginDeclaration declaration,
                                    PluginDescriptor descriptor,
                                    List<Class<?>> requiredServices) {
        Objects.requireNonNull(artifactId, "artifactId");
        PluginDescriptor config = descriptor == null ? PluginDescriptor.empty() : descriptor;
        String key = declaration.getKey();
        String id = artifactId.isBlank() ? key : artifactId + "/" + key;
        return new PluginMetadata(
                id,
                artifactId,
                config.getName(),
                config.getVersion(),
                config.getDescription(),
                config.getTips(),
                config.getAuthor(),
                config.getHomepage(),
                config.getRepository(),
                config.getIcon(),
                declaration.isDefaultEnable(),
                declaration.isBuildIn(),
                requiredServices);
    }

    /**
     * 构造最小元数据——无加载单元、无配置描述。
     *
     * <p>供测试与框架占位使用；正常加载路径由加载器提供完整声明与描述。</p>
     *
     * @param key 声明键
     * @return 元数据
     */
    public static PluginMetadata of(String key) {
        return new PluginMetadata(
                key,
                "",
                key,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                true,
                false,
                List.of());
    }

    /**
     * 声明键（加载单元内唯一）——从全局 ID 前缀中截出，不单独存字段。
     *
     * @return 声明键
     */
    public String getKey() {
        return keyOf(id);
    }

    /**
     * 使用提示；未配置且描述非空时回退为「只含描述的单元素列表」。
     *
     * @return 提示列表
     */
    public List<String> getTips() {
        if (!tips.isEmpty()) {
            return tips;
        }
        return description.isBlank() ? List.of() : List.of(description);
    }

    /**
     * 单行诊断描述。
     *
     * @return 形如 {@code PluginMetadata{id='steam/Steam', name='...'}}
     */
    @Override
    public String toString() {
        return "PluginMetadata{id='" + id + "', name='" + name + "'}";
    }

    private static String keyOf(String id) {
        int slash = id.lastIndexOf('/');
        return slash < 0 ? id : id.substring(slash + 1);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
