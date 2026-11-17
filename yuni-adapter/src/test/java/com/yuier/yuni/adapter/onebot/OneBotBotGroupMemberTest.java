package com.yuier.yuni.adapter.onebot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuier.yuni.adapter.config.OneBotProperties;
import com.yuier.yuni.adapter.onebot.transport.OneBotTransport;
import com.yuier.yuni.core.bot.BotGroupMemberInfo;
import com.yuier.yuni.core.bot.JsonCodec;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OneBotBotGroupMemberTest {

    private static final String PLACEHOLDER_RESPONSE = """
            {"status":"ok","retcode":0,"data":{
              "group_id":876900675,"user_id":10001,"nickname":"","card":"",
              "sex":"unknown","age":0,"join_time":0,"last_sent_time":0,
              "level":"0","role":"member","title":""}}
            """;

    private static final String MEMBER_RESPONSE = """
            {"status":"ok","retcode":0,"data":{
              "group_id":876900675,"user_id":2937818202,"nickname":"yuier","card":"",
              "sex":"unknown","age":0,"join_time":1648967096,"last_sent_time":1794856759,
              "level":"24","role":"owner","title":""}}
            """;

    private static final String MEMBER_WITH_BLANK_NICKNAME_RESPONSE = """
            {"status":"ok","retcode":0,"data":{
              "group_id":876900675,"user_id":2937818202,"nickname":"","card":"",
              "join_time":1648967096,"role":"member","title":""}}
            """;

    @Test
    void returnsEmptyForNonMemberPlaceholder() {
        OneBotBot bot = botReturning(PLACEHOLDER_RESPONSE);

        Optional<BotGroupMemberInfo> member = bot.getGroupMemberInfo("876900675", "10001");

        assertTrue(member.isEmpty());
    }

    @Test
    void returnsRealMemberInfo() {
        OneBotBot bot = botReturning(MEMBER_RESPONSE);

        BotGroupMemberInfo member = bot.getGroupMemberInfo("876900675", "2937818202").orElseThrow();

        assertEquals(2937818202L, member.getUserId());
        assertEquals("yuier", member.getNickname());
        assertEquals("owner", member.getRole());
    }

    @Test
    void acceptsMemberWithJoinTimeButBlankNickname() {
        OneBotBot bot = botReturning(MEMBER_WITH_BLANK_NICKNAME_RESPONSE);

        BotGroupMemberInfo member = bot.getGroupMemberInfo("876900675", "2937818202").orElseThrow();

        assertEquals(2937818202L, member.getUserId());
        assertTrue(member.getNickname().isEmpty());
    }

    private static OneBotBot botReturning(String responseJson) {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonCodec jsonCodec = new JsonCodec(objectMapper);
        OneBotProperties properties = new OneBotProperties();
        properties.getRetry().setMaxRetries(0);
        return new OneBotBot(
                properties,
                new OneBotProtocolHandler(objectMapper, jsonCodec),
                new StubTransport(responseJson),
                jsonCodec,
                "3557522925");
    }

    private static final class StubTransport implements OneBotTransport {

        private final String responseJson;

        private StubTransport(String responseJson) {
            this.responseJson = responseJson;
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
        public String sendApiRequest(String action, Map<String, Object> params) {
            return responseJson;
        }

        @Override
        public void setEventCallback(Consumer<String> callback) {
        }

        @Override
        public boolean isConnected() {
            return true;
        }
    }
}
