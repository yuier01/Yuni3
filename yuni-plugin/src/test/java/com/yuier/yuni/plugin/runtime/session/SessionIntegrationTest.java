package com.yuier.yuni.plugin.runtime.session;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.yuier.yuni.core.bot.JsonCodec;
import com.yuier.yuni.core.event.YuniMessageEvent;
import com.yuier.yuni.core.event.notice.GroupDecreaseEvent;
import com.yuier.yuni.core.model.bot.BotApp;
import com.yuier.yuni.core.model.message.MessageChain;
import com.yuier.yuni.core.model.user.MessageSender;
import com.yuier.yuni.core.util.PolymorphicRegistrationProcessor;
import com.yuier.yuni.event.detector.message.command.model.CommandNode;
import com.yuier.yuni.plugin.api.Commands;
import com.yuier.yuni.plugin.api.PluginDeclaration;
import com.yuier.yuni.plugin.api.YuniPlugin;
import com.yuier.yuni.plugin.api.session.SessionAction;
import com.yuier.yuni.plugin.api.session.SessionCloseReason;
import com.yuier.yuni.plugin.api.session.SessionConflictException;
import com.yuier.yuni.plugin.api.session.SessionHandle;
import com.yuier.yuni.plugin.api.session.SessionHandler;
import com.yuier.yuni.plugin.api.session.SessionSpec;
import com.yuier.yuni.plugin.api.session.SessionTarget;
import com.yuier.yuni.plugin.api.session.admin.SessionAdmin;
import com.yuier.yuni.plugin.api.session.admin.SessionStats;
import com.yuier.yuni.plugin.runtime.PluginFiber;
import com.yuier.yuni.plugin.runtime.PluginRuntime;
import com.yuier.yuni.plugin.runtime.TestPlugins;
import com.yuier.yuni.plugin.runtime.adapter.RecordingYuniBot;
import com.yuier.yuni.plugin.runtime.adapter.ReplyFactory;
import com.yuier.yuni.plugin.runtime.dispatch.CommandDispatcher;
import com.yuier.yuni.plugin.runtime.dispatch.EventPipeline;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 长会话集成测试：绝对捕获、唯一加入指令、用户归属互斥、退出/禁止退出。
 */
class SessionIntegrationTest {

    private PluginRuntime runtime;
    private RecordingYuniBot bot;
    private EventPipeline pipeline;
    private PluginFiber fiber;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        new PolymorphicRegistrationProcessor().applyTo(mapper);
        JsonCodec jsonCodec = new JsonCodec(mapper);

        runtime = new PluginRuntime(null, jsonCodec);
        runtime.bindSessionSettings(SessionSettings.withoutSweeper());
        bot = new RecordingYuniBot();

        BotApp botApp = new BotApp();
        botApp.setCommandFlag("/");
        ReplyFactory replyFactory = new ReplyFactory(() -> bot, jsonCodec);
        CommandDispatcher commandDispatcher = new CommandDispatcher(
                runtime.getRegistry(), bot, botApp,
                CommandDispatcher.permissiveResolver());
        pipeline = new EventPipeline(runtime.getRegistry(), runtime, replyFactory,
                commandDispatcher, runtime.getSessionManager());

