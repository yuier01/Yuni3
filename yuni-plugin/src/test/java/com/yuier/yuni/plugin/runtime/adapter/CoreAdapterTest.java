package com.yuier.yuni.plugin.runtime.adapter;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.yuier.yuni.core.bot.JsonCodec;
import com.yuier.yuni.core.bot.MessageTarget;
import com.yuier.yuni.core.constants.MessageSegmentTypes;
import com.yuier.yuni.core.model.message.MessageChain;
import com.yuier.yuni.core.model.message.segment.AtSegment;
import com.yuier.yuni.core.model.message.segment.ImageSegment;
import com.yuier.yuni.core.model.message.segment.TextSegment;
import com.yuier.yuni.core.util.PolymorphicRegistrationProcessor;
import com.yuier.yuni.plugin.api.Reply;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 适配器层的单元测试——覆盖 {@link CoreMessageChainView} 与 {@link CoreReply}。
 *
 * <h2>为什么这部分值得单独测</h2>
 * <p>适配器是「插件侧只读视图」与「框架内部可变模型」之间的唯一桥梁，
 * 且它承担了若干<b>行为修正</b>（空段防御、引用段延迟插入、段 JSON 暴露）。
 * 这些修正如果回归，插件会在运行期才暴露问题。</p>
 *
 * <p>同时这些测试也验证了新设计的核心收益之一：<b>插件逻辑现在可以在不起
 * Spring 容器的情况下被测试</b>——通过 {@link RecordingYuniBot} 替身即可。</p>
 */
class CoreAdapterTest {

    private RecordingYuniBot bot;
    private JsonCodec jsonCodec;

    @BeforeEach
    void setUp() {
        bot = new RecordingYuniBot();

        // 构造与生产环境等价的 ObjectMapper：多态子类型必须注册，
        // 否则 MessageSegment 的反序列化会失败
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        new PolymorphicRegistrationProcessor().applyTo(mapper);
        jsonCodec = new JsonCodec(mapper);
    }

    /**
     * 构造一个测试用消息链。
     *
     * @param segments 消息段
     * @return 消息链
     */
    private static MessageChain chainOf(com.yuier.yuni.core.model.message.MessageSegment... segments) {
        MessageChain chain = new MessageChain();
        for (com.yuier.yuni.core.model.message.MessageSegment segment : segments) {
            chain.addSegment(segment);
        }
        return chain;
    }

    // ==================== CoreMessageChainView ====================

    @Test
    @DisplayName("plainText 只拼接文本段，跳过非文本段")
    void plainTextSkipsNonTextSegments() {
        MessageChain chain = chainOf(
                new TextSegment("你好"),
                new AtSegment("10001"),
                new TextSegment("，今天"),
                new ImageSegment().setFile("http://example.com/a.png"),
                new TextSegment("怎么样"));

        CoreMessageChainView view = new CoreMessageChainView(chain, jsonCodec);

        assertEquals("你好，今天怎么样", view.plainText());
    }

    @Test
    @DisplayName("纯文本判定：含非文本段即为 false，空链视为纯文本")
    void isPlainTextReflectsSegmentComposition() {
        assertTrue(new CoreMessageChainView(chainOf(new TextSegment("a"), new TextSegment("b")), jsonCodec)
                .isPlainText());
        // 空链：与现行 MessageChain.isPlainText 行为一致
        assertTrue(new CoreMessageChainView(new MessageChain(), jsonCodec).isPlainText());
        // 含 At 段
        assertFalse(new CoreMessageChainView(chainOf(new TextSegment("a"), new AtSegment("1")), jsonCodec)
                .isPlainText());
    }

    @Test
    @DisplayName("startsWithText：首段必须是非空文本")
    void startsWithTextRequiresNonBlankLeadingText() {
        assertTrue(new CoreMessageChainView(chainOf(new TextSegment("/帮助")), jsonCodec).startsWithText());
        // 首段是空白：命令头取不到有效内容
        assertFalse(new CoreMessageChainView(chainOf(new TextSegment("   ")), jsonCodec).startsWithText());
        // 首段是图片
        assertFalse(new CoreMessageChainView(chainOf(new ImageSegment().setFile("x")), jsonCodec).startsWithText());
        // 空链
        assertFalse(new CoreMessageChainView(new MessageChain(), jsonCodec).startsWithText());
    }

    @Test
    @DisplayName("contains / containsText 行为正确")
    void containsVariants() {
        CoreMessageChainView view = new CoreMessageChainView(
                chainOf(new TextSegment("granbluefantasy.jp"), new ImageSegment().setFile("x")), jsonCodec);

        assertTrue(view.contains(MessageSegmentTypes.TEXT));
        assertTrue(view.contains(MessageSegmentTypes.IMAGE));
        assertFalse(view.contains(MessageSegmentTypes.VIDEO));
        assertFalse(view.contains(null));

        assertTrue(view.containsText("granblue"));
        assertFalse(view.containsText("kancolle"));
        // null / 空串防御
        assertFalse(view.containsText(null));
        assertFalse(view.containsText(""));
    }

