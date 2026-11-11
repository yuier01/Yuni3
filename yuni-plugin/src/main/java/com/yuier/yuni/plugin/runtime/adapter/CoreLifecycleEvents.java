package com.yuier.yuni.plugin.runtime.adapter;

import com.yuier.yuni.core.event.meta.YuniHeartbeatEvent;
import com.yuier.yuni.core.event.meta.YuniMetaEvent;
import com.yuier.yuni.core.event.notice.GroupAdminEvent;
import com.yuier.yuni.core.event.notice.GroupBanEvent;
import com.yuier.yuni.core.event.notice.GroupDecreaseEvent;
import com.yuier.yuni.core.event.notice.GroupIncreaseEvent;
import com.yuier.yuni.core.event.notice.GroupRecallEvent;
import com.yuier.yuni.core.event.notice.GroupUploadEvent;
import com.yuier.yuni.core.event.notice.HonorEvent;
import com.yuier.yuni.core.event.notice.LuckyKingEvent;
import com.yuier.yuni.core.event.notice.PokeEvent;
import com.yuier.yuni.core.event.notice.YuniNoticeEvent;
import com.yuier.yuni.core.event.request.GroupRequest;
import com.yuier.yuni.core.event.request.YuniRequestEvent;
import com.yuier.yuni.plugin.api.Reply;
import com.yuier.yuni.plugin.api.event.MetaEvent;
import com.yuier.yuni.plugin.api.event.NoticeEvent;
import com.yuier.yuni.plugin.api.event.RequestEvent;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.Map;
import java.util.Optional;

/**
 * 通知 / 请求 / 元事件的适配实现集合。
 *
 * <h2>为什么这三个放在一个文件里</h2>
 * <p>它们的结构高度同构：都是一组固定的公共字段（各自的事件类型标识 + userId /
 * groupId / operatorId 等）+ 若干差异字段。相比之下消息事件多出一个完整的
 * 发送者视图与消息链，因此单独成文件。把它们放在一起可以让「字段映射」这件事
 * 横向可比较。</p>
 *
 * <h2>关键修正：子类型不再丢失</h2>
 * <p>现行 {@code EventTranslator} 的问题（审计报告已记录）：</p>
 * <ul>
 *   <li>notice 只搬运 {@code noticeType/userId/groupId/operatorId}，
 *       <b>丢弃 {@code subType}</b>；于是 {@code PokeNotice} 必须自己
 *       反序列化 {@code rawJson} 才能判断是不是戳一戳——而且同一个 JSON 解了两次；</li>
 *   <li>request 丢弃 {@code subType}，导致插件无法区分「主动加群」与「被邀请入群」；</li>
 *   <li>meta 丢弃 {@code status} 与 {@code interval}，导致 {@code HeartBeatEvent}
 *       实际拿不到心跳间隔。</li>
 * </ul>
 * <p>本实现把上述字段<b>全部</b>保留，并统一放进
 * {@link BaseCoreEvent#fieldsBuilder()} 构造的字段表。</p>
 *
 * <h2>子类型的取值规则</h2>
 * <p>notice / request 事件的子类型来自 detector 匹配到的具体子类型事件
 * （{@code matchedEvent.subType}），回退到基础事件自身的 {@code subType}。
 * 之所以需要这个回退：{@code matchedEvent} 只在 detector 成功匹配后才有值，
 * 而事件的公共字段（含 {@code subType}）在翻译阶段就已写入基础事件。</p>
 *
 * <h2>关于 matchedEvent 的编译期可见性</h2>
 * <p>{@code YuniNoticeEvent.getMatchedEvent()} 的返回类型是<b>基类</b>
 * {@code YuniNoticeEvent}，因此拿不到子类特有字段（{@code PokeEvent.targetId} 等）。
 * 实现上必须先把返回的基类引用与各具体子类型做 {@code instanceof} 判定，
 * 再访问其特有 getter——这也是为什么字段补齐逻辑写成独立的静态方法：
 * 它接收「匹配到的具体事件」，与「基础事件」的字段收集分开，避免把
 * 类型判定混进主构造路径。</p>
 *
 * @see NoticeEvent
 * @see RequestEvent
 * @see MetaEvent
 */
public final class CoreLifecycleEvents {

    /** 工具类不可实例化。 */
    private CoreLifecycleEvents() {
    }

