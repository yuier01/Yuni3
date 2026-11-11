package com.yuier.yuni.plugin.api;

import java.util.Objects;

/**
 * 不透明令牌——在新插件系统中仅用于「匹配到的具体事件子类型」这一场景。
 *
 * <h2>为什么需要它</h2>
 * <p>现行系统把每个事件子类型都做成一个独立的 Java 类（{@code PokeEvent}、
 * {@code GroupAdminEvent}、{@code YuniHeartbeatEvent} …），导致两个问题：</p>
 * <ol>
 *   <li><b>与 core 耦合</b>：插件作者必须 import {@code com.yuier.yuni.core.event.notice.*}
 *       下的具体类，而那个包是框架内部实现，改一次就破坏全部插件。</li>
 *   <li><b>横向依赖</b>：新增一个 notice 子类型要同时改 core（事件类）、
 *       adapter（翻译器）、以及所有想处理它的插件。</li>
 * </ol>
 *
 * <p>新系统中，插件侧的事件类型<b>只表达「是哪一类事件」</b>（消息 / 通知 / 请求 /
 * 元 / 消息发出），具体子类型由 {@code Event} 自身携带的类型标识描述，
 * 判定方式是{@link #is(String)} 而不是 {@code instanceof}。</p>
 *
 * <h2>用法</h2>
 * <pre>{@code
 * ctx.onNotice("poke", (ev, reply) -> {          // 只关心 poke 通知
 *     long target = ev.longValue("target_id");
 *     ...
 * });
 * }</pre>
 *
 * <h2>与 frame object 的关系</h2>
 * <p>{@link #getFrame()} 返回框架侧（{@code yuni-core}）的原始事件对象，供
 * 过渡期使用——插件若需要访问新 API 尚未覆盖的字段，可以取它。
 * <b>这是逃生舱，不应作为常规用法</b>：一旦插件依赖 frame object 的具体类型，
 * 就重新引入了上面说的耦合。</p>
 *
 * @deprecated 本类的设计仍在演进。当前建议：优先使用 {@code Event} 上的通用访问方法
 *             （{@code getType()} / {@code is(String)} / {@code longValue(String)} 等）。
 *             {@link #getFrame()} 仅作为过渡期逃生舱保留。
 */
@Deprecated(since = "插件系统重构 P1", forRemoval = false)
public interface MatchToken {

    /**
     * 本令牌对应的类型标识。
     *
     * <p>取值约定与 OneBot 协议对齐，例如通知事件取 {@code notice_type} 或
     * {@code sub_type}（{@code "poke"} / {@code "group_admin"} / {@code "honor"} …），
     * 元事件取 {@code meta_event_type}（{@code "heartbeat"} / {@code "lifecycle"}）。</p>
     *
     * @return 类型标识，永不为 {@code null}
     */
    String getType();

    /**
     * 判定本令牌是否为指定类型。
     *
     * @param expected 期望的类型标识；{@code null} 恒返回 {@code false}
     * @return 类型标识相等时返回 {@code true}
     */
    default boolean is(String expected) {
        return Objects.equals(getType(), expected);
    }

    /**
     * 取框架侧的原始事件对象（逃生舱）。
     *
     * <p>返回值类型由产生本令牌的事件类型决定；无原始对象时返回 {@code null}。
     * <b>不要依赖其具体类型</b>——那会把插件重新绑定到框架内部模型上。</p>
     *
     * @return 框架侧原始事件对象，可能为 {@code null}
     */
    Object getFrame();
}
