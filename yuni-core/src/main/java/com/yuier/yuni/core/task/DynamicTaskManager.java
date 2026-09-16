package com.yuier.yuni.core.task;

import com.yuier.yuni.core.util.LogStringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * @Title: DynamicTaskManager
 * @Author yuier
 * @Package com.yuier.yuni.core.task
 * @Date 2025/12/23 23:17
 * @description: 动态管理定时任务
 */

@Component
@Slf4j
public class DynamicTaskManager {

    // Spring 提供的线程安全调度器，自动注入
    @Autowired
    private TaskScheduler taskScheduler;

    // 保存任务引用，方便后续取消（key 可以是 pluginId）
    private final Map<String, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();

    /**
     * 通过句柄登记的任务集合。
     *
     * <p>供新 API {@link #scheduleCron(String, Runnable)} 使用——调用方持有
     * 句柄自行取消，无需字符串 ID。这里额外留一份引用是为了让
     * {@link #cancelAllTasks()} 能覆盖到它们。</p>
     */
    private final java.util.Set<ScheduledFuture<?>> trackedFutures =
            ConcurrentHashMap.newKeySet();

    /**
     * 动态添加一个 cron 定时任务
     *
     * @param taskId        任务唯一 ID（如 pluginId），用于后续取消
     * @param cronExpression cron 表达式，如 "0/10 * * * * ?"
     * @param task          要执行的逻辑（Runnable）
     */
    public void addCronTask(String taskId, String cronExpression, Runnable task) {
        // 防止重复注册
        if (tasks.containsKey(taskId)) {
            throw new IllegalArgumentException("任务已存在: " + taskId);
        }

        // 创建触发器
        CronTrigger trigger = new CronTrigger(cronExpression);

        // 注册任务，并保存返回的 future
        ScheduledFuture<?> future = taskScheduler.schedule(() -> {
            try {
                CompletableFuture.runAsync(() -> {
                    log.debug("定时任务 [" + taskId + "] 正在执行...");
                    task.run();
                });
            } catch (Exception e) {
                // 记录异常，避免静默失败
                log.info("任务 [" + taskId + "] 执行出错: " + e.getMessage());
            }
        }, trigger);

        tasks.put(taskId, future);
        log.info("已注册定时任务: " + taskId + " | cron = " + LogStringUtil.buildBrightRedLog(cronExpression));
    }

    /**
     * 取消指定任务
     */
    public void cancelTask(String taskId) {
        ScheduledFuture<?> future = tasks.remove(taskId);
        if (future != null) {
            future.cancel(true); // true = 中断正在执行的任务
            log.info("已取消任务: " + taskId);
        }
    }

    /**
     * 批量取消任务。
     *
     * <p>同时覆盖两种 API 注册的任务：字符串 ID 的（{@link #addCronTask}）
     * 与句柄式的（{@link #scheduleCron}）。</p>
     */
    public void cancelAllTasks() {
        for (String taskId : tasks.keySet()) {
            cancelTask(taskId);
        }
        // 句柄式任务没有 taskId，需单独遍历取消
        for (ScheduledFuture<?> future : java.util.Set.copyOf(trackedFutures)) {
            cancelTask(future);
        }
    }

    // ==================== 基于句柄的 API（插件系统重构新增） ====================

    /**
     * 调度一个 cron 任务并返回其句柄。
     *
     * <p>与 {@link #addCronTask(String, String, Runnable)} 的区别：</p>
     * <ul>
     *   <li><b>用句柄而非字符串 ID 取消</b>。字符串 ID 方案要求调用方自己保证
     *       ID 唯一，而现行系统用 {@code pluginFullId} 作 ID，导致「一个插件
     *       只能注册一个定时任务」这一隐性限制。</li>
     *   <li><b>异常真的被兜住</b>。现行实现把 {@code task.run()} 放进
     *       {@code CompletableFuture.runAsync}，而 try/catch 在 runAsync
     *       <i>外面</i>——异步体里抛出的异常根本不会进入那个 catch，
     *       只会被 commonPool 静默吞掉。新方法在任务体内直接捕获
     *       {@code Throwable}。</li>
     *   <li><b>不重复执行</b>：不再包一层 runAsync，由 Spring 的
     *       {@code TaskScheduler} 线程池直接执行。</li>
     * </ul>
     *
     * <p>返回的句柄同时被登记到批量取消集合中，因此
     * {@link #cancelAllTasks()} 对它同样有效。</p>
     *
     * @param cronExpression cron 表达式（Spring 六/七段格式）
     * @param task           任务体
     * @return 任务句柄，可用于 {@link #cancelTask(ScheduledFuture)}
     * @throws IllegalArgumentException cron 表达式非法
     */
    public ScheduledFuture<?> scheduleCron(String cronExpression, Runnable task) {
        CronTrigger trigger = new CronTrigger(cronExpression);
        ScheduledFuture<?> future = taskScheduler.schedule(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                // 必须捕获 Throwable：Error（如类加载失败）同样不能被静默吞掉
                log.error("定时任务执行失败 | cron = " + cronExpression, t);
            }
        }, trigger);
        if (future != null) {
            trackedFutures.add(future);
        }
        log.info("已注册定时任务: cron = " + LogStringUtil.buildBrightRedLog(cronExpression));
        return future;
    }

    /**
     * 取消一个由 {@link #scheduleCron(String, Runnable)} 返回的任务。
     *
     * @param future 任务句柄；{@code null} 时为空操作
     * @return 实际取消了任务时返回 {@code true}
     */
    public boolean cancelTask(ScheduledFuture<?> future) {
        if (future == null) {
            return false;
        }
        trackedFutures.remove(future);
        boolean cancelled = future.cancel(true); // true = 中断正在执行的任务
        if (cancelled) {
            log.debug("已取消定时任务（句柄）");
        }
        return cancelled;
    }

    /**
     * 当前已登记的定时任务总数（含两种 API 注册的任务）。
     *
     * <p>用于泄漏检测：插件卸载后该计数应回到卸载前的水平。</p>
     *
     * @return 任务总数
     */
    public int taskCount() {
        return tasks.size() + trackedFutures.size();
    }
}
