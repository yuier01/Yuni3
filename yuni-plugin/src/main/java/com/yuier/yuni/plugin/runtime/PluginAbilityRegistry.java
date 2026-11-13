package com.yuier.yuni.plugin.runtime;

import com.yuier.yuni.event.detector.message.command.model.CommandNode;
import com.yuier.yuni.plugin.api.Disposable;
import com.yuier.yuni.plugin.api.ScopeFilter;
import com.yuier.yuni.plugin.api.event.MessageEvent;
import com.yuier.yuni.plugin.api.event.MessageSentEvent;
import com.yuier.yuni.plugin.api.event.MetaEvent;
import com.yuier.yuni.plugin.api.event.NoticeEvent;
import com.yuier.yuni.plugin.api.event.RequestEvent;
import com.yuier.yuni.plugin.api.handler.MessageHandler;
import com.yuier.yuni.plugin.api.handler.MessageSentHandler;
import com.yuier.yuni.plugin.api.handler.MetaHandler;
import com.yuier.yuni.plugin.api.handler.NoticeHandler;
import com.yuier.yuni.plugin.api.handler.RequestHandler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 能力注册总表——框架侧持有「所有插件注册了什么能力」的唯一索引。
 *
 * <h2>为什么用 sealed</h2>
 * <p>注册项的能力种类、谓词类型、处理器类型是<b>一一对应</b>的：
 * 消息注册项只可能带 {@code Predicate<MessageEvent>} 与 {@code MessageHandler}，
 * 通知注册项只可能带 {@code Predicate<NoticeEvent>} 与 {@code NoticeHandler}。</p>
 *
 * <p>{@link Registration} 用 sealed 接口 + 每种能力一个 record 表达这个约束：
 * 种类由<b>子类型</b>决定，而不是一个可以和谓词/载荷不一致的
 * {@code kind} 字段。分发路径用 pattern matching 穷尽处理，新增能力类型时
 * 编译器会强制所有 switch 补分支。</p>
 *
 * <h2>顺序语义</h2>
 * <p>同桶内的条目按注册顺序保存。命令优先于模式是 {@code EventPipeline}
 * 的阶段语义，不依赖桶内顺序；桶内顺序仅影响同类处理器之间的调用先后。</p>
 *
 * <h2>线程安全</h2>
 * <p>注册发生在插件加载期，分发发生在运行期，且热重载会并发增删。使用
 * {@link CopyOnWriteArrayList}：读取零同步开销。</p>
 */
public final class PluginAbilityRegistry {

    /** 命令树根节点的注册项。 */
    private final List<Registration.Command> commandEntries = new CopyOnWriteArrayList<>();

    /** 消息事件处理器注册项。 */
    private final List<Registration.Message> messageEntries = new CopyOnWriteArrayList<>();

    /** 消息发出事件处理器注册项。 */
    private final List<Registration.MessageSent> messageSentEntries = new CopyOnWriteArrayList<>();

    /** 通知事件处理器注册项。 */
    private final List<Registration.Notice> noticeEntries = new CopyOnWriteArrayList<>();

    /** 请求事件处理器注册项。 */
    private final List<Registration.Request> requestEntries = new CopyOnWriteArrayList<>();

    /** 元事件处理器注册项。 */
    private final List<Registration.Meta> metaEntries = new CopyOnWriteArrayList<>();

    /**
     * 注册项的能力种类。
     *
     * <p>只用于<b>查询/统计</b>（{@link #kindsOf}、{@link #countByKind}），
     * 不参与类型判定——类型由 {@link Registration} 的子类型决定。</p>
     */
    public enum RegistrationKind {
        /** 命令树。 */
        COMMAND("命令"),
        /** 消息事件处理器。 */
        MESSAGE("消息"),
        /** 机器人自身发出消息的事件处理器。 */
        MESSAGE_SENT("消息发出"),
        /** 通知事件处理器。 */
        NOTICE("通知"),
        /** 请求事件处理器。 */
        REQUEST("请求"),
        /** 元事件处理器。 */
        META("元事件");

