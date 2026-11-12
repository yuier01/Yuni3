package com.yuier.yuni.plugin.api;

import com.yuier.yuni.core.bot.YuniBot;
import com.yuier.yuni.event.detector.message.command.model.CommandNode;
import com.yuier.yuni.plugin.api.event.MessageEvent;
import com.yuier.yuni.plugin.api.event.MessageSentEvent;
import com.yuier.yuni.plugin.api.event.MetaEvent;
import com.yuier.yuni.plugin.api.event.NoticeEvent;
import com.yuier.yuni.plugin.api.event.RequestEvent;
import com.yuier.yuni.plugin.api.handler.MessageHandler;
import com.yuier.yuni.plugin.api.handler.MessageSentHandler;
import com.yuier.yuni.plugin.api.handler.MetaHandler;
import com.yuier.yuni.plugin.api.handler.NoticeHandler;
import com.yuier.yuni.plugin.api.handler.RequestHandler;
import com.yuier.yuni.plugin.api.service.*;
import com.yuier.yuni.plugin.api.service.YuniCache;
import com.yuier.yuni.plugin.data.PluginDataService;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import org.slf4j.Logger;

/**
 * 插件上下文——插件与框架交互的<b>唯一</b>入口。
 *
 * <h2>它取代了什么</h2>
 * <p>现行系统给插件暴露的是一个静态工具门面 {@code PluginUtils}，里面有
 * {@code getYuniBot()} / {@code getBean()} / {@code sendGroupMessage()} /
 * {@code simplePost()} / {@code registerEntity()} 等 20 多个静态方法。
 * 这带来三个问题：</p>
 * <ol>
 *   <li><b>无法测试</b>：静态方法无法打桩，插件的任何逻辑都必须起整个 Spring 容器；</li>
 *   <li><b>隐式全局状态</b>：{@code SpringContextUtil.getBean(...)} 让插件可以拿到
 *       任意 Bean，依赖关系不可见；</li>
 *   <li><b>生命周期不可追踪</b>：静态调用建立的注册（如 {@code DynamicTaskManager}
 *       里的定时任务）框架不知道归属，卸载时无法自动清理。</li>
 * </ol>
 * <p>本接口取代它，并附带三个性质：<b>可打桩</b>（是接口）、<b>依赖显式</b>
 * （通过 {@link #service(Class)} 与 {@code YuniPlugin.inject()}）、
 * <b>可撤销</b>（全部注册方法返回 {@link Disposable}）。</p>
 *
 * <h2>核心不变式</h2>
 * <blockquote>
 *   <b>{@code ctx} 上的一切注册都是 PluginEffect——在插件卸载时自动撤销。</b>
 * </blockquote>
 * <p>唯一的例外是框架不认识的资源（自建连接、线程池、文件句柄），
 * 它们必须通过 {@link #effect(PluginEffect)} 声明清理方式。</p>
 *
 * <h2>一个典型插件的完整写法</h2>
 * <pre>{@code
 * public void apply(YuniContext ctx) {
 *     // 1. 基础设施
 *     ctx.getDb().register(UserSteamId.class);
 *     SteamApiKey key = ctx.getConfig().load("api_key.json", SteamApiKey.class);
 *
 *     // 2. 定时任务
 *     ctx.schedule("0 0/5 * * * ?", () -> pollSteam(key));
 *
 *     // 3. 事件处理（条件与行为写在一起）
 *     ctx.onMessage(ev -> ev.getChain().startsWithText() && ev.getChain().containsText("/steam"),
 *                   (ev, reply) -> handleSteamCommand(ev, reply));
 *
 *     // 4. 自定义资源
 *     ctx.effect(() -> {
 *         HttpClient client = HttpClient.newHttpClient();
 *         return client::close;
 *     });
 * }
 * }</pre>
 *
 * @see YuniPlugin
 * @see Disposable
 * @see PluginEffect
 */
public interface YuniContext {

    // ==================== 元信息 ====================

    /**
     * 当前作用域标识。
     *
     * <p>新系统中插件<b>全局只有一个实例</b>，因此本方法通常返回固定值
     * （如 {@code "default"}）。保留它是为了将来可能出现真正需要独立作用域的
     * 场景（如多机器人实例）。</p>
     *
     * @return 作用域标识
     */
    String getScopeId();

