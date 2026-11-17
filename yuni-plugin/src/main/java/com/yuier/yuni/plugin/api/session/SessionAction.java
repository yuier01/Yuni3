package com.yuier.yuni.plugin.api.session;

/**
 * 会话处理器/通道指令的返回值。
 *
 * <p>消息一旦落入通道就被消费，没有“交还常规分发”的选项。</p>
 */
public enum SessionAction {

    /** 消费本条消息，通道继续存活。 */
    CONSUMED,

    /** 消费本条消息，并在本轮结束后关闭通道。 */
    CLOSE;

    /**
     * 是否请求关闭。
     *
     * @return 是则返回 {@code true}
     */
    public boolean closesSession() {
        return this == CLOSE;
    }
}
