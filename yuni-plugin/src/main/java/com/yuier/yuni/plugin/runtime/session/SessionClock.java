package com.yuier.yuni.plugin.runtime.session;

import java.time.Instant;

/**
 * 会话时钟——为超时逻辑提供可替换的时间源。
 */
public interface SessionClock {

    /**
     * 单调纳秒时间，用于超时判定。
     *
     * @return 纳秒
     */
    long nanoTime();

    /**
     * 当前墙上时间，仅用于展示。
     *
     * @return 时间
     */
    Instant now();

    /**
     * 系统时钟。
     *
     * @return 时钟
     */
    static SessionClock system() {
        return new SessionClock() {
            @Override
            public long nanoTime() {
                return System.nanoTime();
            }

            @Override
            public Instant now() {
                return Instant.now();
            }
        };
    }
}
