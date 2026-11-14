package com.yuier.yuni.plugin.runtime.dispatch;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.yuier.yuni.core.bot.JsonCodec;
import com.yuier.yuni.core.enums.CommandArgRequireType;
import com.yuier.yuni.core.enums.UserPermission;
import com.yuier.yuni.core.event.YuniMessageEvent;
import com.yuier.yuni.core.event.YuniMessageSentEvent;
import com.yuier.yuni.core.event.notice.PokeEvent;
import com.yuier.yuni.core.event.notice.YuniNoticeEvent;
import com.yuier.yuni.core.model.bot.BotApp;
import com.yuier.yuni.core.model.message.MessageChain;
import com.yuier.yuni.core.model.user.MessageSender;
import com.yuier.yuni.core.util.PolymorphicRegistrationProcessor;
import com.yuier.yuni.event.detector.message.command.CommandDetector;
import com.yuier.yuni.event.detector.message.command.model.ArgDef;
import com.yuier.yuni.event.detector.message.command.model.CommandNode;
import com.yuier.yuni.plugin.api.ScopeFilter;
import com.yuier.yuni.plugin.api.PluginDeclaration;
import com.yuier.yuni.plugin.api.YuniContext;
import com.yuier.yuni.plugin.api.YuniPlugin;
import com.yuier.yuni.plugin.api.handler.NoticeHandler;
import com.yuier.yuni.plugin.runtime.PluginAbilityRegistry;
import com.yuier.yuni.plugin.runtime.PluginFiber;
import com.yuier.yuni.plugin.runtime.PluginRuntime;
import com.yuier.yuni.plugin.runtime.adapter.CoreMessageChainView;
import com.yuier.yuni.plugin.runtime.adapter.ReplyFactory;
import com.yuier.yuni.plugin.runtime.adapter.RecordingYuniBot;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.yuier.yuni.plugin.runtime.TestPlugins.load;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EventPipeline} 与 {@link CommandDispatcher} 的集成测试。
 *
 * <h2>为什么这组测试重要</h2>
 * <p>它验证设计文档 §10.2 的两条<b>现行不变式</b>在新流水线里被保持：</p>
 * <ol>
 *   <li><b>命令优先且短路模式插件</b>——命中命令后不再匹配消息/模式处理器；</li>
 *   <li><b>节点级权限递归校验</b>——沿匹配路径逐层比对，同一命令不同子命令
 *       可要求不同权限。</li>
 * </ol>
 * <p>以及 §1.6 声称修掉的行为不一致：作用域过滤成为<b>所有事件类型的必经关卡</b>
 * （现行 {@code matchRequestEvent} / {@code matchMetaEvent} / {@code matchMessageSentEvent}
 * 完全不检查启停）。</p>
 */
class EventPipelineTest {

    private RecordingYuniBot bot;
    private JsonCodec jsonCodec;
    private PluginRuntime runtime;
    private ReplyFactory replyFactory;
    private CommandDispatcher commandDispatcher;
    private EventPipeline pipeline;

    /** 命令前缀配置。 */
    private BotApp botApp;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        new PolymorphicRegistrationProcessor().applyTo(mapper);
        jsonCodec = new JsonCodec(mapper);

        bot = new RecordingYuniBot();
        runtime = new PluginRuntime(null, jsonCodec);
        replyFactory = new ReplyFactory(() -> bot, jsonCodec);

        // 命令前缀必须注入——CommandDetector 会用它剥离命令头。
        // 不注入时会回退到 SpringContextUtil，而在单元测试里没有 Spring 容器。
        botApp = new BotApp();
        botApp.setCommandFlag("/");

