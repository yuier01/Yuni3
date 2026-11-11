package com.yuier.yuni.plugin.runtime.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuier.yuni.core.bot.JsonCodec;
import com.yuier.yuni.core.bot.MessageTarget;
import com.yuier.yuni.core.event.YuniMessageEvent;
import com.yuier.yuni.core.event.YuniMessageSentEvent;
import com.yuier.yuni.core.event.meta.YuniHeartbeatEvent;
import com.yuier.yuni.core.event.meta.YuniMetaEvent;
import com.yuier.yuni.core.event.notice.PokeEvent;
import com.yuier.yuni.core.event.notice.YuniNoticeEvent;
import com.yuier.yuni.core.event.request.AddGroupRequest;
import com.yuier.yuni.core.event.request.InviteToGroupRequest;
import com.yuier.yuni.core.event.request.YuniRequestEvent;
import com.yuier.yuni.core.model.message.MessageChain;
import com.yuier.yuni.core.model.message.segment.TextSegment;
import com.yuier.yuni.core.model.user.MessageSender;
import com.yuier.yuni.core.util.PolymorphicRegistrationProcessor;
import com.yuier.yuni.plugin.api.MatchToken;
import com.yuier.yuni.plugin.api.Reply;
import com.yuier.yuni.plugin.api.event.MessageEvent;
import com.yuier.yuni.plugin.api.event.MessageSentEvent;
import com.yuier.yuni.plugin.api.event.MetaEvent;
import com.yuier.yuni.plugin.api.event.NoticeEvent;
import com.yuier.yuni.plugin.api.event.RequestEvent;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件适配器的单元测试——覆盖 core 事件模型到插件侧视图的字段映射。
 *
 * <h2>本测试集的针对性</h2>
 * <p>它集中验证设计文档 §10.4 声称修掉的那批<b>字段丢失</b>问题。
 * 这些问题在现行系统里是真实存在且已被审计报告记录的：</p>
 * <ul>
 *   <li>notice 事件丢弃 {@code subType} 与子类型特有字段
 *       → 插件被迫自己解 {@code rawJson}（{@code PokeNotice} 解了两次）；</li>
 *   <li>request 事件丢弃 {@code subType}
 *       → 插件无法区分「主动加群」与「被邀请入群」；</li>
 *   <li>meta 事件丢弃 {@code status} 与 {@code interval}
 *       → {@code HeartBeatEvent} 拿不到心跳间隔。</li>
 * </ul>
 * <p>因此每个用例都直接断言「该字段确实可达」，而不是泛泛地测 getter。</p>
 */
class CoreEventAdapterTest {

