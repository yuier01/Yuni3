package com.yuier.yuni.plugin.api.event;

import java.util.Optional;

/**
 * 通知事件。
 *
 * <p>对应 OneBot {@code post_type = "notice"}。涵盖戳一戳、群成员增减、禁言、
 * 撤回、文件上传、群荣誉变动等子类型。</p>
 *
 * <h2>子类型判定方式</h2>
 * <p>现行系统要求插件自己把 {@code rawJson} 反序列化成具体子类型再 {@code instanceof}
 * 判定（{@code PokeNotice} 因此解了两次 JSON、又在执行入口强转一次）。
 * 新系统中 {@link Event#subType()} 已由适配器填好，插件用字符串判定：</p>
 * <pre>{@code
 * // 只关心戳一戳
 * ctx.onNotice("poke", (ev, reply) -> {
 *     if (!ev.targetId().map(t -> t == ev.selfId()).orElse(false)) return;
 *     reply.at(ev.userId()).text("戳我干嘛？");
 * });
 * }</pre>
 *
 * <h2>关于通知事件的权限</h2>
 * <p>现行系统对通知事件只做「用户未被封禁」检查。新系统中该检查<b>不</b>自动施加，
 * 插件若需要应按 {@code PermissionService} 显式判断——让「我做了权限检查」
 * 在代码里可见，而不是隐藏的默认行为。</p>
 */
public interface NoticeEvent extends Event {

    /**
     * 通知类型（OneBot {@code notice_type}）。
     *
     * <p>取值：{@code notify}（含 {@code poke} / {@code lucky_king} / {@code honor}
     * 子类型）、{@code group_admin}、{@code group_ban}、{@code group_increase}、
     * {@code group_decrease}、{@code group_recall}、{@code friend_add}、
     * {@code group_upload} 等。</p>
     *
     * @return 通知类型；未提供时为 {@code null}
     */
    String noticeType();

    /**
     * 通知的主体用户账号。
     *
     * <p>语义随子类型而异：戳一戳是「发起戳的人」，禁言是「被禁言的人」，
     * 群成员减少是「离开的人」。</p>
     *
     * @return 用户 QQ 号；该子类型无主体时返回 {@code 0}
     */
    long userId();

    /**
     * 操作者账号。
     *
     * <p>仅部分子类型提供：例如禁言事件里是「执行禁言的管理员」，
     * 群成员减少里是「踢人者」。戳一戳无此字段。</p>
     *
     * @return 操作者 QQ 号；未提供时为空
     */
    Optional<Long> operatorId();

    /**
     * 戳一戳的目标账号。
     *
     * <p>即 OneBot 通知事件里的 {@code target_id}。判定「是否戳的机器人自己」
     * 是戳一戳处理的必要前置。</p>
     *
     * @return 目标 QQ 号；非戳一戳子类型时为空
     */
    default Optional<Long> targetId() {
        Object v = value("target_id").orElse(null);
        return v == null ? Optional.empty() : Optional.of(longValue("target_id"));
    }

    /**
     * 禁言时长（秒）。
     *
     * @return 禁言秒数；{@code 0} 表示解除禁言；非禁言子类型时为空
     */
    default Optional<Long> duration() {
        return value("duration").map(v -> longValue("duration"));
    }

    /**
     * 是否为「全员禁言」。
     *
     * <p>OneBot 协议约定：禁言事件的 {@code user_id} 为 0 表示对该群全员禁言。
     * 现行系统在注释里记了这条约定但没有封装，这里提供。</p>
     *
     * @return 全员禁言时返回 {@code true}
     */
    default boolean isWholeGroupBan() {
        return "group_ban".equals(noticeType()) && userId() == 0L;
    }

    /**
     * 群成员减少的原因。
     *
     * @return {@code leave}（主动退群）/ {@code kick}（被踢）/ {@code kick_me}
     *         （机器人自己被踢）；非成员减少子类型时为空
     */
    default Optional<String> decreaseSubType() {
        return "group_decrease".equals(noticeType())
                ? Optional.ofNullable(subType())
                : Optional.empty();
    }

    /**
     * 上传文件的文件名。
     *
     * @return 文件名；非文件上传子类型时为空
     */
    default Optional<String> fileName() {
        return Optional.ofNullable(stringValue("file_name"));
    }
}
