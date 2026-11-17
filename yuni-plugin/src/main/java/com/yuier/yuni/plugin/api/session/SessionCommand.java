package com.yuier.yuni.plugin.api.session;

import com.yuier.yuni.core.event.YuniMessageEvent;
import com.yuier.yuni.plugin.api.Reply;

/**
 * 通道本地指令。
 *
 * <p>不是全局命令系统：不经过 CommandDispatcher，不参与 CommandNode 权限校验。
 * 它只在通道内部生效，用于“提示”“投票”“开始下一轮”等通道内交互。</p>
 *
 * <p>检查顺序：退出指令（若启用）→ 加入指令（部分群友）→ 自定义指令 → 主 handler。
 * 第一个匹配者生效，消息被消费。</p>
 */
public interface SessionCommand {

    /**
     * 是否匹配本条消息。
     *
     * @param event 消息事件
     * @return 匹配则返回 {@code true}
     */
    boolean matches(YuniMessageEvent event);

    /**
     * 处理匹配的消息。
     *
     * @param session 会话上下文
     * @param event   消息事件
     * @param reply   回复入口；可能为 {@code null}
     * @return 处理动作
     * @throws Exception 处理失败
     */
    SessionAction handle(SessionContext session,
                         YuniMessageEvent event,
                         Reply reply) throws Exception;

    /**
     * 精确文本匹配（忽略首尾空白）。
     *
     * @param command 指令文本
     * @param handler 处理器
     * @return 指令
     */
    static SessionCommand text(String command, SessionHandler handler) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("指令文本不能为空");
        }
        if (handler == null) {
            throw new IllegalArgumentException("指令处理器不能为 null");
        }
        String expected = command.strip();
        return new SessionCommand() {
            @Override
            public boolean matches(YuniMessageEvent event) {
                return expected.equals(plainText(event));
            }

            @Override
            public SessionAction handle(SessionContext session,
                                        YuniMessageEvent event,
                                        Reply reply) throws Exception {
                return handler.handle(session, event, reply);
            }
        };
    }

    /**
     * 前缀匹配（忽略首尾空白）。
     *
     * @param prefix  前缀
     * @param handler 处理器
     * @return 指令
     */
    static SessionCommand prefix(String prefix, SessionHandler handler) {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("指令前缀不能为空");
        }
        if (handler == null) {
            throw new IllegalArgumentException("指令处理器不能为 null");
        }
        String expected = prefix.strip();
        return new SessionCommand() {
            @Override
            public boolean matches(YuniMessageEvent event) {
                return plainText(event).startsWith(expected);
            }

            @Override
            public SessionAction handle(SessionContext session,
                                        YuniMessageEvent event,
                                        Reply reply) throws Exception {
                return handler.handle(session, event, reply);
            }
        };
    }

    /**
     * 取消息的纯文本并去掉首尾空白。
     *
     * @param event 消息事件
     * @return 纯文本；无消息链时为空串
     */
    static String plainText(YuniMessageEvent event) {
        if (event == null || event.getChain() == null) {
            return "";
        }
        String text = event.getChain().getPlainText();
        return text == null ? "" : text.strip();
    }
}
