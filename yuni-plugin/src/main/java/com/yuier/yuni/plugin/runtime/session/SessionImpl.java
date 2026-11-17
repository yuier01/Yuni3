package com.yuier.yuni.plugin.runtime.session;

import com.yuier.yuni.core.event.YuniMessageEvent;
import com.yuier.yuni.plugin.api.Reply;
import com.yuier.yuni.plugin.api.session.SessionAction;
import com.yuier.yuni.plugin.api.session.SessionCloseEvent;
import com.yuier.yuni.plugin.api.session.SessionCloseReason;
import com.yuier.yuni.plugin.api.session.SessionCommand;
import com.yuier.yuni.plugin.api.session.SessionContext;
import com.yuier.yuni.plugin.api.session.SessionHandle;
import com.yuier.yuni.plugin.api.session.SessionHandler;
import com.yuier.yuni.plugin.api.session.SessionSpec;
import com.yuier.yuni.plugin.api.session.SessionTarget;
import com.yuier.yuni.plugin.runtime.PluginFiber;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 会话实现——同时实现 {@link SessionContext}，对插件暴露为轻量 {@link SessionHandle}。
 */
final class SessionImpl implements SessionContext {

    private static final Logger log = LoggerFactory.getLogger(SessionImpl.class);

    private enum State {
        ACTIVE,
        CLOSING,
        CLOSED
    }

    private final long id;
    private volatile PluginFiber owner;
    private final String pluginId;
    private final SessionTarget target;
    private final long openerUserId;
    private final String tag;
    private final Instant createdAt;
    private final Instant expiresAt;
    private final long createdNanos;
    private final SessionManager manager;
    private final SessionClock clock;
    private final SessionMailbox mailbox;
    private final SessionHandle handle = new Handle();
    private final Map<Class<?>, Object> states = new ConcurrentHashMap<>();
    private final Set<Long> participants = ConcurrentHashMap.newKeySet();
    private final AtomicInteger turnCount = new AtomicInteger();
    private final AtomicInteger consecutiveErrors = new AtomicInteger();
    private final AtomicReference<State> state = new AtomicReference<>(State.ACTIVE);
    private final CompletableFuture<Void> closed = new CompletableFuture<>();

    private volatile SessionSpec spec;
    private volatile SessionHandler handler;
    private volatile Instant lastActiveAt;
    private volatile long lastActiveNanos;
    private volatile SessionCloseReason closeReason;

    SessionImpl(long id,
                PluginFiber owner,
                SessionSpec spec,
                SessionHandler handler,
                SessionManager manager,
                SessionClock clock,
                Executor executor) {
        this.id = id;
        this.owner = Objects.requireNonNull(owner, "owner");
        this.pluginId = owner.getMetadata().getId();
        this.spec = Objects.requireNonNull(spec, "spec");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.manager = Objects.requireNonNull(manager, "manager");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.target = spec.getTarget();
        this.openerUserId = spec.getOpener();
        this.tag = spec.getTag();
        this.createdAt = clock.now();
        this.expiresAt = createdAt.plus(spec.getMaxDuration());
        this.createdNanos = clock.nanoTime();
        this.lastActiveAt = createdAt;
        this.lastActiveNanos = createdNanos;
        if (target.isGroupParticipants()) {
            participants.add(openerUserId);
        }
        this.mailbox = new SessionMailbox(this, executor, spec.getMaxPendingMessages());
    }

    // ==================== 核心执行 ====================

    void execute(Turn turn) {
        if (state.get() != State.ACTIVE) {
            return;
        }
        SessionSpec current = spec;
        if (current == null) {
            return;
        }
        YuniMessageEvent event = turn.event();
        Reply reply = turn.reply();
        turnCount.incrementAndGet();
        touch();

        try {
            Long userId = event.getUserId();
            boolean partial = target.isGroupParticipants();

            if (partial && (userId == null || !participants.contains(userId))) {
                if (matchesJoin(current, event)) {
                    handleJoin(current, userId, reply);
                }
                return;
            }

            if (current.isExitEnabled() && matchesExit(current, event)) {
                handleExit(current, userId, reply);
                return;
            }

            // 参与者重复发送加入指令：只提示，不重复加入
            if (partial && matchesJoin(current, event)) {
                send(reply, "你已在会话中。");
                return;
            }

            for (SessionCommand command : current.getCommands()) {
                if (command.matches(event)) {
                    SessionAction action = command.handle(this, event, reply);
                    finishAction(current, action);
                    return;
                }
            }

            SessionHandler currentHandler = handler;
            if (currentHandler == null) {
                return;
            }
            SessionAction action = currentHandler.handle(this, event, reply);
            finishAction(current, action);
        } catch (Throwable t) {
            onError(t, reply);
        }
    }

