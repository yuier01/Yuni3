package com.yuier.yuni.core.util;

import com.yuier.yuni.core.enums.UserPermission;

import java.util.HashMap;
import java.util.Map;

import static com.yuier.yuni.core.constants.MessageSenderRole.*;

/**
 * @Title: BusinessMappingUtil
 * @Author yuier
 * @Package com.yuier.yuni.core.util
 * @Date 2025/12/24 16:26
 * @description: 业务概念映射
 */
public final class BusinessMappingUtil {

    /**
     * 群成员角色 → 权限等级。
     *
     * <p><b>必须在静态初始化块里填充</b>：早期实现把填充写在实例构造器里，
     * 而类上还标了 {@code @Component}。这导致一个隐蔽的依赖——静态方法
     * {@link #roleToPermission(String)} 只有在该 Bean 被容器实例化之后才可用。
     * 单元测试、以及任何早于 Bean 创建期的调用都会拿到空表，
     * 进而抛「Invalid role」并被调用方回落成 {@code USER}。</p>
     */
    private static final Map<String, UserPermission> ROLE_TO_PERMISSION;

    static {
        Map<String, UserPermission> map = new HashMap<>();
        map.put(OWNER, UserPermission.MASTER);
        map.put(ADMIN, UserPermission.ADMIN);
        map.put(MEMBER, UserPermission.USER);
        ROLE_TO_PERMISSION = Map.copyOf(map);
    }

    /** 纯静态工具类，不允许实例化。 */
    private BusinessMappingUtil() {
    }

    /**
     * 角色 → 权限。
     *
     * @param role 角色字符串（{@code owner} / {@code admin} / {@code member}）
     * @return 对应权限
     * @throws IllegalArgumentException 未知角色
     */
    public static UserPermission roleToPermission(String role) {
        UserPermission permission = ROLE_TO_PERMISSION.get(role);
        if (permission == null) {
            throw new IllegalArgumentException("Invalid role: " + role);
        }
        return permission;
    }
}
