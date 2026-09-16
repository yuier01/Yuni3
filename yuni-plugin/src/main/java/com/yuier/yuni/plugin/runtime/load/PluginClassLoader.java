package com.yuier.yuni.plugin.runtime.load;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * 插件类加载器——child-first（子优先）委派策略。
 *
 * <h2>委派顺序</h2>
 * <pre>
 * loadClass(name):
 *   if name 以 java. / javax. 开头  →  交给父加载器（系统类必须唯一）
 *   else:
 *       findLoadedClass(name)        →  已加载过则直接返回
 *       findClass(name)              →  先从本插件 jar 里找（← 子优先）
 *       失败则 super.loadClass(name)  →  再委托父加载器（提供框架类）
 * </pre>
 *
 * <h2>为什么是 child-first 而不是标准的双亲委派</h2>
 * <p>标准双亲委派会先问父加载器，导致插件无法覆盖框架里的同名类。
 * child-first 保证插件类<b>始终从自己的 jar 加载</b>，从而实现：</p>
 * <ul>
 *   <li>插件之间互不可见（A 插件看不到 B 插件的类）；</li>
 *   <li>不同的插件可以携带同名但版本不同的依赖。</li>
 * </ul>
 *
 * <h2>父加载器的职责</h2>
 * <p>父加载器<b>只提供框架类</b>。插件类 {@code MyCommandPlugin} 继承框架类
 * {@code YuniPlugin}，JVM 在定义前者时要求后者已加载；此时本加载器在自己的
 * jar 里找不到（框架类不在插件 jar 内），就委托父加载器。因此父加载器必须是
 * 「能看到框架类」的那一个——这个选择发生在
 * {@link PluginClassLoaderFactory#create}。</p>
 *
 * <h2>关于关闭</h2>
 * <p>扫描阶段会加载 jar 内的全部类，之后即可 {@link #close()} 以释放 jar 文件
 * 句柄（Windows 下这是覆盖 jar 实现热更新的前提）。<b>已加载的类定义不受
 * close 影响</b>，因此运行期引用仍然有效。这是现行系统已验证的做法。</p>
 *
 * @see PluginClassLoaderFactory
 */
public final class PluginClassLoader extends URLClassLoader {

    /**
     * 构造插件类加载器。
     *
     * @param urls   插件 jar 的 URL
     * @param parent 框架类加载器（见 {@link PluginClassLoaderFactory}）
     */
    public PluginClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    @Override
    protected Class<?> loadClass(String fullName, boolean resolve) throws ClassNotFoundException {
        // 检查是否为系统类或框架类，优先使用父类加载器
        if (isSystemClass(fullName)) {
            return super.loadClass(fullName, resolve);
        }

        // 优先从当前加载器加载插件类
        synchronized (getClassLoadingLock(fullName)) {
            Class<?> loadedClass = findLoadedClass(fullName);
            if (loadedClass == null) {
                try {
                    loadedClass = findClass(fullName);
                } catch (ClassNotFoundException e) {
                    // 委托给父类加载器
                    loadedClass = super.loadClass(fullName, resolve);
                }
            }

            if (resolve) {
                resolveClass(loadedClass);
            }
            return loadedClass;
        }
    }

    /**
     * 判断是否为系统类。
     *
     * <p>只对 JDK 自身的包前缀走父优先：这些类的唯一性由 JVM 保证，
     * 插件若自带同名类会造成灾难性的类型混淆。</p>
     *
     * @param name 类全限定名
     * @return 是系统类返回 {@code true}
     */
    private boolean isSystemClass(String name) {
        return name.startsWith("java.") ||
                name.startsWith("javax.");  // 插件系统类
    }
}
