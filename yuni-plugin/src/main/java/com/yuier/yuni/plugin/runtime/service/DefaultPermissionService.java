package com.yuier.yuni.plugin.runtime.service;

import com.yuier.yuni.core.bot.YuniBot;
import com.yuier.yuni.core.enums.UserPermission;
import com.yuier.yuni.core.model.bot.Bot;
import com.yuier.yuni.core.util.BusinessMappingUtil;
import com.yuier.yuni.permission.domain.entity.GroupUserPluginPermissionEntity;
import com.yuier.yuni.permission.service.GroupUserPluginPermissionService;
import com.yuier.yuni.plugin.api.event.MessageEvent;
import com.yuier.yuni.plugin.api.service.PermissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link PermissionService} 的默认实现。
 *
 * <h2>判定链（与现行系统保持一致）</h2>
 * <ol>
 *   <li>内存缓存（键 {@code userId@groupId:pluginId}）；</li>
 *   <li>数据库精确查（{@code group_user_plugin_permission} 的例外记录）；</li>
 *   <li>默认权限回落：
 *     <ul>
 *       <li>私聊：{@code userId == bot.masterId} → MASTER，否则 USER；</li>
 *       <li>群聊：按发送者角色映射（owner → MASTER / admin → ADMIN / member → USER）。</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <h2>相对现行实现修正的两个缺陷</h2>
 * <p>审计报告记录的 {@code UserPermissionManager} 问题，本实现全部修掉：</p>
 * <ol>
 *   <li><b>缓存永不刷新</b>。现行实现只在启动时装载一次 {@code userPermissionMap}，
 *       之后运行时新增的缓存项永不过期，后台改权限必须重启才生效。
 *       本实现提供 {@link #invalidate} 并在 {@link #grant}/{@link #revoke}
 *       成功后自动失效对应条目。</li>
 *   <li><b>非线程安全</b>。现行用普通 {@code HashMap}，而后端插件在
 *       {@code ForkJoinPool} 的异步线程上并发读取。本实现改用
 *       {@link ConcurrentHashMap}。</li>
 * </ol>
 *
 * <h2>关于"只缓存例外"</h2>
 * <p>缓存只存<b>数据库里显式存在的例外记录</b>，不缓存默认权限的计算结果。
 * 理由：默认权限依赖群成员角色，而角色随时可能变化（管理员被撤销）；
 * 缓存它会导致降权不生效。例外记录是低频变更的配置数据，缓存是安全的。</p>
 *
 * @see PermissionService
 */
@Slf4j
@RequiredArgsConstructor
public final class DefaultPermissionService implements PermissionService {

    /** 权限例外数据访问。 */
    private final GroupUserPluginPermissionService permissionStore;

    /** 机器人配置，用于私聊场景判定 MASTER。 */
    private final Bot botConfig;

    /** 机器人实例，用于群聊场景查询发送者角色。 */
    private final YuniBot bot;

    /**
     * 例外记录缓存：键 {@code userId@groupId:pluginId}。
     *
     * <p>用 {@link ConcurrentHashMap}——分配发在异步线程上执行，
     * 现行实现的 {@code HashMap} 在这里存在真实的数据竞争。</p>
     */
    private final Map<String, UserPermission> exceptionCache = new ConcurrentHashMap<>();

    /* 构造器由 @RequiredArgsConstructor 生成：
     * (GroupUserPluginPermissionService, Bot, YuniBot)。
     * exceptionCache 有初始值，不参与生成。 */

    @Override
    public UserPermission resolve(long userId, Long groupId, String pluginId) {
        // ① 例外记录（缓存 → 数据库）
        UserPermission exception = lookupException(userId, groupId, pluginId);
        if (exception != null) {
            return exception;
        }

        // ② 默认权限回落
        if (groupId == null) {
            // 私聊：只有 bot 拥有者是 MASTER
            return defaultPrivatePermission(userId);
        }
        return defaultGroupPermission(userId, groupId);
    }

    @Override
    public UserPermission resolve(MessageEvent event, String pluginId) {
        /*
         * 群聊场景优先使用事件里已携带的角色，避免一次额外的
         * get_group_member_info 接口调用。
         *
         * 注意例外记录仍然优先——它是显式配置，应当覆盖角色推断。
         */
        UserPermission exception = lookupException(
                event.getUserId(), event.getGroupId().orElse(null), pluginId);
        if (exception != null) {
            return exception;
        }

        if (event.isGroup()) {
            Optional<String> role = event.getSender().getRole();
            if (role.isPresent()) {
                UserPermission mapped = safeRoleToPermission(role.get());
                if (mapped != null) {
                    return mapped;
                }
            }
            // 事件未带角色（部分协议实现不填）：回退到接口查询
            return defaultGroupPermission(event.getUserId(), event.getGroupId().orElse(null));
        }
        return defaultPrivatePermission(event.getUserId());
    }

    @Override
    public Optional<UserPermission> explicitPermission(long userId, Long groupId, String pluginId) {
        return Optional.ofNullable(lookupException(userId, groupId, pluginId));
    }

    @Override
    public void grant(long userId, Long groupId, String pluginId, UserPermission permission) {
        if (pluginId == null || permission == null) {
            throw new IllegalArgumentException("插件标识与权限等级均不能为空");
        }
        GroupUserPluginPermissionEntity entity = new GroupUserPluginPermissionEntity(
                null, groupId, pluginId, userId, permission);

        // 先查是否已有记录：有则更新，无则插入。
        // 表上有 UNIQUE(group_id, plugin_id, user_id) 约束，重复插入会失败。
        GroupUserPluginPermissionEntity existing = findEntity(userId, groupId, pluginId);
        if (existing != null) {
            entity.setId(existing.getId());
            permissionStore.updateById(entity);
        } else {
            permissionStore.save(entity);
        }

        // 授予后立即失效缓存，使下一次判定回源——这是现行系统缺失的能力
        invalidate(userId, groupId, pluginId);
        log.info("已授予权限: userId={}, groupId={}, pluginId={}, permission={}",
                userId, groupId, pluginId, permission);
    }

    @Override
    public boolean revoke(long userId, Long groupId, String pluginId) {
        if (pluginId == null) {
            return false;
        }
        GroupUserPluginPermissionEntity existing = findEntity(userId, groupId, pluginId);
        if (existing == null) {
            return false;
        }
        permissionStore.removeById(existing.getId());
        invalidate(userId, groupId, pluginId);
        log.info("已撤销权限: userId={}, groupId={}, pluginId={}", userId, groupId, pluginId);
        return true;
    }

    @Override
    public void invalidate(Long userId, Long groupId, String pluginId) {
        /*
         * 三个参数都可为 null，语义为「不限」：
         *   invalidate(null, null, null)  清空全部
         *   invalidate(uid, null, null)   清掉该用户在所有群/插件的条目
         *   invalidate(null, gid, null)   清掉该群的所有条目
         */
        if (userId == null && groupId == null && pluginId == null) {
            int cleared = exceptionCache.size();
            exceptionCache.clear();
            log.debug("权限缓存已全量失效，清理 {} 条", cleared);
            return;
        }
        // 键格式为 userId@groupId:pluginId，用字符串前缀/后缀做粗筛。
        // 条目数远小于「用户 × 群 × 插件」的组合数（只存显式配置），因此遍历可接受。
        exceptionCache.keySet().removeIf(key -> {
            String[] parts = splitKey(key);
            if (parts == null) {
                return false;
            }
            return matches(parts[0], userId)
                    && matches(parts[1], groupId)
                    && matches(parts[2], pluginId);
        });
    }

    // ==================== 内部 ====================

    /**
     * 查例外记录：先缓存后数据库。
     *
     * @param userId   用户 QQ 号
     * @param groupId  群号；{@code null} 表示全局例外
     * @param pluginId 插件标识
     * @return 权限等级；无记录时返回 {@code null}
     */
    private UserPermission lookupException(long userId, Long groupId, String pluginId) {
        if (pluginId == null) {
            // 未指定插件时不存在「插件级例外」，直接回落默认权限
            return null;
        }
        String key = keyOf(userId, groupId, pluginId);
        UserPermission cached = exceptionCache.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            UserPermission fromDb = permissionStore.getUserPermission(userId, groupId, pluginId);
            if (fromDb != null) {
                exceptionCache.put(key, fromDb);
            }
            return fromDb;
        } catch (Exception e) {
            // 数据库不可用时回落默认权限，而不是让整个事件分发失败
            log.warn("查询权限例外失败，回落默认权限: userId={}, groupId={}, pluginId={}",
                    userId, groupId, pluginId, e);
            return null;
        }
    }

    /**
     * 查询数据库中的例外记录实体。
     *
     * @param userId   用户 QQ 号
     * @param groupId  群号
     * @param pluginId 插件标识
     * @return 实体；不存在时返回 {@code null}
     */
    private GroupUserPluginPermissionEntity findEntity(long userId, Long groupId, String pluginId) {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<GroupUserPluginPermissionEntity> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        wrapper.eq(GroupUserPluginPermissionEntity::getUserId, userId)
                .eq(GroupUserPluginPermissionEntity::getGroupId, groupId)
                .eq(GroupUserPluginPermissionEntity::getPluginId, pluginId);
        return permissionStore.getOne(wrapper);
    }

    /**
     * 私聊场景的默认权限。
     *
     * @param userId 用户 QQ 号
     * @return MASTER 或 USER
     */
    private UserPermission defaultPrivatePermission(long userId) {
        Long masterId = botConfig == null ? null : botConfig.getMasterId();
        return masterId != null && masterId == userId ? UserPermission.MASTER : UserPermission.USER;
    }

    /**
     * 群聊场景的默认权限——按成员角色映射。
     *
     * @param userId  用户 QQ 号
     * @param groupId 群号
     * @return 映射后的权限；查询失败时回落 USER
     */
    private UserPermission defaultGroupPermission(long userId, Long groupId) {
        if (bot == null || groupId == null) {
            return UserPermission.USER;
        }
        try {
            return bot.getGroupMemberInfo(String.valueOf(groupId), String.valueOf(userId))
                    .map(info -> safeRoleToPermission(info.getRole()))
                    .orElse(UserPermission.USER);
        } catch (Exception e) {
            log.debug("查询群成员角色失败，回落 USER: userId={}, groupId={}", userId, groupId, e);
            return UserPermission.USER;
        }
    }

    /**
     * 角色 → 权限的安全映射。
     *
     * <p>{@link BusinessMappingUtil#roleToPermission} 对未知角色抛
     * {@code IllegalArgumentException}（审计报告记录的缺陷：可能打断整个
     * 事件处理）。这里返回 {@code null} 交由调用方回落，不让异常外泄。</p>
     *
     * @param role 角色字符串
     * @return 映射后的权限；未知角色返回 {@code null}
     */
    private static UserPermission safeRoleToPermission(String role) {
        if (role == null) {
            return null;
        }
        try {
            return BusinessMappingUtil.roleToPermission(role);
        } catch (IllegalArgumentException e) {
            log.debug("未知的成员角色，回落默认权限: role={}", role);
            return null;
        }
    }

    /**
     * 组装缓存键。
     *
     * @param userId   用户 QQ 号
     * @param groupId  群号；{@code null} 表示全局例外
     * @param pluginId 插件标识
     * @return 形如 {@code "10001@123456:fortune"} 的键
     */
    private static String keyOf(long userId, Long groupId, String pluginId) {
        // groupId 为 null 的全局例外用字面量 "null" 表示，与现行系统的
        // assembleUserPermissionKey 保持一致的字符串形态
        return userId + "@" + groupId + ":" + pluginId;
    }

    /**
     * 拆解缓存键。
     *
     * @param key 缓存键
     * @return 长度 3 的数组 {@code [userId, groupId, pluginId]}；格式不符返回 {@code null}
     */
    private static String[] splitKey(String key) {
        int at = key.indexOf('@');
        int colon = key.indexOf(':', at + 1);
        if (at < 0 || colon < 0) {
            return null;
        }
        return new String[]{
                key.substring(0, at),
                key.substring(at + 1, colon),
                key.substring(colon + 1)
        };
    }

    /**
     * 判定键的一个分段是否命中给定的过滤条件。
     *
     * @param keyPart  键分段
     * @param expected 期望值；{@code null} 表示不限制
     * @return 命中返回 {@code true}
     */
    private static boolean matches(String keyPart, Object expected) {
        return expected == null || keyPart.equals(String.valueOf(expected));
    }
}