    /**
     * 本插件的日志器。
     *
     * <p>已带上插件名前缀，插件内应使用 {@code ctx.getLogger().info(...)}
     * 而不是自建 {@code LoggerFactory.getLogger(...)}——这样日志格式统一，
     * 且能从日志反查是哪个插件输出的。</p>
     *
     * @return 日志器，永不为 {@code null}
     */
    Logger getLogger();

    /**
     * 本插件的元数据。
     *
     * @return 元数据，永不为 {@code null}
     */
    PluginMetadata getSelf();

    // ==================== 事件注册 ====================

    /**
     * 注册消息事件处理器（无条件）。
     *
     * <p>等价于 {@code onMessage(ev -> true, handler)}。显式写出「无条件」
     * 是刻意的——让读者知道这里不是漏了条件。</p>
     *
     * @param handler 处理器
     * @return 撤销句柄
     */
    Disposable onMessage(MessageHandler handler);

    /**
     * 注册消息事件处理器（带探测条件）。
     *
     * <p>这是新系统在开发体验上的核心改进：<b>探测条件与执行入口写在同一个
     * 表达式里</b>，不再像现行系统那样拆成 {@code getDetector()} 与
     * {@code execute()} 两个相距几十行的方法。</p>
     *
     * <pre>{@code
     * ctx.onMessage(ev -> ev.getChain().containsText("granbluefantasy.jp"),
     *               (ev, reply) -> reply.text("大家不要信，这是骑空士的陷阱！"));
     * }</pre>
     *
     * @param when    探测条件；{@code null} 视为无条件
     * @param handler 处理器
     * @return 撤销句柄
     */
    Disposable onMessage(Predicate<MessageEvent> when, MessageHandler handler);

    /**
     * 注册通知事件处理器（按子类型过滤）。
     *
     * <p>子类型判定在注册时完成，处理器拿到的必定是目标子类型，
     * 无需在处理器里再判定。</p>
     *
     * <pre>{@code
     * ctx.onNotice("poke", (ev, reply) -> { ... });
     * }</pre>
     *
     * @param subType 通知子类型（如 {@code "poke"} / {@code "group_admin"}）；
     *                {@code null} 或空串表示不过滤
     * @param handler 处理器
     * @return 撤销句柄
     */
    Disposable onNotice(String subType, NoticeHandler handler);

    /**
     * 注册通知事件处理器（带探测条件）。
     *
     * @param when    探测条件；{@code null} 视为无条件
     * @param handler 处理器
     * @return 撤销句柄
     */
    Disposable onNotice(Predicate<NoticeEvent> when, NoticeHandler handler);

    /**
     * 注册请求事件处理器（按子类型过滤）。
     *
     * @param subType 请求子类型（{@code "friend"} / {@code "group"} / {@code "add"} /
     *                {@code "invite"}）；{@code null} 或空串表示不过滤
     * @param handler 处理器
     * @return 撤销句柄
     */
    Disposable onRequest(String subType, RequestHandler handler);

    /**
     * 注册请求事件处理器（带探测条件）。
     *
     * @param when    探测条件；{@code null} 视为无条件
     * @param handler 处理器
     * @return 撤销句柄
     */
    Disposable onRequest(Predicate<RequestEvent> when, RequestHandler handler);

    /**
     * 注册元事件处理器（按类型过滤）。
     *
     * @param subType 元事件类型（{@code "heartbeat"} / {@code "lifecycle"}）；
     *                {@code null} 或空串表示不过滤
     * @param handler 处理器
     * @return 撤销句柄
     */
    Disposable onMeta(String subType, MetaHandler handler);

    /**
     * 注册元事件处理器（带探测条件）。
     *
     * @param when    探测条件；{@code null} 视为无条件
     * @param handler 处理器
     * @return 撤销句柄
     */
    Disposable onMeta(Predicate<MetaEvent> when, MetaHandler handler);

    /**
     * 注册「机器人自身发出消息」事件处理器。
     *
     * <p>现行 {@code MessageSentPlugin} 无条件是这类事件的特征。新系统中
     * 「无条件」需要显式写出：{@code ctx.onMessageSent(ev -> true, handler)}。</p>
     *
     * @param when    探测条件；{@code null} 视为无条件
     * @param handler 处理器
     * @return 撤销句柄
     */
    Disposable onMessageSent(Predicate<MessageSentEvent> when, MessageSentHandler handler);

    // ==================== 命令注册 ====================

