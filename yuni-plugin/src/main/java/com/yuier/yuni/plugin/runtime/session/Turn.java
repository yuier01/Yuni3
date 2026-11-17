package com.yuier.yuni.plugin.runtime.session;

import com.yuier.yuni.core.event.YuniMessageEvent;
import com.yuier.yuni.plugin.api.Reply;

/**
 * 待处理的通道回合。
 *
 * @param event 消息事件
 * @param reply 回复入口；可能为 {@code null}
 */
record Turn(YuniMessageEvent event, Reply reply) {
}