        /** 面向管理端的中文显示名。 */
        private final String displayName;

        RegistrationKind(String displayName) {
            this.displayName = displayName;
        }

        /**
         * 中文显示名。
         *
         * @return 显示名
         */
        public String getDisplayName() {
            return displayName;
        }
    }

    /**
     * 一条注册项。
     *
     * <p>每个子类型携带该能力自己的谓词与处理器类型，因此不存在
     * {@code Predicate<Object>} / {@code Object payload} 与强转。</p>
     */
    public sealed interface Registration
            permits Registration.Command, Registration.Message,
                    Registration.MessageSent, Registration.Notice,
                    Registration.Request, Registration.Meta {

        /**
         * 能力种类。
         *
         * @return 种类
         */
        RegistrationKind kind();

        /**
         * 注册者；测试里可能为 {@code null}。
         *
         * @return Fiber
         */
        PluginFiber owner();

        /**
         * 注册项自带的作用域过滤器。
         *
         * @return 过滤器
         */
        ScopeFilter filter();

        /**
         * 所属插件当前是否可服务。
         *
         * @return ACTIVE 时返回 {@code true}
         */
        default boolean ownerServiceable() {
            PluginFiber owner = owner();
            return owner != null && owner.isServiceable();
        }

        /**
         * 判定本注册项是否应在指定群生效——必须结合运行时的当前作用域。
         *
         * @param groupId       事件群号；{@code null} 表示非群事件
         * @param runtimeScopes 由插件标识取当前生效的过滤器
         * @return 两道判定都通过时返回 {@code true}
         */
        default boolean scopeMatches(Long groupId,
                                     Function<String, Optional<ScopeFilter>> runtimeScopes) {
            if (!filter().matches(groupId)) {
                return false;
            }
            PluginFiber owner = owner();
            if (runtimeScopes == null || owner == null) {
                return true;
            }
            return runtimeScopes.apply(owner.getId())
                    .map(scope -> scope.matches(groupId))
                    .orElse(true);
        }

        /**
         * 子类型匹配；只有通知/请求/元事件覆写。
         *
         * @param actualSubType 事件子类型
         * @return 匹配时返回 {@code true}
         */
        default boolean subTypeMatches(String actualSubType) {
            return true;
        }

        /**
         * 命令树注册项。
         *
         * @param owner  注册者
         * @param filter 作用域过滤器
         * @param tree   命令树根节点
         */
        record Command(PluginFiber owner, ScopeFilter filter, CommandNode tree)
                implements Registration {

            /** 命令树不能为空。 */
            public Command {
                filter = filter == null ? ScopeFilter.ALL : filter;
                Objects.requireNonNull(tree, "tree");
            }

            @Override
            public RegistrationKind kind() {
                return RegistrationKind.COMMAND;
            }
        }

        /**
         * 消息事件注册项。
         *
         * @param owner   注册者
         * @param filter  作用域过滤器
         * @param when    探测条件
         * @param handler 处理器
         */
        record Message(PluginFiber owner,
                       ScopeFilter filter,
                       Predicate<MessageEvent> when,
                       MessageHandler handler) implements Registration {

            /** 无条件谓词。 */
            public static final Predicate<MessageEvent> ALWAYS = event -> true;

            /** 归一化。 */
            public Message {
                filter = filter == null ? ScopeFilter.ALL : filter;
                Objects.requireNonNull(when, "when");
                Objects.requireNonNull(handler, "handler");
            }

            @Override
            public RegistrationKind kind() {
                return RegistrationKind.MESSAGE;
            }
        }

        /**
         * 消息发出事件注册项。
         *
         * @param owner   注册者
         * @param filter  作用域过滤器
         * @param when    探测条件
         * @param handler 处理器
         */
        record MessageSent(PluginFiber owner,
                           ScopeFilter filter,
                           Predicate<MessageSentEvent> when,
                           MessageSentHandler handler) implements Registration {

            /** 无条件谓词。 */
            public static final Predicate<MessageSentEvent> ALWAYS = event -> true;

            /** 归一化。 */
            public MessageSent {
                filter = filter == null ? ScopeFilter.ALL : filter;
                Objects.requireNonNull(when, "when");
                Objects.requireNonNull(handler, "handler");
            }

            @Override
            public RegistrationKind kind() {
                return RegistrationKind.MESSAGE_SENT;
            }
        }

        /**
         * 通知事件注册项。
         *
         * @param owner   注册者
         * @param filter  作用域过滤器
         * @param when    探测条件
         * @param subType 子类型过滤；{@code null}/空表示不过滤
         * @param handler 处理器
         */
        record Notice(PluginFiber owner,
                      ScopeFilter filter,
                      Predicate<NoticeEvent> when,
                      String subType,
                      NoticeHandler handler) implements Registration {

            /** 无条件谓词。 */
            public static final Predicate<NoticeEvent> ALWAYS = event -> true;

            /** 归一化。 */
            public Notice {
                filter = filter == null ? ScopeFilter.ALL : filter;
                Objects.requireNonNull(when, "when");
                Objects.requireNonNull(handler, "handler");
            }

            @Override
            public RegistrationKind kind() {
                return RegistrationKind.NOTICE;
            }

            @Override
            public boolean subTypeMatches(String actualSubType) {
                return subType == null || subType.isBlank() || subType.equals(actualSubType);
            }
        }

        /**
         * 请求事件注册项。
         *
         * @param owner   注册者
         * @param filter  作用域过滤器
         * @param when    探测条件
         * @param subType 子类型过滤；{@code null}/空表示不过滤
         * @param handler 处理器
         */
        record Request(PluginFiber owner,
                       ScopeFilter filter,
                       Predicate<RequestEvent> when,
                       String subType,
                       RequestHandler handler) implements Registration {

            /** 无条件谓词。 */
            public static final Predicate<RequestEvent> ALWAYS = event -> true;

            /** 归一化。 */
            public Request {
                filter = filter == null ? ScopeFilter.ALL : filter;
                Objects.requireNonNull(when, "when");
                Objects.requireNonNull(handler, "handler");
            }

            @Override
            public RegistrationKind kind() {
                return RegistrationKind.REQUEST;
            }

            @Override
            public boolean subTypeMatches(String actualSubType) {
                return subType == null || subType.isBlank() || subType.equals(actualSubType);
            }
        }

        /**
         * 元事件注册项。
         *
         * @param owner   注册者
         * @param filter  作用域过滤器
         * @param when    探测条件
         * @param subType 子类型过滤；{@code null}/空表示不过滤
         * @param handler 处理器
         */
        record Meta(PluginFiber owner,
                    ScopeFilter filter,
                    Predicate<MetaEvent> when,
                    String subType,
                    MetaHandler handler) implements Registration {

            /** 无条件谓词。 */
            public static final Predicate<MetaEvent> ALWAYS = event -> true;

            /** 归一化。 */
            public Meta {
                filter = filter == null ? ScopeFilter.ALL : filter;
                Objects.requireNonNull(when, "when");
                Objects.requireNonNull(handler, "handler");
            }

            @Override
            public RegistrationKind kind() {
                return RegistrationKind.META;
            }

            @Override
            public boolean subTypeMatches(String actualSubType) {
                return subType == null || subType.isBlank() || subType.equals(actualSubType);
            }
        }
    }