    /**
     * 注册一棵命令树。
     *
     * <p><b>命令系统沿用现有声明框架</b>（{@code CommandNode.builder(...)}），
     * 处理器通过 {@code .on(...)} 就地绑定在节点上：</p>
     *
     * <pre>{@code
     * ctx.command(CommandNode.builder("安慕希")
     *     .description("MC 服务器管理")
     *     .on((ev, reply) -> MCStatus.query(ev))          // 无子命令时的默认行为
     *     .child(CommandNode.builder("添加")
     *         .description("添加 MC 服务器")
     *         .arg(ArgDef.required("服务器名称", "MC 服务器名称"))
     *         .arg(ArgDef.required("服务器地址", "服务器地址"))
     *         .on((ev, reply) -> MCServer.add(ev))        // 处理器紧贴它自己的参数声明
     *         .build())
     *     .build());
     * }</pre>
     *
     * <p>分发时框架取「匹配到的最深节点」的处理器，因此现行
     * {@code MCManage.execute()} 里那串 {@code if (result.hasChild(...))} 分派链
     * 可以被删除。参数取值仍走 {@code ev.getCommandResult()}，与现行写法一致。</p>
     *
     * @param tree 命令树根节点
     * @return 撤销句柄
     */
    Disposable command(CommandNode tree);

    // ==================== 调度 ====================

    /**
     * 注册 cron 定时任务。
     *
     * <p>对应现行 {@code ScheduledPlugin} + {@code cronExpression()}。
     * cron 表达式为 <b>Spring 六/七段格式</b>（秒 分 时 日 月 周 [年]），
     * 可用 {@code CronExpressionBuilder} 构造。</p>
     *
     * <pre>{@code
     * ctx.schedule(CronExpressionBuilder.create().dailyAt(8, 30).build(), this::sendMorningNews);
     * }</pre>
     *
     * <p>插件卸载时任务自动取消——不需要现行代码里那样的
     * {@code DynamicTaskManager.cancelTask(fullId)} 手动调用。</p>
     *
     * @param cron Spring cron 表达式
     * @param task 任务体
     * @return 撤销句柄
     */
    Disposable schedule(String cron, Runnable task);

    /**
     * 注册固定间隔的周期任务。
     *
     * @param interval 执行间隔；必须为正
     * @param task     任务体
     * @return 撤销句柄
     */
    Disposable every(Duration interval, Runnable task);

    /**
     * 立即异步执行一次。
     *
     * <p>对应现行 {@code ImmediatePlugin}——它的全部语义就是「注册后执行一次」，
     * 因此无需单独的类型，本方法即可覆盖。</p>
     *
     * <p>任务在当前线程之外异步执行，不阻塞 {@code apply(ctx)}。</p>
     *
     * @param task 任务体
     * @return 撤销句柄（任务已提交，撤销仅保证不再重复执行）
     */
    Disposable runNow(Runnable task);

    // ==================== 自定义资源 ====================

    /**
     * 声明一个框架不认识的资源及其清理方式。
     *
     * <pre>{@code
     * ctx.effect(() -> {
     *     MaiMaiWsServer server = new MaiMaiWsServer(port);
     *     server.start();
     *     return server::shutdown;      // 插件卸载时调用
     * });
     * }</pre>
     *
     * <p>撤销顺序：全部 Disposer 按注册的<b>逆序</b>执行。</p>
     *
     * @param effect 资源获取与释放声明
     * @return 撤销句柄
     */
    Disposable effect(PluginEffect effect);

    // ==================== 服务 ====================

    /**
     * 解析服务实例。
     *
     * <p>取代现行 {@code PluginUtils.getBean(Class)}。与 Spring 的 {@code getBean}
     * 不同，这里解析的是<b>插件系统中注册的服务</b>——框架内置服务与插件提供的
     * 服务在同一命名空间里，插件之间因此可以互相调用。</p>
     *
     * @param type 服务类型；通常是接口
     * @param <T>  服务类型
     * @return 服务实例
     * @throws IllegalStateException 服务未注册时抛出
     */
    <T> T service(Class<T> type);

    /**
     * 解析服务实例（不存在时返回空）。
     *
     * <p>用于可选依赖：插件在服务缺失时应降级而非崩溃。</p>
     *
     * @param type 服务类型
     * @param <T>  服务类型
     * @return 服务实例；未注册时为空
     */
    <T> Optional<T> serviceIfPresent(Class<T> type);

