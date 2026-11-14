package com.yuier.yuni.plugin.runtime.adapter;

import com.yuier.yuni.core.bot.JsonCodec;
import com.yuier.yuni.core.bot.MessageTarget;
import com.yuier.yuni.core.bot.YuniBot;
import com.yuier.yuni.core.event.YuniMessageEvent;
import com.yuier.yuni.core.event.YuniMessageSentEvent;
import com.yuier.yuni.core.event.meta.YuniMetaEvent;
import com.yuier.yuni.core.event.notice.YuniNoticeEvent;
import com.yuier.yuni.core.event.request.YuniRequestEvent;
import com.yuier.yuni.plugin.api.Reply;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;

/**
 * 回复入口工厂——把 core 事件转换为绑定到该会话的 {@link Reply}。
 *
 * <p>插件事件模型已统一为 core 事件，本类只保留「事件 → Reply」这一件事；
 * 不再包装事件对象，也不修改原始事件。</p>
 */
@RequiredArgsConstructor
public final class ReplyFactory {

    /** 机器人实例供应函数。 */
    private final Supplier<YuniBot> botSupplier;

    /** JSON 编解码器。 */
    private final JsonCodec jsonCodec;

    /**
     * 构造消息事件的回复入口。
     *
     * @param source 核心消息事件
     * @return 回复入口；bot 未就绪时返回 {@code null}
     */
    public Reply forMessage(YuniMessageEvent source) {
        return forTarget(source.getGroupId(), source.getUserId(),
                source.getMessageType(), source.getMessageId());
    }

    /**
     * 构造消息发出事件的回复入口。
     *
     * @param source 核心消息发出事件
     * @return 回复入口；bot 未就绪时返回 {@code null}
     */
    public Reply forMessageSent(YuniMessageSentEvent source) {
        return forTarget(source.getGroupId(), source.getUserId(),
                source.getMessageType(), source.getMessageId());
    }

    /**
     * 构造通知事件的回复入口。
     *
     * @param source 核心通知事件
     * @return 回复入口；无法确定会话时返回 {@code null}
     */
    public Reply forNotice(YuniNoticeEvent source) {
        return source.getGroupId() != null
                ? forTarget(source.getGroupId(), source.getUserId(), "group", null)
                : forTarget(null, source.getUserId(), "private", null);
    }

    /**
     * 构造请求事件的回复入口。
     *
     * @param source 核心请求事件
     * @return 回复入口；无法确定会话时返回 {@code null}
     */
    public Reply forRequest(YuniRequestEvent source) {
        return source.getGroupId() != null
                ? forTarget(source.getGroupId(), source.getUserId(), "group", null)
                : forTarget(null, source.getUserId(), "private", null);
    }

    /**
     * 元事件不属于任何会话，没有回复入口。
     *
     * @param source 核心元事件
     * @return 恒为 {@code null}
     */
    public Reply forMeta(YuniMetaEvent source) {
        return null;
    }

    private Reply forTarget(Long groupId, Long userId, String messageType, Long sourceMessageId) {
        YuniBot bot = bot();
        if (bot == null) {
            return null;
        }
        if ("group".equals(messageType) && groupId != null) {
            return new CoreReply(bot, MessageTarget.group(groupId), sourceMessageId, jsonCodec);
        }
        if (userId != null) {
            return new CoreReply(bot, MessageTarget.privateChat(userId), sourceMessageId, jsonCodec);
        }
        return null;
    }

    private YuniBot bot() {
        if (botSupplier == null) {
            return null;
        }
        try {
            return botSupplier.get();
        } catch (Exception e) {
            return null;
        }
    }
}
