package com.yuier.yuni.core.event.meta;

import lombok.Data;

/**
 * @Title: YuniHeartbeatEvent
 * @Author yuier
 * @Package com.yuier.yuni.event.context.meta
 * @Date 2025/12/29 19:29
 * @description:
 */

@Data
public class YuniHeartbeatEvent extends YuniMetaEvent{

    /**
     * 状态
     */
    private Object status;

    /**
     * 心跳间隔
     */
    private Long interval;

    /**
     * 心跳状态是否在线。
     *
     * @return 在线返回 {@code true}
     */
    public boolean isStatusOnline() {
        return statusValue("online");
    }

    /**
     * 心跳状态是否正常。
     *
     * @return 正常返回 {@code true}
     */
    public boolean isStatusGood() {
        return statusValue("good");
    }

    /**
     * 心跳间隔。
     *
     * @return 间隔毫秒数；未提供时为空
     */
    public java.util.Optional<Long> getHeartbeatInterval() {
        return java.util.Optional.ofNullable(interval);
    }

    private boolean statusValue(String key) {
        if (status instanceof java.util.Map<?, ?> map) {
            Object value = map.get(key);
            return value instanceof Boolean b && b;
        }
        return false;
    }

    @Override
    public String toLogString() {
        return "收到心跳事件，距离下次心跳还有 " + interval / 1000 + " 秒";
    }
}