    private void handleJoin(SessionSpec current, Long userId, Reply reply) {
        if (userId == null || userId <= 0) {
            return;
        }
        SessionManager.JoinStatus status = manager.join(this, userId);
        switch (status) {
            case JOINED -> {
                try {
                    current.getOnParticipantJoin().accept(this, userId);
                } catch (Throwable t) {
                    log.error("会话 {} 的 onParticipantJoin 回调失败", id, t);
                }
                if (turnCount.get() >= current.getMaxTurns()) {
                    close(SessionCloseReason.TURN_LIMIT);
                }
            }
            case ALREADY -> send(reply, "你已在会话中。");
            case FULL -> send(reply, "人数已满。");
            case OCCUPIED -> send(reply, "你已在其他会话中，不能同时加入两个通道。");
            case CLOSED -> {
                // 会话正在关闭，忽略
            }
        }
    }

    private void handleExit(SessionSpec current, Long userId, Reply reply) {
        if (target.isGroupParticipants()) {
            if (userId == null) {
                return;
            }
            if (manager.leave(this, userId)) {
                try {
                    current.getOnParticipantLeave().accept(this, userId);
                } catch (Throwable t) {
                    log.error("会话 {} 的 onParticipantLeave 回调失败", id, t);
                }
                if (participants.isEmpty()) {
                    close(SessionCloseReason.NO_PARTICIPANTS);
                } else if (turnCount.get() >= current.getMaxTurns()) {
                    close(SessionCloseReason.TURN_LIMIT);
                }
            }
        } else {
            close(SessionCloseReason.USER_EXIT);
        }
    }

    private void finishAction(SessionSpec current, SessionAction action) {
        consecutiveErrors.set(0);
        if (action == SessionAction.CLOSE) {
            close(SessionCloseReason.PLUGIN_CLOSED);
            return;
        }
        if (turnCount.get() >= current.getMaxTurns()) {
            close(SessionCloseReason.TURN_LIMIT);
        }
    }

    private void onError(Throwable t, Reply reply) {
        int errors = consecutiveErrors.incrementAndGet();
        log.error("会话 {}（插件 {}，目标 {}）处理失败，连续错误 {}",
                id, getPluginId(), target.displayName(), errors, t);
        send(reply, "处理失败（" + t.getClass().getSimpleName() + "），请联系开发人员。");
        SessionSpec current = spec;
        if (current != null && errors >= current.getMaxConsecutiveErrors()) {
            close(SessionCloseReason.ERROR_LIMIT);
        }
    }

    private static boolean matchesExit(SessionSpec spec, YuniMessageEvent event) {
        String text = SessionCommand.plainText(event);
        return spec.getExitCommands().stream().anyMatch(text::equals);
    }

    private boolean matchesJoin(SessionSpec spec, YuniMessageEvent event) {
        String text = SessionCommand.plainText(event);
        return spec.getJoinCommands().stream().anyMatch(text::equals);
    }

    boolean matchesJoinCommand(YuniMessageEvent event) {
        SessionSpec current = spec;
        return current != null && target.isGroupParticipants() && matchesJoin(current, event);
    }

    private static void send(Reply reply, String text) {
        if (reply != null) {
            reply.text(text).send();
        }
    }

    /**
     * 成功入队后刷新活跃时间。
     */
    void touch() {
        lastActiveAt = clock.now();
        lastActiveNanos = clock.nanoTime();
    }

    // ==================== 关闭 ====================

    boolean tryBeginClose(SessionCloseReason reason) {
        if (!state.compareAndSet(State.ACTIVE, State.CLOSING)) {
            return false;
        }
        closeReason = reason;
        mailbox.close();
        return true;
    }

    void scheduleFinish() {
        mailbox.runAfterDrain(this::finishClose);
    }

    private void finishClose() {
        if (state.get() == State.CLOSED) {
            return;
        }
        mailbox.dropPending();

        SessionSpec current = spec;
        SessionCloseReason reason = closeReason == null
                ? SessionCloseReason.PLUGIN_CLOSED : closeReason;
        int participantCount = target.isGroupParticipants() ? participants.size() : 0;
        SessionCloseEvent event = new SessionCloseEvent(
                id, target, openerUserId, getPluginId(), tag, reason,
                createdAt, clock.now(), turnCount.get(),
                participantCount, mailbox.droppedCount());

        if (current != null && reason.notifiesOnClose()) {
            try {
                current.getOnClose().accept(event);
            } catch (Throwable t) {
                log.error("会话 {} 的 onClose 回调失败", id, t);
            }
        }

        // 释放可能持有插件类引用的内容
        spec = null;
        handler = null;
        states.clear();
        participants.clear();
        owner = null;
        state.set(State.CLOSED);
        closed.complete(null);
        log.info("会话已关闭: id={}, plugin={}, target={}, reason={}, turns={}, dropped={}",
                id, event.pluginId(), target.displayName(), reason,
                event.turnCount(), event.droppedMessages());
    }