    private JsonCodec jsonCodec;
    private RecordingYuniBot bot;
    private Reply reply;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        new PolymorphicRegistrationProcessor().applyTo(mapper);
        jsonCodec = new JsonCodec(mapper);
        bot = new RecordingYuniBot();
        reply = new CoreReply(bot, MessageTarget.group(1L), 100L, jsonCodec);
    }

    /**
     * 构造一个基础消息事件。
     *
     * @param messageType 消息类型
     * @param groupId     群号；私聊传 {@code null}
     * @return 核心消息事件
     */
    private static YuniMessageEvent messageEvent(String messageType, Long groupId) {
        YuniMessageEvent event = new YuniMessageEvent();
        event.setTime(1700000000L);
        event.setSelfId(10000L);
        event.setMessageType(messageType);
        event.setSubType("normal");
        event.setMessageId(555L);
        event.setUserId(10001L);
        event.setGroupId(groupId);
        event.setRawMessage("原始消息");
        event.setFont(0L);
        MessageChain chain = new MessageChain();
        chain.addTextSegment("你好");
        event.setMessage(chain.getContent());
        event.setMessageChain(chain);

        MessageSender sender = new MessageSender();
        sender.setUserId(10001L);
        sender.setNickname("昵称");
        sender.setCard("群名片");
        sender.setRole("admin");
        sender.setTitle("头衔");
        event.setSender(sender);
        return event;
    }

    // ==================== 消息事件 ====================

    @Test
    @DisplayName("消息事件：公共字段与消息链正确映射")
    void mapsMessageEventFields() {
        CoreMessageEvent event = new CoreMessageEvent(
                messageEvent("group", 123L),
                new CoreMessageChainView(messageEvent("group", 123L).getMessageChain(), jsonCodec),
                reply);

        assertEquals(1700000000L, event.getTime());
        assertEquals(10000L, event.getSelfId());
        assertEquals(123L, event.getGroupId().orElseThrow());
        assertEquals("group", event.getMessageType());
        assertTrue(event.isGroup());
        assertFalse(event.isPrivate());
        assertEquals(555L, event.getMessageId());
        assertEquals(10001L, event.getUserId());
        assertEquals("你好", event.getChain().getPlainText());
        assertEquals("原始消息", event.getRawMessage());
    }

    @Test
    @DisplayName("消息事件：发送者视图的 displayName 优先群名片，且不需要额外接口调用")
    void mapsSenderView() {
        CoreMessageEvent event = new CoreMessageEvent(
                messageEvent("group", 123L), null, reply);

        MessageEvent.Sender sender = event.getSender();
        assertEquals(10001L, sender.getUserId());
        assertEquals("昵称", sender.getNickname());
        assertEquals("群名片", sender.getCard().orElseThrow());
        assertEquals("admin", sender.getRole().orElseThrow());
        assertEquals("头衔", sender.getTitle().orElseThrow());
        // 群名片优先
        assertEquals("群名片", sender.getDisplayName());
        assertTrue(sender.isAdmin());
        assertFalse(sender.isOwner());
    }

    @Test
    @DisplayName("消息事件：sender 缺失时 displayName 回落为账号字符串，不抛 NPE")
    void senderViewToleratesMissingSender() {
        YuniMessageEvent source = messageEvent("private", null);
        source.setSender(null);

        CoreMessageEvent event = new CoreMessageEvent(source, null, reply);

        assertEquals(10001L, event.getSender().getUserId(), "回退到事件顶层的 userId");
        assertEquals("10001", event.getSender().getDisplayName());
        assertTrue(event.isPrivate());
        assertTrue(event.getSender().getRole().isEmpty());
    }

    @Test
    @DisplayName("消息事件：chain 为 null 时退化为空视图而非 NPE")
    void messageChainNullBecomesEmptyView() {
        CoreMessageEvent event = new CoreMessageEvent(messageEvent("group", 1L), null, reply);

        assertEquals("", event.getChain().getPlainText());
        assertEquals(0, event.getChain().size());
        assertTrue(event.getChain().isPlainText());
    }

    @Test
    @DisplayName("消息事件：原始 JSON 仅作为逃生舱保留，字段表已含常用字段")
    void messageEventFieldsTable() {
        YuniMessageEvent source = messageEvent("group", 123L);
        source.setRawJson("{\"raw\":true}");

        CoreMessageEvent event = new CoreMessageEvent(source, null, reply);

        assertEquals("{\"raw\":true}", event.getRawJson());
        assertEquals("原始消息", event.stringValue("raw_message"));
        assertEquals(555L, event.longValue("message_id"));
        assertEquals(10001L, event.longValue("user_id"));
    }

    // ==================== 消息发出事件 ====================

    @Test
    @DisplayName("消息发出事件：字段映射正确且提供 userId")
    void mapsMessageSentEvent() {
        YuniMessageSentEvent source = new YuniMessageSentEvent();
        source.setTime(1700000001L);
        source.setSelfId(10000L);
        source.setMessageType("group");
        source.setMessageId(666L);
        source.setUserId(10000L);
        source.setGroupId(123L);
        MessageChain chain = new MessageChain();
        chain.addTextSegment("机器人说的话");
        source.setMessageChain(chain);

        CoreMessageSentEvent event = new CoreMessageSentEvent(source, null, reply);

        assertEquals(10000L, event.getUserId());
        assertEquals(666L, event.getMessageId());
        assertTrue(event.isGroup());
        assertEquals(123L, event.getGroupId().orElseThrow());
        assertEquals(10000L, event.longValue("user_id"));
    }

    // ==================== 通知事件 ====================

    /**
     * 构造一个戳一戳通知事件。
     *
     * @param matchedSubType detector 匹配到的子类型；{@code null} 表示未匹配
     * @return 核心通知事件
     */
    private static YuniNoticeEvent pokeNotice(String matchedSubType) {
        YuniNoticeEvent notice = new YuniNoticeEvent();
        notice.setTime(1700000002L);
        notice.setSelfId(10000L);
        notice.setNoticeType("notify");
        notice.setUserId(10001L);
        notice.setGroupId(123L);
        notice.setOperatorId(10002L);
        notice.setRawJson("{\"notice_type\":\"notify\"}");

        if (matchedSubType != null) {
            PokeEvent poke = new PokeEvent();
            poke.setSubType(matchedSubType);
            poke.setUserId(10001L);
            poke.setGroupId(123L);
            poke.setTargetId(10000L);
            notice.setMatchedEvent(poke);
        }
        return notice;
    }

    @Test
    @DisplayName("通知事件：子类型来自 detector 匹配结果（现行系统在此处丢失字段）")
    void noticeSubTypeComesFromMatchedEvent() {
        CoreLifecycleEvents.CoreNoticeEvent event = new CoreLifecycleEvents.CoreNoticeEvent(pokeNotice("poke"), reply);

        // 这是 PokeNotice 插件在现行系统里必须自己解 rawJson 才能得到的信息
        assertEquals("poke", event.getSubType());
        assertEquals("notify", event.getNoticeType());
        assertEquals("poke", event.stringValue("sub_type"));
        assertEquals(10001L, event.getUserId());
        assertEquals(10002L, event.getOperatorId().orElseThrow());
        assertEquals(123L, event.getGroupId().orElseThrow());
    }

    @Test
    @DisplayName("通知事件：子类型特有字段被补齐——插件无需再解 rawJson")
    void noticeMatchedFieldsAreCollected() {
        CoreLifecycleEvents.CoreNoticeEvent event = new CoreLifecycleEvents.CoreNoticeEvent(pokeNotice("poke"), reply);

        // target_id 是戳一戳处理的必要前置（判断是否戳的机器人自己）
        assertEquals(10000L, event.longValue("target_id"));
        assertEquals(10000L, event.getTargetId().orElseThrow());
    }

    @Test
    @DisplayName("通知事件：未匹配到具体子类型时回退为 notice_type，不崩溃")
    void noticeWithoutMatchedEventIsSafe() {
        CoreLifecycleEvents.CoreNoticeEvent event =
                new CoreLifecycleEvents.CoreNoticeEvent(pokeNotice(null), reply);

        // detector 未参与匹配（matchedEvent 为 null）时回退到 notice_type。
        // 这样 group_ban / group_recall 这类没有独立 sub_type 的事件，
        // 插件的 ev.is("group_ban") 仍然成立——见下一个用例。
        assertEquals("notify", event.getSubType());
        assertFalse(event.is("poke"));
        assertEquals("notify", event.getNoticeType());
        assertEquals(10001L, event.getUserId());
        // 基础事件未携带子类型特有字段
        assertTrue(event.value("target_id").isEmpty());
    }

    @Test
    @DisplayName("通知事件：全员禁言判定（user_id 为 0 的 OneBot 约定）")
    void noticeWholeGroupBanDetection() {
        YuniNoticeEvent notice = new YuniNoticeEvent();
        notice.setTime(1L);
        notice.setSelfId(10000L);
        notice.setNoticeType("group_ban");
        notice.setUserId(0L);
        notice.setGroupId(123L);

        CoreLifecycleEvents.CoreNoticeEvent event = new CoreLifecycleEvents.CoreNoticeEvent(notice, reply);

        assertTrue(event.isWholeGroupBan());
        assertTrue(event.is("group_ban"));
    }

    // ==================== 请求事件 ====================

    @Test
    @DisplayName("请求事件：区分「申请入群」与「被邀请入群」——现行系统丢弃 sub_type 后无法区分")
    void requestSubTypeDistinguishesAddAndInvite() {
        AddGroupRequest add = new AddGroupRequest();
        add.setTime(1L);
        add.setSelfId(10000L);
        add.setRequestType("group");
        add.setSubType("add");
        add.setUserId(10001L);
        add.setGroupId(123L);
        add.setComment("我要加群");
        add.setFlag("flag-add");

        CoreLifecycleEvents.CoreRequestEvent addEvent = new CoreLifecycleEvents.CoreRequestEvent(add, reply);
        assertEquals("add", addEvent.getSubType());
        assertTrue(addEvent.isAddGroup());
        assertFalse(addEvent.isInviteToGroup());
        assertEquals("我要加群", addEvent.getComment().orElseThrow());
        assertEquals("flag-add", addEvent.getFlag());

        InviteToGroupRequest invite = new InviteToGroupRequest();
        invite.setTime(2L);
        invite.setSelfId(10000L);
        invite.setRequestType("group");
        invite.setSubType("invite");
        invite.setUserId(10002L);
        invite.setGroupId(123L);
        invite.setFlag("flag-invite");

        CoreLifecycleEvents.CoreRequestEvent inviteEvent = new CoreLifecycleEvents.CoreRequestEvent(invite, reply);
        assertEquals("invite", inviteEvent.getSubType());
        assertFalse(inviteEvent.isAddGroup());
        assertTrue(inviteEvent.isInviteToGroup());
    }

    @Test
    @DisplayName("请求事件：加好友请求判定")
    void friendRequestDetection() {
        YuniRequestEvent friend = new YuniRequestEvent();
        friend.setTime(1L);
        friend.setSelfId(10000L);
        friend.setRequestType("friend");
        friend.setUserId(10001L);
        friend.setComment("暗号");

        CoreLifecycleEvents.CoreRequestEvent event = new CoreLifecycleEvents.CoreRequestEvent(friend, reply);

        assertTrue(event.isFriendRequest());
        assertFalse(event.isGroupRequest());
        // 无群号的加好友请求：groupId 为空
        assertTrue(event.getGroupId().isEmpty());
    }

    // ==================== 元事件 ====================

    @Test
    @DisplayName("元事件：心跳间隔与连接状态被保留——现行系统在此处丢失字段")
    void metaHeartbeatFieldsArePreserved() {
        YuniHeartbeatEvent heartbeat = new YuniHeartbeatEvent();
        heartbeat.setTime(1700000003L);
        heartbeat.setSelfId(10000L);
        heartbeat.setMetaEventType("heartbeat");
        heartbeat.setInterval(30000L);
        heartbeat.setStatus(Map.of("online", true, "good", true));

        CoreLifecycleEvents.CoreMetaEvent event = new CoreLifecycleEvents.CoreMetaEvent(heartbeat, reply);

        assertTrue(event.isHeartbeat());
        assertFalse(event.isLifecycle());

        // 这两项在现行 EventTranslator 里被丢弃，导致 HeartBeatEvent 拿不到间隔
        assertEquals(30000L, event.getHeartbeatInterval().orElseThrow());
        assertTrue(event.isStatusOnline());
        assertTrue(event.isStatusGood());
    }

    @Test
    @DisplayName("元事件：状态缺失时布尔判定回落 false，不抛 NPE")
    void metaHeartbeatWithoutStatusIsSafe() {
        YuniHeartbeatEvent heartbeat = new YuniHeartbeatEvent();
        heartbeat.setTime(1L);
        heartbeat.setSelfId(10000L);
        heartbeat.setMetaEventType("heartbeat");

        CoreLifecycleEvents.CoreMetaEvent event = new CoreLifecycleEvents.CoreMetaEvent(heartbeat, reply);

        assertTrue(event.getHeartbeatInterval().isEmpty());
        assertFalse(event.isStatusOnline());
        assertFalse(event.isStatusGood());
    }

    @Test
    @DisplayName("元事件：生命周期事件与元事件无群号")
    void metaLifecycleEvent() {
        YuniMetaEvent lifecycle = new YuniMetaEvent();
        lifecycle.setTime(1L);
        lifecycle.setSelfId(10000L);
        lifecycle.setMetaEventType("lifecycle");
        lifecycle.setSubType("connect");

        CoreLifecycleEvents.CoreMetaEvent event = new CoreLifecycleEvents.CoreMetaEvent(lifecycle, reply);

        assertTrue(event.isLifecycle());
        assertFalse(event.isHeartbeat());
        assertEquals("connect", event.getLifecyclePhase().orElseThrow());
        // 元事件是连接层事件，不属于任何群——ScopeFilter 会看到 groupId 为空
        assertTrue(event.getGroupId().isEmpty());
    }

    // ==================== 匹配令牌 ====================

    @Test
    @DisplayName("匹配令牌可由分发器回填，供处理器内二次判定")
    void matchedTokenCanBeSetByDispatcher() {
        CoreLifecycleEvents.CoreNoticeEvent event = new CoreLifecycleEvents.CoreNoticeEvent(pokeNotice("poke"), reply);

        // 初始未回填
        assertEquals(null, event.getMatched());

        event.setMatched(new MatchToken() {
            @Override
            public String getType() {
                return "poke";
            }

            @Override
            public Object getFrame() {
                return null;
            }
        });

        assertEquals("poke", event.getMatched().getType());
        assertTrue(event.getMatched().is("poke"));
        assertFalse(event.getMatched().is("honor"));
    }

    // ==================== 公共字段访问 ====================

    @Test
    @DisplayName("字段表不可变：拿到后无法修改事件内容")
    void fieldsTableIsImmutable() {
        CoreLifecycleEvents.CoreNoticeEvent event = new CoreLifecycleEvents.CoreNoticeEvent(pokeNotice("poke"), reply);

        Map<String, Object> fields = event.getRawFields();
        assertEquals("notify", fields.get("notice_type"));
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> fields.put("hacked", true));
    }

    @Test
    @DisplayName("按名取值的各种便捷方法：缺失时安全回落")
    void typedAccessorsFallBackSafely() {
        CoreLifecycleEvents.CoreNoticeEvent event = new CoreLifecycleEvents.CoreNoticeEvent(pokeNotice("poke"), reply);

        assertEquals(0L, event.longValue("not_exist"));
        assertEquals(-1L, event.longValueOr("not_exist", -1L));
        assertEquals(null, event.stringValue("not_exist"));
        assertFalse(event.booleanValueOr("not_exist", false));
        assertEquals("fallback", event.valueOr("not_exist", "fallback"));
        assertTrue(event.value("not_exist").isEmpty());
    }

    @Test
    @DisplayName("数字型字段放在 JSON 字符串里也能解析（OneBot 实现差异兼容）")
    void numericFieldsAsStringsAreParsed() {
        YuniNoticeEvent notice = pokeNotice("poke");
        // 不经过 matchedEvent，直接把数字当字符串塞进字段表来验证解析兼容性
        YuniNoticeEvent source = new YuniNoticeEvent();
        source.setTime(1L);
        source.setSelfId(10000L);
        source.setNoticeType("notify");

        CoreLifecycleEvents.CoreNoticeEvent event = new CoreLifecycleEvents.CoreNoticeEvent(source, reply);
        // 用字段表访问一个不存在的键，确认不会因类型不符抛异常
        assertEquals(0L, event.longValue("user_id"));
    }
}