    /**
     * 注册一个服务供其他插件使用。
     *
     * <p>是 PluginEffect——插件卸载时服务自动注销，依赖它的插件会转为
     * {@code PENDING} 或失败（取决于依赖是必需还是可选）。</p>
     *
     * <pre>{@code
     * ctx.provide(MaiMaiApi.class, new MaiMaiApiImpl(server));
     * }</pre>
     *
     * @param type 服务类型（通常是接口）
     * @param impl 实现实例
     * @return 撤销句柄
     */
    Disposable provide(Class<?> type, Object impl);

    /**
     * 注册一个服务，实现由工厂函数<b>按服务类型</b>创建。
     *
     * <h3>为什么需要这个重载</h3>
     * <p>当服务契约是<b>函数式接口</b>（例如 {@code interface Greeter { String greet(); }}）
     * 时，想把一个 lambda 直接注册为服务会遇到 Java 类型推断的硬限制：</p>
     *
     * <pre>{@code
     * ctx.provide(Greeter.class, () -> "hello");          // ✗ 编译失败
     * }</pre>
     *
     * <p>{@link #provide(Class, Object)} 的第二参数是 {@code Object}，
     * 编译器没有任何信息把 lambda 推断成 {@code Greeter}，于是把它当成
     * 「目标类型为 Object 的 lambda」——而 {@code Object} 不是函数式接口，报错。
     * 写法只能是带转型的丑陋形式：{@code (Greeter) () -> "hello"}。</p>
     *
     * <p>本重载把服务类型<b>同时作为工厂的返回值类型</b>，编译器于是能把 lambda
     * 正确推断为 {@code Greeter}：</p>
     *
     * <pre>{@code
     * ctx.provide(Greeter.class, type -> () -> "hello");  // ✓
     * // 或更简洁：对函数式契约用方法引用
     * ctx.provide(Greeter.class, type -> this::greet);
     * }</pre>
     *
     * <p>工厂参数 {@code type} 就是传入的服务类型，便于同一实现在多个契约间分派。</p>
     *
     * @param type    服务类型
     * @param factory 由服务类型创建实现的工厂
     * @param <T>     服务类型
     * @return 撤销句柄
     */
    <T> Disposable provide(Class<T> type, Function<Class<T>, T> factory);

    /**
     * 作用域查询——「本插件在某个群是否生效」。
     *
     * <p>主动插件（定时任务）需要它：这类插件不由事件触发，因此没有「当前群号」
     * 上下文，框架无法自动施加 {@link ScopeFilter} 过滤。插件在向一批群广播时
     * 需要自己尊重群粒度启停，而本服务提供了稳定的查询入口——
     * 取代现行插件直接依赖框架内部的 {@code PluginEnableProcessor}。</p>
     *
     * <pre>{@code
     * ScopeQuery scope = ctx.getScope();
     * for (BotGroupInfo group : bot.getGroupList().orElse(List.of())) {
     *     if (!scope.isEnabledIn(group.getGroupId())) continue;
     *     ctx.getSender().group(group.getGroupId()).text("...").send();
     * }
     * }</pre>
     *
     * @return 作用域查询服务，永不为 {@code null}
     */
    ScopeQuery getScope();

    /**
     * 插件管理服务——枚举、启停与重载插件。
     *
     * <p>供<b>管理类插件</b>使用。Yuni 内置的 {@code plugin-manage} 模块本身就是
     * 一个插件，它需要在聊天里展示与操作插件列表；webapi 的管理接口同样需要。
     * 本方法取代的是现行插件直接依赖 {@code PluginContainer} /
     * {@code PluginEnableProcessor} / {@code PluginReloadProcessor} 这些框架内部类
     * 的做法。</p>
     *
     * <p>普通插件<b>不应</b>使用本服务——它与 {@link #service(Class)} 的区别是：
     * 服务用于「我需要某个能力」，而本方法用于「我要管理别的插件」。
     * 后者是管理端职责，不是插件间协作。</p>
     *
     * <pre>{@code
     * PluginAdmin admin = ctx.getPlugins();
     * for (PluginModuleView module : admin.getModules()) {
     *     for (PluginView plugin : module.getPlugins()) {
     *         log.info("{} [{}] 在群 {} 生效={}",
     *                 plugin.getName(), plugin.getState().getDisplayName(),
     *                 groupId, admin.isEnabledIn(plugin.getId(), groupId));
     *     }
     * }
     * }</pre>
     *
     * @return 插件管理服务，永不为 {@code null}
     * @see PluginAdmin
     */
    PluginAdmin getPlugins();

    // ==================== 作用域状态 ====================