    @Test
    @DisplayName("segmentTypes 与 size 反映链结构")
    void segmentTypesReflectChain() {
        CoreMessageChainView view = new CoreMessageChainView(
                chainOf(new TextSegment("a"), new AtSegment("1"), new ImageSegment().setFile("x")), jsonCodec);

        assertEquals(3, view.size());
        assertEquals(List.of("text", "at", "image"), view.segmentTypes());
    }

    @Test
    @DisplayName("segmentJson 暴露消息段的 JSON，而非框架内部对象")
    void segmentJsonExposesSerializedSegment() {
        CoreMessageChainView view = new CoreMessageChainView(
                chainOf(new TextSegment("hello")), jsonCodec);

        String json = view.segmentJson(0);

        // 插件通过 JSON 读取段内容，从而不绑定到 core 的具体段类型
        assertTrue(json.contains("\"text\""), json);
        assertTrue(json.contains("hello"), json);
        assertEquals(1, view.segmentJsonList().size());
    }

    @Test
    @DisplayName("空链视图：各查询方法均安全返回")
    void emptyChainIsSafe() {
        CoreMessageChainView view = CoreMessageChainView.empty(jsonCodec);

        assertEquals("", view.plainText());
        assertEquals(0, view.size());
        assertTrue(view.isEmpty());
        assertTrue(view.segmentTypes().isEmpty());
        assertTrue(view.segmentJsonList().isEmpty());
        assertFalse(view.containsForward());
        assertFalse(view.startsWithReply());
    }

    @Test
    @DisplayName("视图对原链做快照：构造后修改原链不影响视图")
    void viewSnapshotsSourceChain() {
        MessageChain chain = chainOf(new TextSegment("原始内容"));
        CoreMessageChainView view = new CoreMessageChainView(chain, jsonCodec);

        // 构造之后追加内容
        chain.addTextSegment("追加内容");

        assertEquals("原始内容", view.plainText(), "视图内容不应随原链变化");
    }

    @Test
    @DisplayName("null 消息段被替换为占位符而不是抛 NPE")
    void nullSegmentIsPlaceholder() {
        MessageChain chain = new MessageChain();
        chain.getContent().add(null);
        chain.addTextSegment("后续");

        CoreMessageChainView view = new CoreMessageChainView(chain, jsonCodec);

        // 现行 MessageChain.toString 在若干段实现里会解引用 null 字段；
        // 视图层统一兜住，保证插件的文本匹配不会因此崩溃
        assertEquals("后续", view.plainText());
        assertEquals(2, view.size());
        assertFalse(view.isPlainText(), "含 null 段不应被判定为纯文本");
    }

    // ==================== CoreReply ====================

    @Test
    @DisplayName("构建不发送：只有 send() 才产生网络调用")
    void buildingDoesNotSend() {
        CoreReply reply = new CoreReply(bot, MessageTarget.group(123L), null, jsonCodec);

        reply.text("a").at(10001L).text("b");

        assertEquals(0, bot.sentCount(), "构建阶段不应发送");

        Reply.SentResult result = reply.send();

        assertEquals(1, bot.sentCount());
        assertTrue(result.success());
        assertEquals(1L, result.messageId());
    }

    @Test
    @DisplayName("发送内容与目标正确；@ 之后自动补空格（与现行 MessageChain.addAt 一致）")
    void sendDeliversCorrectContentAndTarget() {
        CoreReply reply = new CoreReply(bot, MessageTarget.group(456L), null, jsonCodec);

        reply.at(10001L).text("你好").image("http://example.com/a.png").send();

        RecordingYuniBot.SentMessage sent = bot.lastMessage().orElseThrow();
        assertEquals(MessageTarget.TargetType.GROUP, sent.target().getTargetType());
        assertEquals(456L, sent.target().getTargetId());
        List<String> types = sent.chain().getContent().stream()
                .map(com.yuier.yuni.core.model.message.MessageSegment::getType)
                .toList();
        // 第 3 段是 addAt 自动补的空格文本段：避免「@某人你好」被客户端
        // 渲染成「@某人的你好」。这是刻意保留的现行行为。
        assertEquals(List.of("at", "text", "text", "image"), types);

        // @ 的目标账号确实写入了消息段
        AtSegment atSegment = (AtSegment) sent.chain().getContent().get(0);
        assertEquals("10001", atSegment.getQq());

        // 自动补的空格段内容为单个空格
        TextSegment spacer = (TextSegment) sent.chain().getContent().get(1);
        assertEquals(" ", spacer.getText());
    }

