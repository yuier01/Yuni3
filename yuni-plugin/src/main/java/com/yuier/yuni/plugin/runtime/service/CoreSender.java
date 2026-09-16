package com.yuier.yuni.plugin.runtime.service;

import com.yuier.yuni.core.bot.MessageTarget;
import com.yuier.yuni.core.bot.YuniBot;
import com.yuier.yuni.core.bot.JsonCodec;
import com.yuier.yuni.plugin.api.Reply;
import com.yuier.yuni.plugin.api.event.MessageChainView;
import com.yuier.yuni.plugin.api.service.Sender;
import com.yuier.yuni.plugin.runtime.adapter.CoreMessageChainView;
import com.yuier.yuni.plugin.runtime.adapter.CoreReply;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link Sender} 的默认实现——把主动发送请求转成 {@link CoreReply} 并交给
 * {@link YuniBot} 执行。
 *
 * <h2>取代了什么</h2>
 * <p>现行 {@code PluginUtils.sendGroupMessage(long, MessageChain)} 与
 * {@code sendPrivateMessage(long, MessageChain)}——两个只支持「立即发送一条已
 * 构建好的链」的静态方法。本实现支持链式构建、取回消息 ID、引用回复，
 * 以及把事件里读到的链原样转发。</p>
 *
 * <h2>与 {@code Reply} 的分工</h2>
 * <p>{@link Reply} 来自事件处理器参数，已绑定好目标会话；
 * {@code Sender} 用于「没有触发会话」的场景（定时任务、服务回调），
 * 需要显式指定目标。两者最终都走 {@link CoreReply#send()}。</p>
 *
 * @see Sender
 * @see CoreReply
 */
@Slf4j
public final class CoreSender implements Sender {

    /** 机器人实例，发送的实际执行者。 */
    private final YuniBot bot;

    /** JSON 编解码器，用于把视图转成内部消息段。 */
    private final JsonCodec jsonCodec;

    /**
     * 构造发送门面。
     *
     * @param bot       机器人实例
     * @param jsonCodec JSON 编解码器
     */
    public CoreSender(YuniBot bot, JsonCodec jsonCodec) {
        this.bot = bot;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public Reply group(long groupId) {
        return new CoreReply(bot, MessageTarget.group(groupId), null, jsonCodec);
    }

    @Override
    public Reply privateChat(long userId) {
        return new CoreReply(bot, MessageTarget.privateChat(userId), null, jsonCodec);
    }

    @Override
    public Reply.SentResult sendToGroup(long groupId, MessageChainView chain) {
        // 直接构造一次回复并立即发送：用于消息中转这类「拿到什么就发什么」的场景
        return group(groupId).chain(chain).send();
    }

    @Override
    public Reply.SentResult sendToPrivate(long userId, MessageChainView chain) {
        return privateChat(userId).chain(chain).send();
    }

    @Override
    public boolean recall(long messageId) {
        try {
            bot.recallMessage(String.valueOf(messageId));
            return true;
        } catch (Throwable t) {
            // 撤回有 2 分钟时间限制，超时是正常业务失败而非异常；
            // 记录后返回 false，让调用方自行决定是否提示用户
            log.warn("撤回消息失败: messageId={}", messageId, t);
            return false;
        }
    }

    /**
     * 把核心消息链包装为插件侧视图。
     *
     * <p>供框架内部与 webapi 复用，避免各处重复创建适配器。</p>
     *
     * @param chain 核心消息链
     * @return 插件侧只读视图
     */
    public CoreMessageChainView wrap(com.yuier.yuni.core.model.message.MessageChain chain) {
        return new CoreMessageChainView(chain, jsonCodec);
    }
}