    /**
     * 取通知事件的子类型。
     *
     * <p>{@code YuniNoticeEvent} <b>基类没有 {@code subType} 字段</b>——
     * 该字段只定义在各具体子类型上（{@code PokeEvent.subType}、
     * {@code GroupAdminEvent.subType} …）。而 {@code getMatchedEvent()} 的
     * 静态返回类型是基类，因此无法直接调用。这里按具体类型分支取值。</p>
     *
     * @param matched 匹配到的具体事件；可为 {@code null}
     * @return 子类型；无法确定时返回空串
     */
    private static String subTypeOf(YuniNoticeEvent matched) {
        if (matched == null) {
            return "";
        }
        String subType = switch (matched) {
            case PokeEvent e -> e.getSubType();
            case GroupBanEvent e -> e.getSubType();
            case GroupUploadEvent e -> e.getSubType();
            case GroupDecreaseEvent e -> e.getSubType();
            case GroupIncreaseEvent e -> e.getSubType();
            case HonorEvent e -> e.getSubType();
            case LuckyKingEvent e -> e.getSubType();
            case GroupAdminEvent e -> e.getSubType();
            // 未覆盖的子类型：无法从静态类型上取到 subType
            default -> null;
        };
        return subType == null ? "" : subType;
    }

    /**
     * 取请求事件的子类型。
     *
     * <p>{@code YuniRequestEvent} 基类没有 {@code subType} 字段，该字段定义在
     * {@code GroupRequest}（{@code AddGroupRequest} 与 {@code InviteToGroupRequest}
     * 又各自重复声明了一次——审计报告记录的字段遮蔽缺陷）。</p>
     *
     * <p>取值顺序：matchedEvent 的子类型 → 请求事件自身的具体类型判定。
     * 后者是必需的：detector 未参与匹配时（例如直接构造事件、或该事件被
     * 以非匹配路径消费）{@code matchedEvent} 为 null，此时仍应能从具体类型
     * 推出子类型。</p>
     *
     * @param source   核心请求事件
     * @param matched  匹配到的具体事件；可为 {@code null}
     * @return 子类型；无法确定时返回空串
     */
    private static String subTypeOf(YuniRequestEvent source, YuniRequestEvent matched) {
        if (matched instanceof GroupRequest groupRequest && groupRequest.getSubType() != null) {
            return groupRequest.getSubType();
        }
        if (source instanceof GroupRequest groupRequest && groupRequest.getSubType() != null) {
            return groupRequest.getSubType();
        }
        return "";
    }

    /**
     * 取通知事件的基础子类型（不含 matchedEvent）。
     *
     * <p>与 {@link #subTypeOf(YuniNoticeEvent)} 的区别：那个方法只认
     * detector 匹配结果，用于「匹配路径」；本方法在匹配结果缺失时，
     * 回退到 {@code notice_type}——因为对 {@code group_ban} /
     * {@code group_recall} / {@code friend_add} 这类事件来说，
     * {@code notice_type} 本身就是有效的子类型标识（它们没有独立的
     * {@code sub_type}）。</p>
     *
     * @param source 核心通知事件
     * @return 子类型；无法确定时返回空串
     */
    private static String baseSubTypeOf(YuniNoticeEvent source) {
        String fromMatched = subTypeOf(source.getMatchedEvent());
        if (!fromMatched.isEmpty()) {
            return fromMatched;
        }
        String noticeType = source.getNoticeType();
        return noticeType == null ? "" : noticeType;
    }

    /**
     * {@link NoticeEvent} 的适配实现。
     *
     * <h3>子类型解析</h3>
     * <p>OneBot 的 notice 事件有两层类型：{@code notice_type}（如 {@code notify}）
     * 与 {@code sub_type}（如 {@code poke}）。实际业务关注的是<b>后者</b>
     * ——戳一戳、群荣誉、幸运王都属于 {@code notify}。因此本实现把
     * {@code subType()} 暴露为子类型，把 {@code notice_type} 保留在
     * {@link NoticeEvent#noticeType()} 与字段表里。</p>
     */
    @Getter
    @Accessors(fluent = true)
    public static final class CoreNoticeEvent extends BaseCoreEvent implements NoticeEvent {

        /** 通知类型（notice_type）。 */
        private final String noticeType;

        /** 主体用户账号。 */
        private final long userId;

        /** 操作者账号；缺失为 {@code null}。 */
        private final Long operatorId;

