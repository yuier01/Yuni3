package com.yuier.yuni.plugin.api;

import com.yuier.yuni.plugin.api.handler.MessageHandler;
import com.yuier.yuni.plugin.api.handler.MessageSentHandler;
import com.yuier.yuni.plugin.api.handler.MetaHandler;
import com.yuier.yuni.plugin.api.handler.NoticeHandler;
import com.yuier.yuni.plugin.api.handler.RequestHandler;

import java.util.List;

/**
 * 插件接口——新插件系统中的<b>唯一</b>插件形态。
 *
 * <h2>设计要点</h2>
 * <p>现行系统有 8 种插件类型（{@code ScheduledPlugin} / {@code ImmediatePlugin} /
 * {@code CommandPlugin} / {@code PatternPlugin} / {@code NoticePlugin} /
 * {@code RequestPlugin} / {@code MetaPlugin} / {@code MessageSentPlugin}），
 * 每个插件必须 {@code extends} 一个类型基类。新系统中这些类型全部消失，
 * 取而代之的是一句陈述：</p>
 *
 * <blockquote>
 *   <b>插件 = 一个元数据 + 一个 {@code apply(ctx)} 函数。</b>
 * </blockquote>
 *
 * <p>「这个插件是什么类型」不再是<b>声明</b>，而是它在 {@code apply} 里注册了
 * 什么能力的<b>推论</b>——注册了 {@code ctx.schedule(...)} 就是定时插件，
 * 注册了 {@code ctx.command(...)} 就是命令插件，两者兼有就是两者兼有。</p>
 *
 * <h2>一个完整插件</h2>
 * <pre>{@code
 * public final class OhaYo implements YuniPlugin {
 *
 *     @Override
 *     public PluginMetadata getMetadata() {
 *         return PluginMetadata.of("定时问候")
 *                 .version("0.0.1")
 *                 .description("每天早上向 bot master 问好")
 *                 .author("Yuier me@yuier.com")
 *                 .build();
 *     }
 *
 *     @Override
 *     public void apply(YuniContext ctx) {
 *         ctx.schedule("0 0 10 * * ?", () ->
 *                 ctx.getSender().privateChat(ctx.masterId()).text("偶哈哟，欧尼酱！").send());
 *     }
 * }
 * }</pre>
 *
 * <h2>生命周期</h2>
 * <p>只有两个时机：</p>
 * <ol>
 *   <li>{@link #apply(YuniContext)} —— 插件被加载时调用一次，用于注册全部能力。</li>
 *   <li>插件卸载 —— <b>无需插件参与</b>。框架自动逆序撤销 {@code apply} 期间
 *       建立的全部 Effect（监听器、命令、定时任务、服务、自定义资源）。
 *       框架不认识的资源用 {@code ctx.effect(...)} 声明。</li>
 * </ol>
 *
 * <p>因此现行系统的 {@code initialize()} / {@code destroy()} / {@code enable()} /
 * {@code disable()} 四个回调<b>全部消失</b>：</p>
 * <ul>
 *   <li>{@code initialize()} → {@code apply} 的开头几行；</li>
 *   <li>{@code destroy()} → {@code ctx.effect(() -> disposer)}；</li>
 *   <li>{@code enable()} / {@code disable()} → 不需要。群粒度启停由
 *       {@link ScopeFilter} 表达，不涉及插件的代码。</li>
 * </ul>
 *
 * <h2>实现方式</h2>
 * <p>本接口<b>不是</b>函数式接口（有两个方法），但 {@link #getMetadata()} 有默认实现，
 * 因此对不需要元数据的内部插件可以用 lambda：</p>
 * <pre>{@code
 * YuniPlugin greet = ctx -> ctx.onMessage(ev -> ev.getChain().containsText("早"),
 *                                        (ev, reply) -> reply.text("早上好～"));
 * }</pre>
 *
 * @see YuniContext
 * @see PluginMetadata
 */
@FunctionalInterface
public interface YuniPlugin {

    /**
     * 注册本插件的全部能力。
     *
     * <p>本方法在插件加载时被调用<b>一次</b>。期间通过 {@code ctx} 建立的一切注册
     * 都是 Effect，会在插件卸载时自动逆序撤销。</p>
     *
     * <h3>实现约束</h3>
     * <ul>
     *   <li><b>不要阻塞</b>：本方法在启动流程中同步执行。耗时初始化（网络探测、
     *       大文件读取）应放进 {@code ctx.runNow(...)} 或 {@code ctx.schedule(...)}。</li>
     *   <li><b>不要捕获 ctx 到静态字段</b>：{@code ctx} 的生命周期与插件实例一致，
     *       静态持有会导致卸载后仍被引用，进而泄漏插件 ClassLoader。</li>
     *   <li><b>幂等性不要求</b>：框架保证每个插件实例只调一次 {@code apply}。
     *       热重载时会用<b>新实例</b>重新调用。</li>
     * </ul>
     *
     * <h3>异常</h3>
     * <p>抛出异常会使该插件进入 {@code FAILED} 状态：框架记录完整堆栈、
     * 在管理端标记为失败、并跳过该插件（不影响其他插件）。已建立的 Effect
     * 会被回滚，避免半初始化状态。</p>
     *
     * @param ctx 该插件专属的上下文；不可为 {@code null}
     * @throws Exception 任何导致插件无法完成初始化的错误
     */
    void apply(YuniContext ctx) throws Exception;

