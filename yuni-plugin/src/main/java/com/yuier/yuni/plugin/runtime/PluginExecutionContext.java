package com.yuier.yuni.plugin.runtime;

/**
 * 以插件类加载器作为线程上下文类加载器执行插件代码。
 *
 * <p>插件处理器通常运行在 {@code ForkJoinPool.commonPool} 上，该线程的 TCCL 是
 * 应用类加载器。fat jar 部署时它看不到 {@code BOOT-INF/lib} 中嵌套 jar 的资源，
 * 例如 Playwright 的 {@code driver/linux-amd64}，最终表现为驱动包资源
 * {@code getResource(...)} 返回 {@code null}。</p>
 *
 * <p>插件代码执行期间临时切换 TCCL，结束后恢复原值；异常路径也必须恢复，
 * 避免污染 ForkJoinPool 的复用线程。</p>
 */
public final class PluginExecutionContext {

    private PluginExecutionContext() {
    }

    /**
     * 以插件类加载器执行一段代码。
     *
     * @param fiber  插件 Fiber；可为 {@code null}，此时不切换 TCCL
     * @param action 待执行代码
     * @throws Exception 代码抛出的异常
     */
    public static void run(PluginFiber fiber, ThrowingRunnable action) throws Exception {
        if (action == null) {
            throw new IllegalArgumentException("执行体不能为 null");
        }
        ClassLoader target = fiber == null ? null : fiber.getClassLoader();
        if (target == null) {
            action.run();
            return;
        }

        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(target);
        try {
            action.run();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /**
     * 可抛异常的 Runnable。
     */
    @FunctionalInterface
    public interface ThrowingRunnable {

        /**
         * 执行。
         *
         * @throws Exception 执行失败
         */
        void run() throws Exception;
    }
}
