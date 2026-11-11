package com.yuier.yuni.plugin.runtime.service;

import com.yuier.yuni.core.bot.YuniBot;
import com.yuier.yuni.core.enums.UserPermission;
import com.yuier.yuni.core.model.bot.Bot;
import com.yuier.yuni.permission.service.GroupUserPluginPermissionService;
import com.yuier.yuni.plugin.runtime.adapter.RecordingYuniBot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link DefaultPermissionService} 的晚绑定回归测试。
 *
 * <h2>背景</h2>
 * <p>权限服务在容器 refresh 期创建，而 {@code YuniBot} 要到启动编排的
 * {@code BotManager.startBot()} 才注册。早期实现直接注入 bot 实例，
 * 得到的是 {@code null} 且永不复原——于是
 * {@code defaultGroupPermission} 永远回落 {@code USER}，群主/管理员的
 * 权限提升在群里完全不生效，所有 ADMIN 级命令都会被拒。</p>
 *
 * <p>本类钉住修复后的行为：服务保存的是<b>供应函数</b>，bot 何时就绪都能读到。</p>
 */
class DefaultPermissionServiceTest {

    /**
     * 构造一个「没有任何例外记录」的权限存储桩。
     *
     * <p>用 {@link Proxy} 而不是实现接口：{@code GroupUserPluginPermissionService}
     * 继承自 MyBatis-Plus 的 {@code IService}，手写实现要覆盖几十个方法，
     * 而本测试只关心 {@code getUserPermission} 返回 {@code null}（无例外）。</p>
     *
     * @return 存储桩
     */
    private static GroupUserPluginPermissionService stubStore() {
        return (GroupUserPluginPermissionService) Proxy.newProxyInstance(
                DefaultPermissionServiceTest.class.getClassLoader(),
                new Class<?>[]{GroupUserPluginPermissionService.class},
                (proxy, method, args) -> {
                    if ("getUserPermission".equals(method.getName())) {
                        // 无显式例外 → 走默认角色映射
                        return null;
                    }
                    Class<?> returnType = method.getReturnType();
                    if (returnType == boolean.class) {
                        return false;
                    }
                    if (returnType == int.class) {
                        return 0;
                    }
                    if (returnType == long.class) {
                        return 0L;
                    }
                    return null;
                });
    }

    @Test
    @DisplayName("bot 晚于服务创建时，群角色映射仍然生效（回归）")
    void resolvesBotLazily() {
        AtomicReference<YuniBot> botRef = new AtomicReference<>();
        DefaultPermissionService service =
                new DefaultPermissionService(stubStore(), null, botRef::get);

        // ① bot 尚未就绪：群聊回落 USER
        assertEquals(UserPermission.USER, service.resolve(10001L, 123L, "m/P"),
                "bot 未就绪时应回落 USER，而不是抛异常");

        // ② bot 就绪后：同一个服务实例应能读到管理员角色
        RecordingYuniBot bot = new RecordingYuniBot();
        bot.setGroupMemberRole("admin");
        botRef.set(bot);
        assertEquals(UserPermission.ADMIN, service.resolve(10001L, 123L, "m/P"),
                "bot 就绪后必须能读到群管理员角色——这正是修复前失效的路径");

        // ③ 群主
        bot.setGroupMemberRole("owner");
        assertEquals(UserPermission.MASTER, service.resolve(10001L, 123L, "m/P"));

        // ④ 普通成员
        bot.setGroupMemberRole("member");
        assertEquals(UserPermission.USER, service.resolve(10001L, 123L, "m/P"));
    }

    @Test
    @DisplayName("私聊不依赖 bot：MASTER 由 bot 配置的 masterId 决定")
    void privateChatDoesNotRequireBot() {
        Bot botConfig = new Bot();
        botConfig.setMasterId(10001L);

        DefaultPermissionService service =
                new DefaultPermissionService(stubStore(), botConfig, () -> null);

        assertEquals(UserPermission.MASTER, service.resolve(10001L, null, "m/P"));
        assertEquals(UserPermission.USER, service.resolve(20002L, null, "m/P"));
    }

    @Test
    @DisplayName("botSupplier 为 null 时不抛异常，群聊回落 USER")
    void nullSupplierIsSafe() {
        DefaultPermissionService service =
                new DefaultPermissionService(stubStore(), null, null);

        assertEquals(UserPermission.USER, service.resolve(10001L, 123L, "m/P"));
    }
}