        fiber = TestPlugins.load(runtime, YuniPlugin.of("session-test", ctx -> { }));
    }

    @AfterEach
    void tearDown() {
        runtime.shutdown();
    }

    @Test
    @DisplayName("通道绝对优先：参与者命令被吞，退出后全局命令恢复")
    void sessionCapturesEverythingUntilExit() {
        AtomicInteger sessionHits = new AtomicInteger();
        AtomicInteger commandHits = new AtomicInteger();
        AtomicInteger messageHits = new AtomicInteger();

        TestPlugins.load(runtime, YuniPlugin.of("observer", ctx -> {
            ctx.command(Commands.on(
                    CommandNode.builder("help").build(),
                    (event, result, reply) -> commandHits.incrementAndGet()));
            ctx.onMessage((event, reply) -> messageHits.incrementAndGet());
        }));

        SessionHandle handle = openPartial(123L, "guess", 10001L,
                "/join-guess", "/exit-guess", true,
                (session, event, reply) -> {
                    sessionHits.incrementAndGet();
                    return SessionAction.CONSUMED;
                });

        pipeline.onMessage(groupMessage(123L, 10001L, "50"));
        await(() -> sessionHits.get() == 1);
        assertEquals(0, commandHits.get());
        assertEquals(0, messageHits.get());

        pipeline.onMessage(groupMessage(123L, 10001L, "/help"));
        await(() -> sessionHits.get() == 2);
        assertEquals(0, commandHits.get(), "通道内全局命令不应执行");
        assertEquals(0, messageHits.get());

        pipeline.onMessage(groupMessage(123L, 10001L, "/exit-guess"));
        await(() -> !handle.isActive());
        assertEquals(0, commandHits.get());

        pipeline.onMessage(groupMessage(123L, 10001L, "/help"));
        await(() -> commandHits.get() == 1);
        assertEquals(0, sessionHits.get() - 2);
    }

    @Test
    @DisplayName("多个部分群友通道使用各自加入指令，非参与者普通消息不受影响")
    void multiplePartialChannelsUseOwnJoinCommands() {
        AtomicInteger aHits = new AtomicInteger();
        AtomicInteger bHits = new AtomicInteger();
        AtomicInteger bJoins = new AtomicInteger();
        AtomicInteger normalHits = new AtomicInteger();

        TestPlugins.load(runtime, YuniPlugin.of("normal", ctx ->
                ctx.onMessage((event, reply) -> normalHits.incrementAndGet())));

        SessionHandle a = openPartial(123L, "a", 10001L,
                "/join-a", "/exit-a", true,
                (session, event, reply) -> {
                    aHits.incrementAndGet();
                    return SessionAction.CONSUMED;
                });
        SessionHandle b = runtime.openSession(fiber, SessionSpec.builder(
                        SessionTarget.groupParticipants(123L, "b"))
                .opener(10002L)
                .initialParticipants(Set.of(10002L))
                .joinCommands("/join-b")
                .exitCommands("/exit-b")
                .onParticipantJoin((session, userId) -> bJoins.incrementAndGet())
                .build(), (session, event, reply) -> {
                    bHits.incrementAndGet();
                    return SessionAction.CONSUMED;
                });

        // 非参与者用 B 的加入指令加入 B
        pipeline.onMessage(groupMessage(123L, 10003L, "/join-b"));
        await(() -> bJoins.get() == 1);
        assertEquals(2, b.getParticipantCount());
        assertEquals(1, a.getParticipantCount());

        // 加入 B 后，普通消息进入 B
        pipeline.onMessage(groupMessage(123L, 10003L, "hello"));
        await(() -> bHits.get() == 1);
        assertEquals(0, aHits.get());

        // 已在 B 中，A 的加入指令也只会进入 B
        pipeline.onMessage(groupMessage(123L, 10003L, "/join-a"));
        await(() -> bHits.get() == 2);
        assertEquals(1, a.getParticipantCount(), "一个用户不能同时加入两个部分群友通道");

        // 非参与者 10004 的普通消息不进入任何通道
        pipeline.onMessage(groupMessage(123L, 10004L, "hello"));
        await(() -> normalHits.get() == 1);

        // 离开 B 后可以加入 A
        pipeline.onMessage(groupMessage(123L, 10003L, "/exit-b"));
        await(() -> b.getParticipantCount() == 1);
        pipeline.onMessage(groupMessage(123L, 10003L, "/join-a"));
        await(() -> a.getParticipantCount() == 2);
        assertEquals(2, a.getParticipantCount());
    }

    @Test
    @DisplayName("加入指令在同一群内必须唯一")
    void duplicateJoinCommandIsRejected() {
        openPartial(123L, "a", 10001L, "/join", "/exit-a", true,
                (session, event, reply) -> SessionAction.CONSUMED);

        SessionSpec duplicate = SessionSpec.builder(
                        SessionTarget.groupParticipants(123L, "b"))
                .opener(10002L)
                .initialParticipants(Set.of(10002L))
                .joinCommands("/join")
                .exitCommands("/exit-b")
                .build();

        assertThrows(SessionConflictException.class,
                () -> runtime.openSession(fiber, duplicate,
                        (session, event, reply) -> SessionAction.CONSUMED));
    }

    @Test
    @DisplayName("一个用户在一个群内只能处于一个通道")
    void userCannotBeInTwoGroupChannels() {
        openPartial(123L, "a", 10001L, "/join-a", "/exit-a", true,
                (session, event, reply) -> SessionAction.CONSUMED);

        // 已是部分群友参与者，不能再开单人通道
        SessionSpec member = SessionSpec.builder(
                        SessionTarget.groupMember(123L, 10001L))
                .build();
        assertThrows(SessionConflictException.class,
                () -> runtime.openSession(fiber, member,
                        (session, event, reply) -> SessionAction.CONSUMED));

        // 也不能再开另一个以他为发起者的部分群友通道
        SessionSpec another = SessionSpec.builder(
                        SessionTarget.groupParticipants(123L, "b"))
                .opener(10001L)
                .initialParticipants(Set.of(10001L))
                .joinCommands("/join-b")
                .exitCommands("/exit-b")
                .build();
        assertThrows(SessionConflictException.class,
                () -> runtime.openSession(fiber, another,
                        (session, event, reply) -> SessionAction.CONSUMED));

        // 其他用户可以开单人通道
        SessionSpec other = SessionSpec.builder(
                        SessionTarget.groupMember(123L, 10002L))
                .build();
        runtime.openSession(fiber, other,
                (session, event, reply) -> SessionAction.CONSUMED);
    }

    @Test
    @DisplayName("exitEnabled=false 时 /退出 只是普通消息，不能关闭通道")
    void exitCanBeDisabled() {
        AtomicInteger hits = new AtomicInteger();
        SessionHandle handle = openPartial(123L, "roulette", 10001L,
                "/join-roulette", "/exit-roulette", false,
                (session, event, reply) -> {
                    hits.incrementAndGet();
                    return SessionAction.CONSUMED;
                });

        pipeline.onMessage(groupMessage(123L, 10001L, "/exit-roulette"));
        await(() -> hits.get() == 1);
        assertTrue(handle.isActive(), "禁止退出时通道不能被 /退出 关闭");

        handle.close(SessionCloseReason.PLUGIN_CLOSED);
        await(() -> !handle.isActive());
    }

    @Test
    @DisplayName("参与者退出只移除自己，最后一人退出才关闭")
    void participantExitRemovesOnlySelf() {
        SessionHandle handle = openPartial(123L, "a", 10001L,
                "/join-a", "/exit-a", true,
                (session, event, reply) -> SessionAction.CONSUMED);

        pipeline.onMessage(groupMessage(123L, 10002L, "/join-a"));
        await(() -> handle.getParticipantCount() == 2);

        pipeline.onMessage(groupMessage(123L, 10002L, "/exit-a"));
        await(() -> handle.getParticipantCount() == 1);
        assertTrue(handle.isActive());

        pipeline.onMessage(groupMessage(123L, 10001L, "/exit-a"));
        await(() -> !handle.isActive());
    }

    @Test
    @DisplayName("群内单成员通道只捕获目标用户，其他用户不受影响")
    void groupMemberCapturesOnlyTarget() {
        AtomicInteger memberHits = new AtomicInteger();
        AtomicInteger normalHits = new AtomicInteger();

        TestPlugins.load(runtime, YuniPlugin.of("normal", ctx ->
                ctx.onMessage((event, reply) -> normalHits.incrementAndGet())));

        SessionSpec spec = SessionSpec.builder(
                        SessionTarget.groupMember(123L, 10001L))
                .build();
        SessionHandle handle = runtime.openSession(fiber, spec,
                (session, event, reply) -> {
                    memberHits.incrementAndGet();
                    return SessionAction.CONSUMED;
                });

        pipeline.onMessage(groupMessage(123L, 10002L, "hello"));
        await(() -> normalHits.get() == 1);
        assertEquals(0, memberHits.get());

        pipeline.onMessage(groupMessage(123L, 10001L, "hello"));
        await(() -> memberHits.get() == 1);
        assertEquals(1, normalHits.get());

        handle.close(SessionCloseReason.PLUGIN_CLOSED);
        await(() -> !handle.isActive());
    }

    @Test
    @DisplayName("私聊通道只捕获目标用户的私聊消息")
    void privateSessionCapturesOnlyTarget() {
        AtomicInteger hits = new AtomicInteger();
        SessionSpec spec = SessionSpec.builder(
                        SessionTarget.privateChat(10001L))
                .build();
        SessionHandle handle = runtime.openSession(fiber, spec,
                (session, event, reply) -> {
                    hits.incrementAndGet();
                    return SessionAction.CONSUMED;
                });

        pipeline.onMessage(privateMessage(10001L, "hello"));
        await(() -> hits.get() == 1);

        pipeline.onMessage(privateMessage(10002L, "hello"));
        await(() -> hits.get() == 1);

        handle.close(SessionCloseReason.PLUGIN_CLOSED);
        await(() -> !handle.isActive());
    }

    @Test
    @DisplayName("插件卸载时强制关闭会话")
    void unloadClosesSessions() {
        SessionHandle handle = openPartial(123L, "a", 10001L,
                "/join-a", "/exit-a", true,
                (session, event, reply) -> SessionAction.CONSUMED);

        runtime.unload("session-test");
        await(() -> !handle.isActive());
        assertFalse(handle.isActive());
    }

    @Test
    @DisplayName("参数校验：初始参与者只能是 opener，且必须提供加入指令")
    void specValidation() {
        assertThrows(IllegalArgumentException.class, () -> SessionSpec.builder(
                        SessionTarget.groupParticipants(123L, "a"))
                .opener(10001L)
                .initialParticipants(Set.of(10001L, 10002L))
                .joinCommands("/join-a")
                .build());

        assertThrows(IllegalArgumentException.class, () -> SessionSpec.builder(
                        SessionTarget.groupParticipants(123L, "a"))
                .opener(10001L)
                .initialParticipants(Set.of(10001L))
                .build());

        assertThrows(IllegalArgumentException.class,
                () -> SessionSpec.defaults(
                        SessionTarget.groupParticipants(123L, "a")));

        assertThrows(IllegalArgumentException.class, () -> SessionSpec.builder(
                        SessionTarget.groupMember(123L, 10001L))
                .opener(10002L)
                .build());
    }

    @Test
    @DisplayName("已有单人通道的用户不能再通过加入指令进入部分群友通道")
    void memberChannelBlocksPartialJoin() {
        AtomicInteger memberHits = new AtomicInteger();

        SessionSpec member = SessionSpec.builder(
                        SessionTarget.groupMember(123L, 10002L))
                .build();
        runtime.openSession(fiber, member,
                (session, event, reply) -> {
                    memberHits.incrementAndGet();
                    return SessionAction.CONSUMED;
                });

        SessionHandle partial = openPartial(123L, "a", 10001L,
                "/join-a", "/exit-a", true,
                (session, event, reply) -> SessionAction.CONSUMED);

        pipeline.onMessage(groupMessage(123L, 10002L, "/join-a"));
        await(() -> memberHits.get() == 1);
        assertEquals(1, partial.getParticipantCount(), "单人通道用户不能被拉进部分群友通道");
    }

    @Test
    @DisplayName("管理端可列出、统计并强制关闭会话")
    void adminCanListAndCloseSessions() {
        SessionHandle handle = openPartial(123L, "a", 10001L,
                "/join-a", "/exit-a", true,
                (session, event, reply) -> SessionAction.CONSUMED);

        SessionAdmin admin = runtime.getSessionAdmin();
        assertEquals(1, admin.list().size());
        SessionStats before = admin.stats();
        assertEquals(1, before.activeSessions());
        assertEquals(1, before.totalOpened());

        assertTrue(admin.close(handle.getId(), SessionCloseReason.ADMIN_CLOSED));
        await(() -> !handle.isActive());
        assertEquals(0, admin.list().size());
        assertEquals(1, admin.stats().totalClosed());
    }

    @Test
    @DisplayName("成员退群移除参与者，机器人被踢关闭整群通道")
    void groupDecreaseCleansSessions() {
        SessionHandle handle = openPartial(123L, "a", 10001L,
                "/join-a", "/exit-a", true,
                (session, event, reply) -> SessionAction.CONSUMED);

        pipeline.onMessage(groupMessage(123L, 10002L, "/join-a"));
        await(() -> handle.getParticipantCount() == 2);

        GroupDecreaseEvent leave = new GroupDecreaseEvent();
        leave.setSubType("leave");
        leave.setGroupId(123L);
        leave.setUserId(10002L);
        leave.setSelfId(10000L);
        pipeline.onNotice(leave);
        await(() -> handle.getParticipantCount() == 1);
        assertTrue(handle.isActive());

        GroupDecreaseEvent kickMe = new GroupDecreaseEvent();
        kickMe.setSubType("kick_me");
        kickMe.setGroupId(123L);
        kickMe.setUserId(10000L);
        kickMe.setSelfId(10000L);
        pipeline.onNotice(kickMe);
        await(() -> !handle.isActive());
    }

    @Test
    @DisplayName("空闲超时关闭会话")
    void idleTimeoutClosesSession() {
        FakeClock clock = new FakeClock();
        PluginRuntime timeoutRuntime = new PluginRuntime(null);
        timeoutRuntime.bindSessionSettings(SessionSettings.withoutSweeper());
        timeoutRuntime.bindSessionClock(clock);
        PluginFiber timeoutFiber = TestPlugins.load(timeoutRuntime,
                YuniPlugin.of("timeout", ctx -> { }));

        AtomicReference<SessionCloseReason> reason = new AtomicReference<>();
        SessionSpec spec = SessionSpec.builder(
                        SessionTarget.groupParticipants(123L, "a"))
                .opener(10001L)
                .initialParticipants(Set.of(10001L))
                .joinCommands("/join-a")
                .exitCommands("/exit-a")
                .idleTimeout(Duration.ofMinutes(5))
                .maxDuration(Duration.ofMinutes(30))
                .onClose(close -> reason.set(close.reason()))
                .build();
        SessionHandle handle = timeoutRuntime.openSession(timeoutFiber, spec,
                (session, event, reply) -> SessionAction.CONSUMED);

        clock.advance(Duration.ofMinutes(6));
        timeoutRuntime.getSessionManager().sweepNow();
        // isActive 在进入 CLOSING 时就变 false；onClose 在清理阶段才执行，需等 reason
        await(() -> !handle.isActive() && reason.get() != null);
        assertEquals(SessionCloseReason.IDLE_TIMEOUT, reason.get());
        timeoutRuntime.shutdown();
    }

    private static final class FakeClock implements SessionClock {

        private long nanos;
        private Instant instant = Instant.parse("2026-01-01T00:00:00Z");

        @Override
        public long nanoTime() {
            return nanos;
        }

        @Override
        public Instant now() {
            return instant;
        }

        void advance(Duration duration) {
            nanos += duration.toNanos();
            instant = instant.plus(duration);
        }
    }

    private static YuniMessageEvent privateMessage(long userId, String text) {
        YuniMessageEvent event = new YuniMessageEvent();
        event.setTime(1700000000L);
        event.setSelfId(10000L);
        event.setMessageType("private");
        event.setSubType("friend");
        event.setMessageId(2L);
        event.setUserId(userId);
        MessageChain chain = new MessageChain();
        chain.addTextSegment(text);
        event.setMessage(chain.getContent());
        event.setMessageChain(chain);

        MessageSender sender = new MessageSender();
        sender.setUserId(userId);
        sender.setNickname("测试用户");
        sender.setRole("member");
        event.setSender(sender);
        return event;
    }

    private SessionHandle openPartial(long groupId,
                                      String key,
                                      long opener,
                                      String joinCommand,
                                      String exitCommand,
                                      boolean exitEnabled,
                                      SessionHandler handler) {
        SessionSpec spec = SessionSpec.builder(
                        SessionTarget.groupParticipants(groupId, key))
                .opener(opener)
                .initialParticipants(Set.of(opener))
                .joinCommands(joinCommand)
                .exitEnabled(exitEnabled)
                .exitCommands(exitCommand)
                .idleTimeout(Duration.ofMinutes(5))
                .build();
        return runtime.openSession(fiber, spec, handler);
    }

    private static YuniMessageEvent groupMessage(long groupId, long userId, String text) {
        YuniMessageEvent event = new YuniMessageEvent();
        event.setTime(1700000000L);
        event.setSelfId(10000L);
        event.setMessageType("group");
        event.setSubType("normal");
        event.setMessageId(1L);
        event.setUserId(userId);
        event.setGroupId(groupId);
        MessageChain chain = new MessageChain();
        chain.addTextSegment(text);
        event.setMessage(chain.getContent());
        event.setMessageChain(chain);

        MessageSender sender = new MessageSender();
        sender.setUserId(userId);
        sender.setNickname("测试用户");
        sender.setRole("member");
        event.setSender(sender);
        return event;
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
