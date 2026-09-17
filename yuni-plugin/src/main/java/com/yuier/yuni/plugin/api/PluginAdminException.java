package com.yuier.yuni.plugin.api;

/**
 * 插件管理操作失败。
 *
 * <h2>为什么不用 {@code IllegalStateException}</h2>
 * <p>插件管理是<b>会被用户操作触发</b>的路径（{@code /插件 开启 3}）。失败原因
 * 大多是「可预期的业务拒绝」——目标不存在、内置插件不允许操作、jar 文件找不到、
 * 模块正在重载中。这些与「框架内部状态不一致」是两类问题：</p>
 * <ul>
 *   <li>{@code IllegalStateException} 表示「代码走到了不该走到的分支」，
 *       应当修代码；</li>
 *   <li>本异常表示「用户的这次操作不成立」，应当把消息回给用户。</li>
 * </ul>
 * <p>分开之后，调用方的处理也自然分开：捕获本异常并向用户回复
 * {@link #getMessage()}，其余异常交给框架的统一兜底。</p>
 *
 * <h2>它是受检异常吗</h2>
 * <p>不是。插件管理调用大多发生在命令处理器内，而那些处理器
 * （{@code CommandHandler}）允许抛 {@code Exception}。做成运行时异常可以
 * 避免在不关心失败的场景（例如启动时的日志输出）写无意义的 {@code try}。
 * 但<b>面向用户的管理操作应当捕获它</b>——否则用户看到的是框架的统一失败提示
 * （「插件执行出错」），而不是「该插件为内置插件，只有 bot 拥有者有权操作」。</p>
 *
 * @see PluginAdmin
 */
public class PluginAdminException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 出问题的插件标识；与具体插件无关的失败（如参数为空）时为 {@code null}。 */
    private final String pluginId;

    /**
     * 构造异常。
     *
     * @param pluginId 插件标识；可为 {@code null}
     * @param message  面向用户的失败描述
     */
    public PluginAdminException(String pluginId, String message) {
        super(message);
        this.pluginId = pluginId;
    }

    /**
     * 构造异常（带根因）。
     *
     * @param pluginId 插件标识；可为 {@code null}
     * @param message  面向用户的失败描述
     * @param cause    根因
     */
    public PluginAdminException(String pluginId, String message, Throwable cause) {
        super(message, cause);
        this.pluginId = pluginId;
    }

    /**
     * 出问题的插件标识。
     *
     * @return 插件标识；可能为 {@code null}
     */
    public String pluginId() {
        return pluginId;
    }
}
