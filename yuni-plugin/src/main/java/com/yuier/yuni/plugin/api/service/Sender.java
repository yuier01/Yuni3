package com.yuier.yuni.plugin.api.service;

import com.yuier.yuni.plugin.api.Reply;
import com.yuier.yuni.plugin.api.event.MessageChainView;

/**
 * 消息发送门面。
 *
 * <h2>取代了什么</h2>
 * <p>现行 {@code PluginUtils.sendGroupMessage(long, MessageChain)} 与
 * {@code sendPrivateMessage(long, MessageChain)} 是两个静态方法，只支持
 * 「立即发送一条链」，不支持分批、不支持取回消息 ID。</p>
 *
 * <h2>用法</h2>
 * <pre>{@code
 * // 发群消息
 * ctx.sender().group(groupId).text("上线了").send();
 *
 * // 发私聊，并取回消息 ID 以便后续撤回
 * long msgId = ctx.sender().privateChat(userId).text("稍等").sendAndGetId();
 *
 * // 引用回复某条历史消息
 * ctx.sender().group(groupId).quote(originalMsgId).text("收到").send();
 *
 * // 已构建好的链直接发出
 * ctx.sender().group(groupId).chain(someChain).send();
 * }</pre>
 *
 * <h2>与 {@code Reply} 的关系</h2>
 * <p>{@link Reply} 是「绑定到某个会话」的发送入口，来自事件处理器参数；
 * 本接口是「主动指定目标」的发送入口，用于定时任务等没有触发会话的场景。
 * 两者最终走同一条发送链路。</p>
 *
 * @see Reply
 */
public interface Sender {

    /**
     * 构造发往群聊的消息。
     *
     * @param groupId 群号
     * @return 消息构建门面
     */
    Reply group(long groupId);

    /**
     * 构造发往私聊的消息。
     *
     * @param userId 用户 QQ 号
     * @return 消息构建门面
     */
    Reply privateChat(long userId);

    /**
     * 直接向群发送一条已构建的消息链。
     *
     * @param groupId 群号
     * @param chain   消息链
     * @return 发送结果
     */
    Reply.SentResult sendToGroup(long groupId, MessageChainView chain);

    /**
     * 直接向私聊发送一条已构建的消息链。
     *
     * @param userId 用户 QQ 号
     * @param chain  消息链
     * @return 发送结果
     */
    Reply.SentResult sendToPrivate(long userId, MessageChainView chain);

    /**
     * 撤回消息。
     *
     * <p>取代现行 {@code YuniBot.recallMessage(String)}。注意撤回有<b>时间限制</b>
     * （QQ 通常为 2 分钟），超时会失败。</p>
     *
     * @param messageId 消息 ID
     * @return 成功返回 {@code true}
     */
    boolean recall(long messageId);
}
