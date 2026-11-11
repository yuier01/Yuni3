package com.yuier.yuni.plugin.runtime.service;

import com.yuier.yuni.core.bot.JsonCodec;
import com.yuier.yuni.plugin.api.service.Http;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link Http} 的默认实现——基于共享 {@link RestTemplate} 的阻塞式客户端。
 *
 * <h2>取代了什么</h2>
 * <p>现行 {@code PluginUtils.simpleGet} / {@code simplePost}：</p>
 * <ul>
 *   <li><b>每次调用 new RestTemplate()</b>——不复用连接池，每次重新建 TCP 连接。
 *       对定时轮询类插件（steam 状态监控、每日早报）是持续浪费。
 *       本实现持有单个实例，连接池与线程安全由 Spring 保证。</li>
 *   <li><b>没有超时</b>——{@code RestTemplate} 默认无超时，对端不响应会永久挂住
 *       调用线程。本实现默认连接/读取各 5 秒，并允许逐请求覆盖。</li>
 *   <li><b>simpleGet 的 params 实为 URL 模板变量</b>而非查询参数（审计报告记录的
 *       命名误导）。本实现把 {@link Request#query} 明确实现为<b>查询参数</b>，
 *       路径变量的需求可自行在 URL 里拼好或用 {@code {placeholder}} 形式。</li>
 * </ul>
 *
 * <h2>错误语义</h2>
 * <p>三类失败统一抛 {@link HttpException} 并带上 URL 与状态码：
 * 网络错误、非 2xx 响应、JSON 反序列化失败。
 * {@code executeIgnoringResult()} 是唯一的例外，它捕获全部异常并返回布尔值，
 * 供 webhook 这类「发出即可」的场景使用。</p>
 *
 * @see Http
 */
@Slf4j
public final class RestTemplateHttp implements Http {

    /** 默认连接超时。 */
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** 默认读取超时。 */
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(5);

    /** 共享的 RestTemplate；线程安全，可被多个插件的请求复用。 */
    private final RestTemplate restTemplate;

    /** JSON 编解码器，用于请求体序列化与响应反序列化。 */
    private final JsonCodec jsonCodec;

    /**
     * 以默认超时构造客户端。
     *
     * @param jsonCodec JSON 编解码器
     */
    public RestTemplateHttp(JsonCodec jsonCodec) {
        this(jsonCodec, DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
    }

    /**
     * 以指定超时构造客户端。
     *
     * @param jsonCodec       JSON 编解码器
     * @param connectTimeout  连接超时
     * @param readTimeout     读取超时
     */
    public RestTemplateHttp(JsonCodec jsonCodec, Duration connectTimeout, Duration readTimeout) {
        this.jsonCodec = jsonCodec;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) connectTimeout.toMillis());
        factory.setReadTimeout((int) readTimeout.toMillis());
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * 取底层 {@link RestTemplate}。
     *
     * <p>供需要自定义拦截器或消息转换器的场景。插件不应直接依赖它——
     * 一旦如此就绕过了本接口的错误语义统一。</p>
     *
     * @return 底层实例
     */
    public RestTemplate getRawTemplate() {
        return restTemplate;
    }

    @Override
    public Request get(String url) {
        return new RequestImpl(HttpMethod.GET, url);
    }

    @Override
    public Request post(String url) {
        return new RequestImpl(HttpMethod.POST, url);
    }

    /**
     * 单次请求的构建与执行实现。
     *
     * <p>非线程安全：一个实例只应被一个调用链使用（每次 {@code get/post}
     * 都会新建）。</p>
     */
    private final class RequestImpl implements Request {

        /** HTTP 方法。 */
        private final HttpMethod method;

        /** 目标 URL。 */
        private final String url;

        /** 查询参数，保持插入顺序。 */
        private final Map<String, String> queryParams = new LinkedHashMap<>();

        /** 请求头。 */
        private final HttpHeaders headers = new HttpHeaders();

        /** 请求体（已序列化的字符串）。 */
        private String body;

        /** 是否把请求体标记为 JSON。 */
        private boolean jsonBody;

        /** 本次请求的超时覆盖；{@code null} 表示沿用客户端默认。 */
        private Duration timeout;

        private RequestImpl(HttpMethod method, String url) {
            this.method = method;
            this.url = url;
        }

        @Override
        public Request query(String name, Object value) {
            if (name != null && value != null) {
                queryParams.put(name, String.valueOf(value));
            }
            return this;
        }

        @Override
        public Request header(String name, String value) {
            if (name != null && value != null) {
                headers.set(name, value);
            }
            return this;
        }

        @Override
        public Request jsonBody(Object body) {
            if (body != null) {
                this.body = jsonCodec.toJson(body);
                this.jsonBody = true;
            }
            return this;
        }

        @Override
        public Request stringBody(String body) {
            this.body = body;
            this.jsonBody = false;
            return this;
        }

        @Override
        public Request timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        @Override
        public <T> T execute(Class<T> type) {
            ResponseEntity<String> response = exchange();
            String responseBody = response.getBody();
            if (responseBody == null || responseBody.isBlank()) {
                throw new HttpException(url, response.getStatusCode().value(),
                        "响应体为空，无法反序列化为 " + type.getSimpleName(), null);
            }
            try {
                return jsonCodec.fromJson(responseBody, type);
            } catch (Exception e) {
                throw new HttpException(url, response.getStatusCode().value(),
                        "响应反序列化失败（目标 " + type.getSimpleName() + "）: " + snippet(responseBody), e);
            }
        }

        @Override
        public String executeAsString() {
            ResponseEntity<String> response = exchange();
            return response.getBody();
        }

        @Override
        public boolean executeIgnoringResult() {
            try {
                exchange();
                return true;
            } catch (Exception e) {
                // webhook 类场景：调用方只关心「发出去了」，不关心业务结果
                log.debug("HTTP 请求失败（已忽略）: {} {}", method, url, e);
                return false;
            }
        }

        /**
         * 执行请求并返回响应，把各类失败统一包装为 {@link HttpException}。
         *
         * @return 响应实体
         * @throws HttpException 网络失败或非 2xx 响应
         */
        private ResponseEntity<String> exchange() {
            URI uri = buildUri();
            HttpEntity<String> entity = buildEntity();
            try {
                ResponseEntity<String> response =
                        timeout == null
                                ? restTemplate.exchange(uri, method, entity, String.class)
                                : exchangeWithTimeout(uri, entity);
                return response;
            } catch (RestClientResponseException e) {
                // 非 2xx：RestTemplate 默认抛此异常，携带状态码与响应体
                throw new HttpException(url, e.getStatusCode().value(),
                        "非成功响应: " + snippet(e.getResponseBodyAsString()), e);
            } catch (HttpException e) {
                throw e;
            } catch (Exception e) {
                // 连接失败、超时、DNS 解析失败等
                throw new HttpException(url, -1, "请求失败: " + e.getMessage(), e);
            }
        }

        /**
         * 以本次请求专属的超时执行。
         *
         * <p>Spring 的 {@code RestTemplate} 不支持逐请求超时，因此这里临时
         * 构造一个带该超时的 RestTemplate。这会有一次对象创建开销，
         * 但只在显式调用 {@link #timeout} 时发生——默认路径复用共享实例。</p>
         *
         * @param uri    目标 URI
         * @param entity 请求实体
         * @return 响应实体
         */
        private ResponseEntity<String> exchangeWithTimeout(URI uri, HttpEntity<String> entity) {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout((int) timeout.toMillis());
            factory.setReadTimeout((int) timeout.toMillis());
            return new RestTemplate(factory).exchange(uri, method, entity, String.class);
        }

        /**
         * 拼装最终 URI（含查询参数）。
         *
         * @return 目标 URI
         */
        private URI buildUri() {
            UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(url);
            queryParams.forEach(builder::queryParam);
            return builder.build().encode().toUri();
        }

        /**
         * 构造请求实体。
         *
         * @return 请求实体
         */
        private HttpEntity<String> buildEntity() {
            if (jsonBody) {
                headers.setContentType(MediaType.APPLICATION_JSON);
            }
            return new HttpEntity<>(body, headers);
        }
    }

    /**
     * 截取响应片段用于错误信息，避免把整页 HTML 塞进异常。
     *
     * @param body 响应体
     * @return 最多 200 字符的片段
     */
    private static String snippet(String body) {
        if (body == null) {
            return "(无响应体)";
        }
        String trimmed = body.strip();
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 200) + "...";
    }
}