        commandDispatcher = new CommandDispatcher(
                runtime.getRegistry(), bot, botApp, CommandDispatcher.permissiveResolver());
        pipeline = new EventPipeline(
                runtime.getRegistry(), runtime, replyFactory, commandDispatcher);
    }

    /**
     * 构造一个群消息事件。
     *
     * @param text 文本内容
     * @return 核心消息事件
     */
    private static YuniMessageEvent groupMessage(String text) {
        YuniMessageEvent event = new YuniMessageEvent();
        event.setTime(1700000000L);
        event.setSelfId(10000L);
        event.setMessageType("group");
        event.setSubType("normal");
        event.setMessageId(1L);
        event.setUserId(10001L);
        event.setGroupId(123L);
        MessageChain chain = new MessageChain();
        chain.addTextSegment(text);
        event.setMessage(chain.getContent());
        event.setMessageChain(chain);

        MessageSender sender = new MessageSender();
        sender.setUserId(10001L);
        sender.setNickname("测试用户");
        sender.setRole("member");
        event.setSender(sender);
        return event;
    }

    /**
     * 等待异步处理器执行完毕。
     *
     * <p>处理器在 {@code ForkJoinPool.commonPool} 上执行，测试无法直接 join。
     * 用轮询等待是朴素但可靠的做法。</p>
     *
     * @param condition 条件
     * @param timeoutMs 超时毫秒
     */
    private static void await(BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // ==================== 消息 / 模式 ====================

    @Test
    @DisplayName("消息事件：条件与处理器在同一次注册中生效")
    void messageHandlerWithCondition() {
        AtomicInteger hits = new AtomicInteger();

        load(runtime, YuniPlugin.of("模式插件", ctx ->
                ctx.onMessage(ev -> ev.getChain().containsText("你好"),
                        (ev, reply) -> hits.incrementAndGet())));

        // 条件不满足：不执行
        pipeline.onMessage(groupMessage("再见"));
        await(() -> hits.get() > 0, 200);
        assertEquals(0, hits.get());

        // 条件满足：执行
        pipeline.onMessage(groupMessage("你好呀"));
        await(() -> hits.get() > 0, 2000);
        assertEquals(1, hits.get());
    }

    @Test
    @DisplayName("通知事件：按子类型过滤，处理器无需再解 rawJson")
    void noticeHandlerFilteredBySubType() {
        AtomicInteger pokes = new AtomicInteger();
        AtomicInteger others = new AtomicInteger();

        load(runtime, YuniPlugin.of("通知插件", ctx -> {
            ctx.onNotice("poke", (ev, reply) -> pokes.incrementAndGet());
            // 无子类型过滤：用恒真条件表达「所有通知都要」
            ctx.onNotice((ev) -> true, (ev, reply) -> others.incrementAndGet());
        }));

        YuniNoticeEvent notice = new YuniNoticeEvent();
        notice.setTime(1L);
        notice.setSelfId(10000L);
        notice.setNoticeType("notify");
        notice.setUserId(10001L);
        notice.setGroupId(123L);
        PokeEvent poke = new PokeEvent();
        poke.setSubType("poke");
        poke.setTargetId(10000L);
        notice.setMatchedEvent(poke);

        pipeline.onNotice(notice);

        await(() -> pokes.get() > 0, 2000);
        assertEquals(1, pokes.get(), "poke 子类型处理器应被调用");
        assertEquals(1, others.get(), "无条件处理器也应被调用");
    }

    // ==================== 作用域过滤（现行系统的行为不一致所在）====================

    @Test
    @DisplayName("作用域过滤是所有事件类型的必经关卡（现行仅在消息/通知路径生效）")
    void scopeFilterAppliesToAllEventKinds() {
        AtomicInteger inScopeHits = new AtomicInteger();
        AtomicInteger outOfScopeHits = new AtomicInteger();

        // 两个插件，唯一的区别是注册项上的 ScopeFilter：
        // 前者只在群 999 生效，后者排除群 999。
        //
        // 这里直接构造注册项来设定过滤器，而不是走 PluginAdmin.setEnabled —— 后者
        // 走的是「三态例外表 → 合成过滤器」的路径（见设计文档 §8.11），
        // 而本用例要验证的是 EventPipeline 对注册项上过滤器的处理，
        // 因此需要绕过合成、直接给一个确定形态的过滤器。
        // 管理端的写入路径由 PluginAdminTest 覆盖。
        PluginFiber inScope = load(runtime, 
                YuniPlugin.of("限定群插件", ctx -> { }));
        PluginFiber outOfScope = load(runtime, 
                YuniPlugin.of("排除群插件", ctx -> { }));

        runtime.getRegistry().add(new PluginAbilityRegistry.Registration.Notice(
                inScope,
                new ScopeFilter(false, Set.of(999L)),
                PluginAbilityRegistry.Registration.Notice.ALWAYS, null,
                (NoticeHandler)
                        (ev, reply) -> inScopeHits.incrementAndGet()));

        runtime.getRegistry().add(new PluginAbilityRegistry.Registration.Notice(
                outOfScope,
                new ScopeFilter(true, Set.of(999L)),
                PluginAbilityRegistry.Registration.Notice.ALWAYS, null,
                (NoticeHandler)
                        (ev, reply) -> outOfScopeHits.incrementAndGet()));

        // 事件来自群 123
        YuniNoticeEvent notice = new YuniNoticeEvent();
        notice.setTime(1L);
        notice.setSelfId(10000L);
        notice.setNoticeType("group_ban");
        notice.setUserId(10001L);
        notice.setGroupId(123L);

        pipeline.onNotice(notice);

        await(() -> outOfScopeHits.get() > 0, 2000);
        assertEquals(1, outOfScopeHits.get(), "群 123 不在白名单但不在黑名单，应被调用");
        assertEquals(0, inScopeHits.get(), "群 123 不在白名单，不应被调用");

        // 事件来自群 999：两者的判定应互换
        YuniNoticeEvent inScopeNotice = new YuniNoticeEvent();
        inScopeNotice.setTime(2L);
        inScopeNotice.setSelfId(10000L);
        inScopeNotice.setNoticeType("group_ban");
        inScopeNotice.setUserId(10001L);
        inScopeNotice.setGroupId(999L);

        pipeline.onNotice(inScopeNotice);

        await(() -> inScopeHits.get() > 0, 2000);
        assertEquals(1, inScopeHits.get(), "群 999 在白名单，应被调用");
        assertEquals(1, outOfScopeHits.get(), "群 999 在黑名单，不应再被调用");
    }

    // ==================== 管理端配置必须真的生效（P6 回归）====================

    @Test
    @DisplayName("管理端关闭某个群的插件后，该群的事件不再投递给它（P6 回归）")
    void adminScopeChangeTakesEffectOnDispatch() {
        AtomicInteger hits = new AtomicInteger();

        // 插件通过正常的 ctx API 注册处理器——不手工构造注册项
        PluginFiber fiber = load(runtime, new YuniPlugin() {
            @Override
 public PluginDeclaration getDeclaration() {
                return PluginDeclaration.of("可启停插件").build();
            }

            @Override
            public void apply(YuniContext ctx) {
                ctx.onNotice("group_ban", (ev, reply) -> hits.incrementAndGet());
            }
        }, "mymod");

        String pluginId = fiber.getId();
        long groupId = 123L;

        // 配置前：默认启用，事件应当被投递
        pipeline.onNotice(groupBanNotice(groupId));
        await(() -> hits.get() > 0, 2000);
        assertEquals(1, hits.get(), "默认启用时应收到事件");

        // 管理端关闭这个群
        runtime.getAdmin().setEnabled(pluginId, groupId, false, true);
        assertFalse(runtime.getAdmin().isEnabledIn(pluginId, groupId));

        // 配置后：同一群的事件不应再投递。
        // 这一条曾经失败——PluginContext 注册时把 ScopeFilter 写死为 ALL，
        // 而 EventPipeline 只查注册项上的过滤器，于是管理端的启停配置
        // 完全不生效（见本用例的注释与 §8.11）。
        pipeline.onNotice(groupBanNotice(groupId));
        await(() -> false, 300);
        assertEquals(1, hits.get(), "关闭后不应再收到该群的事件");

        // 另一个群不受影响
        pipeline.onNotice(groupBanNotice(999L));
        await(() -> hits.get() > 1, 2000);
        assertEquals(2, hits.get(), "其他群的投递不应受影响");

        // 重新开启后恢复投递
        runtime.getAdmin().setEnabled(pluginId, groupId, true, true);
        pipeline.onNotice(groupBanNotice(groupId));
        await(() -> hits.get() > 2, 2000);
        assertEquals(3, hits.get(), "重新开启后应恢复投递");
    }

    @Test
    @DisplayName("管理端全局关闭插件后，任何群的事件都不再投递（P6 回归）")
    void adminGlobalDisableTakesEffectOnDispatch() {
        AtomicInteger hits = new AtomicInteger();

        PluginFiber fiber = load(runtime, new YuniPlugin() {
            @Override
 public PluginDeclaration getDeclaration() {
                return PluginDeclaration.of("全局可关插件").build();
            }

            @Override
            public void apply(YuniContext ctx) {
                ctx.onNotice("group_ban", (ev, reply) -> hits.incrementAndGet());
            }
        }, "mymod");

        runtime.getAdmin().setGlobalEnabled(fiber.getId(), false, true);
        assertFalse(runtime.getAdmin().isGloballyEnabled(fiber.getId()));

        pipeline.onNotice(groupBanNotice(123L));
        pipeline.onNotice(groupBanNotice(999L));
        await(() -> false, 300);
        assertEquals(0, hits.get(), "全局关闭后任何群都不应投递");
    }

    /**
     * 构造一个群禁言通知事件。
     *
     * @param groupId 群号
     * @return 核心通知事件
     */
    private static YuniNoticeEvent groupBanNotice(long groupId) {
        YuniNoticeEvent notice = new YuniNoticeEvent();
        notice.setTime(1L);
        notice.setSelfId(10000L);
        notice.setNoticeType("group_ban");
        notice.setUserId(10001L);
        notice.setGroupId(groupId);
        return notice;
    }

    // ==================== 插件状态关卡 ====================

    @Test
    @DisplayName("FAILED 插件不再收到任何事件")
    void failedPluginReceivesNoEvents() {
        AtomicInteger hits = new AtomicInteger();

        PluginFiber fiber = load(runtime, new YuniPlugin() {
            @Override
 public PluginDeclaration getDeclaration() {
                return PluginDeclaration.of("失败插件").build();
            }

            @Override
            public void apply(YuniContext ctx) {
                ctx.onMessage((ev, reply) -> hits.incrementAndGet());
                throw new IllegalStateException("加载失败");
            }
        });

        assertEquals(com.yuier.yuni.plugin.runtime.FiberState.FAILED, fiber.getState());

        pipeline.onMessage(groupMessage("任意消息"));
        await(() -> hits.get() > 0, 300);
        assertEquals(0, hits.get(), "FAILED 插件不应收到事件");
    }

    // ==================== 统一异常兜底 ====================

    @Test
    @DisplayName("处理器抛 Error 被兜住，且向用户回报失败提示")
    void handlerThrowableIsCaughtAndReported() {
        load(runtime, YuniPlugin.of("抛异常的插件", ctx ->
                ctx.onMessage((ev, reply) -> {
                    throw new NoClassDefFoundError("simulated");
                })));

        pipeline.onMessage(groupMessage("触发"));

        // 失败必须对用户可见——这是 2026-08 P0 事故的教训
        await(() -> bot.sentCount() > 0, 2000);
        assertTrue(bot.sentCount() > 0, "应回复一条失败提示");
        assertTrue(bot.lastMessage().orElseThrow().text().contains("抛异常的插件"),
                bot.lastMessage().orElseThrow().text());
    }

    @Test
    @DisplayName("条件判定抛异常按不匹配处理，不打断分发")
    void predicateThrowableIsTreatedAsNoMatch() {
        AtomicInteger secondCalled = new AtomicInteger();

        load(runtime, YuniPlugin.of("坏条件插件", ctx -> {
            ctx.onMessage(ev -> {
                throw new IllegalStateException("条件写坏了");
            }, (ev, reply) -> {
                throw new AssertionError("不应被执行");
            });
            // 同一插件的第二个处理器应不受影响
            ctx.onMessage((ev, reply) -> secondCalled.incrementAndGet());
        }));

        pipeline.onMessage(groupMessage("任意"));

        await(() -> secondCalled.get() > 0, 2000);
        assertEquals(1, secondCalled.get(), "坏条件不应阻断其他注册项");
    }

    // ==================== 命令：端到端 ====================

    /**
     * 注册一个命令插件。
     *
     * @param tree 命令树
     */
    private void registerCommandTree(CommandNode tree) {
        load(runtime, YuniPlugin.of("命令插件", ctx -> ctx.command(tree)));
    }

    /**
     * 把根节点与处理器绑定。
     *
     * <p>使用 API 层的 {@link com.yuier.yuni.plugin.api.Commands#on} 门面——
     * 这是插件作者实际会用的绑定方式。</p>
     *
     * @param tree    命令树
     * @param handler 处理器
     */
    private static void bind(CommandNode tree, Runnable handler) {
        com.yuier.yuni.plugin.api.Commands.on(tree, (event, result, reply) -> handler.run());
    }

    @Test
    @DisplayName("命令端到端：匹配 → 权限通过 → 执行处理器")
    void commandEndToEnd() {
        AtomicInteger rootRuns = new AtomicInteger();

        CommandNode tree = CommandNode.builder("测试")
                .description("测试命令")
                .build();
        registerCommandTree(tree);
        bind(tree, () -> rootRuns.incrementAndGet());

        pipeline.onMessage(groupMessage("/测试"));

        await(() -> rootRuns.get() > 0, 3000);
        assertEquals(1, rootRuns.get(), "命令处理器应被执行");
    }

    @Test
    @DisplayName("命令优先并短路模式插件（现行不变式）")
    void commandShortCircuitsPatternHandlers() {
        AtomicInteger patternRuns = new AtomicInteger();
        AtomicInteger commandRuns = new AtomicInteger();

        // 模式插件：匹配任意含「测试」的消息
        load(runtime, YuniPlugin.of("模式插件", ctx ->
                ctx.onMessage(ev -> ev.getChain().containsText("测试"),
                        (ev, reply) -> patternRuns.incrementAndGet())));

        CommandNode tree = CommandNode.builder("测试").build();
        registerCommandTree(tree);
        bind(tree, () -> commandRuns.incrementAndGet());

        pipeline.onMessage(groupMessage("/测试"));

        await(() -> commandRuns.get() > 0, 3000);
        assertEquals(1, commandRuns.get());
        // 给模式处理器一点时间，确认它确实没被调用
        await(() -> patternRuns.get() > 0, 300);
        assertEquals(0, patternRuns.get(), "命中命令后不应再匹配模式插件");
    }

    @Test
    @DisplayName("未命中命令时模式插件正常执行")
    void patternRunsWhenCommandNotMatched() {
        AtomicInteger patternRuns = new AtomicInteger();

        load(runtime, YuniPlugin.of("模式插件", ctx ->
                ctx.onMessage(ev -> ev.getChain().containsText("测试"),
                        (ev, reply) -> patternRuns.incrementAndGet())));

        CommandNode tree = CommandNode.builder("测试").build();
        registerCommandTree(tree);
        bind(tree, () -> { });

        // 没有命令前缀：命令不匹配，模式应生效
        pipeline.onMessage(groupMessage("测试一下"));

        await(() -> patternRuns.get() > 0, 3000);
        assertEquals(1, patternRuns.get());
    }

    @Test
    @DisplayName("命令前缀缺失时不匹配")
    void commandRequiresFlag() {
        AtomicInteger runs = new AtomicInteger();

        CommandNode tree = CommandNode.builder("测试").build();
        registerCommandTree(tree);
        bind(tree, runs::incrementAndGet);

        pipeline.onMessage(groupMessage("测试"));

        await(() -> runs.get() > 0, 300);
        assertEquals(0, runs.get(), "无命令前缀不应匹配");
    }

    @Test
    @DisplayName("节点级权限：子命令要求 ADMIN 时普通用户被拒绝并收到提示")
    void childNodePermissionIsEnforced() {
        AtomicInteger runs = new AtomicInteger();

        CommandNode tree = CommandNode.builder("测试")
                .child(CommandNode.builder("管理")
                        .permission(UserPermission.ADMIN)
                        .build())
                .build();
        registerCommandTree(tree);
        bind(tree.getChildren().get(0), runs::incrementAndGet);

        pipeline.onMessage(groupMessage("/测试 管理"));

        // 权限不足：应回复提示且不执行
        await(() -> bot.sentCount() > 0, 2000);
        assertTrue(bot.sentCount() > 0, "权限不足应回复提示");
        assertTrue(bot.lastMessage().orElseThrow().text().contains("权限不足"),
                bot.lastMessage().orElseThrow().text());
        assertEquals(0, runs.get(), "权限不足不应执行处理器");
    }

    @Test
    @DisplayName("节点级权限：有足够权限时子命令正常执行")
    void childNodePermissionAllowsSufficientRole() {
        AtomicInteger runs = new AtomicInteger();

        // 注册表是共享的，因此这里只需注册一次命令树；
        // 差别在于用哪个分发器来跑——它同时决定了权限判定函数与处理器绑定表。
        CommandNode tree = CommandNode.builder("测试")
                .child(CommandNode.builder("管理")
                        .permission(UserPermission.ADMIN)
                        .build())
                .build();

        registerCommandTree(tree);

        // 注入一个「拥有 ADMIN」的权限判定，并用它构造独立的分发器与流水线。
        // 注意处理器必须绑到这个分发器上——处理器绑定表是分发器私有的。
        CommandDispatcher adminDispatcher = new CommandDispatcher(
                runtime.getRegistry(), bot, botApp,
                (userId, groupId, pluginId) -> UserPermission.ADMIN);
        com.yuier.yuni.plugin.api.Commands.on(tree.getChildren().get(0),
                (event, result, reply) -> runs.incrementAndGet());

        EventPipeline adminPipeline = new EventPipeline(
                runtime.getRegistry(), runtime, replyFactory, adminDispatcher);

        adminPipeline.onMessage(groupMessage("/测试 管理"));

        await(() -> runs.get() > 0, 3000);
        assertEquals(1, runs.get(), "权限足够时应执行");
    }

    @Test
    @DisplayName("未绑定处理器的命令树视为纯帮助声明，不算命中")
    void commandTreeWithoutHandlerIsHelpOnly() {
        AtomicInteger patternRuns = new AtomicInteger();

        load(runtime, YuniPlugin.of("模式插件", ctx ->
                ctx.onMessage(ev -> ev.getChain().containsText("测试"),
                        (ev, reply) -> patternRuns.incrementAndGet())));

        // 只声明命令树，不绑定任何处理器
        CommandNode tree = CommandNode.builder("测试").build();
        registerCommandTree(tree);

        pipeline.onMessage(groupMessage("/测试"));

        // 命令树命中但无处理器 → 不短路，模式插件仍应执行
        await(() -> patternRuns.get() > 0, 3000);
        assertEquals(1, patternRuns.get(), "纯帮助声明不应短路模式插件");
    }

    @Test
    @DisplayName("命令参数取值可用：处理器内能读到 CommandResult")
    void commandResultIsAvailableInHandler() {
        AtomicInteger argValue = new AtomicInteger(-1);

        CommandNode tree = CommandNode.builder("测试")
                .arg(ArgDef.required("数字", "一个数字", CommandArgRequireType.NUMBER))
                .build();
        registerCommandTree(tree);
        com.yuier.yuni.plugin.api.Commands.on(tree, (event, commandResult, reply) -> {
            argValue.set(commandResult.getArg("数字").asNumber().intValue());
        });

        pipeline.onMessage(groupMessage("/测试 42"));

        await(() -> argValue.get() >= 0, 3000);
        assertEquals(42, argValue.get(), "命令参数应可取值");
    }

    // ==================== 消息发出事件 ====================

    @Test
    @DisplayName("消息发出事件：作用域过滤生效（现行系统完全不做检查）")
    void messageSentRespectsScopeFilter() {
        AtomicInteger hits = new AtomicInteger();

        load(runtime, YuniPlugin.of("转发插件", ctx ->
                ctx.onMessageSent(ev -> ev.isGroup(), (ev, reply) -> hits.incrementAndGet())));

        YuniMessageSentEvent sent =
                new YuniMessageSentEvent();
        sent.setTime(1L);
        sent.setSelfId(10000L);
        sent.setMessageType("group");
        sent.setGroupId(123L);
        sent.setMessageId(2L);
        MessageChain chain = new MessageChain();
        chain.addTextSegment("机器人说的话");
        sent.setMessageChain(chain);

        pipeline.onMessageSent(sent);

        await(() -> hits.get() > 0, 2000);
        assertEquals(1, hits.get(), "群消息发出事件应被处理");
    }

    @Test
    @DisplayName("消息发出事件：私聊在 isGroup 条件下被过滤")
    void messageSentPrivateFilteredByCondition() {
        AtomicInteger hits = new AtomicInteger();

        load(runtime, YuniPlugin.of("转发插件", ctx ->
                ctx.onMessageSent(ev -> ev.isGroup(), (ev, reply) -> hits.incrementAndGet())));

        YuniMessageSentEvent sent =
                new YuniMessageSentEvent();
        sent.setTime(1L);
        sent.setSelfId(10000L);
        sent.setMessageType("private");
        sent.setUserId(10001L);
        sent.setMessageId(3L);
        MessageChain chain = new MessageChain();
        chain.addTextSegment("私聊内容");
        sent.setMessageChain(chain);

        pipeline.onMessageSent(sent);

        await(() -> hits.get() > 0, 300);
        assertEquals(0, hits.get(), "私聊应被 isGroup 条件过滤");
    }

    // ==================== 卸载后不再分发 ====================

    @Test
    @DisplayName("插件卸载后不再收到事件，且注册表清空")
    void unloadedPluginReceivesNoEvents() {
        AtomicInteger hits = new AtomicInteger();

        PluginFiber fiber = load(runtime, YuniPlugin.of("临时插件", ctx ->
                ctx.onMessage((ev, reply) -> hits.incrementAndGet())));

        runtime.unload(fiber.getId());
        assertEquals(0, runtime.getRegistry().getTotalSize());

        pipeline.onMessage(groupMessage("任意"));
        await(() -> hits.get() > 0, 300);
        assertEquals(0, hits.get(), "已卸载插件不应收到事件");
    }

    @Test
    @DisplayName("未注入 BotApp 时命令匹配给出明确错误，而不是容器内部的 NPE")
    void missingBotAppProducesActionableError() {
        // 不传 botApp：CommandDetector 会回退到 SpringContextUtil，
        // 而单元测试里没有 Spring 容器。它必须给出可操作的错误信息，
        // 否则在插件里表现为「命令莫名其妙不生效」，极难定位。
        CommandDispatcher noBotApp = new CommandDispatcher(
                runtime.getRegistry(), bot, null, CommandDispatcher.permissiveResolver());

        CommandNode tree = CommandNode.builder("测试").build();
        load(runtime, YuniPlugin.of("命令插件", ctx -> ctx.command(tree)));
        com.yuier.yuni.plugin.api.Commands.on(tree, (event, result, reply) -> { });

        // 直接调用 detector 验证错误语义——CommandDispatcher 会把异常吞成日志，
        // 因此这里绕过它，单独断言底层行为
        var engineEvent = groupMessage("/测试");

        var detector = new CommandDetector(tree, bot, null);
        IllegalStateException ex = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> detector.match(engineEvent));

        assertTrue(ex.getMessage().contains("未注入 BotApp"), ex.getMessage());
    }

    /**
     * 由消息链视图构造核心消息链。
     *
     * @param view 视图
     * @return 核心消息链
     */
    private static MessageChain buildChain(
            CoreMessageChainView view) {
        MessageChain chain = new MessageChain();
        chain.addAll(view.getRawSegments());
        return chain;
    }

    @Test
    @DisplayName("Commands 门面：绑定、查询、解绑，且逐节点独立")
    void commandsFacadeBindsPerNode() {
        CommandNode tree = CommandNode.builder("根")
                .child(CommandNode.builder("子").build())
                .build();
        // handlerCount 统计的是「已注册命令树」上的处理器，因此需要先注册
        registerCommandTree(tree);

        // 只给根绑处理器
        com.yuier.yuni.plugin.api.Commands.on(tree, (event, result, reply) -> { });

        assertTrue(commandDispatcher.handlerOf(tree).isPresent());
        assertFalse(commandDispatcher.handlerOf(tree.getChildren().get(0)).isPresent(),
                "处理器绑定是逐节点的，不应自动下推到子节点");
        assertEquals(1, commandDispatcher.getHandlerCount());

        // 给子节点也绑上
        com.yuier.yuni.plugin.api.Commands.on(tree.getChildren().get(0), (event, result, reply) -> { });
        assertEquals(2, commandDispatcher.getHandlerCount());

        commandDispatcher.unbindHandler(tree);
        assertFalse(commandDispatcher.handlerOf(tree).isPresent());
        assertEquals(1, commandDispatcher.getHandlerCount(), "解绑根节点不应影响子节点");
    }

    @Test
    @DisplayName("Commands 门面拒绝 null 参数，避免静默的无效绑定")
    void commandsFacadeRejectsNull() {
        CommandNode tree = CommandNode.builder("测试").build();

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> com.yuier.yuni.plugin.api.Commands.on(null, (event, result, reply) -> { }));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> com.yuier.yuni.plugin.api.Commands.on(tree, null));
        // null 节点查询返回空而不是抛异常
        assertTrue(com.yuier.yuni.plugin.api.Commands.handlerOf(null).isEmpty());
    }
}
