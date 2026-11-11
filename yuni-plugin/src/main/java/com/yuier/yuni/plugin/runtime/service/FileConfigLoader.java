package com.yuier.yuni.plugin.runtime.service;

import com.yuier.yuni.core.bot.JsonCodec;
import com.yuier.yuni.plugin.api.service.ConfigLoader;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@link ConfigLoader} 的默认实现——按插件资源目录读写配置与资源文件。
 *
 * <h2>取代了什么</h2>
 * <p>现行 {@code PluginUtils} 上的四个静态方法：</p>
 * <ul>
 *   <li>{@code loadJsonConfigFromPlugin(file, clazz, pluginClass)}</li>
 *   <li>{@code loadTextFromPluginFolder(file, pluginClass)}</li>
 *   <li>{@code loadFontFromPlugin(file, size, pluginClass)}</li>
 *   <li>{@code getPluginRootPath(pluginClass)}</li>
 * </ul>
 * <p>它们的签名都要额外传一个 {@code Class<? extends YuniPlugin>}——因为静态方法
 * 无从得知「我是谁」，只能靠调用方传入自己的 Class 去反查模块与 jar 位置。
 * 有了 {@code ctx} 之后，资源目录在创建上下文时就已经确定，这个参数消失了。</p>
 *
 * <h2>每插件一个实例</h2>
 * <p>本类的实例绑定到<b>单个插件</b>的资源目录。因此它不作为「框架内置服务」
 * 预注册到 {@code ServiceRegistry}，而是由 {@code PluginContext.getConfig()}
 * 按需构造（见 {@code PluginContext} 的实现）。这样插件调用
 * {@code ctx.getConfig().load("x.json", C.class)} 时不需要传自己的 Class。</p>
 *
 * <h2>为什么读磁盘而不是读 jar 内资源</h2>
 * <p>插件资源位于 jar <b>同级目录</b>（{@code plugins/<模块名>/}），由各插件
 * pom 的 maven-resources-plugin 在构建时复制过去。这样运维可以直接改配置而
 * 无需重新打包——这是现行系统刻意保留的性质，新实现保持一致。</p>
 *
 * <h2>错误语义</h2>
 * <p>读取或解析失败时抛 {@link IllegalStateException} 并带上文件名与目录，
 * 而不是返回 {@code null}。理由：配置缺失几乎总是部署问题，静默返回 null
 * 会让插件在后续逻辑里抛 NPE，远离真正的出错点。需要「缺失也可启动」的
 * 场景请使用 {@link #loadOrDefault}。</p>
 *
 * @see ConfigLoader
 */
@RequiredArgsConstructor
public final class FileConfigLoader implements ConfigLoader {

    /** 本插件的资源目录。 */
    @Getter
    private final Path dataDir;

    /** JSON 编解码器。 */
    private final JsonCodec jsonCodec;

    /* 构造器由 @RequiredArgsConstructor 生成：(Path dataDir, JsonCodec jsonCodec)。 */

    @Override
    public <T> T load(String fileName, Class<T> type) {
        Path path = resolve(fileName);
        String json = readString(path, fileName);
        try {
            return jsonCodec.fromJson(json, type);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "配置文件 JSON 格式错误: " + path + "（目标类型 " + type.getSimpleName() + "）", e);
        }
    }

    @Override
    public <T> T loadOrDefault(String fileName, Class<T> type, T defaultValue) {
        try {
            return load(fileName, type);
        } catch (Exception e) {
            // 可选配置：读取失败降级为默认值。这里刻意不区分「文件不存在」
            // 与「格式错误」——两者对调用方的含义都是「用默认值继续」
            return defaultValue;
        }
    }

    @Override
    public String loadText(String fileName) {
        return readString(resolve(fileName), fileName);
    }

    @Override
    public InputStream open(String fileName) {
        Path path = resolve(fileName);
        try {
            return Files.newInputStream(path);
        } catch (IOException e) {
            throw new IllegalStateException("打开插件资源失败: " + path, e);
        }
    }

    @Override
    public Path resolve(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("资源文件名不能为空");
        }
        // 拒绝绝对路径与上跳：插件不应读到资源目录之外的任意文件。
        // 现行 PluginUtils 用 Paths.get(root, resourcePath) 拼接，
        // 若 resourcePath 含 ".." 即可逃逸出插件目录。
        Path resolved = dataDir.resolve(fileName).normalize();
        if (!resolved.startsWith(dataDir.normalize())) {
            throw new IllegalArgumentException(
                    "资源路径越界，必须位于插件目录内: " + fileName + "（插件目录 " + dataDir + "）");
        }
        return resolved;
    }

    @Override
    public boolean exists(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return false;
        }
        return Files.isRegularFile(resolve(fileName));
    }

    /**
     * 读取文件全部内容为字符串。
     *
     * @param path     已解析的绝对路径
     * @param fileName 原始文件名（用于错误信息）
     * @return 文件内容
     * @throws IllegalStateException 文件不存在或读取失败
     */
    private String readString(Path path, String fileName) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException(
                    "插件资源文件不存在: " + path
                            + "（请求的文件名 " + fileName + "，插件目录 " + dataDir + "）");
        }
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException("读取插件资源失败: " + path, e);
        }
    }
}