    CompletableFuture<Void> closedFuture() {
        return closed;
    }

    // ==================== SessionContext ====================

    @Override
    public long getId() {
        return id;
    }

    @Override
    public SessionTarget getTarget() {
        return target;
    }

    @Override
    public long getOpenerUserId() {
        return openerUserId;
    }

    @Override
    public String getPluginId() {
        return pluginId;
    }

    @Override
    public String getTag() {
        return tag;
    }

    @Override
    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public Instant getLastActiveAt() {
        return lastActiveAt;
    }

    @Override
    public Instant getExpiresAt() {
        return expiresAt;
    }

    @Override
    public int getTurnCount() {
        return turnCount.get();
    }

    @Override
    public int getPendingMessageCount() {
        return mailbox.pendingCount();
    }

    @Override
    public boolean isActive() {
        return state.get() == State.ACTIVE;
    }

    @Override
    public Set<Long> getParticipants() {
        return target.isGroupParticipants() ? Set.copyOf(participants) : Set.of();
    }

    @Override
    public int getParticipantCount() {
        return target.isGroupParticipants() ? participants.size() : 0;
    }

    @Override
    public boolean isParticipant(long userId) {
        return target.isGroupParticipants() && participants.contains(userId);
    }

    @Override
    public boolean addParticipant(long userId) {
        if (!target.isGroupParticipants()) {
            throw new UnsupportedOperationException("只有部分群友通道支持参与者管理");
        }
        return manager.join(this, userId) == SessionManager.JoinStatus.JOINED;
    }

    @Override
    public boolean removeParticipant(long userId) {
        if (!target.isGroupParticipants()) {
            throw new UnsupportedOperationException("只有部分群友通道支持参与者管理");
        }
        if (!manager.leave(this, userId)) {
            return false;
        }
        if (participants.isEmpty()) {
            close(SessionCloseReason.NO_PARTICIPANTS);
        }
        return true;
    }

    @Override
    public <T> T state(Class<T> type) {
        return state(type, () -> {
            try {
                return type.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                        "会话状态类型 " + type.getName() + " 缺少可访问的无参构造器", e);
            }
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T state(Class<T> type, Supplier<T> factory) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(factory, "factory");
        if (state.get() == State.CLOSED) {
            throw new IllegalStateException("会话已关闭，不能再访问状态");
        }
        Object value = states.computeIfAbsent(type, k -> {
            T created = factory.get();
            if (created == null) {
                throw new IllegalStateException("会话状态工厂返回 null: " + type.getName());
            }
            return created;
        });
        return (T) value;
    }

    @Override
    public void close(SessionCloseReason reason) {
        manager.close(this, reason == null ? SessionCloseReason.PLUGIN_CLOSED : reason);
    }

    // ==================== 包内访问 ====================

    PluginFiber owner() {
        return owner;
    }

    SessionSpec specOrNull() {
        return spec;
    }

    SessionMailbox mailbox() {
        return mailbox;
    }

    long lastActiveNanos() {
        return lastActiveNanos;
    }

    long createdNanos() {
        return createdNanos;
    }

    Set<Long> participantSnapshot() {
        return Set.copyOf(participants);
    }

    void addParticipantDirect(long userId) {
        participants.add(userId);
    }

    void removeParticipantDirect(long userId) {
        participants.remove(userId);
    }

    SessionHandle handle() {
        return handle;
    }

    boolean isClosingOrClosed() {
        return state.get() != State.ACTIVE;
    }

    SessionCloseReason closeReason() {
        return closeReason;
    }

    // ==================== Handle ====================

    private final class Handle implements SessionHandle {

        @Override
        public long getId() {
            return id;
        }

        @Override
        public SessionTarget getTarget() {
            return target;
        }

        @Override
        public String getTag() {
            return tag;
        }

        @Override
        public boolean isActive() {
            return SessionImpl.this.isActive();
        }

        @Override
        public int getTurnCount() {
            return turnCount.get();
        }

        @Override
        public int getParticipantCount() {
            return SessionImpl.this.getParticipantCount();
        }

        @Override
        public void close(SessionCloseReason reason) {
            SessionImpl.this.close(reason);
        }
    }
}
