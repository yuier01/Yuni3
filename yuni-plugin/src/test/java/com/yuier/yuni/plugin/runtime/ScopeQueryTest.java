package com.yuier.yuni.plugin.runtime;

import com.yuier.yuni.core.bot.BotGroupInfo;
import com.yuier.yuni.core.bot.BotGroupMemberInfo;
import com.yuier.yuni.core.bot.BotMessageInfo;
import com.yuier.yuni.core.bot.BotStatus;
import com.yuier.yuni.core.bot.BotUserInfo;
import com.yuier.yuni.core.bot.MessageSentResult;
import com.yuier.yuni.core.bot.MessageTarget;
import com.yuier.yuni.core.bot.YuniBot;
import com.yuier.yuni.core.model.bot.BotApp;
import com.yuier.yuni.core.model.message.MessageChain;
import com.yuier.yuni.plugin.api.PluginMetadata;
import com.yuier.yuni.plugin.api.ScopeFilter;
import com.yuier.yuni.plugin.api.ScopeQuery;
import com.yuier.yuni.plugin.api.YuniContext;
import com.yuier.yuni.plugin.api.YuniPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ScopeQuery} 的单元测试——主动插件的群粒度启停查询。
 *
 * <h2>为什么需要这个能力</h2>
 * <p>被动插件的事件路径上，框架会自动施加 {@link ScopeFilter} 过滤，
 * 插件无需关心。但<b>主动插件（定时任务）由时间触发，没有「当前群号」上下文</b>，
 * 因此在向一批群广播时必须自己回答「这个群是否启用了本插件」。</p>
 *
 * <p>现行系统的做法是让插件直接查框架内部的
 * {@code PluginEnableProcessor.isPluginEnabled(groupId, CrazyThursday.class)}——
 * 这是插件依赖框架实现细节的典型。本契约把它收敛为稳定的插件侧接口。</p>
 */
class ScopeQueryTest {

    private PluginRuntime runtime;

    @BeforeEach
    void setUp() {
        runtime = new PluginRuntime(null, null);
        BotApp botApp = new BotApp();
        botApp.setCommandFlag("/");
        runtime.bindBot(() -> new FakeGroupListBot(List.of(100L, 200L, 300L)), botApp);
    }

    /**
     * 只支持 {@code getGroupList} 的机器人替身。
     *
     * <p>主动插件的群广播只需要这一个能力，其余方法返回空值即可。</p>
     */
    private static final class FakeGroupListBot implements YuniBot {

        /** 本机器人所在的群。 */
        private final List<Long> groupIds;

        private FakeGroupListBot(List<Long> groupIds) {
            this.groupIds = groupIds;
        }

        @Override
        public String getPlatform() {
            return "test";
        }

        @Override
        public String getBotId() {
            return "10000";
        }

        @Override
        public BotStatus getStatus() {
            return BotStatus.ONLINE;
        }

        @Override
        public CompletableFuture<Void> connect() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> disconnect() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public MessageSentResult sendMessage(MessageTarget target, MessageChain message) {
            return MessageSentResult.ok("1");
        }

        @Override
        public void recallMessage(String messageId) {
            // 测试不关心
        }

        @Override
        public Optional<List<BotGroupInfo>> getGroupList() {
            List<BotGroupInfo> groups = new ArrayList<>();
            for (Long id : groupIds) {
                BotGroupInfo info = new BotGroupInfo();
                info.setGroupId(id);
                groups.add(info);
            }
            return Optional.of(groups);
        }

        @Override
        public Optional<BotGroupInfo> getGroupInfo(String groupId) {
            return Optional.empty();
        }

        @Override
        public Optional<BotGroupInfo> getGroupInfo(String groupId, boolean noCache) {
            return Optional.empty();
        }

        @Override
        public Optional<List<BotGroupMemberInfo>> getGroupMemberList(String groupId) {
            return Optional.empty();
        }

        @Override
        public Optional<List<BotGroupMemberInfo>> getGroupMemberList(String groupId, boolean noCache) {
            return Optional.empty();
        }

        @Override
        public Optional<BotGroupMemberInfo> getGroupMemberInfo(String groupId, String userId) {
            return Optional.empty();
        }

        @Override
        public Optional<BotGroupMemberInfo> getGroupMemberInfo(String groupId, String userId, boolean noCache) {
            return Optional.empty();
        }

        @Override
        public Optional<BotUserInfo> getUserInfo(String userId) {
            return Optional.empty();
        }

        @Override
        public Optional<BotUserInfo> getUserInfo(String userId, boolean noCache) {
            return Optional.empty();
        }

        @Override
        public Optional<BotMessageInfo> getMessage(String messageId) {
            return Optional.empty();
        }

        @Override
        public void kickMember(String groupId, String userId, boolean rejectAddRequest) {
            // 测试不关心
        }

