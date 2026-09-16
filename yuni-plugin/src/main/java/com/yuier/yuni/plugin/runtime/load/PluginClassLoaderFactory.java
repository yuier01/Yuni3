package com.yuier.yuni.plugin.runtime.load;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;

/**
 * 插件类加载器工厂——为每个 jar 创建一个 child-first 类加载器。
 *
 * <h2>父加载器的选择（本类唯一的关键决策）</h2>
 * <p>父加载器必须是<b>加载本工厂类自己的那个加载器</b>：</p>
 *
 * <pre>{@code
 * PluginClassLoaderFactory.class.getClassLoader()
 * }</pre>
 *
 * <p><b>绝不能用 {@code Thread.currentThread().getContextClassLoader()}</b>。
 * 这条约束来自 2026-08 的一次 P0 事故（详见
 * {@code Notes/技术报告-插件重载静默失败.md}），其失败链路是：</p>
 * <ol>
 *   <li>QQ 命令路径的插件执行跑在 {@code ForkJoinPool.commonPool} 上；</li>
 *   <li>commonPool 工作线程的上下文类加载器是 {@code AppClassLoader}；</li>
 *   <li>fat jar（{@code java -jar}）部署下，框架类在
 *       {@code BOOT-INF/classes} 里，只有 {@code LaunchedClassLoader} 能看到，
 *       {@code AppClassLoader} 看不到；</li>
 *   <li>于是插件类加载时解析父类（框架类）失败，抛
 *       {@code NoClassDefFoundError}——而那是 {@code Error}，
 *       被只 catch {@code Exception} 的代码放过，最终被
 *       fire-and-forget 的 future 静默吞掉，表现为「命令无响应、容器被清空」。</li>
 * </ol>
 *
 * <p>改用工厂类自己的加载器后：IDEA 平铺 classpath 下它是 {@code AppClassLoader}，
 * fat jar 下它是 {@code LaunchedClassLoader}——<b>无论哪种部署形态，
 * 它都恰好是「能看到全部框架类」的那一个</b>，且不随线程而变。</p>
 *
 * <h2>隔离性不受影响</h2>
 * <p>插件隔离由 {@link PluginClassLoader} 的 child-first 策略保证：插件类始终
 * 先从自己的 jar 加载。父加载器只提供框架类；插件之间依旧互不可见。</p>
 *
 * @see PluginClassLoader
 */
@Slf4j
public final class PluginClassLoaderFactory {

    /** 工具类不可实例化。 */
    private PluginClassLoaderFactory() {
    }

    /**
     * 为 jar 创建插件类加载器。
     *
     * @param jarFile jar 文件
     * @return 插件类加载器
     * @throws IllegalArgumentException jar 路径无法转换为 URL 时
     */
    public static PluginClassLoader create(File jarFile) {
        URL jarUrl;
        try {
            jarUrl = jarFile.toURI().toURL();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("插件 jar 路径非法: " + jarFile, e);
        }
        return new PluginClassLoader(new URL[]{jarUrl}, frameworkClassLoader());
    }

    /**
     * 取「能看到全部框架类」的类加载器。
     *
     * <p>即加载本工厂类的加载器。抽成方法是为了让这条约束有一个显式的、
     * 有注释的落点——它是本类唯一容易写错的地方。</p>
     *
     * @return 框架类加载器
     */
    public static ClassLoader frameworkClassLoader() {
        return PluginClassLoaderFactory.class.getClassLoader();
    }
}
