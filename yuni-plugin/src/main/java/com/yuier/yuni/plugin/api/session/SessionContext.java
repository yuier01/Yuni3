package com.yuier.yuni.plugin.api.session;

import java.time.Instant;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 会话上下文——handler 与通道指令内可见的会话视图。
 *
 * <p>状态按“会话”隔离，不是按群/用户。关闭后清空。</p>
 */
public interface SessionContext {

    /**
     * 会话 ID，仅用于日志/管理端；重启后不保证唯一。
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
     * 发起者 QQ 号。
     *
     * @return 发起者
     */
    long getOpenerUserId();

    /**
     * 所属插件标识。
     *
     * @return 插件标识
     */
    String getPluginId();

    /**
     * 通道标签。
     *
     * @return 标签；可能为空串
     */
    String getTag();

    /**
     * 创建时间。
     *
     * @return 创建时间
     */
    Instant getCreatedAt();

    /**
     * 最后一条捕获消息的时间。
     *
     * @return 最后活跃时间
     */
    Instant getLastActiveAt();

    /**
     * 绝对过期时间（maxDuration 截止点）。
     *
     * @return 过期时间
     */
    Instant getExpiresAt();

    /**
     * 已处理的通道回合数（含加入/退出/自定义指令）。
     *
     * @return 回合数
     */
    int getTurnCount();

    /**
     * 当前排队等待处理的消息数。
     *
     * @return 数量
     */
    int getPendingMessageCount();

    /**
     * 会话是否仍可接收消息。
     *
     * @return 可接收返回 {@code true}
     */
    boolean isActive();

    /**
     * 当前参与者快照。
     *
     * <p>仅 {@code GROUP_PARTICIPANTS} 有意义；其他目标返回空集。</p>
     *
     * @return 不可变参与者集合
     */
    Set<Long> getParticipants();

    /**
     * 参与者数量；非部分群友为 0。
     *
     * @return 数量
     */
    int getParticipantCount();

    /**
     * 是否参与者；仅部分群友有意义。
     *
     * @param userId 用户 QQ 号
     * @return 是则返回 {@code true}
     */
    boolean isParticipant(long userId);

    /**
     * 手动加入参与者；仅部分群友可用。
     *
     * <p>不触发 {@code onParticipantJoin}；调用方自行通知。
     * 会检查“一个用户在一个群内只能处于一个通道”的约束。</p>
     *
     * @param userId 用户 QQ 号
     * @return 发生变化返回 {@code true}
     */
    boolean addParticipant(long userId);

    /**
     * 手动移除参与者；仅部分群友可用。
     *
     * <p>不触发 {@code onParticipantLeave}；移除最后一人会关闭通道。</p>
     *
     * @param userId 用户 QQ 号
     * @return 发生变化返回 {@code true}
     */
    boolean removeParticipant(long userId);

    /**
     * 取会话内状态；不存在时用无参构造器创建。
     *
     * @param type 状态类型
     * @param <T>  状态类型
     * @return 状态实例
     */
    <T> T state(Class<T> type);

    /**
     * 取会话内状态；不存在时用 factory 创建。
     *
     * @param type    状态类型
     * @param factory 创建函数；不能返回 {@code null}
     * @param <T>     状态类型
     * @return 状态实例
     */
    <T> T state(Class<T> type, Supplier<T> factory);

    /**
     * 请求关闭会话。
     *
     * <p>幂等、非阻塞；实际清理在当前 handler 返回后进行。</p>
     *
     * @param reason 关闭原因
     */
    void close(SessionCloseReason reason);
}
