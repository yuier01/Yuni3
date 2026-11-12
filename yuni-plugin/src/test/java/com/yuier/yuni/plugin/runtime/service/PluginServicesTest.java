package com.yuier.yuni.plugin.runtime.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.yuier.yuni.core.bot.JsonCodec;
import com.yuier.yuni.core.bot.MessageTarget;
import com.yuier.yuni.core.model.message.MessageChain;
import com.yuier.yuni.core.util.PolymorphicRegistrationProcessor;
import com.yuier.yuni.plugin.api.Reply;
import com.yuier.yuni.plugin.api.service.ConfigLoader;
import com.yuier.yuni.plugin.runtime.adapter.CoreMessageChainView;
import com.yuier.yuni.plugin.runtime.adapter.RecordingYuniBot;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FileConfigLoader} 与 {@link CoreMessageSender} 的单元测试。
 *
 * <p>这两个实现覆盖了插件最常用的两类基础设施：读自身资源文件、主动发送消息。
 * 尤其是 {@link FileConfigLoader#resolve(String)} 的路径越界防护——
 * 现行 {@code PluginUtils} 用 {@code Paths.get(root, resourcePath)} 直接拼接，
 * 传入含 {@code ..} 的路径即可读到插件目录之外的任意文件。</p>
 */
class PluginServicesTest {

    private JsonCodec jsonCodec;

    @TempDir
    Path pluginDir;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        new PolymorphicRegistrationProcessor().applyTo(mapper);
        jsonCodec = new JsonCodec(mapper);
    }

    /**
     * 在插件目录下写一个文件。
     *
     * @param relative 相对路径
     * @param content  内容
     * @throws Exception 写入失败
     */
    private void write(String relative, String content) throws Exception {
        Path target = pluginDir.resolve(relative);
        Files.createDirectories(target.getParent() == null ? pluginDir : target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    // ==================== FileConfigLoader ====================

    /** 用于测试反序列化的简单配置模型。 */
    public static class TestConfig {
        /** 服务地址。 */
        public String serverUrl;
        /** 端口。 */
        public int port;
    }

    @Test
    @DisplayName("load：读取并反序列化 JSON 配置")
    void loadsJsonConfig() throws Exception {
        write("config.json", """
                {"server_url": "ws://example.com", "port": 8080}
                """);

        ConfigLoader loader = new FileConfigLoader(pluginDir, jsonCodec);
        TestConfig config = loader.load("config.json", TestConfig.class);

        assertEquals("ws://example.com", config.serverUrl);
        assertEquals(8080, config.port);
    }

    @Test
    @DisplayName("loadText：读取文本资源")
    void loadsTextResource() throws Exception {
        write("help.txt", "这是帮助信息");

        ConfigLoader loader = new FileConfigLoader(pluginDir, jsonCodec);

        assertEquals("这是帮助信息", loader.loadText("help.txt"));
        assertTrue(loader.exists("help.txt"));
        assertFalse(loader.exists("missing.txt"));
    }

    @Test
    @DisplayName("open：以流方式读取二进制资源（字体、图片）")
    void opensBinaryResource() throws Exception {
        write("font.bin", "binary-content");

        ConfigLoader loader = new FileConfigLoader(pluginDir, jsonCodec);
        try (InputStream in = loader.open("font.bin")) {
            assertEquals("binary-content", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("配置缺失时抛明确异常，而不是返回 null 导致后续 NPE")
    void missingFileFailsLoudly() {
        ConfigLoader loader = new FileConfigLoader(pluginDir, jsonCodec);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> loader.load("nope.json", TestConfig.class));

        // 错误信息要带上路径与插件目录，便于定位部署问题
        assertTrue(ex.getMessage().contains("nope.json"), ex.getMessage());
        assertTrue(ex.getMessage().contains(pluginDir.toString()), ex.getMessage());
    }

    @Test
    @DisplayName("loadOrDefault：配置缺失时降级为默认值")
    void loadOrDefaultDegradesGracefully() {
        ConfigLoader loader = new FileConfigLoader(pluginDir, jsonCodec);
        TestConfig fallback = new TestConfig();
        fallback.port = 1;

        TestConfig result = loader.loadOrDefault("missing.json", TestConfig.class, fallback);

        assertEquals(1, result.port);
    }

    @Test
    @DisplayName("JSON 格式错误时抛明确异常并带上目标类型")
    void malformedJsonFailsLoudly() throws Exception {
        write("bad.json", "{ 这不是 JSON");

        ConfigLoader loader = new FileConfigLoader(pluginDir, jsonCodec);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> loader.load("bad.json", TestConfig.class));

        assertTrue(ex.getMessage().contains("JSON 格式错误"), ex.getMessage());
    }

    @Test
    @DisplayName("路径越界（..）被拒绝——现行 PluginUtils 直接拼接路径时无此防护")
    void rejectsPathTraversal() {
        ConfigLoader loader = new FileConfigLoader(pluginDir, jsonCodec);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> loader.resolve("../../etc/passwd"));

        assertTrue(ex.getMessage().contains("越界"), ex.getMessage());
        // 绝对路径同样越界
        assertThrows(IllegalArgumentException.class, () -> loader.resolve("/etc/passwd"));
    }

    @Test
    @DisplayName("子目录内的资源可正常读取")
    void allowsNestedResourcePaths() throws Exception {
        write("font/test.txt", "nested");

        ConfigLoader loader = new FileConfigLoader(pluginDir, jsonCodec);

        assertEquals("nested", loader.loadText("font/test.txt"));
    }

    @Test
    @DisplayName("dataDir 暴露插件资源目录")
    void exposesDataDir() {
        ConfigLoader loader = new FileConfigLoader(pluginDir, jsonCodec);
        assertEquals(pluginDir, loader.getDataDir());
    }

    // ==================== CoreMessageSender ====================

    @Test
    @DisplayName("group / privateChat 构造正确的发送目标")
    void senderBuildsCorrectTargets() {
        RecordingYuniBot bot = new RecordingYuniBot();
        CoreMessageSender sender = new CoreMessageSender(bot, jsonCodec);

        sender.group(123L).text("群消息").send();
        sender.privateChat(456L).text("私聊消息").send();

        List<RecordingYuniBot.SentMessage> sent = bot.sentMessages();
        assertEquals(2, sent.size());
        assertEquals(MessageTarget.TargetType.GROUP, sent.get(0).getTarget().getTargetType());
        assertEquals(123L, sent.get(0).getTarget().getTargetId());
        assertEquals(MessageTarget.TargetType.PRIVATE, sent.get(1).getTarget().getTargetType());
        assertEquals(456L, sent.get(1).getTarget().getTargetId());
    }

    @Test
    @DisplayName("sendToGroup 直接转发已有消息链（消息中转场景）")
    void sendToGroupForwardsChain() {
        RecordingYuniBot bot = new RecordingYuniBot();
        CoreMessageSender sender = new CoreMessageSender(bot, jsonCodec);

        MessageChain chain =
                new MessageChain();
        chain.addTextSegment("转发的内容");

        Reply.SentResult result = sender.sendToGroup(
                789L, new CoreMessageChainView(chain, jsonCodec));

        assertTrue(result.isSuccess());
        assertEquals(1, bot.sentCount());
        assertTrue(bot.lastMessage().orElseThrow().text().contains("转发的内容"));
    }

    @Test
    @DisplayName("recall 失败时返回 false 而不抛异常（撤回超时是正常业务失败）")
    void recallFailureReturnsFalse() {
        RecordingYuniBot bot = new RecordingYuniBot();
        CoreMessageSender sender = new CoreMessageSender(bot, jsonCodec);

        // RecordingYuniBot 的 recallMessage 不抛异常，因此这里为 true
        assertTrue(sender.recall(1L));
    }

    @Test
    @DisplayName("wrap 把核心消息链包装为插件侧视图")
    void wrapsChainIntoView() {
        RecordingYuniBot bot = new RecordingYuniBot();
        CoreMessageSender sender = new CoreMessageSender(bot, jsonCodec);

        MessageChain chain =
                new MessageChain();
        chain.addTextSegment("hello");

        assertEquals("hello", sender.wrap(chain).getPlainText());
    }
}
