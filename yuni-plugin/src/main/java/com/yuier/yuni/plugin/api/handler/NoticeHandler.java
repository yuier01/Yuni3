package com.yuier.yuni.plugin.api.handler;

import com.yuier.yuni.plugin.api.Reply;
import com.yuier.yuni.plugin.api.event.NoticeEvent;

/**
 * 通知事件处理器。
 *
 * <p>由 {@code ctx.onNotice(...)} 注册。处理戳一戳、群成员增减、禁言、撤回、
 * 文件上传、群荣誉变动等通知。</p>
 *
 * <h2>示例</h2>
 * <pre>{@code
 * ctx.onNotice("poke", (ev, reply) -> {
 *     if (ev.getTargetId().filter(t -> t == ev.getSelfId()).isEmpty()) return;   // 不是戳机器人
 *     reply.at(ev.getUserId()).text("戳我干嘛？").send();
 * });
 * }</pre>
 *
 * <h2>不要在处理器里再解 rawJson</h2>
 * <p>现行 {@code PokeNotice} 因为事件模型字段缺失，被迫把 {@code rawJson}
 * 反序列化两次来判断子类型。新系统中子类型已由适配器写入
 * {@link NoticeEvent#getSubType()}，且判定在注册时就完成了（用
 * {@code ctx.onNotice("poke", ...)}），处理器拿到的必定是目标子类型。</p>
 *
 * <h2>权限</h2>
 * <p>框架<b>不</b>自动施加权限检查（现行系统只做了「用户未封禁」检查，且是隐式的）。
 * 需要时用 {@code ctx.findService(PermissionService.class)} 显式判断。</p>
 *
 * @see com.yuier.yuni.plugin.api.YuniContext#onNotice
 */
@FunctionalInterface
public interface NoticeHandler {

    /**
     * 处理一条通知事件。
     *
     * @param event 通知事件（只读）
     * @param reply 回复入口
     * @throws Exception 处理失败时抛出
     */
    void handle(NoticeEvent event, Reply reply) throws Exception;
}
