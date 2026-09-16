package com.yuier.yuni.plugin.runtime.adapter;

import com.yuier.yuni.core.bot.JsonCodec;
import com.yuier.yuni.core.bot.MessageTarget;
import com.yuier.yuni.core.bot.YuniBot;
import com.yuier.yuni.core.event.YuniMessageEvent;
import com.yuier.yuni.core.event.YuniMessageSentEvent;
import com.yuier.yuni.core.event.meta.YuniMetaEvent;
import com.yuier.yuni.core.event.notice.YuniNoticeEvent;
import com.yuier.yuni.core.event.request.YuniRequestEvent;
import com.yuier.yuni.plugin.api.Reply;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 事件适配工厂——把 core 事件对象翻译为插件侧的 {@code Event} 与 {@link Reply}。
 *
 * <h2>为什么需要一个工厂</h2>
 * <p>分发流程需要把「同一个 core 事件」翻译成三样东西：</p>
 * <ol>
 *   <li>供<b>条件判定</b>用的插件侧事件（每条注册项都要用它跑 predicate）；</li>
 *   <li>供<b>处理器调用</b>用的插件侧事件（同上，可复用同一个实例）；</li>
 *   <li>绑定到该事件会话的 {@link Reply}。</li>
 * </ol>
 * <p>如果把这些逻辑散在 {@code EventPipeline} 的各分支里，五类事件会各写一遍
 * 「取群号 → 构造 Reply → 包装事件」。集中到本工厂后，
 * {@code EventPipeline} 只负责「按类型分派 + 作用域过滤 + 权限检查 + 异常兜底」，
 * 两件事各自内聚。</p>
 *
 * <h2>翻译是无副作用的</h2>
 * <p>本工厂不访问数据库、不发网络请求。唯一可能的外部依赖是 {@link YuniBot}
 * （仅用于构造 {@link CoreReply}，构造时不发送任何消息）。
 * 因此翻译可以安全地在分发线程上同步完成。</p>
 *
 * <h2>关于「不需要翻译的旧路径」</h2>
 * <p>过渡期内旧插件系统仍会消费同一批 core 事件。本工厂<b>只读取</b>事件对象，
 * 不修改它，因此新旧两条路径可以并存消费同一事件而互不干扰。</p>
 */
public final class PluginEventAdapter {

    /**
     * 机器人实例供应函数。
     *
     * <p>用 {@link Supplier} 而非直接持有实例：{@code YuniBot} 由 engine 的
     * {@code BotManager} 在启动流程中<b>动态创建</b>，时机晚于本适配器的构造。
     * 因此这里按需解析——适配器构造时 bot 可能还不存在，
     * 但事件真正到达时它一定已经就绪。</p>
     */
    private final Supplier<YuniBot> botSupplier;

    /** JSON 编解码器，用于消息段序列化与 Reply 的段构造。 */
    private final JsonCodec jsonCodec;

    /**
     * 构造适配工厂。
     *
     * @param botSupplier 机器人实例供应函数；可为 {@code null}（则 Reply 不可用，仅能翻译事件）
     * @param jsonCodec   JSON 编解码器；可为 {@code null}
     */
    public PluginEventAdapter(Supplier<YuniBot> botSupplier, JsonCodec jsonCodec) {
        this.botSupplier = botSupplier;
        this.jsonCodec = jsonCodec;
    }

    /**
     * 以固定机器人实例构造适配工厂（测试与静态场景）。
     *
     * @param bot       机器人实例；可为 {@code null}
     * @param jsonCodec JSON 编解码器
     * @return 适配工厂
     */
    public static PluginEventAdapter of(YuniBot bot, JsonCodec jsonCodec) {
        return new PluginEventAdapter(() -> bot, jsonCodec);
    }

    /**
     * 解析当前的机器人实例。
     *
     * @return 机器人实例；不可用时返回 {@code null}
     */
    private YuniBot bot() {
        if (botSupplier == null) {
            return null;
        }
        try {
            return botSupplier.get();
        } catch (Exception e) {
            // bot 尚未就绪（启动早期）：事件仍可翻译，只是没有 Reply。
            // 不抛异常——否则启动阶段的第一批事件会全部失败。
            return null;
        }
    }

