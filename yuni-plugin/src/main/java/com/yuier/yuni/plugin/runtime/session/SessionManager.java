package com.yuier.yuni.plugin.runtime.session;

import com.yuier.yuni.core.event.YuniMessageEvent;
import com.yuier.yuni.core.event.notice.GroupDecreaseEvent;
import com.yuier.yuni.core.event.notice.YuniNoticeEvent;
import com.yuier.yuni.plugin.api.Reply;
import com.yuier.yuni.plugin.api.ScopeFilter;
import com.yuier.yuni.plugin.api.session.SessionCloseReason;
import com.yuier.yuni.plugin.api.session.SessionConflictException;
import com.yuier.yuni.plugin.api.session.SessionHandle;
import com.yuier.yuni.plugin.api.session.SessionHandler;
import com.yuier.yuni.plugin.api.session.SessionLimitExceededException;
import com.yuier.yuni.plugin.api.session.SessionSpec;
import com.yuier.yuni.plugin.api.session.SessionTarget;
import com.yuier.yuni.plugin.api.session.SessionUnavailableException;
import com.yuier.yuni.plugin.api.session.admin.SessionAdmin;
import com.yuier.yuni.plugin.api.session.admin.SessionInfo;
import com.yuier.yuni.plugin.api.session.admin.SessionStats;
import com.yuier.yuni.plugin.runtime.PluginFiber;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 会话管理器——注册、路由、参与者归属、超时与关闭。
 */
public final class SessionManager implements SessionAdmin {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    /** 一次加入操作的结果。 */
    enum JoinStatus {
        JOINED,
        ALREADY,
        FULL,
        OCCUPIED,
        CLOSED
    }

    private final SessionSettings settings;
    private final Function<String, Optional<ScopeFilter>> scopeResolver;
    private final SessionClock clock;
    private final ExecutorService sessionExecutor;
    private final ScheduledExecutorService sweeper;
    private final ReentrantLock lock = new ReentrantLock();

    private final Map<SessionTarget, SessionImpl> byTarget = new ConcurrentHashMap<>();
    private final Map<Long, ConcurrentHashMap<String, SessionImpl>> partialByGroup = new ConcurrentHashMap<>();
    private final Map<Long, ConcurrentHashMap<String, SessionImpl>> joinCommandsByGroup = new ConcurrentHashMap<>();
    private final Map<GroupUserKey, SessionImpl> membership = new ConcurrentHashMap<>();

    private final Set<PluginFiber> effectInstalled = ConcurrentHashMap.newKeySet();
    private final Map<PluginFiber, Object> effectLocks = new ConcurrentHashMap<>();
    private final Set<PluginFiber> unloading = ConcurrentHashMap.newKeySet();

    private final AtomicLong idSeq = new AtomicLong();

    private final LongAdder totalOpened = new LongAdder();
    private final LongAdder totalClosed = new LongAdder();
    private final LongAdder totalTimeouts = new LongAdder();
    private final LongAdder totalErrors = new LongAdder();
    private final LongAdder totalDropped = new LongAdder();

