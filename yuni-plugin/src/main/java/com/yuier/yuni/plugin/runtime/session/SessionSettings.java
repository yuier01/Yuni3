package com.yuier.yuni.plugin.runtime.session;

import java.time.Duration;

/**
 * 长会话全局配置。
 *
 * <p>字段含义见设计文档 6.3.1 参数字典。</p>
 *
 * @param enabled                 总开关
 * @param sweepInterval           超时扫描周期；{@link Duration#ZERO} 表示不启动扫描
 * @param maxSessions             全局活跃会话上限
 * @param maxSessionsPerPlugin    单插件活跃会话上限
 * @param closeWaitTimeout        卸载时等待全部在途 handler 的总上限
 * @param defaultIdleTimeout      单通道 idle 默认值
 * @param defaultMaxDuration      单通道绝对时长默认值
 * @param maxDurationLimit        单通道绝对时长硬上限
 * @param maxIdleTimeoutLimit     单通道 idle 硬上限
 * @param defaultMaxParticipants  部分群友参与者默认上限
 */
public record SessionSettings(
        boolean enabled,
        Duration sweepInterval,
        int maxSessions,
        int maxSessionsPerPlugin,
        Duration closeWaitTimeout,
        Duration defaultIdleTimeout,
        Duration defaultMaxDuration,
        Duration maxDurationLimit,
        Duration maxIdleTimeoutLimit,
        int defaultMaxParticipants) {

    /**
     * 默认配置。
     *
     * @return 默认值
     */
    public static SessionSettings defaults() {
        return new SessionSettings(
                true,
                Duration.ofSeconds(5),
                10_000,
                1_000,
                Duration.ofSeconds(2),
                Duration.ofMinutes(5),
                Duration.ofMinutes(30),
                Duration.ofHours(24),
                Duration.ofHours(2),
                200);
    }

    /**
     * 测试/无扫描配置。
     *
     * @return 不启动 sweeper 的默认配置
     */
    public static SessionSettings withoutSweeper() {
        return defaults().withSweepInterval(Duration.ZERO);
    }

    public SessionSettings withSweepInterval(Duration interval) {
        return new SessionSettings(enabled, interval, maxSessions, maxSessionsPerPlugin,
                closeWaitTimeout, defaultIdleTimeout, defaultMaxDuration,
                maxDurationLimit, maxIdleTimeoutLimit, defaultMaxParticipants);
    }

    public SessionSettings withEnabled(boolean value) {
        return new SessionSettings(value, sweepInterval, maxSessions, maxSessionsPerPlugin,
                closeWaitTimeout, defaultIdleTimeout, defaultMaxDuration,
                maxDurationLimit, maxIdleTimeoutLimit, defaultMaxParticipants);
    }
}