    /**
     * 插件元数据。
     *
     * <p>默认实现返回一个「以类名命名、全部字段取默认值」的元数据，
     * 便于编写无需展示信息的内部插件。对外发布的插件应当覆写本方法。</p>
     *
     * @return 元数据，永不为 {@code null}
     */
    default PluginMetadata getMetadata() {
        return PluginMetadata.of(getClass().getSimpleName()).build();
    }

    /**
     * 声明本插件依赖的服务类型。
     *
     * <p>框架在调用 {@link #apply(YuniContext)} 之前会检查这些服务是否已注册。
     * 若未就绪，插件停在 {@code PENDING} 状态，<b>不</b>调用 {@code apply}——
     * 等到依赖满足后再加载。这使「我的插件为什么没启动」成为一个可回答的问题，
     * 而不是静默失败。</p>
     *
     * <h3>用法</h3>
     * <pre>{@code
     * @Override
     * public List<Class<?>> inject() {
     *     return List.of(MaiMai.Api.class);   // 需要 maimai 插件提供的服务
     * }
     * }</pre>
     *
     * <h3>循环依赖</h3>
     * <p>若 A 依赖 B 且 B 依赖 A，两者都会停在 {@code PENDING}。框架在启动完成后
     * 检测全部 PENDING 插件并<b>显式报错</b>列出依赖环，不会静默。</p>
     *
     * @return 依赖的服务类型列表；无依赖时返回空列表
     */
    default List<Class<?>> inject() {
        return List.of();
    }

    // ==================== 函数式便捷构造 ====================

    /**
     * 用 lambda 构造一个无元数据的简单插件。
     *
     * <p>用于框架内部的胶水逻辑、以及把一个 {@code apply} 函数直接挂载为子插件。
     * 对外发布的插件应实现本接口并覆写 {@link #getMetadata()}，以便管理端正确展示。</p>
     *
     * @param metadata 元数据
     * @param apply    能力注册函数
     * @return 插件实例
     */
    static YuniPlugin of(PluginMetadata metadata, java.util.function.Consumer<YuniContext> apply) {
        return new YuniPlugin() {
            @Override
            public void apply(YuniContext ctx) {
                apply.accept(ctx);
            }

            @Override
            public PluginMetadata getMetadata() {
                return metadata;
            }
        };
    }

    /**
     * 用 lambda 构造一个仅指定名称的简单插件。
     *
     * @param name  插件显示名
     * @param apply 能力注册函数
     * @return 插件实例
     */
    static YuniPlugin of(String name, java.util.function.Consumer<YuniContext> apply) {
        return of(PluginMetadata.of(name).build(), apply);
    }

    /**
     * 用 lambda 构造一个「只注册一条消息处理器」的极简插件。
     *
     * <p>最高频用法的语法糖，等价：</p>
     * <pre>{@code
     * YuniPlugin.of(name, ctx -> ctx.onMessage(when, handler));
     * }</pre>
     *
     * @param name    插件显示名
     * @param when    消息探测条件
     * @param handler 消息处理器
     * @return 插件实例
     */
    static YuniPlugin onMessage(String name,
                                java.util.function.Predicate<com.yuier.yuni.plugin.api.event.MessageEvent> when,
                                MessageHandler handler) {
        return of(name, ctx -> ctx.onMessage(when, handler));
    }

    /**
     * 用 lambda 构造一个「只注册一条通知处理器」的极简插件。
     *
     * @param name      插件显示名
     * @param subType   通知子类型（如 {@code "poke"}）
     * @param handler   通知处理器
     * @return 插件实例
     */
    static YuniPlugin onNotice(String name, String subType, NoticeHandler handler) {
        return of(name, ctx -> ctx.onNotice(subType, handler));
    }

    /**
     * 用 lambda 构造一个「只注册一条请求处理器」的极简插件。
     *
     * @param name    插件显示名
     * @param subType 请求子类型（如 {@code "friend"}）
     * @param handler 请求处理器
     * @return 插件实例
     */
    static YuniPlugin onRequest(String name, String subType, RequestHandler handler) {
        return of(name, ctx -> ctx.onRequest(subType, handler));
    }

    /**
     * 用 lambda 构造一个「只注册一条元事件处理器」的极简插件。
     *
     * @param name    插件显示名
     * @param subType 元事件类型（如 {@code "heartbeat"}）
     * @param handler 元事件处理器
     * @return 插件实例
     */
    static YuniPlugin onMeta(String name, String subType, MetaHandler handler) {
        return of(name, ctx -> ctx.onMeta(subType, handler));
    }

    /**
     * 用 lambda 构造一个「只注册一条消息发出处理器」的极简插件。
     *
     * @param name    插件显示名
     * @param when    探测条件
     * @param handler 处理器
     * @return 插件实例
     */
    static YuniPlugin onMessageSent(String name,
                                    java.util.function.Predicate<com.yuier.yuni.plugin.api.event.MessageSentEvent> when,
                                    MessageSentHandler handler) {
        return of(name, ctx -> ctx.onMessageSent(when, handler));
    }
}
