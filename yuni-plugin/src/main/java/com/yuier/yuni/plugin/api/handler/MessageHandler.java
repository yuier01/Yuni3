package com.yuier.yuni.plugin.api.handler;

import com.yuier.yuni.plugin.api.Reply;
import com.yuier.yuni.plugin.api.event.MessageEvent;

/**
 * 消息事件处理器。
 *
 * <p>由 {@code ctx.onMessage(...)} 注册。参数为「事件」与「回复入口」两件东西：
 * 事件只读，回复入口负责构造与发送。</p>
 *
 * <h2>示例</h2>
 * <pre>{@code
 * ctx.onMessage(ev -> ev.getChain().containsText("你好"),
 *               (ev, reply) -> reply.at(ev.getUserId()).text(" 你好呀～").send());
 * }</pre>
 *
 * <h2>线程模型</h2>
 * <p>处理器在<b>异步线程</b>（{@code ForkJoinPool.commonPool}）上执行，不阻塞
 * OneBot 的事件接收线程。因此：</p>
 * <ul>
 *   <li>处理器内的阻塞操作（HTTP 请求、图片生成、{@code Thread.sleep}）是允许的，
 *       但会占用公共池线程——长时间阻塞建议自行切换到专用线程池；</li>
 *   <li>处理器可能被并发调用（多条消息同时到达），共享状态必须做并发保护，
 *       或改用 {@code ctx.state(Class)} 提供的分作用域状态。</li>
 * </ul>
 *
 * <h2>异常</h2>
 * <p>抛出任何 {@code Throwable}（含 {@code Error}）都会被框架统一捕获：记录完整
 * 堆栈、计入该插件的失败计数、并自动向会话回复一条失败提示。插件无需自行
 * try/catch——但这意味着<b>不要用异常做流程控制</b>，否则用户会收到多余提示。</p>
 *
 * @see com.yuier.yuni.plugin.api.YuniContext#onMessage
 */
@FunctionalInterface
public interface MessageHandler {

    /**
     * 处理一条消息事件。
     *
     * @param event 消息事件（只读）
     * @param reply 回复入口，绑定到本消息所在的会话
     * @throws Exception 处理失败时抛出；框架会捕获并回复用户
     */
    void handle(MessageEvent event, Reply reply) throws Exception;
}
