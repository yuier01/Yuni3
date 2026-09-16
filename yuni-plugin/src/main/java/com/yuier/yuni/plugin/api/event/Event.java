package com.yuier.yuni.plugin.api.event;

import com.yuier.yuni.plugin.api.MatchToken;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 插件侧事件视图——所有事件类型的公共基类。
 *
 * <h2>设计目标</h2>
 * <ol>
 *   <li><b>协议无关</b>：插件只面对本接口与 {@link MessageChainView}，
 *       不 import {@code yuni-core} 下的任何事件类。</li>
 *   <li><b>字段去重</b>：现行系统有 23 个事件类、字段在子类间互相遮蔽
 *       （{@code AddGroupRequest} 与 {@code InviteToGroupRequest} 重复声明父类字段）。
 *       新设计用一个 {@code Map} 承载差异字段，公共字段提升到本接口。</li>
 *   <li><b>不可变</b>：事件在分发期间不应被插件修改。</li>
 * </ol>
 *
 * <h2>字段访问约定</h2>
 * <p>差异字段通过 {@link #value(String)} 系列方法按名访问。键名与 OneBot 协议
 * 字段名保持一致（snake_case 的语义等价形式，如 {@code target_id} / {@code sub_type}）。
 * 各子类型接口会为高频字段提供带类型的便捷方法，插件应优先使用那些方法。</p>
 *
 * <h2>缺失字段的语义</h2>
 * <ul>
 *   <li>{@link #value(String)} 返回 {@link Optional}，缺字段或值为 {@code null}
 *       时为空——调用方必须处理。</li>
 *   <li>{@link #longValue(String)} / {@link #stringValue(String)} 在缺字段时返回
 *       {@code null}（对象类型）或 {@code 0}（{@code longValueOf}）。</li>
 * </ul>
 *
 * @see MessageEvent
 * @see NoticeEvent
 * @see RequestEvent
 * @see MetaEvent
 * @see MessageSentEvent
 */
public interface Event {

    /**
     * 事件时间戳（秒级 Unix 时间）。
     *
     * @return 事件发生时间；协议未提供时为 {@code 0}
     */
    long time();

    /**
     * 收到/产生本事件的机器人账号 ID。
     *
     * @return 机器人自身 QQ 号
     */
    long selfId();

    /**
     * 事件来源的群号。
     *
     * <p>群聊事件返回群号；私聊事件、元事件与心跳返回 {@link Optional#empty()}。
     * 这是分发器做 {@code ScopeFilter} 判定时使用的字段。</p>
     *
     * @return 群号；非群事件为空
     */
    Optional<Long> groupId();

    /**
     * 事件子类型标识，用于「匹配到哪一类具体事件」的判定。
     *
     * <p>由适配器在翻译阶段写入，取值对齐 OneBot 协议，例如：</p>
     * <ul>
     *   <li>通知事件：{@code poke} / {@code group_admin} / {@code group_ban} / {@code honor} …</li>
     *   <li>请求事件：{@code friend} / {@code group}（加群）/ {@code invite}（被邀请）</li>
     *   <li>元事件：{@code heartbeat} / {@code lifecycle}</li>
     * </ul>
     *
     * @return 子类型标识，永不为 {@code null}（无子类型时返回空串）
     */
    String subType();

    /**
     * 判定本事件的子类型是否匹配。
     *
     * <p>与 {@link MatchToken#is(String)} 对称。在处理器里做二次判定时比
     * {@code "poke".equals(ev.subType())} 更简洁，且 {@code null} 安全。</p>
     *
     * <pre>{@code
     * ctx.onNotice((ev, reply) -> {
     *     if (ev.is("poke")) { ... }
     * });
     * }</pre>
     *
     * @param expected 期望的子类型；{@code null} 恒返回 {@code false}
     * @return 子类型相等时返回 {@code true}
     */
    default boolean is(String expected) {
        return expected != null && expected.equals(subType());
    }

    /**
     * 按名取差异字段。
     *
     * @param key 字段名
     * @return 字段值；键不存在或值为 {@code null} 时为空
     */
    Optional<Object> value(String key);

    /**
     * 按名取差异字段，缺省返回 {@code fallback}。
     *
     * @param key      字段名
     * @param fallback 缺省值
     * @return 字段值或 {@code fallback}
     */
    default Object valueOr(String key, Object fallback) {
        return value(key).orElse(fallback);
    }

    /**
     * 按名取字符串字段。
     *
     * @param key 字段名
     * @return 字段的字符串形式；缺失或为 {@code null} 时返回 {@code null}
     */
    default String stringValue(String key) {
        Object v = value(key).orElse(null);
        return v == null ? null : String.valueOf(v);
    }

    /**
     * 按名取整数字段。
     *
     * <p>用于 QQ 号、群号、消息 ID 等。数值类型直接取值；字符串类型尝试解析，
     * 解析失败返回 {@code fallback}。OneBot 实现有时把数字放在 JSON 字符串里，
     * 因此这里做兼容。</p>
     *
     * @param key      字段名
     * @param fallback 无法解析时的返回値
     * @return 解析出的整数或 {@code fallback}
     */
    default long longValueOr(String key, long fallback) {
        Object v = value(key).orElse(null);
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    /**
     * 按名取整数字段，缺失时返回 {@code 0}。
     *
     * @param key 字段名
     * @return 整数或 {@code 0}
     */
    default long longValue(String key) {
        return longValueOr(key, 0L);
    }

    /**
     * 按名取布尔字段。
     *
     * @param key      字段名
     * @param fallback 缺失时的返回值
     * @return 布尔值或 {@code fallback}
     */
    default boolean booleanValueOr(String key, boolean fallback) {
        Object v = value(key).orElse(null);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s) {
            return Boolean.parseBoolean(s.trim());
        }
        return fallback;
    }

    /**
     * 本事件携带的全部差异字段（只读快照）。
     *
     * <p>用于诊断与日志输出。<b>不要依赖其内容做业务判定</b>——字段集合随协议
     * 实现（NapCat / LLOneBot / go-cqhttp）而异，应使用带类型的便捷方法。</p>
     *
     * @return 不可变的字段映射
     */
    default Map<String, Object> rawFields() {
        return Collections.emptyMap();
    }

    /**
     * 原始协议 JSON。
     *
     * <p><b>逃生舱</b>：仅用于诊断、日志、以及管理端推送。
     * 插件业务逻辑<b>不应</b>依赖它——现行系统里 {@code PokeNotice} 因为事件模型
     * 字段缺失而被迫两次反序列化 rawJson，那正是本设计要消除的模式。</p>
     *
     * @return 原始 JSON 字符串，可能为 {@code null}
     */
    default String rawJson() {
        return null;
    }

    /**
     * 本事件被哪个探测器/类型化处理器匹配到的令牌。
     *
     * <p>类型化处理器（如 {@code ctx.onNotice("poke", handler)}）在调用前会把它
     * 写入事件，供处理器内做二次判定。非类型化匹配时为 {@code null}。</p>
     *
     * @return 匹配令牌，可能为 {@code null}
     */
    default MatchToken matched() {
        return null;
    }

    /**
     * 供框架在类型化匹配成功后回填匹配令牌。
     *
     * <p>实现应为幂等赋值；插件不应调用本方法。</p>
     *
     * @param token 匹配令牌
     */
    default void setMatched(MatchToken token) {
        // 默认空实现：不可变事件实现可以忽略
    }

    /**
     * 事件的单行日志表示。
     *
     * @return 人类可读的事件摘要
     */
    default String toLogString() {
        return getClass().getSimpleName() + "{time=" + time() + ", selfId=" + selfId()
                + ", groupId=" + groupId().orElse(null) + ", subType='" + subType() + "'}";
    }

    /**
     * 本事件携带的全部字段，用于日志与诊断（含公共字段）。
     *
     * @return 字段名 → 值的只读列表
     */
    default List<String> describeFields() {
        return List.of();
    }
}