        /**
         * 从核心通知事件构造。
         *
         * @param source 核心事件
         * @param reply  回复入口；可为 {@code null}
         */
        public CoreNoticeEvent(YuniNoticeEvent source, Reply reply) {
            super(source.getTime() == null ? 0L : source.getTime(),
                    source.getSelfId() == null ? 0L : source.getSelfId(),
                    source.getGroupId(),
                    // 子类型：matchedEvent 优先，回退到 notice_type。
                    // 回退很关键——group_ban / group_recall 这类事件没有独立的
                    // sub_type，notice_type 本身就是有效的子类型标识，
                    // 因此插件的 ev.is("group_ban") 才能成立。
                    baseSubTypeOf(source),
                    buildFields(source),
                    source.getRawJson());
            this.noticeType = source.getNoticeType();
            this.userId = source.getUserId() == null ? 0L : source.getUserId();
            this.operatorId = source.getOperatorId();
        }

        /**
         * 组装差异字段表：基础字段 + 匹配到的具体子类型的特有字段。
         *
         * @param source 核心事件
         * @return 字段表
         */
        private static Map<String, Object> buildFields(YuniNoticeEvent source) {
            Map<String, Object> fields = fieldsBuilder();
            putIfPresent(fields, "notice_type", source.getNoticeType());
            putIfPresent(fields, "user_id", source.getUserId());
            putIfPresent(fields, "group_id", source.getGroupId());
            putIfPresent(fields, "operator_id", source.getOperatorId());
            // 基类没有 subType，只能从匹配到的具体子类型取
            putIfPresent(fields, "sub_type", subTypeOf(source.getMatchedEvent()));
            collectMatchedFields(fields, source.getMatchedEvent());
            return fields;
        }

        /**
         * 从 detector 匹配到的具体子类型事件里补齐特有字段。
         *
         * <p>现行系统丢弃了这些字段，插件因此必须回头自己解 {@code rawJson}。
         * 这里按「凡是有 getter 的都搬运」的原则补齐。未覆盖的字段仍可通过
         * {@link BaseCoreEvent#rawJson()} 访问。</p>
         *
         * @param fields  字段表
         * @param matched 匹配到的具体事件；可为 {@code null}
         */
        private static void collectMatchedFields(Map<String, Object> fields, YuniNoticeEvent matched) {
            if (matched == null) {
                return;
            }
            // 具体子类型的 sub_type 覆盖基础事件的空值
            // （基类没有子类型字段，必须经 subTypeOf 按具体类型取值）
            putIfPresent(fields, "sub_type", subTypeOf(matched));

            if (matched instanceof PokeEvent poke) {
                putIfPresent(fields, "target_id", poke.getTargetId());
            } else if (matched instanceof GroupBanEvent ban) {
                putIfPresent(fields, "duration", ban.getDuration());
                putIfPresent(fields, "operator_id", ban.getOperatorId());
            } else if (matched instanceof GroupUploadEvent upload) {
                putIfPresent(fields, "file_id", upload.getFileId());
                putIfPresent(fields, "file_name", upload.getFileName());
                putIfPresent(fields, "file_size", upload.getFileSize());
                putIfPresent(fields, "busid", upload.getBusid());
            } else if (matched instanceof GroupRecallEvent recall) {
                putIfPresent(fields, "message_id", recall.getMessageId());
                putIfPresent(fields, "operator_id", recall.getOperatorId());
            } else if (matched instanceof GroupDecreaseEvent decrease) {
                putIfPresent(fields, "operator_id", decrease.getOperatorId());
            } else if (matched instanceof GroupIncreaseEvent increase) {
                putIfPresent(fields, "operator_id", increase.getOperatorId());
            } else if (matched instanceof HonorEvent honor) {
                putIfPresent(fields, "honor_type", honor.getHonorType());
            } else if (matched instanceof LuckyKingEvent luckyKing) {
                putIfPresent(fields, "target_id", luckyKing.getTargetId());
            }
        }

        @Override
        public Optional<Long> operatorId() {
            return Optional.ofNullable(operatorId);
        }

        @Override
        public String toLogString() {
            return "NoticeEvent" + describeCommon();
        }
    }

    /**
     * {@link RequestEvent} 的适配实现。
     */
    @Getter
    @Accessors(fluent = true)
    public static final class CoreRequestEvent extends BaseCoreEvent implements RequestEvent {

        /** 请求类型（friend / group）。 */
        private final String requestType;

        /** 发起请求的用户账号。 */
        private final long userId;

        /** 验证信息。 */
        private final String comment;

