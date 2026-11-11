package com.yuier.yuni.plugin.api.event;

import java.util.Optional;

/**
 * 元事件。
 *
 * <p>对应 OneBot {@code post_type = "meta_event"}，是<b>连接层</b>事件而非用户行为：
 * 心跳与生命周期。</p>
 *
 * <h2>子类型</h2>
 * <ul>
 *   <li>{@link #TYPE_HEARTBEAT}：周期性心跳，携带连接状态与间隔。</li>
 *   <li>{@link #TYPE_LIFECYCLE}：连接建立 / 启用 / 停用。</li>
 * </ul>
 *
 * <h2>典型用法</h2>
 * <pre>{@code
 * // 记录心跳，用于观测前端是否存活
 * ctx.onMeta("heartbeat", (ev, reply) -> {
 *     log.debug("心跳: online={}, interval={}ms",
 *               ev.isStatusOnline(), ev.getHeartbeatInterval().orElse(0L));
 * });
 * }</pre>
 *
 * <h2>与现行系统的差异</h2>
 * <p>现行 {@code EventTranslator.buildYuniMetaEvent} 只搬运 {@code metaEventType}
 * 与 {@code subType}，<b>丢弃了 {@code status} 与 {@code interval}</b>，
 * 于是 {@code HeartBeatEvent} 插件实际上拿不到心跳间隔。新系统保留这两个字段
 * （通过 {@link #getHeartbeatInterval()} 与 {@link #isStatusOnline()}）。</p>
 */
public interface MetaEvent extends Event {

    /** OneBot {@code meta_event_type} 取值：心跳。 */
    String TYPE_HEARTBEAT = "heartbeat";

    /** OneBot {@code meta_event_type} 取值：生命周期。 */
    String TYPE_LIFECYCLE = "lifecycle";

    /**
     * 元事件类型（OneBot {@code meta_event_type}）。
     *
     * @return {@link #TYPE_HEARTBEAT} 或 {@link #TYPE_LIFECYCLE}
     */
    String getMetaEventType();

    /**
     * 是否为心跳事件。
     *
     * @return 心跳返回 {@code true}
     */
    default boolean isHeartbeat() {
        return TYPE_HEARTBEAT.equals(getMetaEventType());
    }

    /**
     * 是否为生命周期事件。
     *
     * @return 生命周期返回 {@code true}
     */
    default boolean isLifecycle() {
        return TYPE_LIFECYCLE.equals(getMetaEventType());
    }

    /**
     * 心跳间隔（毫秒）。
     *
     * <p>现行系统丢弃了该字段（见类注释），新系统保留。同时也可用于校验
     * 「本端配置的心跳间隔」与「对端实际间隔」是否一致。</p>
     *
     * @return 心跳间隔；非心跳事件或未提供时为空
     */
    default Optional<Long> getHeartbeatInterval() {
        Object v = value("interval").orElse(null);
        return v == null ? Optional.empty() : Optional.of(longValue("interval"));
    }

    /**
     * 连接是否在线（心跳 {@code status.online}）。
     *
     * @return 在线返回 {@code true}；非心跳事件或未提供时返回 {@code false}
     */
    default boolean isStatusOnline() {
        return booleanValueOr("status.online", false);
    }

    /**
     * 连接是否正常（心跳 {@code status.good}）。
     *
     * @return 正常返回 {@code true}；非心跳事件或未提供时返回 {@code false}
     */
    default boolean isStatusGood() {
        return booleanValueOr("status.good", false);
    }

    /**
     * 生命周期阶段（生命周期事件的 {@code sub_type}）。
     *
     * @return {@code connect} / {@code enable} / {@code disable}；非生命周期事件时为空
     */
    default Optional<String> getLifecyclePhase() {
        return isLifecycle() ? Optional.ofNullable(getSubType()) : Optional.empty();
    }
}
