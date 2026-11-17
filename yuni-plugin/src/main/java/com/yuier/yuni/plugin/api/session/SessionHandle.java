package com.yuier.yuni.plugin.api.session;

import com.yuier.yuni.plugin.api.Disposable;

/**
 * 会话句柄——插件持有的关闭/查询入口。
 *
 * <p>不暴露 {@link SessionContext} / 状态：状态只能在串行 handler 内访问。</p>
 */
public interface SessionHandle extends Disposable {

    /**
     * 会话 ID。
     *
     * @return ID
     */
    long getId();

    /**
     * 会话目标。
     *
     * @return 目标
     */
    SessionTarget getTarget();

    /**
     * 通道标签。
     *
     * @return 标签
     */
    String getTag();

    /**
     * 会话是否仍可接收消息。
     *
     * @return 可接收返回 {@code true}
     */
    boolean isActive();

    /**
     * 已处理回合数。
     *
     * @return 回合数
     */
    int getTurnCount();

    /**
     * 参与者数量；非部分群友为 0。
     *
     * @return 数量
     */
    int getParticipantCount();

    /**
     * 请求关闭；幂等、非阻塞。
     *
     * @param reason 关闭原因
     */
    void close(SessionCloseReason reason);

    @Override
    default void dispose() {
        close(SessionCloseReason.PLUGIN_CLOSED);
    }
}