    // ==================== 注册 ====================

    /**
     * 登记一条注册项，返回撤销句柄。
     *
     * @param registration 注册项
     * @return 撤销句柄
     */
    public Disposable add(Registration registration) {
        Objects.requireNonNull(registration, "registration");
        return switch (registration) {
            case Registration.Command command -> addTo(commandEntries, command);
            case Registration.Message message -> addTo(messageEntries, message);
            case Registration.MessageSent messageSent -> addTo(messageSentEntries, messageSent);
            case Registration.Notice notice -> addTo(noticeEntries, notice);
            case Registration.Request request -> addTo(requestEntries, request);
            case Registration.Meta meta -> addTo(metaEntries, meta);
        };
    }

    /**
     * 把注册项放入对应桶。
     *
     * @param bucket       桶
     * @param registration 注册项
     * @param <R>          注册项类型
     * @return 撤销句柄
     */
    private <R extends Registration> Disposable addTo(List<R> bucket, R registration) {
        bucket.add(registration);
        return () -> bucket.remove(registration);
    }

    // ==================== 查询 ====================

    /**
     * 取某个能力种类的桶。
     *
     * @param kind 能力种类
     * @return 桶（只读快照）
     */
    private List<? extends Registration> bucketOf(RegistrationKind kind) {
        return switch (kind) {
            case COMMAND -> commandEntries;
            case MESSAGE -> messageEntries;
            case MESSAGE_SENT -> messageSentEntries;
            case NOTICE -> noticeEntries;
            case REQUEST -> requestEntries;
            case META -> metaEntries;
        };
    }

