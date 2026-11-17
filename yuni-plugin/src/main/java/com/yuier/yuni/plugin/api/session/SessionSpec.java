package com.yuier.yuni.plugin.api.session;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 会话参数。
 *
 * <p>使用 {@link #builder(SessionTarget)} 构造。默认值见设计文档 6.3.1 参数字典。</p>
 */
public final class SessionSpec {

    private static final Duration MAX_DURATION_LIMIT = Duration.ofHours(24);
    private static final Duration MAX_IDLE_LIMIT = Duration.ofHours(2);

    private final SessionTarget target;
    private final String tag;
    private final long opener;
    private final Duration idleTimeout;
    private final Duration maxDuration;
    private final int maxTurns;
    private final int maxPendingMessages;
    private final int maxConsecutiveErrors;
    private final boolean exitEnabled;
    private final List<String> exitCommands;
    private final List<String> joinCommands;
    private final Set<Long> initialParticipants;
    private final int maxParticipants;
    private final List<SessionCommand> commands;
    private final Consumer<SessionCloseEvent> onClose;
    private final BiConsumer<SessionContext, Long> onParticipantJoin;
    private final BiConsumer<SessionContext, Long> onParticipantLeave;

    private SessionSpec(Builder b) {
        this.target = Objects.requireNonNull(b.target, "target");

        String normalizedTag = b.tag == null ? "" : b.tag.strip();
        if (normalizedTag.length() > 64) {
            throw new IllegalArgumentException("tag 最长 64 个字符");
        }
        this.tag = normalizedTag;

        Duration idle = Objects.requireNonNull(b.idleTimeout, "idleTimeout");
        Duration max = Objects.requireNonNull(b.maxDuration, "maxDuration");
        if (idle.isZero() || idle.isNegative()) {
            throw new IllegalArgumentException("idleTimeout 必须为正");
        }
        if (max.isZero() || max.isNegative()) {
            throw new IllegalArgumentException("maxDuration 必须为正");
        }
        if (idle.compareTo(max) > 0) {
            throw new IllegalArgumentException("idleTimeout 不能大于 maxDuration");
        }
        if (max.compareTo(MAX_DURATION_LIMIT) > 0) {
            throw new IllegalArgumentException("maxDuration 不能超过 24 小时");
        }
        if (idle.compareTo(MAX_IDLE_LIMIT) > 0) {
            throw new IllegalArgumentException("idleTimeout 不能超过 2 小时");
        }
        this.idleTimeout = idle;
        this.maxDuration = max;

        if (b.maxTurns < 1 || b.maxTurns > 10_000) {
            throw new IllegalArgumentException("maxTurns 必须在 1~10000 之间");
        }
        this.maxTurns = b.maxTurns;
        if (b.maxPendingMessages < 1 || b.maxPendingMessages > 1_000) {
            throw new IllegalArgumentException("maxPendingMessages 必须在 1~1000 之间");
        }
        this.maxPendingMessages = b.maxPendingMessages;
        if (b.maxConsecutiveErrors < 1 || b.maxConsecutiveErrors > 10) {
            throw new IllegalArgumentException("maxConsecutiveErrors 必须在 1~10 之间");
        }
        this.maxConsecutiveErrors = b.maxConsecutiveErrors;

        this.exitEnabled = b.exitEnabled;
        List<String> exits = normalizeCommands(b.exitCommands);
        if (exitEnabled) {
            if (exits.isEmpty()) {
                throw new IllegalArgumentException("exitEnabled=true 时至少需要一个退出指令");
            }
        } else {
            exits = List.of();
        }
        this.exitCommands = exits;

        long effectiveOpener;
        List<String> joins;
        Set<Long> initial;
        int participantsLimit;
        switch (target.type()) {
            case GROUP_PARTICIPANTS -> {
                if (b.opener <= 0) {
                    throw new IllegalArgumentException("GROUP_PARTICIPANTS 必须设置 opener");
                }
                effectiveOpener = b.opener;
                joins = normalizeCommands(b.joinCommands);
                if (joins.isEmpty()) {
                    throw new IllegalArgumentException(
                            "GROUP_PARTICIPANTS 至少需要一个 joinCommands；请为每个通道设置独有加入指令");
                }
                initial = b.initialParticipants == null
                        ? Set.of() : Set.copyOf(b.initialParticipants);
                if (initial.size() != 1 || !initial.contains(effectiveOpener)) {
                    throw new IllegalArgumentException(
                            "GROUP_PARTICIPANTS 的 initialParticipants 必须恰好是 {opener}");
                }
                if (initial.stream().anyMatch(id -> id == null || id <= 0)) {
                    throw new IllegalArgumentException("initialParticipants 不能包含非法用户号");
                }
                participantsLimit = b.maxParticipants;
                if (participantsLimit < 2 || participantsLimit > 2_000) {
                    throw new IllegalArgumentException("maxParticipants 必须在 2~2000 之间");
                }
                if (initial.size() > participantsLimit) {
                    throw new IllegalArgumentException("initialParticipants 超过 maxParticipants");
                }
            }
            case GROUP_MEMBER, PRIVATE -> {
                long targetUserId = target.userId();
                if (b.opener == 0) {
                    effectiveOpener = targetUserId;
                } else if (b.opener != targetUserId) {
                    throw new IllegalArgumentException("群成员/私聊目标的 opener 必须等于目标用户");
                } else {
                    effectiveOpener = b.opener;
                }
                joins = List.of();
                initial = Set.of();
                participantsLimit = 0;
            }
            default -> throw new IllegalArgumentException("未知会话目标类型: " + target.type());
        }
        this.opener = effectiveOpener;
        this.joinCommands = joins;
        this.initialParticipants = initial;
        this.maxParticipants = participantsLimit;

        this.commands = b.commands == null ? List.of() : List.copyOf(b.commands);
        if (this.commands.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("commands 不能包含 null");
        }
        this.onClose = b.onClose == null ? e -> { } : b.onClose;
        this.onParticipantJoin = b.onParticipantJoin == null ? (s, u) -> { } : b.onParticipantJoin;
        this.onParticipantLeave = b.onParticipantLeave == null ? (s, u) -> { } : b.onParticipantLeave;
    }

    /**
     * 默认参数。
     *
     * <p>只适用于 {@code GROUP_MEMBER} / {@code PRIVATE}。
     * {@code GROUP_PARTICIPANTS} 必须使用 builder 提供 opener、初始参与者与独有加入指令。</p>
     *
     * @param target 目标
     * @return 参数
     */
    public static SessionSpec defaults(SessionTarget target) {
        if (target != null && target.isGroupParticipants()) {
            throw new IllegalArgumentException(
                    "GROUP_PARTICIPANTS 必须使用 SessionSpec.builder(...) 提供 opener / initialParticipants / joinCommands");
        }
        return builder(target).build();
    }

    /**
     * 构造 builder。
     *
     * @param target 目标
     * @return builder
     */
    public static Builder builder(SessionTarget target) {
        return new Builder(target);
    }

    /**
     * 归一化指令列表：去空白、去重、保持顺序。
     *
     * @param raw 原始指令
     * @return 不可变列表
     */
    private static List<String> normalizeCommands(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String command : raw) {
            if (command == null) {
                continue;
            }
            String normalized = command.strip();
            if (!normalized.isEmpty()) {
                set.add(normalized);
            }
        }
        return List.copyOf(set);
    }

    /**
     * 会话参数 builder。
     */
    public static final class Builder {

        private final SessionTarget target;
        private String tag = "";
        private long opener;
        private Duration idleTimeout = Duration.ofMinutes(5);
        private Duration maxDuration = Duration.ofMinutes(30);
        private int maxTurns = 100;
        private int maxPendingMessages = 16;
        private int maxConsecutiveErrors = 3;
        private boolean exitEnabled = true;
        private List<String> exitCommands = new ArrayList<>(List.of("/退出"));
        private List<String> joinCommands = new ArrayList<>();
        private Set<Long> initialParticipants = Set.of();
        private int maxParticipants = 200;
        private final List<SessionCommand> commands = new ArrayList<>();
        private Consumer<SessionCloseEvent> onClose;
        private BiConsumer<SessionContext, Long> onParticipantJoin;
        private BiConsumer<SessionContext, Long> onParticipantLeave;

        private Builder(SessionTarget target) {
            this.target = Objects.requireNonNull(target, "target");
        }

        public Builder tag(String tag) {
            this.tag = tag;
            return this;
        }

        public Builder opener(long userId) {
            this.opener = userId;
            return this;
        }

        public Builder idleTimeout(Duration idleTimeout) {
            this.idleTimeout = idleTimeout;
            return this;
        }

        public Builder maxDuration(Duration maxDuration) {
            this.maxDuration = maxDuration;
            return this;
        }

        public Builder maxTurns(int maxTurns) {
            this.maxTurns = maxTurns;
            return this;
        }

        public Builder maxPendingMessages(int maxPendingMessages) {
            this.maxPendingMessages = maxPendingMessages;
            return this;
        }

        public Builder maxConsecutiveErrors(int maxConsecutiveErrors) {
            this.maxConsecutiveErrors = maxConsecutiveErrors;
            return this;
        }

        public Builder exitEnabled(boolean exitEnabled) {
            this.exitEnabled = exitEnabled;
            return this;
        }

        public Builder exitCommands(String... commands) {
            this.exitCommands = commands == null ? List.of() : List.of(commands);
            return this;
        }

        public Builder joinCommands(String... commands) {
            this.joinCommands = commands == null ? List.of() : List.of(commands);
            return this;
        }

        public Builder initialParticipants(Collection<Long> userIds) {
            this.initialParticipants = userIds == null ? Set.of() : Set.copyOf(userIds);
            return this;
        }

        public Builder maxParticipants(int maxParticipants) {
            this.maxParticipants = maxParticipants;
            return this;
        }

        public Builder command(SessionCommand command) {
            this.commands.add(Objects.requireNonNull(command, "command"));
            return this;
        }

        public Builder commands(Collection<SessionCommand> commands) {
            if (commands != null) {
                this.commands.addAll(commands);
            }
            return this;
        }

        public Builder onClose(Consumer<SessionCloseEvent> onClose) {
            this.onClose = onClose;
            return this;
        }

        public Builder onParticipantJoin(BiConsumer<SessionContext, Long> listener) {
            this.onParticipantJoin = listener;
            return this;
        }

        public Builder onParticipantLeave(BiConsumer<SessionContext, Long> listener) {
            this.onParticipantLeave = listener;
            return this;
        }

        public SessionSpec build() {
            return new SessionSpec(this);
        }
    }

    public SessionTarget getTarget() {
        return target;
    }

    public String getTag() {
        return tag;
    }

    public long getOpener() {
        return opener;
    }

    public Duration getIdleTimeout() {
        return idleTimeout;
    }

    public Duration getMaxDuration() {
        return maxDuration;
    }

    public int getMaxTurns() {
        return maxTurns;
    }

    public int getMaxPendingMessages() {
        return maxPendingMessages;
    }

    public int getMaxConsecutiveErrors() {
        return maxConsecutiveErrors;
    }

    public boolean isExitEnabled() {
        return exitEnabled;
    }

    public List<String> getExitCommands() {
        return exitCommands;
    }

    public List<String> getJoinCommands() {
        return joinCommands;
    }

    public Set<Long> getInitialParticipants() {
        return initialParticipants;
    }

    public int getMaxParticipants() {
        return maxParticipants;
    }

    public List<SessionCommand> getCommands() {
        return commands;
    }

    public Consumer<SessionCloseEvent> getOnClose() {
        return onClose;
    }

    public BiConsumer<SessionContext, Long> getOnParticipantJoin() {
        return onParticipantJoin;
    }

    public BiConsumer<SessionContext, Long> getOnParticipantLeave() {
        return onParticipantLeave;
    }
}
