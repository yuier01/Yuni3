package com.yuier.yuni.plugin.api;

import lombok.Getter;

import java.util.List;

/**
 * 插件元数据——{@link PluginDeclaration}（代码）与 {@link PluginDescriptor}
 * （配置）组装后的只读视图。
 *
 * <h2>两个来源，各管各的</h2>
 * <table border="1">
 *   <caption>职责划分</caption>
 *   <tr><th>来源</th><th>类型</th><th>字段</th></tr>
 *   <tr><td>代码</td><td>{@link PluginDeclaration}</td>
 *       <td>id、defaultEnable、buildIn</td></tr>
 *   <tr><td>metadata.json</td><td>{@link PluginDescriptor}</td>
 *       <td>name、version、description、tips、author、homepage、repository、icon</td></tr>
 * </table>
 *
 * <p>配置缺失时 {@link PluginDescriptor} 为空：名称回落到插件 ID，其余字段为空串/空列表。
 * 这不影响插件加载与启停——展示信息缺失不是功能故障。</p>
 *
 * <h2>依赖展示</h2>
 * <p>{@link #getRequiredServices()} 来自插件代码的
 * {@link YuniPlugin#declareRequiredServices()}，是<b>真实的</b>服务依赖，
 * 而非配置文件中手写的声明。</p>
 *
 * @see PluginDeclaration
 * @see PluginDescriptor
 */
@Getter
public final class PluginMetadata {

    /** 代码侧声明。 */
    private final PluginDeclaration declaration;

    /** 配置侧描述。 */
    private final PluginDescriptor descriptor;

    /** 插件声明的必需服务类型。 */
    private final List<Class<?>> requiredServices;

    /**
     * 组装元数据。
     *
     * @param declaration      代码侧声明
     * @param descriptor       配置侧描述
     * @param requiredServices 必需服务类型
     */
    public PluginMetadata(PluginDeclaration declaration,
                          PluginDescriptor descriptor,
                          List<Class<?>> requiredServices) {
        this.declaration = declaration;
        this.descriptor = descriptor == null ? PluginDescriptor.empty() : descriptor;
        this.requiredServices = requiredServices == null ? List.of() : List.copyOf(requiredServices);
        this.tips = this.descriptor.getTips();
    }

    /** 配置里的提示列表（不代替描述）。 */
    private final List<String> tips;

    /**
     * 按标识构造最小元数据——无配置展示信息、无依赖。
     *
     * <p>供测试与框架占位使用；正常加载路径由加载器提供
     * {@link PluginDescriptor}。</p>
     *
     * @param id 插件标识
     * @return 元数据
     */
    public static PluginMetadata of(String id) {
        return new PluginMetadata(
                PluginDeclaration.of(id).build(),
                PluginDescriptor.empty(),
                List.of());
    }

    /**
     * 插件稳定标识。
     *
     * @return 标识
     */
    public String getId() {
        return declaration.getId();
    }

    /**
     * 插件显示名；配置未提供时回落到插件 ID。
     *
     * @return 显示名
     */
    public String getName() {
        String name = descriptor.getName();
        return name == null || name.isBlank() ? declaration.getId() : name;
    }

    /**
     * 版本号；未配置时为空串。
     *
     * @return 版本
     */
    public String getVersion() {
        return descriptor.getVersion();
    }

    /**
     * 描述；未配置时为空串。
     *
     * @return 描述
     */
    public String getDescription() {
        return descriptor.getDescription();
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
        String description = getDescription();
        return description == null || description.isBlank()
                ? List.of()
                : List.of(description);
    }

    /**
     * 作者 / 版权声明；未配置时为空串。
     *
     * @return 作者
     */
    public String getAuthor() {
        return descriptor.getAuthor();
    }

    /**
     * 项目主页；未配置时为空串。
     *
     * @return 主页
     */
    public String getHomepage() {
        return descriptor.getHomepage();
    }

    /**
     * 代码仓库；未配置时为空串。
     *
     * @return 仓库
     */
    public String getRepository() {
        return descriptor.getRepository();
    }

    /**
     * 图标；未配置时为空串。
     *
     * @return 图标
     */
    public String getIcon() {
        return descriptor.getIcon();
    }

    /**
     * 无用户配置时默认是否生效。
     *
     * @return 默认生效返回 {@code true}
     */
    public boolean isDefaultEnable() {
        return declaration.isDefaultEnable();
    }

    /**
     * 是否内置插件。
     *
     * @return 内置返回 {@code true}
     */
    public boolean isBuildIn() {
        return declaration.isBuildIn();
    }

    /**
     * 单行诊断描述。
     *
     * @return 形如 {@code PluginMetadata{id='steam/Steam', name='...'}}
     */
    @Override
    public String toString() {
        return "PluginMetadata{id='" + getKey() + "', name='" + getName() + "'}";
    }

    /**
     * 不带加载单元前缀的标识。
     *
     * @return 插件 ID
     */
    public String getKey() {
        return declaration.getId();
    }
}