    public SessionManager(SessionSettings settings,
                          Function<String, Optional<ScopeFilter>> scopeResolver,
                          SessionClock clock) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.scopeResolver = scopeResolver;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sessionExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "yuni-session-sweeper");
            thread.setDaemon(true);
            return thread;
        });
        Duration interval = settings.sweepInterval();
        if (interval != null && !interval.isZero() && !interval.isNegative()) {
            long millis = Math.max(1, interval.toMillis());
            sweeper.scheduleAtFixedRate(this::sweep, millis, millis, TimeUnit.MILLISECONDS);
        }
    }

    // ==================== 打开 ====================

    /**
     * 打开一个会话。
     *
     * @param fiber   所属插件
     * @param spec    参数
     * @param handler 消息入口
     * @return 句柄
     */
    public SessionHandle open(PluginFiber fiber, SessionSpec spec, SessionHandler handler) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(handler, "handler");
        if (!settings.enabled()) {
            throw new SessionUnavailableException("长会话功能已关闭");
        }
        if (fiber == null || fiber.isDisposed() || fiber.getEffects().isSealed()
                || unloading.contains(fiber)) {
            throw new SessionUnavailableException("插件已卸载或正在卸载");
        }
        if (spec.getMaxDuration().compareTo(settings.maxDurationLimit()) > 0) {
            throw new SessionLimitExceededException(
                    "maxDuration 超过全局硬上限: " + settings.maxDurationLimit());
        }
        if (spec.getIdleTimeout().compareTo(settings.maxIdleTimeoutLimit()) > 0) {
            throw new SessionLimitExceededException(
                    "idleTimeout 超过全局硬上限: " + settings.maxIdleTimeoutLimit());
        }

        SessionImpl session;
        lock.lock();
        try {
            SessionTarget target = spec.getTarget();
            SessionImpl existing = byTarget.get(target);
            if (existing != null) {
                throw conflict(target, existing);
            }
            if (byTarget.size() >= settings.maxSessions()) {
                throw new SessionLimitExceededException("全局会话数已达上限");
            }
            if (countOf(fiber) >= settings.maxSessionsPerPlugin()) {
                throw new SessionLimitExceededException("插件会话数已达上限");
            }

            session = new SessionImpl(idSeq.incrementAndGet(), fiber, spec, handler,
                    this, clock, sessionExecutor);

            switch (target.type()) {
                case GROUP_PARTICIPANTS -> openParticipants(session);
                case GROUP_MEMBER -> openMember(session);
                case PRIVATE -> byTarget.put(target, session);
            }
        } finally {
            lock.unlock();
        }

        try {
            ensureAggregateEffect(fiber);
        } catch (Throwable t) {
            rollbackOpen(session);
            throw new SessionUnavailableException("注册会话清理器失败", t);
        }
        totalOpened.increment();
        return session.handle();
    }

    private void openParticipants(SessionImpl session) {
        SessionTarget target = session.getTarget();
        long groupId = target.groupId();
        String key = target.channelKey();

        ConcurrentHashMap<String, SessionImpl> partials =
                partialByGroup.computeIfAbsent(groupId, g -> new ConcurrentHashMap<>());
        if (partials.putIfAbsent(key, session) != null) {
            throw conflict(target, partials.get(key));
        }

        ConcurrentHashMap<String, SessionImpl> joins =
                joinCommandsByGroup.computeIfAbsent(groupId, g -> new ConcurrentHashMap<>());
        List<String> registered = new ArrayList<>();
        try {
            for (String command : session.specOrNull().getJoinCommands()) {
                SessionImpl owner = joins.putIfAbsent(command, session);
                if (owner != null) {
                    throw new SessionConflictException(
                            "加入指令已被占用: " + command, target,
                            owner.getPluginId(), owner.getId());
                }
                registered.add(command);
            }

            GroupUserKey openerKey = new GroupUserKey(groupId, session.getOpenerUserId());
            SessionImpl owner = membership.putIfAbsent(openerKey, session);
            if (owner != null) {
                throw new SessionConflictException(
                        "用户 " + session.getOpenerUserId() + " 已在群 " + groupId + " 的其他会话中",
                        target, owner.getPluginId(), owner.getId());
            }
            byTarget.put(target, session);
        } catch (RuntimeException e) {
            // 回滚本次已登记的部分
            partials.remove(key, session);
            for (String command : registered) {
                joins.remove(command, session);
            }
            membership.remove(new GroupUserKey(groupId, session.getOpenerUserId()), session);
            throw e;
        }
    }

    private void openMember(SessionImpl session) {
        SessionTarget target = session.getTarget();
        GroupUserKey key = new GroupUserKey(target.groupId(), target.userId());
        SessionImpl owner = membership.putIfAbsent(key, session);
        if (owner != null) {
            throw new SessionConflictException(
                    "用户 " + target.userId() + " 已在群 " + target.groupId() + " 的其他会话中",
                    target, owner.getPluginId(), owner.getId());
        }
        byTarget.put(target, session);
    }

    private void rollbackOpen(SessionImpl session) {
        lock.lock();
        try {
            unregister(session);
        } finally {
            lock.unlock();
        }
    }

    // ==================== 路由 ====================

    /**
     * 尝试将消息路由到会话。
     *
     * @param event 消息事件
     * @param reply 回复入口
     * @return 已被会话接管返回 {@code true}
     */
    public boolean route(YuniMessageEvent event, Reply reply) {
        if (event == null) {
            return false;
        }
        if (!event.isGroup() && !event.isPrivate()) {
            return false;
        }
        if (isSelf(event)) {
            return false;
        }

        SessionImpl session = findSession(event);
        if (session == null) {
            return false;
        }

        if (!session.owner().isServiceable()
                || !scopeAllows(session.getPluginId(), event.getGroupId())) {
            close(session, SessionCloseReason.SCOPE_REVOKED);
            return false;
        }

        if (session.getTarget().isGroupParticipants()) {
            Long userId = event.getUserId();
            if (userId == null || userId <= 0) {
                return false;
            }
            if (!session.isParticipant(userId) && !session.matchesJoinCommand(event)) {
                return false;
            }
        }

        SessionMailbox.EnqueueResult result =
                session.mailbox().enqueue(new Turn(event, reply));
        return switch (result) {
            case ENQUEUED -> {
                session.touch();
                yield true;
            }
            case FULL -> true;
            case CLOSED -> false;
        };
    }

    private SessionImpl findSession(YuniMessageEvent event) {
        if (event.isPrivate()) {
            Long userId = event.getUserId();
            if (userId == null || userId <= 0) {
                return null;
            }
            return active(byTarget.get(SessionTarget.privateChat(userId)));
        }

        Long groupId = event.getGroupId();
        if (groupId == null) {
            return null;
        }
        Long userId = event.getUserId();

        if (userId != null && userId > 0) {
            SessionImpl member = active(byTarget.get(
                    SessionTarget.groupMember(groupId, userId)));
            if (member != null) {
                return member;
            }
            SessionImpl owned = active(membership.get(
                    new GroupUserKey(groupId, userId)));
            if (owned != null) {
                return owned;
            }
        }

        ConcurrentHashMap<String, SessionImpl> partials = partialByGroup.get(groupId);
        if (partials == null) {
            return null;
        }
        for (SessionImpl candidate : partials.values()) {
            if (candidate.isActive() && candidate.matchesJoinCommand(event)) {
                return candidate;
            }
        }
        return null;
    }

    private static SessionImpl active(SessionImpl session) {
        return session != null && session.isActive() ? session : null;
    }

    // ==================== 通知事件 ====================

    /**
     * 处理通知事件：退群/踢出时清理对应会话。
     *
     * <p>只处理 {@link GroupDecreaseEvent}；插件通知处理器仍会照常收到该事件。</p>
     *
     * @param event 通知事件
     */
    public void onNotice(YuniNoticeEvent event) {
        if (!(event instanceof GroupDecreaseEvent decrease)) {
            return;
        }
        Long groupId = decrease.getGroupId();
        if (groupId == null) {
            return;
        }
        String subType = decrease.getSubType();
        if ("kick_me".equals(subType)) {
            closeGroup(groupId);
            return;
        }
        if (!"leave".equals(subType) && !"kick".equals(subType)) {
            return;
        }
        Long userId = decrease.getUserId();
        if (userId == null) {
            return;
        }

        SessionImpl member = active(byTarget.get(
                SessionTarget.groupMember(groupId, userId)));
        if (member != null) {
            close(member, SessionCloseReason.TARGET_UNAVAILABLE);
        }

        SessionImpl owned = active(membership.get(new GroupUserKey(groupId, userId)));
        if (owned != null && owned.getTarget().isGroupParticipants()) {
            lock.lock();
            try {
                if (leave(owned, userId) && owned.getParticipantCount() == 0) {
                    close(owned, SessionCloseReason.TARGET_UNAVAILABLE);
                }
            } finally {
                lock.unlock();
            }
        }
    }

    private void closeGroup(long groupId) {
        for (SessionImpl session : snapshot()) {
            Long targetGroup = session.getTarget().groupId();
            if (targetGroup != null && targetGroup == groupId) {
                close(session, SessionCloseReason.TARGET_UNAVAILABLE);
            }
        }
    }

    // ==================== 参与者 ====================

    JoinStatus join(SessionImpl session, long userId) {
        if (session == null || !session.getTarget().isGroupParticipants()) {
            return JoinStatus.CLOSED;
        }
        if (userId <= 0) {
            return JoinStatus.CLOSED;
        }
        lock.lock();
        try {
            if (!session.isActive()) {
                return JoinStatus.CLOSED;
            }
            if (session.isParticipant(userId)) {
                return JoinStatus.ALREADY;
            }
            SessionSpec spec = session.specOrNull();
            if (spec == null) {
                return JoinStatus.CLOSED;
            }
            if (session.getParticipantCount() >= spec.getMaxParticipants()) {
                return JoinStatus.FULL;
            }

            long groupId = session.getTarget().groupId();
            GroupUserKey key = new GroupUserKey(groupId, userId);
            SessionImpl owner = membership.putIfAbsent(key, session);
            if (owner != null) {
                return JoinStatus.OCCUPIED;
            }
            if (!session.isActive()) {
                membership.remove(key, session);
                return JoinStatus.CLOSED;
            }
            session.addParticipantDirect(userId);
            return JoinStatus.JOINED;
        } finally {
            lock.unlock();
        }
    }

    boolean leave(SessionImpl session, long userId) {
        if (session == null || !session.getTarget().isGroupParticipants()) {
            return false;
        }
        lock.lock();
        try {
            if (!session.isParticipant(userId)) {
                return false;
            }
            long groupId = session.getTarget().groupId();
            membership.remove(new GroupUserKey(groupId, userId), session);
            session.removeParticipantDirect(userId);
            return true;
        } finally {
            lock.unlock();
        }
    }

    // ==================== 关闭 ====================

    void close(SessionImpl session, SessionCloseReason reason) {
        if (session == null || !session.tryBeginClose(reason)) {
            return;
        }
        totalClosed.increment();
        if (reason == SessionCloseReason.IDLE_TIMEOUT
                || reason == SessionCloseReason.MAX_DURATION) {
            totalTimeouts.increment();
        } else if (reason == SessionCloseReason.ERROR_LIMIT) {
            totalErrors.increment();
        }
        totalDropped.add(session.mailbox().droppedCount() + session.getPendingMessageCount());
        lock.lock();
        try {
            unregister(session);
        } finally {
            lock.unlock();
        }
        session.scheduleFinish();
    }

    /**
     * 关闭某个插件在某个群内的全部会话。
     *
     * @param pluginId 插件标识
     * @param groupId  群号
     * @param reason   原因
     */
    public void closeForPluginInGroup(String pluginId, long groupId, SessionCloseReason reason) {
        for (SessionImpl session : snapshot()) {
            if (pluginId.equals(session.getPluginId())
                    && session.getTarget().groupId() != null
                    && session.getTarget().groupId() == groupId) {
                close(session, reason);
            }
        }
    }

    /**
     * 关闭某个插件的全部会话。
     *
     * @param pluginId 插件标识
     * @param reason   原因
     */
    public void closeAllOfPlugin(String pluginId, SessionCloseReason reason) {
        for (SessionImpl session : snapshot()) {
            if (pluginId.equals(session.getPluginId())) {
                close(session, reason);
            }
        }
    }

    /**
     * 标记插件开始卸载，之后拒绝新会话。
     *
     * @param fiber 插件
     */
    public void beginUnload(PluginFiber fiber) {
        if (fiber != null) {
            unloading.add(fiber);
        }
    }

    /**
     * 结束卸载标记。
     *
     * @param fiber 插件
     */
    public void endUnload(PluginFiber fiber) {
        if (fiber != null) {
            unloading.remove(fiber);
        }
    }

    /**
     * 关闭某插件的全部会话并等待（有总超时）。
     *
     * @param fiber  插件
     * @param reason 原因
     */
    public void closeAllOf(PluginFiber fiber, SessionCloseReason reason) {
        List<SessionImpl> owned = snapshot().stream()
                .filter(s -> s.owner() == fiber)
                .toList();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (SessionImpl session : owned) {
            futures.add(session.closedFuture());
            close(session, reason);
        }
        if (!futures.isEmpty()) {
            try {
                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                        .get(settings.closeWaitTimeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                log.warn("关闭插件 {} 的会话超时，仍有 {} 个在途 handler",
                        fiber == null ? "?" : fiber.getId(), owned.size());
            } catch (Exception e) {
                log.warn("等待插件会话关闭时出错", e);
            }
        }
        effectInstalled.remove(fiber);
        effectLocks.remove(fiber);
    }

    private void unregister(SessionImpl session) {
        SessionTarget target = session.getTarget();
        byTarget.remove(target, session);
        if (target.isGroupParticipants()) {
            long groupId = target.groupId();
            ConcurrentHashMap<String, SessionImpl> partials = partialByGroup.get(groupId);
            if (partials != null) {
                partials.remove(target.channelKey(), session);
                if (partials.isEmpty()) {
                    partialByGroup.remove(groupId, partials);
                }
            }
            ConcurrentHashMap<String, SessionImpl> joins = joinCommandsByGroup.get(groupId);
            if (joins != null) {
                joins.entrySet().removeIf(e -> e.getValue() == session);
                if (joins.isEmpty()) {
                    joinCommandsByGroup.remove(groupId, joins);
                }
            }
            for (Long userId : session.participantSnapshot()) {
                membership.remove(new GroupUserKey(groupId, userId), session);
            }
        } else if (target.type() == SessionTarget.Type.GROUP_MEMBER) {
            membership.remove(new GroupUserKey(target.groupId(), target.userId()), session);
        }
    }

    // ==================== 查询 ====================

    /**
     * 查找本插件在目标上的活跃会话。
     *
     * @param fiber  插件
     * @param target 目标
     * @return 句柄
     */
    public Optional<SessionHandle> findOwnSession(PluginFiber fiber, SessionTarget target) {
        SessionImpl session = active(byTarget.get(target));
        if (session == null || session.owner() != fiber) {
            return Optional.empty();
        }
        return Optional.of(session.handle());
    }

    /**
     * 本插件持有的全部会话。
     *
     * @param fiber 插件
     * @return 句柄列表
     */
    public List<SessionHandle> getOwnSessions(PluginFiber fiber) {
        List<SessionHandle> handles = new ArrayList<>();
        for (SessionImpl session : snapshot()) {
            if (session.owner() == fiber && session.isActive()) {
                handles.add(session.handle());
            }
        }
        return List.copyOf(handles);
    }

    /**
     * 当前活跃会话数。
     *
     * @return 数量
     */
    public int sessionCount() {
        return byTarget.size();
    }

    private List<SessionImpl> snapshot() {
        return List.copyOf(byTarget.values());
    }

    private int countOf(PluginFiber fiber) {
        int count = 0;
        for (SessionImpl session : byTarget.values()) {
            if (session.owner() == fiber) {
                count++;
            }
        }
        return count;
    }

    private boolean scopeAllows(String pluginId, Long groupId) {
        if (scopeResolver == null) {
            return true;
        }
        return scopeResolver.apply(pluginId)
                .map(filter -> filter.matches(groupId))
                .orElse(true);
    }

    private static boolean isSelf(YuniMessageEvent event) {
        return event.getSelfId() != null && event.getSelfId().equals(event.getUserId());
    }

    private static SessionConflictException conflict(SessionTarget target, SessionImpl owner) {
        return new SessionConflictException(target,
                owner == null ? "?" : owner.getPluginId(),
                owner == null ? 0 : owner.getId());
    }

    // ==================== 清理器，超时，关闭系统 ====================

    private void ensureAggregateEffect(PluginFiber fiber) {
        Object effectLock = effectLocks.computeIfAbsent(fiber, f -> new Object());
        synchronized (effectLock) {
            if (effectInstalled.contains(fiber)) {
                return;
            }
            boolean accepted = fiber.getEffects().register(
                    () -> closeAllOf(fiber, SessionCloseReason.PLUGIN_UNLOAD));
            if (!accepted) {
                throw new SessionUnavailableException("插件正在卸载，无法注册会话清理器");
            }
            effectInstalled.add(fiber);
        }
    }

    private void sweep() {
        long now = clock.nanoTime();
        for (SessionImpl session : snapshot()) {
            if (!session.isActive()) {
                continue;
            }
            SessionSpec spec = session.specOrNull();
            if (spec == null) {
                continue;
            }
            if (now - session.createdNanos() >= spec.getMaxDuration().toNanos()) {
                close(session, SessionCloseReason.MAX_DURATION);
                continue;
            }
            if (session.mailbox().isIdle()
                    && now - session.lastActiveNanos() >= spec.getIdleTimeout().toNanos()) {
                close(session, SessionCloseReason.IDLE_TIMEOUT);
            }
        }
    }

    /**
     * 关闭全部会话并释放执行器。
     */
    public void shutdown() {
        for (SessionImpl session : snapshot()) {
            close(session, SessionCloseReason.PLUGIN_UNLOAD);
        }
        sweeper.shutdownNow();
        sessionExecutor.shutdownNow();
    }

    // ==================== SessionAdmin ====================

    @Override
    public List<SessionInfo> list() {
        List<SessionInfo> infos = new ArrayList<>();
        for (SessionImpl session : snapshot()) {
            if (!session.isActive()) {
                continue;
            }
            infos.add(new SessionInfo(
                    session.getId(),
                    session.getTarget(),
                    session.getPluginId(),
                    session.getTag(),
                    session.getOpenerUserId(),
                    session.getCreatedAt(),
                    session.getLastActiveAt(),
                    session.getExpiresAt(),
                    session.getTurnCount(),
                    session.getParticipantCount(),
                    session.getPendingMessageCount(),
                    true));
        }
        return List.copyOf(infos);
    }

    @Override
    public boolean close(long sessionId, SessionCloseReason reason) {
        SessionCloseReason effective =
                reason == null ? SessionCloseReason.ADMIN_CLOSED : reason;
        for (SessionImpl session : snapshot()) {
            if (session.getId() == sessionId && session.isActive()) {
                close(session, effective);
                return true;
            }
        }
        return false;
    }

    @Override
    public SessionStats stats() {
        Set<String> plugins = new HashSet<>();
        int participants = 0;
        int active = 0;
        for (SessionImpl session : byTarget.values()) {
            if (!session.isActive()) {
                continue;
            }
            active++;
            plugins.add(session.getPluginId());
            participants += session.getParticipantCount();
        }
        return new SessionStats(
                active,
                plugins.size(),
                participants,
                totalOpened.sum(),
                totalClosed.sum(),
                totalTimeouts.sum(),
                totalErrors.sum(),
                totalDropped.sum());
    }

    /**
     * 立即执行一次超时扫描（测试/管理端使用）。
     */
    void sweepNow() {
        sweep();
    }

    /**
     * 诊断描述。
     *
     * @return 形如 {@code sessions=3, partialGroups=1}
     */
    public String describe() {
        return "sessions=" + byTarget.size() + ", partialGroups=" + partialByGroup.size();
    }
}