        @Override
        public void banMember(String groupId, String userId, long durationSeconds) {
            // 测试不关心
        }
    }

    /**
     * 加载一个在 apply 期间捕获 {@link ScopeQuery} 的插件。
     *
     * @param filter 作用域过滤器
     * @return 捕获到的查询对象（单元素数组，便于 lambda 内赋值）
     */
    private ScopeQuery[] loadPluginCapturingScope(ScopeFilter filter) {
        ScopeQuery[] captured = new ScopeQuery[1];
        YuniPlugin plugin = new YuniPlugin() {
            @Override
            public PluginMetadata metadata() {
                return PluginMetadata.of("主动插件").defaultEnable(true).build();
            }

            @Override
            public void apply(YuniContext ctx) {
                captured[0] = ctx.scope();
            }
        };
        runtime.load(plugin, null, null, null, null, filter);
        return captured;
    }

    @Test
    @DisplayName("白名单：只在列出的群生效；全局开关是「关」")
    void whitelistScope() {
        ScopeQuery scope = loadPluginCapturingScope(ScopeFilter.onlyGroups(200L))[0];

        assertFalse(scope.isEnabledIn(100L));
        assertTrue(scope.isEnabledIn(200L));
        assertFalse(scope.isEnabledIn(300L));
        // 白名单 = 全局关 + 个别群开
        assertFalse(scope.isGloballyEnabled());
    }

    @Test
    @DisplayName("黑名单：除列出的群外都生效；全局开关仍是「开」")
    void blacklistScope() {
        ScopeQuery scope = loadPluginCapturingScope(ScopeFilter.excludeGroups(300L))[0];

        assertTrue(scope.isEnabledIn(100L));
        assertTrue(scope.isEnabledIn(200L));
        assertFalse(scope.isEnabledIn(300L));
        /*
         * 黑名单 = 全局开 + 个别群关，因此 isGloballyEnabled 为 true。
         *
         * isGloballyEnabled 回答「底座（全局默认值）是否生效」，
         * 群例外不影响它——否则 setGlobalEnabled(true) 之后
         * isGloballyEnabled() 会读回 false，读写不对偶。
         */
        assertTrue(scope.isGloballyEnabled());
    }

    @Test
    @DisplayName("ALL：所有群都生效")
    void allScope() {
        ScopeQuery scope = loadPluginCapturingScope(ScopeFilter.ALL)[0];

        assertTrue(scope.isEnabledIn(100L));
        assertTrue(scope.isEnabledIn(99999L));
        assertTrue(scope.isGloballyEnabled());
    }

    @Test
    @DisplayName("enabledGroups：只返回机器人当前所在且启用了本插件的群")
    void enabledGroupsIntersectsWithActualGroups() {
        // 白名单里有 200 与 999（后者机器人不在其中）
        ScopeQuery scope = loadPluginCapturingScope(
                ScopeFilter.onlyGroups(200L, 999L))[0];

        List<Long> groups = scope.enabledGroups();

        // 999 不在机器人的群列表里，因此不应出现——避免向不存在的群发送
        assertEquals(List.of(200L), groups);
    }

    @Test
    @DisplayName("无过滤器记录时按元数据的 defaultEnable 回落")
    void fallsBackToMetadataDefaultEnable() {
        ScopeQuery[] captured = new ScopeQuery[1];
        YuniPlugin disabled = new YuniPlugin() {
            @Override
            public PluginMetadata metadata() {
                return PluginMetadata.of("默认关闭的插件").defaultEnable(false).build();
            }

            @Override
            public void apply(YuniContext ctx) {
                captured[0] = ctx.scope();
            }
        };
        // 不传 filter，也不记录到 pluginScopeFilters
        runtime.load(disabled, null, null, null, null, null);

        // 无过滤器记录 → 回落 defaultEnable=false
        assertFalse(captured[0].isEnabledIn(100L));
    }

    @Test
    @DisplayName("插件卸载后作用域记录被清理")
    void scopeFilterIsRemovedOnUnload() {
        ScopeQuery[] captured = loadPluginCapturingScope(ScopeFilter.onlyGroups(100L));
        assertTrue(captured[0].isEnabledIn(100L));

        runtime.unload("主动插件");

        assertTrue(runtime.scopeFilterOf("主动插件").isEmpty(),
                "卸载后作用域过滤器记录应被清理，避免泄漏");
    }

    @Test
    @DisplayName("describeScope 给出人类可读的作用域描述")
    void describeScopeIsReadable() {
        loadPluginCapturingScope(ScopeFilter.onlyGroups(100L, 200L));
        assertTrue(runtime.describeScope("主动插件").contains("仅限群"));

        // 未知插件（无 Fiber、无配置）按默认值处理，因此描述里不带群例外
        assertTrue(runtime.describeScope("不存在的插件").contains("未记录"));
    }
}
