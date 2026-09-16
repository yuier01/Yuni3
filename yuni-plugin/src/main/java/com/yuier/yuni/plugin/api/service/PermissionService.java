package com.yuier.yuni.plugin.api.service;

import com.yuier.yuni.core.enums.UserPermission;
import com.yuier.yuni.plugin.api.event.MessageEvent;
import com.yuier.yuni.plugin.api.event.NoticeEvent;
import com.yuier.yuni.plugin.api.event.RequestEvent;

import java.util.Optional;

/**
 * 权限判定服务。
 *
 * <h2>取代了什么</h2>
 * <p>现行 {@code UserPermissionManager} 保留，但入口从「插件自己去 Spring 容器取
 * Bean」改为通过 {@code ctx.permission()} 注入。判定链路本身<b>不变</b>：</p>
 * <ol>
 *   <li>内存缓存（键 {@code userId@groupId:pluginId}）；</li>
 *   <li>数据库精确查（{@code group_user_plugin_permission} 的例外记录）；</li>
 *   <li>默认权限回落：私聊按 {@code userId == masterId} 判 MASTER 否则 USER；
 *       群聊按发送者角色映射（owner → MASTER / admin → ADMIN / member → USER）。</li>
 * </ol>
 *
 * <h2>用法</h2>
 * <pre>{@code
 * ctx.onMessage((ev, reply) -> {
 *     // 显式要求 USER 及以上
 *     if (!ctx.permission().atLeast(ev, "myplugin", UserPermission.USER)) {
 *         return;
 *     }
 *     ...
 * });
 * }</pre>
 *
 * <h2>命令的权限不走这里</h2>
 * <p>命令的权限是<b>节点级</b>的：声明在 {@code CommandNode.permission(...)} 上，
 * 由框架在分发时自动递归校验（保持现行 {@code checkCommandNodePermission} 语义），
 * 插件不需要在本服务上再做一次判定。</p>
 *
 * <h2>现行系统的已知问题</h2>
 * <p>本服务在实现时需修掉现行 {@code UserPermissionManager} 的两个缺陷
 * （审计报告已记录）：权限缓存<b>只启动装载、运行时不刷新</b>，且用普通
 * {@code HashMap} 非线程安全。新实现应使用并发容器并提供显式失效接口。</p>
 */
public interface PermissionService {

    /**
     * 查询用户在指定群、针对指定插件的权限等级。
     *
     * @param userId   用户 QQ 号
     * @param groupId  群号；私聊场景传 {@code null}
     * @param pluginId 插件标识；传 {@code null} 表示查询与该插件无关的通用权限
     * @return 权限等级
     */
    UserPermission resolve(long userId, Long groupId, String pluginId);

    /**
     * 查询消息发送者的权限等级。
     *
     * @param event    消息事件
     * @param pluginId 插件标识
     * @return 权限等级
     */
    default UserPermission resolve(MessageEvent event, String pluginId) {
        return resolve(event.userId(), event.groupId().orElse(null), pluginId);
    }

    /**
     * 查询通知事件主体的权限等级。
     *
     * @param event    通知事件
     * @param pluginId 插件标识
     * @return 权限等级
     */
    default UserPermission resolve(NoticeEvent event, String pluginId) {
        return resolve(event.userId(), event.groupId().orElse(null), pluginId);
    }

    /**
     * 查询请求发起者的权限等级。
     *
     * @param event    请求事件
     * @param pluginId 插件标识
     * @return 权限等级
     */
    default UserPermission resolve(RequestEvent event, String pluginId) {
        return resolve(event.userId(), event.groupId().orElse(null), pluginId);
    }

    /**
     * 判定用户权限是否达到要求。
     *
     * @param event    消息事件
     * @param pluginId 插件标识
     * @param required 要求的权限等级
     * @return 达到或超过时返回 {@code true}
     */
    default boolean atLeast(MessageEvent event, String pluginId, UserPermission required) {
        return resolve(event, pluginId).getPriority() >= required.getPriority();
    }

    /**
     * 判定用户是否未被封禁。
     *
     * <p>现行系统对通知事件只做这一项检查，且是隐式的（写死在
     * {@code PassivePluginMatcher.checkPermission} 里）。新系统中它是显式调用。</p>
     *
     * @param event    通知事件
     * @param pluginId 插件标识
     * @return 权限高于 BLOCKED 时返回 {@code true}
     */
    default boolean notBlocked(NoticeEvent event, String pluginId) {
        return resolve(event, pluginId).getPriority() > UserPermission.BLOCKED.getPriority();
    }

    /**
     * 查询显式配置的权限例外。
     *
     * @param userId   用户 QQ 号
     * @param groupId  群号；{@code null} 表示全局例外
     * @param pluginId 插件标识
     * @return 例外记录的权限等级；无记录时为空
     */
    Optional<UserPermission> explicitPermission(long userId, Long groupId, String pluginId);

    /**
     * 设置权限例外。
     *
     * @param userId     用户 QQ 号
     * @param groupId    群号；{@code null} 表示全局例外
     * @param pluginId   插件标识
     * @param permission 权限等级
     */
    void grant(long userId, Long groupId, String pluginId, UserPermission permission);

    /**
     * 撤销权限例外。
     *
     * @param userId   用户 QQ 号
     * @param groupId  群号；{@code null} 表示全局例外
     * @param pluginId 插件标识
     * @return 撤销了存在的记录时返回 {@code true}
     */
    boolean revoke(long userId, Long groupId, String pluginId);

    /**
     * 令缓存失效，强制下次查询回源数据库。
     *
     * <p>现行系统<b>没有</b>这个能力——权限缓存只在启动时装载一次，
     * 后台改了权限必须重启才生效（审计报告记录的中危问题）。新实现必须提供。</p>
     *
     * @param userId   用户 QQ 号；{@code null} 表示不限用户
     * @param groupId  群号；{@code null} 表示不限群
     * @param pluginId 插件标识；{@code null} 表示不限插件
     */
    void invalidate(Long userId, Long groupId, String pluginId);
}
