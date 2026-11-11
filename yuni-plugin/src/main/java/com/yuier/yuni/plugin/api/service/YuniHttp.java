package com.yuier.yuni.plugin.api.service;

import lombok.Getter;

import java.time.Duration;

/**
 * HTTP 客户端门面。
 *
 * <h2>取代了什么</h2>
 * <p>现行 {@code PluginUtils.simpleGet} / {@code simplePost} 存在三个问题：</p>
 * <ol>
 *   <li><b>每次调用都 {@code new RestTemplate()}</b>——不复用连接池，
 *       且每次都要重新建 TCP 连接，对定时轮询类插件（如 steam 状态监控）
 *       是持续的浪费；</li>
 *   <li><b>没有超时配置</b>——{@code RestTemplate} 默认无超时，
 *       对端不响应时会永久挂住调用线程；</li>
 *   <li><b>{@code simpleGet} 的 {@code params} 实为 URL 模板变量而非查询参数</b>
 *       （审计报告已记录该命名误导）。</li>
 * </ol>
 * <p>本接口复用连接池、默认带超时，并把「路径变量」与「查询参数」分开。</p>
 *
 * <h2>用法</h2>
 * <pre>{@code
 * // GET，带查询参数
 * PigImageList list = ctx.getHttp()
 *         .get("https://api.example.com/images")
 *         .query("page", 1)
 *         .query("size", 20)
 *         .execute(PigImageList.class);
 *
 * // POST JSON
 * LoliconApiResponse resp = ctx.getHttp()
 *         .post("https://api.lolicon.app/setu/v2")
 *         .jsonBody(Map.of("r18", 0, "num", 1))
 *         .execute(LoliconApiResponse.class);
 *
 * // 直接取字符串（非 JSON 响应）
 * String text = ctx.getHttp().get(url).executeAsString();
 * }</pre>
 *
 * <h2>默认行为</h2>
 * <ul>
 *   <li>连接超时与读取超时默认 5 秒，可在请求上覆盖；</li>
 *   <li>非 2xx 响应抛 {@link HttpException}；</li>
 *   <li>JSON 反序列化失败抛 {@link HttpException}，不返回 {@code null}。</li>
 * </ul>
 * <p>与现行实现「失败时抛 {@code RuntimeException} 且信息含糊」不同，
 * 异常里带 URL、状态码与响应片段，便于定位。</p>
 */
public interface YuniHttp {

    /**
     * 发起 GET 请求。
     *
     * @param url 完整 URL（不含查询串，查询参数用 {@code query(...)} 追加）
     * @return 请求构建器
     */
    Request get(String url);

    /**
     * 发起 POST 请求。
     *
     * @param url 完整 URL
     * @return 请求构建器
     */
    Request post(String url);

    /**
     * 一次 HTTP 请求的构建器。
     *
     * <p>全部设置方法返回 {@code this}，以 {@code execute*} 终结。</p>
     */
    interface Request {

        /**
         * 追加查询参数。
         *
         * @param name  参数名
         * @param value 参数值；会被转为字符串
         * @return {@code this}
         */
        Request query(String name, Object value);

        /**
         * 追加请求头。
         *
         * @param name  头名
         * @param value 头值
         * @return {@code this}
         */
        Request header(String name, String value);

        /**
         * 设置 JSON 请求体。
         *
         * @param body 会被序列化为 JSON 的对象
         * @return {@code this}
         */
        Request jsonBody(Object body);

        /**
         * 设置原始字符串请求体。
         *
         * @param body 请求体
         * @return {@code this}
         */
        Request stringBody(String body);

        /**
         * 覆盖本次请求的超时。
         *
         * @param timeout 超时时长
         * @return {@code this}
         */
        Request timeout(Duration timeout);

        /**
         * 执行请求并把响应体反序列化为指定类型。
         *
         * @param type 目标类型
         * @param <T>  目标类型
         * @return 反序列化结果
         * @throws HttpException 网络失败、非 2xx 响应或反序列化失败
         */
        <T> T execute(Class<T> type);

        /**
         * 执行请求并返回响应体字符串。
         *
         * @return 响应体
         * @throws HttpException 网络失败或非 2xx 响应
         */
        String executeAsString();

        /**
         * 执行请求，只关心是否成功。
         *
         * <p>用于 webhook 之类的「发出即可」场景。方法内部捕获异常并返回
         * 布尔值，调用方无需 try/catch。</p>
         *
         * @return 2xx 响应返回 {@code true}
         */
        boolean executeIgnoringResult();
    }

    /**
     * HTTP 调用失败异常。
     *
     * <p>统一承载「网络错误」「非 2xx」「反序列化失败」三类失败，
     * 并把 URL 与状态码放进消息，替代现行实现里含糊的
     * {@code RuntimeException}。</p>
     */
    @Getter
    class HttpException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /** 出错的请求 URL；非网络阶段失败时可能为 {@code null}。
         *  访问器 {@code getUrl()} 由 @Getter + @Accessors(fluent) 生成。 */
        private final String url;

        /** HTTP 状态码；未收到响应时为 {@code -1}。
         *  访问器 {@code getStatusCode()} 由 @Getter + @Accessors(fluent) 生成。 */
        private final int statusCode;

        /**
         * 构造异常。
         *
         * @param url        请求 URL
         * @param statusCode 状态码；无响应传 {@code -1}
         * @param message    描述
         * @param cause      根因
         */
        public HttpException(String url, int statusCode, String message, Throwable cause) {
            super("HTTP " + (statusCode > 0 ? statusCode + " " : "") + "[" + url + "] " + message, cause);
            this.url = url;
            this.statusCode = statusCode;
        }
    }
}
