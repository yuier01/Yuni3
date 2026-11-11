package com.yuier.yuni.plugin.api.service;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 缓存服务（Redis）门面。
 *
 * <h2>取代了什么</h2>
 * <p>现行插件直接使用静态类 {@code RedisUtil}（其内部是一个 {@code static}
 * 字段承载的 {@code RedisTemplate}）。静态字段承载注入实例在单元测试与
 * 多容器场景下都会出问题，且插件的缓存依赖不可见。</p>
 *
 * <h2>用法</h2>
 * <pre>{@code
 * // 简单值
 * ctx.getCache().set("plugin:fortune:" + userId, cache, Duration.ofHours(24));
 * FortuneTodayCache today = ctx.getCache().get("plugin:fortune:" + userId, FortuneTodayCache.class);
 *
 * // 哈希（现行 fortune / pig-today 插件用的是 Map 结构）
 * ctx.getCache().hashSet(KEY, userId.toString(), today);
 * Optional<FortuneTodayCache> t = ctx.getCache().hashGet(KEY, userId.toString(), FortuneTodayCache.class);
 * }</pre>
 *
 * <h2>键名约定</h2>
 * <p>插件键名前缀统一为 {@code plugin:<模块名>:...}，避免与其他插件的键碰撞。
 * 现行代码里 {@code PigToday} 与 {@code Fortune} 恰好都定义了名为
 * {@code *_TODAY_CACHE_KEY} 的常量，靠具体值区分——本约定是显式化该做法。</p>
 *
 * @see <a href="https://redis.io/commands/">Redis 命令参考</a>
 */
public interface YuniCache {

    // ==================== 字符串 / 对象 ====================

    /**
     * 写入一个值。
     *
     * @param key   键
     * @param value 值；会被序列化为 JSON
     */
    void set(String key, Object value);

    /**
     * 写入一个值并设置过期时间。
     *
     * @param key     键
     * @param value   值
     * @param ttl     存活时长；{@code null} 表示不过期
     */
    void set(String key, Object value, Duration ttl);

    /**
     * 读取一个值。
     *
     * @param key   键
     * @param clazz 目标类型
     * @param <T>   目标类型
     * @return 值；键不存在或反序列化失败时为空
     */
    <T> Optional<T> get(String key, Class<T> clazz);

    /**
     * 键是否存在。
     *
     * @param key 键
     * @return 存在返回 {@code true}
     */
    boolean exists(String key);

    /**
     * 删除一个键。
     *
     * @param key 键
     * @return 键存在并被删除时返回 {@code true}
     */
    boolean delete(String key);

    /**
     * 设置过期时间。
     *
     * @param key 键
     * @param ttl 存活时长
     * @return 设置成功返回 {@code true}
     */
    boolean expire(String key, Duration ttl);

    /**
     * 查询剩余存活时间。
     *
     * @param key 键
     * @return 剩余时长；键不存在或未设置过期时为空
     */
    Optional<Duration> ttl(String key);

    // ==================== 哈希 ====================

    /**
     * 写入哈希字段。
     *
     * @param key   哈希键
     * @param field 字段名
     * @param value 字段值
     */
    void hashSet(String key, String field, Object value);

    /**
     * 读取哈希字段。
     *
     * @param key   哈希键
     * @param field 字段名
     * @param clazz 目标类型
     * @param <T>   目标类型
     * @return 字段值；不存在时为空
     */
    <T> Optional<T> hashGet(String key, String field, Class<T> clazz);

    /**
     * 读取整个哈希。
     *
     * @param key   哈希键
     * @param clazz 值的目标类型
     * @param <T>   目标类型
     * @return 字段名 → 值；键不存在时返回空 Map
     */
    <T> Map<String, T> hashGetAll(String key, Class<T> clazz);

    /**
     * 删除哈希字段。
     *
     * @param key   哈希键
     * @param field 字段名
     * @return 字段存在并被删除时返回 {@code true}
     */
    boolean hashDelete(String key, String field);

    /**
     * 哈希是否包含某字段。
     *
     * @param key   哈希键
     * @param field 字段名
     * @return 包含返回 {@code true}
     */
    boolean hashHasKey(String key, String field);

    // ==================== 扫描 ====================

    /**
     * 按键模式扫描键。
     *
     * <p>内部使用 {@code SCAN} 游标迭代而<b>不是</b> {@code KEYS}——后者会阻塞
     * Redis 服务端（现行 webapi 的 {@code RedisViewService} 正是用 {@code KEYS}，
     * 键量大时会造成明显卡顿）。</p>
     *
     * @param pattern 匹配模式，如 {@code "plugin:fortune:*"}
     * @return 匹配的键集合
     */
    Set<String> keys(String pattern);
}