    @Test
    @DisplayName("quote() 把回复段插到链首，且无论调用顺序如何")
    void quoteInsertsReplyAtFirstPosition() {
        CoreReply reply = new CoreReply(bot, MessageTarget.group(1L), 999L, jsonCodec);

        // 先追加内容，再引用——延迟插入保证回复段仍在第一位
        reply.text("内容").quote().send();

        List<String> types = bot.lastMessage().orElseThrow().chain().getContent().stream()
                .map(com.yuier.yuni.core.model.message.MessageSegment::getType)
                .toList();
        assertEquals("reply", types.get(0), "QQ 客户端要求回复段必须在第一位");
        assertEquals(List.of("reply", "text"), types);
    }

    @Test
    @DisplayName("无源消息时 quote() 退化为空操作，不抛异常（定时任务场景）")
    void quoteWithoutSourceMessageIsNoop() {
        CoreReply reply = new CoreReply(bot, MessageTarget.privateChat(1L), null, jsonCodec);

        reply.quote().text("定时播报").send();

        List<String> types = bot.lastMessage().orElseThrow().chain().getContent().stream()
                .map(com.yuier.yuni.core.model.message.MessageSegment::getType)
                .toList();
        assertEquals(List.of("text"), types);
    }

    @Test
    @DisplayName("segment() 逃生舱支持任意消息段类型（经 Jackson 多态）")
    void genericSegmentSupportsArbitraryTypes() {
        CoreReply reply = new CoreReply(bot, MessageTarget.group(1L), null, jsonCodec);

        // face 的 data 在 core 中是 private 无 setter，只能走多态反序列化路径
        reply.face(123).send();

        List<String> types = bot.lastMessage().orElseThrow().chain().getContent().stream()
                .map(com.yuier.yuni.core.model.message.MessageSegment::getType)
                .toList();
        assertEquals(List.of("face"), types);
    }

    @Test
    @DisplayName("文本为 null 时被忽略，不产生空文本段")
    void nullTextIsIgnored() {
        CoreReply reply = new CoreReply(bot, MessageTarget.group(1L), null, jsonCodec);

        reply.text(null).text("有效").send();

        List<String> types = bot.lastMessage().orElseThrow().chain().getContent().stream()
                .map(com.yuier.yuni.core.model.message.MessageSegment::getType)
                .toList();
        assertEquals(List.of("text"), types);
    }

    @Test
    @DisplayName("chain() 复用视图的原始段，避免 JSON 往返")
    void chainReusesViewSegments() {
        CoreMessageChainView source = new CoreMessageChainView(
                chainOf(new TextSegment("转发的"), new ImageSegment().setFile("x")), jsonCodec);

        CoreReply reply = new CoreReply(bot, MessageTarget.group(1L), null, jsonCodec);
        reply.chain(source).send();

        List<String> types = bot.lastMessage().orElseThrow().chain().getContent().stream()
                .map(com.yuier.yuni.core.model.message.MessageSegment::getType)
                .toList();
        assertEquals(List.of("text", "image"), types);
    }

    @Test
    @DisplayName("发送失败返回失败结果，不抛异常")
    void sendFailureReturnsFailedResult() {
        bot.failNextSend();
        CoreReply reply = new CoreReply(bot, MessageTarget.group(1L), null, jsonCodec);

        Reply.SentResult result = reply.text("会失败").send();

        assertFalse(result.success());
        assertEquals(0L, result.messageId());
        assertEquals("模拟发送失败", result.errorMessage().orElseThrow());
    }

    @Test
    @DisplayName("发送抛异常（模拟 Error）被兜住，返回失败结果而非中断插件")
    void sendThrowableIsCaught() {
        bot.throwOnNextSend();
        CoreReply reply = new CoreReply(bot, MessageTarget.group(1L), null, jsonCodec);

        // 发送失败不应把插件处理器打挂——「先回复再落库」这类顺序敏感逻辑尤其依赖这一点
        Reply.SentResult result = reply.text("会抛异常").send();

        assertFalse(result.success());
        assertTrue(result.errorMessage().orElseThrow().contains("模拟发送时的 Error"),
                result.errorMessage().orElseThrow());
    }

    @Test
    @DisplayName("sendEach 逐条发送，顺序与入参一致")
    void sendEachDeliversInOrder() {
        CoreReply reply = new CoreReply(bot, MessageTarget.group(1L), null, jsonCodec);

        List<Reply.SentResult> results = reply.sendEach(List.of(
                new CoreMessageChainView(chainOf(new TextSegment("第一条")), jsonCodec),
                new CoreMessageChainView(chainOf(new TextSegment("第二条")), jsonCodec),
                new CoreMessageChainView(chainOf(new TextSegment("第三条")), jsonCodec)));

        assertEquals(3, results.size());
        assertTrue(results.stream().allMatch(Reply.SentResult::success));
        assertEquals(3, bot.sentCount());
        assertTrue(bot.sentMessages().get(0).text().contains("第一条"));
        assertTrue(bot.sentMessages().get(2).text().contains("第三条"));
    }
}
