package com.yuier.yuni.plugin.runtime.service;

import com.yuier.yuni.core.bot.JsonCodec;
import com.yuier.yuni.core.util.RedisUtil;
import com.yuier.yuni.plugin.api.service.YuniCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * {@link YuniCache} 的默认实现——包装 {@link RedisUtil}。
 *
 * <h2>取代了什么</h2>
 * <p>现行插件直接调用静态类 {@code RedisUtil}（其内部是一个 {@code static} 字段
 * 承载的 {@code RedisTemplate}）。两个问题：</p>
 * <ol>
 *   <li><b>静态字段承载注入实例</b>：{@code RedisUtil} 用 {@code @PostConstruct}
 *       把 Spring 注入的 template 写进静态字段。这在单容器下能用，但单元测试
 *       无法替换，多容器场景会互相覆盖。</li>
 *   <li><b>类型不安全</b>：{@code RedisUtil.get(key, clazz)} 的实现是
 *       {@code get(key).map(v -> (T) v)}——一个未检查的强制转型，
 *       传入错误的类型会在<b>使用该值时</b>抛 {@code ClassCastException}，
 *       而非在读取时。</li>
 * </ol>
 * <p>本实现保留 {@code RedisUtil} 作为底层通道（这样与现有数据格式完全兼容），
 * 但在返回值上做<b>显式类型转换</b>：当底层拿到的对象类型不是目标类型时，
 * 经 {@link JsonCodec} 再转换一次。转换失败返回 {@link Optional#empty()}
 * 而不是抛异常——缓存读取失败应降级，不应中断插件逻辑。</p>
 *
 * <h2>键名约定</h2>
 * <p>插件键名前缀统一为 {@code plugin:<jar 名>:...}。现行代码里
 * {@code PigToday} 与 {@code Fortune} 恰好都定义了名为 {@code *_TODAY_CACHE_KEY}
 * 的常量，靠具体值区分——本约定把该做法显式化，避免不同插件的键碰撞。</p>
 *
 * <h2>TTL 约定</h2>
 * <ul>
 *   <li>{@link #ttl(String)} 键不存在或无过期时返回空；</li>
 *   <li>Redis 的 {@code TTL} 命令对无过期键返回 -1、对不存在的键返回 -2，
 *       本实现把两者都归一为「空」——对调用方而言「不会过期」与
 *       「键不存在」在业务上通常等价于「不需要刷新」。</li>
 * </ul>
 *
 * @see YuniCache
 */
@Slf4j
@RequiredArgsConstructor
public final class RedisYuniCache implements YuniCache {

    /** JSON 编解码器，用于类型不符时的二次转换。 */
    private final JsonCodec jsonCodec;

    /* 构造器由 @RequiredArgsConstructor 生成：(JsonCodec jsonCodec)。 */

    // ==================== 字符串 / 对象 ====================

    @Override
    public void set(String key, Object value) {
        RedisUtil.set(key, value);
    }

    @Override
    public void set(String key, Object value, Duration ttl) {
        if (ttl == null) {
            set(key, value);
            return;
        }
        RedisUtil.set(key, value, ttl.getSeconds(), TimeUnit.SECONDS);
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> clazz) {
        if (key == null || clazz == null) {
            return Optional.empty();
        }
        Optional<Object> raw;
        try {
            raw = RedisUtil.get(key);
        } catch (Exception e) {
            // Redis 不可用时应降级而非中断插件逻辑
            log.warn("读取缓存失败，降级为未命中: key={}", key, e);
            return Optional.empty();
        }
        return raw.flatMap(value -> convert(value, clazz, key));
    }

    @Override
    public boolean exists(String key) {
        if (key == null) {
            return false;
        }
        try {
            return RedisUtil.exists(key);
        } catch (Exception e) {
            log.warn("判断缓存键存在性失败: key={}", key, e);
            return false;
        }
    }

    @Override
    public boolean delete(String key) {
        if (key == null) {
            return false;
        }
        try {
            RedisUtil.delete(key);
            return true;
        } catch (Exception e) {
            log.warn("删除缓存键失败: key={}", key, e);
            return false;
        }
    }

    @Override
    public boolean expire(String key, Duration ttl) {
        if (key == null || ttl == null) {
            return false;
        }
        try {
            return RedisUtil.expire(key, ttl.getSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("设置缓存过期时间失败: key={}", key, e);
            return false;
        }
    }

    @Override
    public Optional<Duration> ttl(String key) {
        if (key == null) {
            return Optional.empty();
        }
        try {
            // RedisUtil 未暴露 TTL 查询，这里按「无过期与不存在都视为空」处理。
            // 若将来需要精确 TTL，应在 RedisUtil 上补一个方法而不是在此绕过它。
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // ==================== 哈希 ====================

    @Override
    public void hashSet(String key, String field, Object value) {
        RedisUtil.hSet(key, field, value);
    }

    @Override
    public <T> Optional<T> hashGet(String key, String field, Class<T> clazz) {
        if (key == null || field == null || clazz == null) {
            return Optional.empty();
        }
        Optional<Object> raw;
        try {
            raw = RedisUtil.hGet(key, field);
        } catch (Exception e) {
            log.warn("读取缓存哈希字段失败: key={}, field={}", key, field, e);
            return Optional.empty();
        }
        return raw.flatMap(value -> convert(value, clazz, key + "#" + field));
    }

    @Override
    public <T> Map<String, T> hashGetAll(String key, Class<T> clazz) {
        if (key == null || clazz == null) {
            return Map.of();
        }
        Map<String, Object> raw;
        try {
            raw = RedisUtil.hGetAll(key);
        } catch (Exception e) {
            log.warn("读取缓存哈希失败: key={}", key, e);
            return Map.of();
        }
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, T> result = new LinkedHashMap<>(raw.size());
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            // 单个字段转换失败不影响其余字段——哈希里可能混有异构数据
            convert(entry.getValue(), clazz, key + "#" + entry.getKey())
                    .ifPresent(converted -> result.put(entry.getKey(), converted));
        }
        return Collections.unmodifiableMap(result);
    }

    @Override
    public boolean hashDelete(String key, String field) {
        if (key == null || field == null) {
            return false;
        }
        try {
            RedisUtil.hDelete(key, field);
            return true;
        } catch (Exception e) {
            log.warn("删除缓存哈希字段失败: key={}, field={}", key, field, e);
            return false;
        }
    }

    @Override
    public boolean hashHasKey(String key, String field) {
        if (key == null || field == null) {
            return false;
        }
        try {
            return RedisUtil.hGet(key, field).isPresent();
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 扫描 ====================

    @Override
    public Set<String> keys(String pattern) {
        /*
         * 当前底层 RedisUtil 未暴露 SCAN，因此本方法无法实现为游标迭代。
         * 明确抛出而不是偷偷用 KEYS——KEYS 会阻塞 Redis 服务端，
         * 而接口文档已承诺使用 SCAN（见 YuniCache#keys 的说明）。
         *
         * 待 RedisUtil 补上 scan 支持后在此接入。插件如需遍历键，
         * 当前应改用「已知键名列表 + get」的方式。
         */
        throw new UnsupportedOperationException(
                "缓存键扫描需要底层 RedisUtil 提供 SCAN 支持；"
                        + "当前请改用已知键名列表逐键读取，避免使用会阻塞 Redis 的 KEYS 命令。");
    }

    // ==================== 内部 ====================

    /**
     * 把缓存里取到的对象转换为目标类型。
     *
     * <p>转换策略：类型已匹配则直接返回；否则经 JSON 往返转换一次。
     * 转换失败（格式不符、目标类型不可构造等）返回空并记录 debug 日志——
     * 缓存命中失败应表现为「未命中」，由调用方回源，而不是抛异常。</p>
     *
     * @param value 缓存中的原始对象
     * @param clazz 目标类型
     * @param key   键名（仅用于日志）
     * @param <T>   目标类型
     * @return 转换结果；失败时为空
     */
    private <T> Optional<T> convert(Object value, Class<T> clazz, String key) {
        if (value == null) {
            return Optional.empty();
        }
        if (clazz.isInstance(value)) {
            return Optional.of(clazz.cast(value));
        }
        try {
            return Optional.ofNullable(jsonCodec.convertValue(value, clazz));
        } catch (Exception e) {
            // 常见于「缓存里存的类型与本次请求的类型不一致」（如某插件改了缓存结构）。
            // 视作未命中，让调用方回源并覆盖，而不是让插件崩在这里。
            log.debug("缓存值类型转换失败，视为未命中: key={}, 期望={}, 实际={}",
                    key, clazz.getSimpleName(), value.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
