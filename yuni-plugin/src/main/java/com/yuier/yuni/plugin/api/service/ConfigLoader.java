package com.yuier.yuni.plugin.api.service;

import java.io.InputStream;
import java.nio.file.Path;

/**
 * 插件资源与配置加载门面。
 *
 * <h2>取代了什么</h2>
 * <p>现行 {@code PluginUtils} 上的四个静态方法：</p>
 * <ul>
 *   <li>{@code loadJsonConfigFromPlugin(file, clazz, pluginClass)} → {@link #load}</li>
 *   <li>{@code loadTextFromPluginFolder(file, pluginClass)} → {@link #loadText}</li>
 *   <li>{@code loadFontFromPlugin(file, size, pluginClass)} → 用 {@link #open} 自行处理</li>
 *   <li>{@code getPluginRootPath(pluginClass)} → {@link #dataDir()}</li>
 * </ul>
 *
 * <p>那些方法的签名都要额外传一个 {@code Class<? extends YuniPlugin>} 参数——
 * 因为静态方法无从得知「我是谁」，只能靠调用方传入自己的 Class 去反查模块。
 * 有了 {@code ctx} 之后这个参数不需要了。</p>
 *
 * <h2>用法</h2>
 * <pre>{@code
 * // 读 JSON 配置（自动反序列化）
 * MaimaiConfig cfg = ctx.config().load("maimai_config.json", MaimaiConfig.class);
 *
 * // 读纯文本
 * String help = ctx.config().loadText("help-info.txt");
 *
 * // 按需读取二进制资源（字体、图片）
 * try (InputStream in = ctx.config().open("font/Mamelon.otf")) {
 *     Font font = Font.createFont(Font.TRUETYPE_FONT, in).deriveFont(45f);
 * }
 * }</pre>
 *
 * <h2>资源目录</h2>
 * <p>插件资源位于 jar 包<b>同级目录</b>（即 {@code plugins/<模块名>/}），
 * 由构建脚本把 {@code src/main/resources} 下的文件复制过去
 * （见各插件 pom 的 maven-resources-plugin 配置）。因此插件读配置读的是
 * 磁盘文件而<b>不是</b> jar 内资源——这样运维可以直接改配置而无需重新打包。</p>
 */
public interface ConfigLoader {

    /**
     * 从插件资源目录读取并反序列化一个 JSON 文件。
     *
     * @param fileName 相对插件资源目录的文件名，如 {@code "config.json"}
     * @param type     目标类型
     * @param <T>      目标类型
     * @return 反序列化结果
     * @throws IllegalStateException 文件不存在、读取失败或 JSON 格式错误
     */
    <T> T load(String fileName, Class<T> type);

    /**
     * 从插件资源目录读取一个 JSON 文件；失败时返回默认值。
     *
     * <p>用于「配置缺失也应能启动」的可选配置。</p>
     *
     * @param fileName     相对插件资源目录的文件名
     * @param type         目标类型
     * @param defaultValue 读取失败时的返回值
     * @param <T>          目标类型
     * @return 反序列化结果或 {@code defaultValue}
     */
    <T> T loadOrDefault(String fileName, Class<T> type, T defaultValue);

    /**
     * 从插件资源目录读取一个文本文件。
     *
     * @param fileName 相对插件资源目录的文件名
     * @return 文件内容
     * @throws IllegalStateException 文件不存在或读取失败
     */
    String loadText(String fileName);

    /**
     * 打开插件资源目录下的文件流，由调用方负责关闭。
     *
     * <p>用于字体、图片、二进制资源。</p>
     *
     * @param fileName 相对插件资源目录的文件名
     * @return 输入流
     * @throws IllegalStateException 文件不存在或无法打开
     */
    InputStream open(String fileName);

    /**
     * 解析插件资源目录下的路径（不检查存在性）。
     *
     * @param fileName 相对插件资源目录的文件名
     * @return 绝对路径
     */
    Path resolve(String fileName);

    /**
     * 本插件的资源目录。
     *
     * @return 目录路径
     */
    Path dataDir();

    /**
     * 判断插件资源目录下是否存在指定文件。
     *
     * @param fileName 相对文件名
     * @return 存在返回 {@code true}
     */
    boolean exists(String fileName);
}
