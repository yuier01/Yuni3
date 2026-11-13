package com.yuier.yuni.plugin.runtime;

import com.yuier.yuni.plugin.api.Disposable;
import com.yuier.yuni.plugin.api.ScopeFilter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Predicate;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/**
 * 能力注册总表——框架侧持有「所有插件注册了什么能力」的唯一索引。
 *
 * <h2>取代了什么</h2>
 * <p>现行 {@code PluginContainer} 按插件<b>类型</b>维护了八条独立的 ID 列表：</p>
 * <pre>
 * private final List&lt;String&gt; scheduledPluginFullIds = new ArrayList&lt;&gt;();
 * private final List&lt;String&gt; immediatePluginFullIds = new ArrayList&lt;&gt;();
 * private final List&lt;String&gt; commandPluginFullIds = new ArrayList&lt;&gt;();
 * private final List&lt;String&gt; patternPluginFullIds = new ArrayList&lt;&gt;();
 * private final List&lt;String&gt; noticePluginFullIds = new ArrayList&lt;&gt;();
 * private final List&lt;String&gt; requestPluginFullIds = new ArrayList&lt;&gt;();
 * private final List&lt;String&gt; metaPluginFullIds = new ArrayList&lt;&gt;();
 * private final List&lt;String&gt; messageSentPluginFullIds = new ArrayList&lt;&gt;();
 * private final Map&lt;YuniPluginType, List&lt;String&gt;&gt; pluginTypeToFullIdListMap = ...;
 * </pre>
 * <p>这八条列表的存在理由只有一个：让 {@code PassivePluginMatcher} 能按类型取到
 * 插件列表。但「类型」本身是注册的结果而非原因——这里改为按<b>能力种类</b>
 * 分组，且存的是注册项本身而非插件 ID，分发表因此不需要二次查表。
 * 热重载时也不再需要手工维护八条列表 + 修正 index 偏移。</p>
 *
 * <h2>分组维度</h2>
 * <p>{@link Registration} 携带 {@link RegistrationKind}，表按 RegistrationKind 分桶。这与「8 种插件类型」的区别在于：
 * RegistrationKind 是<b>注册动作的种类</b>（注册了一条消息处理器 / 一个定时任务），
 * 一个插件可以同时出现在多个桶里，不需要插件类型来做互斥分类。</p>
 *
 * <h2>顺序语义</h2>
 * <p>同桶内的条目按注册顺序保存。分发时框架要求「命令优先于模式」，这是
 * {@code EventPipeline} 的<b>阶段</b>语义（见设计文档 §10.2），不依赖桶内顺序；
 * 桶内顺序仅影响同类处理器之间的调用先后。</p>
 *
 * <h2>线程安全</h2>
 * <p>注册发生在插件加载期，分发发生在运行期，且热重载会并发增删。使用
 * {@link CopyOnWriteArrayList} 保证遍历期间不需要加锁——分发是高频路径，
 * 读取零同步开销比写入性能更重要。</p>
 */
public final class PluginAbilityRegistry {

    /** 命令树根节点的注册项。 */
    private final List<Registration> commandEntries = new CopyOnWriteArrayList<>();

    /** 消息事件处理器注册项。 */
    private final List<Registration> messageEntries = new CopyOnWriteArrayList<>();

    /** 消息发出事件处理器注册项。 */
    private final List<Registration> messageSentEntries = new CopyOnWriteArrayList<>();

    /** 通知事件处理器注册项。 */
    private final List<Registration> noticeEntries = new CopyOnWriteArrayList<>();

    /** 请求事件处理器注册项。 */
    private final List<Registration> requestEntries = new CopyOnWriteArrayList<>();

    /** 元事件处理器注册项。 */
    private final List<Registration> metaEntries = new CopyOnWriteArrayList<>();

    /** 定时 / 周期 / 立即执行任务的注册项。 */
    private final List<Registration> scheduleEntries = new CopyOnWriteArrayList<>();

    /** 服务提供者注册项。 */
    private final List<Registration> serviceEntries = new CopyOnWriteArrayList<>();

    /**
     * 注册项的能力种类。
     *
     * <p>与现行 {@code YuniPluginType} 的本质区别：枚举值描述的是
     * <b>注册动作</b>而非插件本身，因此不存在「一个插件只能属于一个类型」的限制。</p>
     */
    @Getter
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
        META("元事件"),
        /** 定时 / 周期 / 立即执行任务。 */
        SCHEDULE("定时"),
        /** 对外提供的服务。 */
        SERVICE("服务");

        /** 面向管理端的中文显示名；访问器由 @Getter + @Accessors(fluent) 生成。 */
        private final String displayName;