    /**
     * 翻译消息事件。
     *
     * @param source 核心消息事件
     * @return 插件侧事件
     */
    public CoreMessageEvent adapt(YuniMessageEvent source) {
        Reply reply = replyFor(source.getGroupId(), source.getUserId(),
                source.getMessageType(), source.getMessageId());
        return new CoreMessageEvent(
                source,
                new CoreMessageChainView(source.getMessageChain(), jsonCodec),
                reply);
    }

    /**
     * 翻译消息发出事件。
     *
     * @param source 核心事件
     * @return 插件侧事件
     */
    public CoreMessageSentEvent adapt(YuniMessageSentEvent source) {
        Reply reply = replyFor(source.getGroupId(), source.getUserId(),
                source.getMessageType(), source.getMessageId());
        return new CoreMessageSentEvent(
                source,
                new CoreMessageChainView(source.getMessageChain(), jsonCodec),
                reply);
    }

    /**
     * 翻译通知事件。
     *
     * @param source 核心事件
     * @return 插件侧事件
     */
    public CoreLifecycleEvents.CoreNoticeEvent adapt(YuniNoticeEvent source) {
        return new CoreLifecycleEvents.CoreNoticeEvent(source, replyFor(source));
    }

    /**
     * 翻译请求事件。
     *
     * @param source 核心事件
     * @return 插件侧事件
     */
    public CoreLifecycleEvents.CoreRequestEvent adapt(YuniRequestEvent source) {
        return new CoreLifecycleEvents.CoreRequestEvent(source, replyFor(source));
    }

    /**
     * 翻译元事件。
     *
     * @param source 核心事件
     * @return 插件侧事件
     */
    public CoreLifecycleEvents.CoreMetaEvent adapt(YuniMetaEvent source) {
        return new CoreLifecycleEvents.CoreMetaEvent(source, replyFor(source));
    }

    /**
     * 为通知事件构造回复入口（目标取自事件自身）。
     *
     * @param source 核心通知事件
     * @return 回复入口；无法确定会话时返回 {@code null}
     */
    private Reply replyFor(YuniNoticeEvent source) {
        // 通知事件没有 messageType 字段，按 groupId 是否存在推断会话类型
        return source.getGroupId() != null
                ? replyFor(source.getGroupId(), source.getUserId(), "group", null)
                : replyFor(null, source.getUserId(), "private", null);
    }

    /**
     * 为请求事件构造回复入口。
     *
     * @param source 核心请求事件
     * @return 回复入口；无法确定会话时返回 {@code null}
     */
    private Reply replyFor(YuniRequestEvent source) {
        return source.getGroupId() != null
                ? replyFor(source.getGroupId(), source.getUserId(), "group", null)
                : replyFor(null, source.getUserId(), "private", null);
    }

    /**
     * 为元事件构造回复入口。
     *
     * <p>元事件是连接层事件，不属于任何会话，因此没有可回复的目标。</p>
     *
     * @param source 核心元事件
     * @return 恒为 {@code null}
     */
    private Reply replyFor(YuniMetaEvent source) {
        return null;
    }

    /**
     * 构造绑定到指定会话的回复入口。
     *
     * @param groupId         群号；私聊时为 {@code null}
     * @param userId          用户 QQ 号
     * @param messageType     消息类型（{@code group} / {@code private}）
     * @param sourceMessageId 源消息 ID，用于 {@link Reply#quote()}；可为 {@code null}
     * @return 回复入口；无法构造时返回 {@code null}
     */
    private Reply replyFor(Long groupId, Long userId, String messageType, Long sourceMessageId) {
        if (bot() == null) {
            // 测试或未接线场景：事件可翻译但没有可发送的 Reply。
            // 返回 null 而不是抛异常——事件翻译本身仍有价值（供条件判定）。
            return null;
        }
        if ("group".equals(messageType) && groupId != null) {
            return new CoreReply(bot(), MessageTarget.group(groupId), sourceMessageId, jsonCodec);
        }
        if (userId != null) {
            // 私聊；也覆盖「messageType 未知但有 userId」的情况
            return new CoreReply(bot(), MessageTarget.privateChat(userId), sourceMessageId, jsonCodec);
        }
        return null;
    }

    /**
     * 取机器人的平台账号 ID。
     *
     * <p>供分发器与诊断使用。</p>
     *
     * @return 机器人 ID；bot 缺失时为空
     */
    public Optional<String> botId() {
        YuniBot current = bot();
        return current == null ? Optional.empty() : Optional.ofNullable(current.getBotId());
    }
}
