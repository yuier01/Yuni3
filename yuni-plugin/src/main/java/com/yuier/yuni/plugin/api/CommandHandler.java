package com.yuier.yuni.plugin.api;

import com.yuier.yuni.core.event.YuniMessageEvent;
import com.yuier.yuni.core.event.matched.CommandResult;

/**
 * 命令处理器——绑定到 {@code CommandNode} 上的执行入口。
 *
 * <h2>为什么参数是 {@code YuniMessageEvent} 而不是插件侧事件</h2>
 * <p>命令的参数取值走 {@code CommandResult}（由命令匹配引擎产出并挂在
 * {@code YuniMessageEvent} 上）。若本接口改用插件侧的 {@code MessageEvent}，
 * 就得在插件侧事件上重新暴露 {@code CommandResult}，等于把命令引擎的数据结构
 * 泄漏到 API 层。</p>
 * <p>因此命令处理器接收的是<b>命令引擎的原生事件类型</b>：</p>
 * <pre>{@code
 * .on((ev, reply) -> {
 *     CommandResult r = ev.getCommandResult();
 *     String 名称 = r.getArg("服务器名称").asText();
 *     reply.text(MCStatus.query(名称)).send();
 * })
 * }</pre>
 * <p>这与现行插件里 {@code execute(YuniMessageEvent)} 的写法<b>完全一致</b>——
 * 迁移命令插件时，参数取值代码一行都不用改（设计文档 §6.5）。</p>
 *
 * <h2>与 {@code MessageHandler} 的区别</h2>
 * <table border="1">
 *   <caption>两种处理器的分工</caption>
 *   <tr><th></th><th>{@link com.yuier.yuni.plugin.api.handler.MessageHandler}</th><th>CommandHandler</th></tr>
 *   <tr><td>触发方式</td><td>条件谓词（模式匹配）</td><td>命令树匹配</td></tr>
 *   <tr><td>事件类型</td><td>插件侧 {@code MessageEvent}</td><td>引擎 {@code YuniMessageEvent}（携带 {@code CommandResult}）</td></tr>
 *   <tr><td>参数</td><td>需自行从消息链解析</td><td>由引擎结构化解析好</td></tr>
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
