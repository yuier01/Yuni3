package com.yuier.yuni.plugin.runtime.session;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 会话串行邮箱。
 *
 * <p>同一会话的回合按 FIFO 串行执行；不同会话由各自邮箱并行。
 * 队列满时丢弃新消息；关闭时待处理消息全部丢弃，不重投。</p>
 */
final class SessionMailbox {

    private static final Logger log = LoggerFactory.getLogger(SessionMailbox.class);

    enum EnqueueResult {
        ENQUEUED,
        CLOSED,
        FULL
    }

    private final SessionImpl session;
    private final int maxPending;
    private final ArrayDeque<Turn> queue = new ArrayDeque<>();
    private final SerialExecutor serial;

    private boolean closed;
    private boolean scheduled;
    private int dropped;

    SessionMailbox(SessionImpl session, Executor executor, int maxPending) {
        this.session = session;
        this.maxPending = maxPending;
        this.serial = new SerialExecutor(executor, "session-" + session.getId());
    }

    EnqueueResult enqueue(Turn turn) {
        synchronized (this) {
            if (closed) {
                return EnqueueResult.CLOSED;
            }
            if (queue.size() >= maxPending) {
                dropped++;
                return EnqueueResult.FULL;
            }
            queue.addLast(turn);
            if (scheduled) {
                return EnqueueResult.ENQUEUED;
            }
            scheduled = true;
        }
        if (!serial.execute(this::drain)) {
            synchronized (this) {
                queue.remove(turn);
                scheduled = false;
            }
            return EnqueueResult.CLOSED;
        }
        return EnqueueResult.ENQUEUED;
    }

    /**
     * 串行执行一个收尾任务（例如关闭清理）。
     *
     * @param task 任务
     */
    void runAfterDrain(Runnable task) {
        if (!serial.execute(task)) {
            // 执行器已关闭：至少保证清理逻辑被执行，避免状态永远停在 CLOSING
            try {
                task.run();
            } catch (Throwable t) {
                log.error("会话 {} 的收尾任务执行失败", session.getId(), t);
            }
        }
    }

    void close() {
        synchronized (this) {
            closed = true;
        }
    }

    void dropPending() {
        synchronized (this) {
            dropped += queue.size();
            queue.clear();
        }
    }

    int pendingCount() {
        synchronized (this) {
            return queue.size();
        }
    }

    int droppedCount() {
        synchronized (this) {
            return dropped;
        }
    }

    boolean isIdle() {
        synchronized (this) {
            return !scheduled && queue.isEmpty();
        }
    }

    private void drain() {
        while (true) {
            Turn turn;
            synchronized (this) {
                if (closed) {
                    scheduled = false;
                    return;
                }
                turn = queue.pollFirst();
                if (turn == null) {
                    scheduled = false;
                    return;
                }
            }
            try {
                session.execute(turn);
            } catch (Throwable t) {
                log.error("会话 {} 处理回合失败", session.getId(), t);
            }
        }
    }

    /**
     * 基于共享 Executor 的串行执行器。
     */
    private static final class SerialExecutor {

        private final Executor executor;
        private final String name;
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        private boolean running;

        private SerialExecutor(Executor executor, String name) {
            this.executor = executor;
            this.name = name;
        }

        private boolean execute(Runnable task) {
            synchronized (this) {
                tasks.addLast(task);
                if (running) {
                    return true;
                }
                running = true;
            }
            try {
                executor.execute(this::run);
                return true;
            } catch (RejectedExecutionException e) {
                synchronized (this) {
                    tasks.remove(task);
                    running = false;
                }
                log.warn("会话执行器已关闭，任务被拒绝: {}", name);
                return false;
            }
        }

        private void run() {
            while (true) {
                Runnable task;
                synchronized (this) {
                    task = tasks.pollFirst();
                    if (task == null) {
                        running = false;
                        return;
                    }
                }
                try {
                    task.run();
                } catch (Throwable t) {
                    log.error("会话串行任务执行失败: {}", name, t);
                }
            }
        }
    }
}