    /**
     * 命令注册项。
     *
     * @return 只读快照
     */
    public List<Registration.Command> getCommandEntries() {
        return List.copyOf(commandEntries);
    }

    /**
     * 消息注册项。
     *
     * @return 只读快照
     */
    public List<Registration.Message> getMessageEntries() {
        return List.copyOf(messageEntries);
    }

    /**
     * 消息发出注册项。
     *
     * @return 只读快照
     */
    public List<Registration.MessageSent> getMessageSentEntries() {
        return List.copyOf(messageSentEntries);
    }

    /**
     * 通知注册项。
     *
     * @return 只读快照
     */
    public List<Registration.Notice> getNoticeEntries() {
        return List.copyOf(noticeEntries);
    }

    /**
     * 请求注册项。
     *
     * @return 只读快照
     */
    public List<Registration.Request> getRequestEntries() {
        return List.copyOf(requestEntries);
    }

    /**
     * 元事件注册项。
     *
     * @return 只读快照
     */
    public List<Registration.Meta> getMetaEntries() {
        return List.copyOf(metaEntries);
    }

    /**
     * 注册项总数。
     *
     * @return 总数
     */
    public int getTotalSize() {
        return commandEntries.size() + messageEntries.size()
                + messageSentEntries.size() + noticeEntries.size()
                + requestEntries.size() + metaEntries.size();
    }

    /**
     * 各能力种类的注册项数量。
     *
     * @return 种类 → 数量；所有种类都有键
     */
    public Map<RegistrationKind, Integer> countByKind() {
        Map<RegistrationKind, Integer> counts = new EnumMap<>(RegistrationKind.class);
        for (RegistrationKind kind : RegistrationKind.values()) {
            counts.put(kind, bucketOf(kind).size());
        }
        return Collections.unmodifiableMap(counts);
    }

    /**
     * 某个插件贡献了哪些能力种类。
     *
     * @param owner 插件 Fiber
     * @return 种类集合
     */
    public Set<RegistrationKind> kindsOf(PluginFiber owner) {
        Set<RegistrationKind> kinds = EnumSet.noneOf(RegistrationKind.class);
        for (RegistrationKind kind : RegistrationKind.values()) {
            for (Registration registration : bucketOf(kind)) {
                if (registration.owner() == owner) {
                    kinds.add(kind);
                    break;
                }
            }
        }
        return Collections.unmodifiableSet(kinds);
    }

    /**
     * 清空全部桶。
     *
     * <p>仅用于测试与全量重载前的兜底清理；正常卸载由各注册项的撤销句柄完成。</p>
     */
    public void clear() {
        commandEntries.clear();
        messageEntries.clear();
        messageSentEntries.clear();
        noticeEntries.clear();
        requestEntries.clear();
        metaEntries.clear();
    }
}
