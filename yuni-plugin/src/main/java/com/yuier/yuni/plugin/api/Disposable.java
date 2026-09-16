package com.yuier.yuni.plugin.api;

/**
 * 可撤销句柄。
 *
 * <p>本接口是新插件系统中「副作用（Effect）」的通用返回类型。凡是插件通过
 * {@link YuniContext} 注册到框架里的东西（事件监听器、命令树、定时任务、服务、
 * 自定义资源），框架都会返回一个 {@code Disposable}。调用 {@link #dispose()}
 * 即可撤销该注册。</p>
 *
 * <h2>为什么需要它</h2>
 * <p>现行插件系统用 {@code YuniPlugin.destroy()} 一个方法承担全部清理工作，导致：
 * <ul>
 *   <li>插件作者必须自己记住「我注册过什么」，漏一个就泄漏；</li>
 *   <li>23 个插件里只有 1 个真正实现了 {@code destroy()}，其余全部空实现；</li>
 *   <li>热重载时无法判断旧实例是否清理干净。</li>
 * </ul>
 * 把「注册」与「撤销」配对成同一个对象后，框架可以在插件卸载时自动逆序撤销
 * 全部 Effect，插件作者不再需要写清理代码。</p>
 *
 * <h2>约定</h2>
 * <ul>
 *   <li><b>幂等</b>：重复调用 {@link #dispose()} 不得抛异常，也不得重复执行清理逻辑。</li>
 *   <li><b>不可逆</b>：撤销后该注册即失效，不存在「重新启用」；如需重新生效，
 *       应重新注册（得到一个新的 {@code Disposable}）。</li>
 *   <li><b>线程安全</b>：可能在异步卸载流程中被调用，实现需保证并发安全。</li>
 * </ul>
 *
 * @see Effect 成对声明「获取资源」与「释放资源」的函数式入口
 * @see YuniContext 全部注册型 API 的宿主
 */
@FunctionalInterface
public interface Disposable {

    /**
     * 撤销本次注册，释放其占用的资源。
     *
     * <p>实现要求：</p>
     * <ul>
     *   <li>幂等——第二次及以后的调用是空操作。</li>
     *   <li>不抛出受检异常；清理失败时应记录日志而非向上传播，
     *       否则一个失败的清理会阻断后续 Effect 的撤销。</li>
     * </ul>
     */
    void dispose();

    /**
     * 一个什么都不做的 {@code Disposable}。
     *
     * <p>用途：某些注册在特定条件下无需清理（例如往一个随插件一同销毁的
     * 内部集合里添加元素）。返回本常量比返回 {@code null} 更安全——
     * 避免调用方判空，也避免把「无需清理」误写成「忘记清理」。</p>
     */
    Disposable NOOP = () -> { };

    /**
     * 把多个 {@code Disposable} 合并成一个。
     *
     * <p>撤销时按传入顺序<b>逆序</b>执行（后注册的先撤销），这与
     * {@link YuniContext} 内部维护 Effect 列表的顺序语义一致。</p>
     *
     * @param disposables 待合并的句柄；{@code null} 元素会被忽略
     * @return 合并后的句柄，撤销时逆序调用各元素
     */
    static Disposable composite(Disposable... disposables) {
        // 防御性拷贝：调用方后续修改原数组不应影响已创建的合并句柄
        Disposable[] snapshot = disposables.clone();
        return () -> {
            for (int i = snapshot.length - 1; i >= 0; i--) {
                Disposable d = snapshot[i];
                if (d == null) {
                    continue;
                }
                try {
                    d.dispose();
                } catch (Throwable ignored) {
                    // 单个清理失败不得阻断其余清理；此处刻意吞掉，
                    // 由各 Disposable 实现自行负责记录日志
                }
            }
        };
    }
}
