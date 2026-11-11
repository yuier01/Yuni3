package com.yuier.yuni.plugin.runtime.adapter;

import com.yuier.yuni.core.bot.BotGroupInfo;
import com.yuier.yuni.core.bot.BotGroupMemberInfo;
import com.yuier.yuni.core.bot.BotMessageInfo;
import com.yuier.yuni.core.bot.BotStatus;
import com.yuier.yuni.core.bot.BotUserInfo;
import com.yuier.yuni.core.bot.MessageSentResult;
import com.yuier.yuni.core.bot.MessageTarget;
import com.yuier.yuni.core.bot.YuniBot;
import com.yuier.yuni.core.model.message.MessageChain;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 测试用的 {@link YuniBot} 替身。
 *
 * <p>记录全部发出的消息，供断言检查「插件到底发了什么」。这是新设计
 * 「可打桩」这一性质的直接体现——现行系统里插件通过静态方法
 * {@code PluginUtils.getYuniBot()} 取机器人实例，无法在单元测试中替换，
 * 因此任何涉及发消息的插件逻辑都必须起整个 Spring 容器才能测。</p>
 *
 * <p>不支持的操作返回空值或抛出明确异常，避免测试里静默通过。</p>
 */
public final class RecordingYuniBot implements YuniBot {

    /** 已发送的消息记录。 */
    @Getter
    public static final class SentMessage {

        /** 发送目标。 */
        private final MessageTarget target;

        /** 消息链。 */
        private final MessageChain chain;

        /**
         * 构造记录。
         *
         * @param target 发送目标
         * @param chain  消息链
         */
        public SentMessage(MessageTarget target, MessageChain chain) {
            this.target = target;
            this.chain = chain;
        }

        /**
         * 消息链的纯文本内容。
         *
         * @return 拼接后的文本
         */
        public String text() {
            return chain == null ? "" : chain.toString();
        }
    }

    /** 全部已发送的消息，按发送顺序。 */
    private final List<SentMessage> sent = new CopyOnWriteArrayList<>();

    /** 消息 ID 生成器。 */
    private final AtomicLong messageIdSeq = new AtomicLong(1);

    /** 是否让发送失败，用于测试失败路径。 */
    private boolean failNextSend;

    /** 是否让发送抛异常（模拟 Error），用于测试兜底。 */
    private boolean throwOnNextSend;

    /** 群成员角色；{@code null} 表示 {@code getGroupMemberInfo} 返回空。 */
    private String groupMemberRole;

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
        if (throwOnNextSend) {
            throwOnNextSend = false;
            throw new IllegalStateException("模拟发送时的 Error");
        }
        if (failNextSend) {
            failNextSend = false;
            return MessageSentResult.fail("模拟发送失败");
        }
        sent.add(new SentMessage(target, message));
        return MessageSentResult.ok(String.valueOf(messageIdSeq.getAndIncrement()));
    }

    @Override
    public void recallMessage(String messageId) {
        // 测试中不关心
    }

    @Override
    public Optional<List<BotGroupInfo>> getGroupList() {
        return Optional.of(List.of());
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

    /**
     * 配置群成员角色；配置后 {@link #getGroupMemberInfo} 会返回该角色的成员。
     *
     * <p>用于验证「权限服务按群角色映射权限」的路径。{@code null} 表示保持
     * 默认行为（返回空），例如「bot 尚未就绪」或协议不提供成员信息。</p>
     *
     * @param role 角色字符串（{@code owner} / {@code admin} / {@code member}）
     */
    public void setGroupMemberRole(String role) {
        this.groupMemberRole = role;
    }

    @Override
    public Optional<BotGroupMemberInfo> getGroupMemberInfo(String groupId, String userId) {
        if (groupMemberRole == null) {
            return Optional.empty();
        }
        BotGroupMemberInfo info = new BotGroupMemberInfo();
        info.setGroupId(groupId == null ? null : Long.valueOf(groupId));
        info.setUserId(userId == null ? null : Long.valueOf(userId));
        info.setRole(groupMemberRole);
        return Optional.of(info);
    }

    @Override
    public Optional<BotGroupMemberInfo> getGroupMemberInfo(String groupId, String userId, boolean noCache) {
        return getGroupMemberInfo(groupId, userId);
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
        // 测试中不关心
    }

    @Override
    public void banMember(String groupId, String userId, long durationSeconds) {
        // 测试中不关心
    }

    // ==================== 测试辅助 ====================

    /**
     * 已发送的全部消息。
     *
     * @return 不可变快照
     */
    public List<SentMessage> sentMessages() {
        return new ArrayList<>(sent);
    }

    /**
     * 最近一条已发送的消息。
     *
     * @return 消息记录；尚未发送过时为空
     */
    public Optional<SentMessage> lastMessage() {
        return sent.isEmpty() ? Optional.empty() : Optional.of(sent.get(sent.size() - 1));
    }

    /**
     * 已发送的消息条数。
     *
     * @return 条数
     */
    public int sentCount() {
        return sent.size();
    }

    /**
     * 清空发送记录。
     */
    public void clear() {
        sent.clear();
    }

    /**
     * 让下一次发送返回失败。
     */
    public void failNextSend() {
        this.failNextSend = true;
    }

    /**
     * 让下一次发送抛出异常。
     */
    public void throwOnNextSend() {
        this.throwOnNextSend = true;
    }
}