    /**
     * 取当前作用域的状态对象，不存在时创建。
     *
     * <p>用于<b>唯一</b>需要群内可变状态的场景。取代「每群一份插件实例」的方案：
     * 插件仍只有一个实例，状态按作用域分键存放。</p>
     *
     * <h3>要求</h3>
     * <ul>
     *   <li>类型必须有可访问的<b>无参构造器</b>；</li>
     *   <li>类型应当<b>线程安全</b>——处理器在异步线程上并发执行，
     *       内部集合建议用 {@code ConcurrentHashMap}；</li>
     *   <li>同一个类型在同一个作用域内返回<b>同一个实例</b>。</li>
     * </ul>
     *
     * <pre>{@code
     * public static final class PokeState {
     *     final Map<Long, Integer> countPerUser = new ConcurrentHashMap<>();
     * }
     *
     * ctx.onNotice("poke", (ev, reply) -> {
     *     PokeState st = ctx.state(PokeState.class);
     *     int n = st.countPerUser.merge(ev.getUserId(), 1, Integer::sum) % MESSAGES.length;
     *     reply.at(ev.getUserId()).text(MESSAGES[n]).send();
     * });
     * }</pre>
     *
     * @param type 状态类型
     * @param <T>  状态类型
     * @return 状态实例
     */
    <T> T state(Class<T> type);

    // ==================== 内置服务快捷方式 ====================

    /**
     * 机器人通信服务。
     *
     * <p>等价 {@code service(YuniBot.class)}，因使用频率极高而提供快捷方法。</p>
     *
     * @return 机器人实例
     */
    YuniBot getBot();

    /**
     * 消息发送门面。
     *
     * <p>取代现行 {@code PluginUtils.sendGroupMessage(...)} 与
     * {@code PluginUtils.sendPrivateMessage(...)}。</p>
     *
     * <pre>{@code
     * ctx.getSender().group(123456L).text("上线了").send();
     * ctx.getSender().privateChat(ctx.getBot().getBotId()).text("自检完成").send();
     * }</pre>
     *
     * @return 发送门面，永不为 {@code null}
     */
    Sender getSender();

    /**
     * 插件数据访问入口（JDBI + SQLite）。
     *
     * <p>取代现行 {@code PluginUtils.registerEntity/repo/query/delete/update/db}。
     * 底层沿用现有的自研 ORM（{@code TableSchema} / {@code CrudRepository} 等），
     * 只是入口从静态方法改为实例方法。</p>
     *
     * @return 数据访问门面，永不为 {@code null}
     */
    PluginDataService getDb();

    /**
     * Redis 缓存门面。
     *
     * <p>取代现行静态类 {@code RedisUtil}。</p>
     *
     * @return 缓存门面，永不为 {@code null}
     */
    YuniCache getCache();

    /**
     * 插件配置加载器。
     *
     * <p>取代现行 {@code PluginUtils.loadJsonConfigFromPlugin} 与
     * {@code loadTextFromPluginFolder}。</p>
     *
     * @return 配置加载器，永不为 {@code null}
     */
    ConfigLoader getConfig();

    /**
     * HTTP 客户端。
     *
     * <p>取代现行 {@code PluginUtils.simpleGet} / {@code simplePost}。
     * 与现行实现（每次调用 {@code new RestTemplate()}）不同，这里复用连接池。</p>
     *
     * @return HTTP 门面，永不为 {@code null}
     */
    YuniHttp getHttp();

    /**
     * 权限判定服务。
     *
     * <p>取代现行 {@code UserPermissionManager}。保留「群×用户×插件例外 →
     * 默认角色映射」的回落链。</p>
     *
     * @return 权限服务，永不为 {@code null}
     */
    PermissionService getPermission();

    /**
     * 本插件的资源目录（jar 所在的父目录）。
     *
     * <p>取代现行 {@code PluginUtils.getPluginRootPath(Class)}。插件把字体、图片、
     * 模板等资源放在这个目录下，用 {@code ctx.getDataDir().resolve("img/...")} 访问。</p>
     *
     * @return 插件资源目录
     */
    Path getDataDir();

    // ==================== 生命周期查询 ====================

    /**
     * 本上下文是否已失效（所属插件已卸载）。
     *
     * <p>异步任务（定时器、网络回调）里应检查本方法，避免在插件卸载后
     * 仍操作已释放的资源。框架的调度类注册已内置该检查，但插件自己创建的
     * 回调需要自行判断。</p>
     *
     * @return 已卸载返回 {@code true}
     */
    boolean isDisposed();
}
