package com.yuier.yuni.plugin.api;

import com.yuier.yuni.core.event.YuniMessageEvent;
import com.yuier.yuni.core.event.matched.CommandResult;

/**
 * 命令处理器——绑定到 {@code CommandNode} 上的执行入口。
 *
 * <p>命令处理器与其他消息处理器使用同一个 {@link YuniMessageEvent}，
 * 额外接收命令匹配结果 {@link CommandResult}。参数与子命令从结果中读取，
 * 不需要插件自己解析消息链：</p>
 *
 * <pre>{@code
 * Commands.on(node, (event, result, reply) -> {
 *     String 名称 = result.getArg("服务器名称").asText();
 *     reply.text(MCStatus.query(名称)).send();
 * });
 * }</pre>
 *
 * <h2>与 {@code MessageHandler} 的区别</h2>
 * <table border="1">
 *   <caption>两种处理器的分工</caption>
 *   <tr><th></th><th>{@link com.yuier.yuni.plugin.api.handler.MessageHandler}</th><th>CommandHandler</th></tr>
 *   <tr><td>触发方式</td><td>条件谓词（模式匹配）</td><td>命令树匹配</td></tr>
 *   <tr><td>事件类型</td><td>{@link YuniMessageEvent}</td><td>{@link YuniMessageEvent}</td></tr>
 *   <tr><td>参数</td><td>需自行从消息链解析</td><td>由 {@link CommandResult} 结构化提供</td></tr>
 *   <tr><td>权限</td><td>插件自行判定</td><td>节点级 {@code CommandNode.permission} 自动校验</td></tr>
 *   <tr><td>优先级</td><td>低于命令</td><td>最高，命中后短路模式插件</td></tr>
 * </table>
 *
 * @see com.yuier.yuni.plugin.api.YuniContext#command
 */
@FunctionalInterface
public interface CommandHandler {

    /**
     * 执行命令。
     *
     * @param event  消息事件
     * @param result 命令匹配结果，提供参数与子命令
     * @param reply  回复入口；可为 {@code null}（例如无法确定会话时）
     * @throws Exception 执行失败；由框架统一兜底并回复用户
     */
    void handle(YuniMessageEvent event, CommandResult result, Reply reply) throws Exception;
}
