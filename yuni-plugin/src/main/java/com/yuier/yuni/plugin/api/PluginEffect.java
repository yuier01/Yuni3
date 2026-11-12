package com.yuier.yuni.plugin.api;

/**
 * 副作用声明——把「获取资源」与「释放资源」写在同一个表达式里。
 *
 * <p>本接口是 {@link YuniContext#effect(PluginEffect)} 的参数类型。它解决的问题是：
 * 插件可能持有框架不认识的资源（网络连接、线程池、文件句柄、原生内存、
 * 第三方库的客户端……）。这类资源不会被自动撤销，必须显式声明清理方式。</p>
 *
 * <h2>用法</h2>
 * <pre>{@code
 * // WebSocket 连接：随插件卸载而关闭
 * ctx.effect(() -> {
 *     MaiMaiWsServer server = new MaiMaiWsServer(port);
 *     server.start();
 *     return server::shutdown;          // 返回 Disposer
 * });
 * }</pre>
 *
 * <h2>与「框架内置 PluginEffect」的区别</h2>
 * <p>{@code ctx.onMessage(...)}、{@code ctx.schedule(...)}、{@code ctx.provide(...)}
 * 这类 API 本身已是 PluginEffect——框架知道怎么撤销它们，插件无需写清理代码。
 * 只有框架<b>不认识</b>的资源才需要 {@code ctx.effect(...)}。</p>
 *
 * <h2>执行时机</h2>
 * <ul>
 *   <li>{@link #apply()} 在插件 {@code apply(ctx)} 执行期间<b>立即</b>被调用。</li>
 *   <li>返回的 {@link Disposable} 在插件卸载时被调用，顺序为注册顺序的<b>逆序</b>。</li>
 * </ul>
 *
 * <h2>错误处理</h2>
 * <p>若 {@link #apply()} 抛出异常，该 {@code effect} 视为注册失败：其返回的
 * Disposer 不会被登记，异常沿 {@code apply(ctx)} 向上传播并把该插件置为
 * {@code FAILED} 状态（参见 {@code FiberState}）。这样可以避免「资源已创建
 * 但清理句柄丢失」的泄漏。</p>
 *
 * @see Disposable
 * @see YuniContext#effect(PluginEffect)
 */
@FunctionalInterface
public interface PluginEffect {

    /**
     * 获取资源并返回其释放函数。
     *
     * @return 释放函数；返回 {@code null} 等价于 {@link Disposable#NOOP}
     * @throws Exception 资源获取失败时抛出，将导致所在插件进入 FAILED 状态
     */
    Disposable apply() throws Exception;
}