        RegistrationKind(String displayName) {
            this.displayName = displayName;
        }
    }

    /**
     * 一条注册项。
     *
     * <p>原本是 record；为统一 JavaBean 访问器改为 {@code @Getter} 类，
     * 构造期空值归一化放在显式构造器里。</p>
     */
    @Getter
    @ToString
    @EqualsAndHashCode
    public static final class Registration {

        /** 无条件谓词——表示「不附加任何条件」，替代 null 哨兵。 */
        public static final Predicate<Object> ALWAYS = event -> true;

        /** 能力种类。 */
        private final RegistrationKind registrationKind;

        /** 注册者（插件 Fiber），用于状态检查、日志与失败归因。 */
        private final PluginFiber owner;

        /**
         * <b>注册项自带</b>的作用域过滤器；构造时 {@code null} 归一化为
         * {@link ScopeFilter#ALL}。注意它不是「该插件在哪些群生效」的
         * 权威来源——那个随管理端配置变化，分发路径必须用
         * {@link #scopeMatches(Long, java.util.function.Function)}。
         */
        private final ScopeFilter filter;

        /** 附加探测条件；无条件时使用 {@link #ALWAYS}，永不为 {@code null}。 */
        private final Predicate<Object> predicate;

        /** 子类型过滤（通知 / 请求 / 元事件使用）；{@code null} 或空表示不过滤。 */
        private final String subType;

        /** 实际载荷：处理器、命令树根节点或 Runnable。 */
        private final Object payload;

        /**
         * 构造注册项。
         *
         * @param registrationKind      能力种类
         * @param owner     注册者
         * @param filter    作用域过滤器；{@code null} 视为 {@link ScopeFilter#ALL}
         * @param predicate 附加条件；可为 {@code null}
         * @param subType   子类型过滤；可为 {@code null}
         * @param payload   载荷
         */
        public Registration(RegistrationKind registrationKind,
                            PluginFiber owner,
                            ScopeFilter filter,
                            Predicate<Object> predicate,
                            String subType,
                            Object payload) {
            this.registrationKind = registrationKind;
            this.owner = owner;
            this.filter = filter == null ? ScopeFilter.ALL : filter;
            this.predicate = java.util.Objects.requireNonNull(predicate, "predicate");
            this.subType = subType;
            this.payload = payload;
        }

        /**
         * 判定本注册项是否应在指定群生效——必须结合运行时的当前作用域。
         *
         * <h2>为什么必须有运行时那一路</h2>
         * <p>插件的群粒度启停由用户随时通过管理端配置，而注册项是插件加载时建立的。
         * 只看注册项上的 {@code filter} 会导致：用户在管理端关掉某个群的插件后，
         * 事件仍被投递过去。</p>
         *
         * <p><b>这不是理论问题。</b>P6 上线前确实如此：
         * {@code PluginContext} 注册时把 filter 写死为 {@link ScopeFilter#ALL}，
         * 而分发只查注册项，于是整套群粒度启停对分发路径完全无效。
         * 该缺陷由 {@code EventPipelineTest#adminScopeChangeTakesEffectOnDispatch}
         * 回归覆盖。</p>
         *
         * <h2>两道判定都要过</h2>
         * <p>保留注册项自带的那道，是为了让测试里显式构造的过滤器仍然生效；
         * 运行时那一路负责用户配置。</p>
         *
         * <h2>为什么没有「只查自己」的单参重载</h2>
         * <p>那个重载曾经存在，而它的存在本身就是缺陷的温床——调用方很容易
         * 「顺手」用它，得到的是<b>看起来正确但忽略用户配置</b>的结果。
         * 删掉之后，编译器会强制每个调用点显式提供运行时解析函数。</p>
         *
         * @param groupId       事件群号；{@code null} 表示非群事件
         * @param runtimeScopes 由插件标识取「该插件当前生效的过滤器」；
         *                      {@code null} 或返回空表示运行时未装配，此时只用注册项过滤器
         * @return 两道判定都通过时返回 {@code true}
         */
        public boolean scopeMatches(Long groupId,
                                    Function<String, Optional<ScopeFilter>> runtimeScopes) {
            if (!filter.matches(groupId)) {
                return false;
            }
            if (runtimeScopes == null || owner == null) {
                return true;
            }
            // 运行时没有该插件的记录（例如测试里直接往注册表塞条目）时放行，
            // 与「无配置即按元数据默认值」的语义一致
            return runtimeScopes.apply(owner.getId())
                    .map(scope -> scope.matches(groupId))
                    .orElse(true);
        }

        /**
         * 判定子类型是否匹配。
         *
         * @param actualSubType 事件的子类型
         * @return 未设置子类型过滤，或与事件子类型相等时返回 {@code true}
         */
        public boolean subTypeMatches(String actualSubType) {
            if (subType == null || subType.isBlank()) {
                return true;
            }
            return subType.equals(actualSubType);
        }

        /**
         * 判定所属插件当前是否可服务。
         *
         * @return 插件处于 ACTIVE 状态时返回 {@code true}
         */
        public boolean ownerServiceable() {
            return owner != null && owner.isServiceable();
        }
    }

    // ==================== 注册 ====================

    /**
     * 登记一条注册项。
     *
     * @param registration 注册项
     * @return 撤销句柄；调用后从对应桶中移除该条目
     */
    public Disposable add(Registration registration) {
        List<Registration> bucket = bucketOf(registration.getRegistrationKind());
        bucket.add(registration);
        // 撤销时只做移除；桶是 CopyOnWriteArrayList，remove 是安全且幂等的
        return () -> bucket.remove(registration);
    }

    /**
     * 取指定种类的桶。
     *
     * @param registrationKind 能力种类
     * @return 对应的桶
     */
    private List<Registration> bucketOf(RegistrationKind registrationKind) {
        return switch (registrationKind) {
            case COMMAND -> commandEntries;
            case MESSAGE -> messageEntries;
            case MESSAGE_SENT -> messageSentEntries;
            case NOTICE -> noticeEntries;
            case REQUEST -> requestEntries;
            case META -> metaEntries;
            case SCHEDULE -> scheduleEntries;
            case SERVICE -> serviceEntries;
        };
    }

    // ==================== 查询 ====================

    /**
     * 取指定种类的全部注册项。
     *
     * @param registrationKind 能力种类
     * @return 不可变快照
     */
    public List<Registration> entriesOf(RegistrationKind registrationKind) {
        return Collections.unmodifiableList(new ArrayList<>(bucketOf(registrationKind)));
    }

    /**
     * 命令树注册项（按注册顺序）。
     *
     * @return 不可变快照
     */
    public List<Registration> getCommandEntries() {
        return entriesOf(RegistrationKind.COMMAND);
    }

    /**
     * 消息事件注册项（按注册顺序）。
     *
     * @return 不可变快照
     */
    public List<Registration> getMessageEntries() {
        return entriesOf(RegistrationKind.MESSAGE);
    }

    /**
     * 消息发出事件注册项（按注册顺序）。
     *
     * @return 不可变快照
     */
    public List<Registration> getMessageSentEntries() {
        return entriesOf(RegistrationKind.MESSAGE_SENT);
    }

    /**
     * 通知事件注册项（按注册顺序）。
     *
     * @return 不可变快照
     */
    public List<Registration> getNoticeEntries() {
        return entriesOf(RegistrationKind.NOTICE);
    }

    /**
     * 请求事件注册项（按注册顺序）。
     *
     * @return 不可变快照
     */
    public List<Registration> getRequestEntries() {
        return entriesOf(RegistrationKind.REQUEST);
    }

    /**
     * 元事件注册项（按注册顺序）。
     *
     * @return 不可变快照
     */
    public List<Registration> getMetaEntries() {
        return entriesOf(RegistrationKind.META);
    }

    /**
     * 调度类注册项（按注册顺序）。
     *
     * @return 不可变快照
     */
    public List<Registration> getScheduleEntries() {
        return entriesOf(RegistrationKind.SCHEDULE);
    }

    /**
     * 服务注册项（按注册顺序）。
     *
     * @return 不可变快照
     */
    public List<Registration> getServiceEntries() {
        return entriesOf(RegistrationKind.SERVICE);
    }

    /**
     * 全部注册项总数（跨所有桶）。
     *
     * <p>用于泄漏检测与诊断。</p>
     *
     * @return 总数
     */
    public int getTotalSize() {
        int total = 0;
        for (RegistrationKind registrationKind : RegistrationKind.values()) {
            total += bucketOf(registrationKind).size();
        }
        return total;
    }

    /**
     * 各桶的条目数统计。
     *
     * <p>用于管理端展示「这个插件贡献了哪些能力」——即设计文档 §15.1 提到的
     * 「把插件类型从声明改为推导」。比如一个插件若 {@code COMMAND=2, SCHEDULE=1}，
     * 管理端就可以把它标为「命令 + 定时」。</p>
     *
     * @return 种类 → 条目数（全部 RegistrationKind 都有键，无条目时为 0）
     */
    public Map<RegistrationKind, Integer> countByKind() {
        Map<RegistrationKind, Integer> counts = new LinkedHashMap<>();
        for (RegistrationKind registrationKind : RegistrationKind.values()) {
            counts.put(registrationKind, bucketOf(registrationKind).size());
        }
        return Collections.unmodifiableMap(counts);
    }

    /**
     * 某个插件贡献的能力种类。
     *
     * <p>用于管理端推导插件类型标签。</p>
     *
     * @param owner 插件 Fiber
     * @return 该插件贡献的种类集合（保持 RegistrationKind 声明顺序）
     */
    public Set<RegistrationKind> kindsOf(PluginFiber owner) {
        Set<RegistrationKind> registrationKinds = java.util.EnumSet.noneOf(RegistrationKind.class);
        for (RegistrationKind registrationKind : RegistrationKind.values()) {
            for (Registration registration : bucketOf(registrationKind)) {
                if (registration.getOwner() == owner) {
                    registrationKinds.add(registrationKind);
                    break;
                }
            }
        }
        return Collections.unmodifiableSet(registrationKinds);
    }

    /**
     * 清空全部桶。
     *
     * <p>注意：正常卸载流程<b>不应</b>调用本方法——Fiber 的 dispose 会通过
     * 各自注册时返回的 {@link com.yuier.yuni.plugin.api.Disposable} 逐条移除。
     * 本方法仅用于测试与全量重载前的兜底清理。</p>
     */
    public void clear() {
        for (RegistrationKind registrationKind : RegistrationKind.values()) {
            bucketOf(registrationKind).clear();
        }
    }
}
