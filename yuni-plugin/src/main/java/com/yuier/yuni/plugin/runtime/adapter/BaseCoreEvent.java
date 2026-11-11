package com.yuier.yuni.plugin.runtime.adapter;

import com.yuier.yuni.plugin.api.MatchToken;
import com.yuier.yuni.plugin.api.event.Event;
import com.yuier.yuni.plugin.api.event.MessageChainView;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.Getter;

/**
 * 插件侧事件的<b>公共基类</b>——承载公共字段与差异字段的通用访问。
 *
 * <h2>为什么用「公共字段 + 字段表」而不是每个子类型一个类</h2>
 * <p>现行系统为每个事件子类型定义了独立类（{@code PokeEvent}、{@code GroupAdminEvent}、
 * {@code YuniHeartbeatEvent} …），并因此在两处出了问题：</p>
 * <ol>
 *   <li>字段在子类间互相遮蔽——{@code AddGroupRequest} 与 {@code InviteToGroupRequest}
 *       重复声明了父类 {@code GroupRequest} 已有的 {@code subType}/{@code groupId}
 *       字段（审计报告记录的缺陷）；</li>
 *   <li>适配器翻译时丢字段——{@code EventTranslator} 只为 notice 搬运了 4 个字段，
 *       导致 {@code PokeNotice} 必须回头自己反序列化 {@code rawJson}。</li>
 * </ol>
 * <p>新设计把「公共字段」提升为方法，「差异字段」放进一张 {@code Map}，
 * 由子类在构造时填入。新增子类型只需在翻译器里多填几个键，
 * 不需要新增 Java 类。</p>
 *
 * <h2>字段表的来源</h2>
 * <p>字段表由 {@code EventTranslator} 在协议翻译阶段填入。为兼容「翻译器尚未覆盖
 * 全部字段」的过渡期，子类可以额外提供一个 {@code rawJson} 逃生舱；
 * 但<b>插件业务逻辑不应依赖它</b>。</p>
 *
 * @see Event
 */
@Getter
public abstract class BaseCoreEvent implements Event {

    /** 事件时间戳（秒）。 */
    private final long time;

    /** 机器人自身账号。 */
    private final long selfId;

    /** 事件所属群号；非群事件为 {@code null}。 */
    private final Long groupId;

    /** 子类型标识。 */
    private final String subType;

    /** 差异字段表（只读）。 */
    private final Map<String, Object> fields;

    /** 原始协议 JSON 逃生舱。 */
    private final String rawJson;

    /** 匹配令牌；由分发器在类型化匹配成功后回填。 */
    private volatile MatchToken matched;

    /**
     * 构造事件。
     *
     * @param time    事件时间戳（秒）
     * @param selfId  机器人自身账号
     * @param groupId 群号；非群事件传 {@code null}
     * @param subType 子类型标识；可为 {@code null}
     * @param fields  差异字段表；可为 {@code null}
     * @param rawJson 原始协议 JSON；可为 {@code null}
     */
    protected BaseCoreEvent(long time,
                            long selfId,
                            Long groupId,
                            String subType,
                            Map<String, Object> fields,
                            String rawJson) {
        this.time = time;
        this.selfId = selfId;
        this.groupId = groupId;
        this.subType = subType == null ? "" : subType;
        this.fields = fields == null || fields.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        this.rawJson = rawJson;
    }

    @Override
    public Optional<Long> getGroupId() {
        return Optional.ofNullable(groupId);
    }

    /*
     * getTime() / getSelfId() / getSubType() / getRawJson() 由类上的
     * @Getter + @Accessors(fluent) 生成。
     *
     * 其余成员保持手写，因为它们并非简单转发：
     * - getGroupId() 包装成 Optional；
     * - getRawFields() 与 value() 读取字段表；
     * - getMatched() / setMatched() 是可变匹配令牌；
     * - fields() 供子类访问（Lombok 发现同名方法会跳过生成）。
     */

    @Override
    public Optional<Object> value(String key) {
        return key == null ? Optional.empty() : Optional.ofNullable(fields.get(key));
    }

    @Override
    public Map<String, Object> getRawFields() {
        return fields;
    }

    @Override
    public MatchToken getMatched() {
        return matched;
    }

    @Override
    public void setMatched(MatchToken token) {
        this.matched = token;
    }

    /**
     * 字段表（供子类与适配器使用）。
     *
     * @return 不可变字段表
     */
    protected Map<String, Object> getFields() {
        return fields;
    }

    /**
     * 为子类的 {@code toLogString} 提供统一的字段摘要。
     *
     * @return 形如 {@code "{time=..., selfId=..., groupId=..., subType='poke'}"}
     */
    protected String describeCommon() {
        return "{time=" + time
                + ", selfId=" + selfId
                + ", groupId=" + groupId
                + ", subType='" + subType + "'"
                + (fields.isEmpty() ? "" : ", fields=" + fields.keySet()) + "}";
    }

    /**
     * 构造字段表的可变构建器。
     *
     * <p>供 {@code EventTranslator} 使用：先把协议模型里的字段全部放进来，
     * 再交给事件构造器冻结。相比「为每个子类型写一个 setter 集合」，
     * 这样新增字段只需加一行 put。</p>
     *
     * @return 可变的字段表
     */
    public static Map<String, Object> fieldsBuilder() {
        return new LinkedHashMap<>();
    }

    /**
     * 把可选字符串放入字段表（{@code null} 与空串都跳过）。
     *
     * @param fields 字段表
     * @param key    键
     * @param value  值
     */
    public static void putIfPresent(Map<String, Object> fields, String key, String value) {
        if (value != null && !value.isEmpty()) {
            fields.put(key, value);
        }
    }

    /**
     * 把可选数值放入字段表（{@code null} 跳过）。
     *
     * @param fields 字段表
     * @param key    键
     * @param value  值
     */
    public static void putIfPresent(Map<String, Object> fields, String key, Number value) {
        if (value != null) {
            fields.put(key, value);
        }
    }

    /**
     * 把可选布尔放入字段表（{@code null} 跳过）。
     *
     * @param fields 字段表
     * @param key    键
     * @param value  值
     */
    public static void putIfPresent(Map<String, Object> fields, String key, Boolean value) {
        if (value != null) {
            fields.put(key, value);
        }
    }

    /**
     * 取消息链视图的类型（供子类做联合类型判定）。
     *
     * @param view 消息链视图；可为 {@code null}
     * @return 视图；{@code null} 输入返回空视图
     */
    protected static MessageChainView nullSafe(MessageChainView view) {
        return view == null ? EmptyChainView.INSTANCE : view;
    }

    /**
     * 空消息链视图的单例——避免反复构造，也让 {@code getChain()} 永不返回 null。
     */
    private enum EmptyChainView implements MessageChainView {
        /** 唯一实例。 */
        INSTANCE;

        @Override
        public String getPlainText() {
            return "";
        }

        @Override
        public boolean isPlainText() {
            return true;
        }

        @Override
        public boolean contains(String segmentType) {
            return false;
        }

        @Override
        public boolean containsText(String text) {
            return false;
        }

        @Override
        public boolean startsWithText() {
            return false;
        }

        @Override
        public boolean startsWithReply() {
            return false;
        }

        @Override
        public boolean containsForward() {
            return false;
        }

        @Override
        public int size() {
            return 0;
        }

        @Override
        public List<String> getSegmentTypes() {
            return java.util.List.of();
        }

        @Override
        public String segmentJson(int index) {
            throw new IndexOutOfBoundsException("空消息链没有消息段");
        }

        @Override
        public List<String> getSegmentJsonList() {
            return java.util.List.of();
        }
    }
}