        /** 请求标识。 */
        private final String flag;

        /**
         * 从核心请求事件构造。
         *
         * @param source 核心事件
         * @param reply  回复入口；可为 {@code null}
         */
        public CoreRequestEvent(YuniRequestEvent source, Reply reply) {
            super(source.getTime() == null ? 0L : source.getTime(),
                    source.getSelfId() == null ? 0L : source.getSelfId(),
                    source.getGroupId(),
                    // 请求事件的子类型是 add（申请入群）/ invite（被邀请），
                    // 现行系统丢弃了它，导致插件无法区分两者
                    subTypeOf(source, source.getMatchedEvent()),
                    buildFields(source),
                    source.getRawJson());
            this.requestType = source.getRequestType();
            this.userId = source.getUserId() == null ? 0L : source.getUserId();
            this.comment = source.getComment();
            this.flag = source.getFlag();
        }

        /**
         * 组装差异字段表。
         *
         * @param source 核心事件
         * @return 字段表
         */
        private static Map<String, Object> buildFields(YuniRequestEvent source) {
            Map<String, Object> fields = fieldsBuilder();
            putIfPresent(fields, "request_type", source.getRequestType());
            putIfPresent(fields, "user_id", source.getUserId());
            putIfPresent(fields, "group_id", source.getGroupId());
            putIfPresent(fields, "comment", source.getComment());
            putIfPresent(fields, "flag", source.getFlag());
            // 基类没有 subType，只能从具体类型取
            putIfPresent(fields, "sub_type", subTypeOf(source, source.getMatchedEvent()));
            return fields;
        }

        @Override
        public Optional<String> comment() {
            return Optional.ofNullable(comment);
        }

        @Override
        public String toLogString() {
            return "RequestEvent" + describeCommon();
        }
    }

    /**
     * {@link MetaEvent} 的适配实现。
     *
     * <p>心跳事件的 {@code status} 与 {@code interval} 被现行翻译器丢弃，
     * 本实现保留：{@code interval} 存入字段表（键 {@code interval}），
     * {@code status} 的两个常用子项平铺为 {@code status.online} 与
     * {@code status.good} 两个键，便于 {@link MetaEvent#statusOnline()} 之类的
     * 默认方法直接读取（{@link com.yuier.yuni.plugin.api.event.Event#booleanValueOr}
     * 支持含点号的键名，因为字段表就是普通 Map）。</p>
     */
    @Getter
    @Accessors(fluent = true)
    public static final class CoreMetaEvent extends BaseCoreEvent implements MetaEvent {

        /** 元事件类型（heartbeat / lifecycle）。 */
        private final String metaEventType;

        /**
         * 从核心元事件构造。
         *
         * @param source 核心事件
         * @param reply  回复入口；可为 {@code null}
         */
        public CoreMetaEvent(YuniMetaEvent source, Reply reply) {
            super(source.getTime() == null ? 0L : source.getTime(),
                    source.getSelfId() == null ? 0L : source.getSelfId(),
                    // 元事件是连接层事件，不属于任何群；
                    // YuniMetaEvent 本身也没有 groupId 字段
                    null,
                    source.getSubType(),
                    buildFields(source),
                    source.getRawJson());
            this.metaEventType = source.getMetaEventType();
        }

        /**
         * 组装差异字段表。
         *
         * @param source 核心事件
         * @return 字段表
         */
        private static Map<String, Object> buildFields(YuniMetaEvent source) {
            Map<String, Object> fields = fieldsBuilder();
            putIfPresent(fields, "meta_event_type", source.getMetaEventType());
            putIfPresent(fields, "sub_type", source.getSubType());

            // 心跳事件携带连接状态与间隔：现行系统丢弃了这两项，
            // 导致 HeartBeatEvent 插件拿不到间隔
            if (source instanceof YuniHeartbeatEvent heartbeat) {
                putIfPresent(fields, "interval", heartbeat.getInterval());
                Object status = heartbeat.getStatus();
                if (status instanceof Map<?, ?> statusMap) {
                    Object online = statusMap.get("online");
                    Object good = statusMap.get("good");
                    if (online instanceof Boolean onlineFlag) {
                        fields.put("status.online", onlineFlag);
                    }
                    if (good instanceof Boolean goodFlag) {
                        fields.put("status.good", goodFlag);
                    }
                }
            }
            return fields;
        }

        @Override
        public String toLogString() {
            return "MetaEvent" + describeCommon();
        }
    }
}
