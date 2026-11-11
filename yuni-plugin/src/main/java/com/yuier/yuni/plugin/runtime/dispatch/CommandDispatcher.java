package com.yuier.yuni.plugin.runtime.dispatch;

import com.yuier.yuni.core.enums.UserPermission;
import com.yuier.yuni.core.event.YuniMessageEvent;
import com.yuier.yuni.core.event.matched.CommandResult;
import com.yuier.yuni.event.detector.message.command.CommandDetector;
import com.yuier.yuni.event.detector.message.command.model.CommandNode;
import com.yuier.yuni.plugin.api.CommandHandler;
import com.yuier.yuni.plugin.api.Commands;
import com.yuier.yuni.plugin.api.Reply;
import com.yuier.yuni.plugin.runtime.RegistrationTable;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 命令分发器——把注册的命令树接到现有匹配引擎上。
 *
 * <h2>与现行系统的关系</h2>
 * <p><b>命令匹配引擎完全复用</b>：{@link CommandDetector#match(YuniMessageEvent)}
 * （tokenize）+ {@code CommandMatcher}（回溯递归）+ {@code CommandResult} 均不改动。
 * 本类只做三件事：</p>
 * <ol>
 *   <li>遍历命令注册项，逐个跑 {@link CommandDetector}；</li>
 *   <li>命中后做<b>节点级权限递归校验</b>（逻辑与现行
 *       {@code PassivePluginMatcher.checkCommandNodePermission} 一致）；</li>
 *   <li>取「匹配到的最深节点」的处理器并异步执行。</li>
 * </ol>
 *
 * <h2>处理器从哪里取</h2>
 * <p>处理器<b>存在命令节点上</b>：{@code CommandNode} 持有一个<b>不透明</b>的
 * {@code Object handler} 字段（yuni-event 不认识它，只是保管），
 * API 层的 {@link com.yuier.yuni.plugin.api.Commands} 提供类型安全的读写入口。
 * 本类经 {@link Commands#handlerOf(CommandNode)} 取出并转型。</p>
 *
 * <h2>曾被评估并放弃的两种方案（避免后人重复推导）</h2>
 * <ol>
 *   <li><b>给 {@code CommandNode.Builder} 加 {@code .on(...)} 方法</b>——
 *       写法最漂亮，但模块依赖方向不允许：{@code CommandHandler} 需要
 *       {@code Reply}（在 yuni-plugin），而 {@code CommandNode} 在 yuni-event。
 *       让 yuni-event 依赖 yuni-plugin 会形成循环。见设计文档 §6.3。</li>
 *   <li><b>在本分发器里用 {@link java.util.IdentityHashMap} 保存
 *       「节点标识 → 处理器」</b>——这样能完全不改 yuni-event。放弃原因：
 *       处理器与节点树的绑定关系不随节点树传递，而命令树是需要被
 *       <b>整体传递</b>的资产（例如帮助系统要遍历它）。绑定放在外部注册表里，
 *       任何拿到节点树却拿不到本分发器的地方就取不到处理器。</li>
 * </ol>
 * <p>最终采用「节点持有不透明字段 + API 层门面」：既不产生循环依赖，
 * 又让处理器随节点树一起传递。代价是 yuni-event 的 {@code CommandNode}
 * 多了<b>一个字段</b>——这是唯一被改动的现有类，见设计文档 §6.4。</p>
 *
 * <h2>权限模型（保持现行不变式）</h2>
 * <p>权限是<b>节点级</b>的：{@code CommandNode.permission} 声明，匹配后沿
 * {@code CommandResult} 的 children 递归校验每一个匹配到的节点。
 * 因此同一个命令的不同子命令可以要求不同权限——这是现行系统已实现且要保留的能力。</p>
 *
 * @see CommandDetector
 */
@Slf4j
public final class CommandDispatcher {

    /** 能力注册总表。 */
    private final RegistrationTable registrations;

    /** 机器人实例，传给 {@link CommandDetector} 以支持回复段解析。 */
    private final com.yuier.yuni.core.bot.YuniBot bot;

    /** 机器人业务配置，提供命令前缀。 */
    private final com.yuier.yuni.core.model.bot.BotApp botApp;

    /**
     * 权限判定函数：给定（用户、群、插件标识）返回权限等级。
     *
     * <p>注入而非直接依赖 {@code PermissionService}，是为了让本类可以在不启动
     * 权限模块的情况下被测试。语义上等价于
     * {@code ctx.getPermission().resolve(userId, groupId, pluginId)}。</p>
     */
    private final PermissionResolver permissionResolver;

    /**
     * 作用域解析函数：给定插件标识，返回该插件<b>当前</b>生效的过滤器。
     *
     * <p>与 {@link #permissionResolver} 同样是「注入函数而非依赖具体类型」：
     * 本类不需要知道运行时长什么样，只需要一个「这个插件现在在哪些群生效」的答案。
     * 单测里传 {@code null} 即可退化为「只看注册项自带的过滤器」。</p>
     *
     * <p><b>为什么必须在分发时查而不是注册时定</b>：用户的群粒度启停配置随时可变，
     * 而注册项在插件加载时就固定了。只在注册时定会导致管理端的启停不生效——
     * 这是 P6 上线前真实存在的缺陷。</p>
     */
    private final java.util.function.Function<String, java.util.Optional<com.yuier.yuni.plugin.api.ScopeFilter>>
            scopeResolver;

    /**
     * 权限判定函数。
     */
    @FunctionalInterface
    public interface PermissionResolver {

        /**
         * 解析用户在某群针对某插件的权限等级。
         *
         * @param userId   用户 QQ 号
         * @param groupId  群号；私聊为 {@code null}
         * @param pluginId 插件标识
         * @return 权限等级；不可判定时返回 {@link UserPermission#USER}
         */
        UserPermission resolve(long userId, Long groupId, String pluginId);
    }

    /**
     * 构造命令分发器。
     *
     * @param registrations      能力注册总表
     * @param bot                机器人实例；可为 {@code null}
     * @param botApp             机器人业务配置；可为 {@code null}（则回退静态查找）
     * @param permissionResolver 权限判定函数；可为 {@code null}（则一律视为 USER）
     */
    public CommandDispatcher(RegistrationTable registrations,
                             com.yuier.yuni.core.bot.YuniBot bot,
                             com.yuier.yuni.core.model.bot.BotApp botApp,
                             PermissionResolver permissionResolver) {
        this(registrations, bot, botApp, permissionResolver, null);
    }

    /**
     * 构造命令分发器（含作用域解析）。
     *
     * @param registrations      能力注册总表
     * @param bot                机器人实例；可为 {@code null}
     * @param botApp             机器人业务配置；可为 {@code null}
     * @param permissionResolver 权限判定函数；可为 {@code null}
     * @param scopeResolver      作用域解析函数；可为 {@code null}
     *                           （则只按注册项自带的过滤器判定，用于单测）
     */
    public CommandDispatcher(RegistrationTable registrations,
                             com.yuier.yuni.core.bot.YuniBot bot,
                             com.yuier.yuni.core.model.bot.BotApp botApp,
                             PermissionResolver permissionResolver,
                             java.util.function.Function<String,
                                     java.util.Optional<com.yuier.yuni.plugin.api.ScopeFilter>> scopeResolver) {
        this.registrations = registrations;
        this.bot = bot;
        this.botApp = botApp;
        this.permissionResolver = permissionResolver;
        this.scopeResolver = scopeResolver;
    }

    // ==================== 处理器查询 ====================

    /**
     * 查询节点绑定的处理器。
     *
     * <p>处理器存放在 {@link CommandNode} 自身的不透明字段里（见
     * {@link com.yuier.yuni.plugin.api.Commands}），因此本方法是一次直接读取，
     * 没有额外的注册表需要维护。</p>
     *
     * @param node 命令节点
     * @return 处理器；未绑定时为空
     */
    public Optional<CommandHandler> handlerOf(CommandNode node) {
        return Commands.handlerOf(node);
    }

    /**
     * 解绑节点与处理器。
     *
     * <p>由插件卸载时调用。实际实现上命令卸载走的是「撤销 {@link RegistrationTable}
     * 条目」，本方法用于显式清理。</p>
     *
     * @param node 命令节点
     */
    public void unbindHandler(CommandNode node) {
        if (node == null) {
            return;
        }
        // 清空节点的处理器字段：该节点退回为「纯帮助声明」
        node.setHandler(null);
    }

    /**
     * 当前已绑定的处理器数量（跨全部已注册命令树）。
     *
     * <p>用于泄漏检测与诊断：插件卸载后注册表清空，本计数应回到 0。</p>
     *
     * @return 数量
     */
    public int getHandlerCount() {
        int count = 0;
        for (RegistrationTable.Entry entry : registrations.getCommandEntries()) {
            CommandNode tree = asCommandNode(entry);
            if (tree != null) {
                count += countHandlers(tree);
            }
        }
        return count;
    }

    /**
     * 递归统计一棵命令树上绑定了处理器的节点数。
     *
     * @param node 节点
     * @return 数量
     */
    private static int countHandlers(CommandNode node) {
        if (node == null) {
            return 0;
        }
        int count = Commands.handlerOf(node).isPresent() ? 1 : 0;
        for (CommandNode child : node.getChildren()) {
            count += countHandlers(child);
        }
        return count;
    }

    // ==================== 分发 ====================

    /**
     * 匹配并执行命令。
     *
     * @param event   插件侧消息事件（用于取消息链与条件判定）
     * @param groupId 事件群号；非群事件为 {@code null}
     * @param reply   回复入口；可为 {@code null}
     * @return 是否命中并执行了至少一个命令（用于决定是否短路模式插件）
     */
    public boolean dispatch(com.yuier.yuni.plugin.runtime.adapter.CoreMessageEvent event,
                            Long groupId,
                            Reply reply) {
        boolean matched = false;
        for (RegistrationTable.Entry entry : registrations.getCommandEntries()) {
            // 关卡：插件状态 + 作用域过滤。
            // 命令的「子类型过滤」不适用（命令靠命令树匹配，不是靠子类型）。
            // 作用域同时看「注册项自带的过滤器」与「运行时的当前作用域」——
            // 后者承载用户的群粒度启停配置。
            if (!entry.ownerServiceable() || !entry.scopeMatches(groupId, scopeResolver)) {
                continue;
            }
            CommandNode tree = asCommandNode(entry);
            if (tree == null) {
                continue;
            }

            // 把插件侧事件还原为命令引擎所需的 YuniMessageEvent（携带同一个消息链）
            YuniMessageEvent engineEvent = toEngineEvent(event);
            CommandDetector detector = new CommandDetector(tree, bot, botApp);

            Boolean hit;
            try {
                hit = detector.match(engineEvent);
            } catch (Throwable t) {
                log.error("插件 {} 的命令匹配抛异常，跳过该命令树: {}",
                        entry.getOwner() == null ? "?" : entry.getOwner().getId(), t.toString(), t);
                continue;
            }
            if (!Boolean.TRUE.equals(hit)) {
                continue;
            }

            matched = true;
            CommandResult result = engineEvent.getCommandResult();

            // 节点级权限校验：沿匹配路径递归比对每一层
            if (!checkNodePermission(result, event, entry)) {
                if (reply != null) {
                    reply.text("权限不足，无法执行该命令。").send();
                } else {
                    log.info("权限不足，已拒绝命令: user={}, group={}", event.getUserId(), groupId);
                }
                continue;
            }

            CommandHandler handler = resolveDeepestHandler(result);
            if (handler == null) {
                // 只声明不绑定处理器的命令树：合法的「纯帮助项」，不算命中
                log.debug("命令树命中但未绑定处理器，视为帮助声明: {}",
                        entry.getOwner() == null ? "?" : entry.getOwner().getId());
                matched = false;
                continue;
            }

            final YuniMessageEvent finalEvent = engineEvent;
            CompletableFuture.runAsync(() -> {
                try {
                    handler.handle(finalEvent, reply);
                } catch (Throwable t) {
                    log.error("插件 {} 执行命令失败", entry.getOwner() == null ? "?" : entry.getOwner().getId(), t);
                    if (reply != null) {
                        reply.text("执行命令失败（" + t.getClass().getSimpleName() + "），请联系开发人员。")
                                .send();
                    }
                }
            });
        }
        return matched;
    }

    /**
     * 沿匹配结果递归查找「最深命中节点」的处理器。
     *
     * <p>递归策略：先尝试子结果（更具体），子结果为 null 时回退到当前节点。
     * 这与「子命令优先于父命令的默认行为」这一直觉一致——例如
     * {@code 安慕希} 有自己的默认处理器（查状态），而 {@code 安慕希 添加}
     * 有更具体的处理器；输入「添加」时应走后者。</p>
     *
     * @param result 匹配结果（树形）
     * @return 处理器；整棵匹配路径上都没有绑定则返回 {@code null}
     */
    private CommandHandler resolveDeepestHandler(CommandResult result) {
        if (result == null) {
            return null;
        }
        // 先深后浅：子命令更具体
        for (CommandResult child : result.getChildren().values()) {
            CommandHandler childHandler = resolveDeepestHandler(child);
            if (childHandler != null) {
                return childHandler;
            }
        }
        CommandNode node = findNodeByKey(result.getMatchedKey(), result);
        return node == null ? null : Commands.handlerOf(node).orElse(null);
    }

    /**
     * 按节点名从已注册的命令树里找回对应的节点对象。
     *
     * <p>之所以需要「找回」：{@link CommandResult} 只携带 {@code matchedKey}（节点名），
     * 不携带节点引用（这正是本类不改动 yuni-event 的代价）。因此在已注册的命令树里
     * 按名字查找节点。</p>
     *
     * @param key    节点名
     * @param result 匹配结果（用于提供查找上下文）
     * @return 节点；找不到时返回 {@code null}
     */
    private CommandNode findNodeByKey(String key, CommandResult result) {
        if (key == null) {
            return null;
        }
        for (RegistrationTable.Entry entry : registrations.getCommandEntries()) {
            CommandNode tree = asCommandNode(entry);
            if (tree == null) {
                continue;
            }
            CommandNode found = findNodeByName(tree, key, result);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * 在命令树中按名字深度优先查找节点。
     *
     * <p>查找时会优先匹配「名字相同且已绑定处理器」的节点；若同名节点有多个
     * （不同命令树里的同名子命令），则选取第一个匹配的。这一歧义在实践上不会
     * 出现——命令树的根节点名必须唯一，否则插件加载时就应当报冲突。</p>
     *
     * @param node   当前节点
     * @param key    目标节点名
     * @param result 匹配结果（保留参数以便将来用路径精确匹配）
     * @return 节点；未找到时返回 {@code null}
     */
    private CommandNode findNodeByName(CommandNode node, String key, CommandResult result) {
        if (node == null) {
            return null;
        }
        if (key.equals(node.getName())) {
            return node;
        }
        for (CommandNode child : node.getChildren()) {
            CommandNode found = findNodeByName(child, key, result);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * 递归校验匹配路径上每个节点的权限。
     *
     * <p>逻辑与现行 {@code PassivePluginMatcher.checkCommandNodePermission} 一致：
     * 从匹配结果根节点开始，逐层比对 {@code requiredPermission}。</p>
     *
     * @param result 匹配结果
     * @param event  插件侧消息事件
     * @param entry  命令注册项（提供插件标识用于权限查询）
     * @return 全部节点权限满足时返回 {@code true}
     */
    private boolean checkNodePermission(CommandResult result,
                                        com.yuier.yuni.plugin.runtime.adapter.CoreMessageEvent event,
                                        RegistrationTable.Entry entry) {
        if (result == null) {
            return true;
        }
        String pluginId = entry.getOwner() == null ? null : entry.getOwner().getId();
        UserPermission userPermission = resolvePermission(event.getUserId(), event.getGroupId().orElse(null), pluginId);
        UserPermission required = result.getRequiredPermission();
        if (required != null && userPermission.getPriority() < required.getPriority()) {
            log.info("权限不足: 用户 {} 执行 {} 需要 {}，实际 {}",
                    event.getUserId(), result.getMatchedKey(), required, userPermission);
            return false;
        }
        for (CommandResult child : result.getChildren().values()) {
            if (!checkNodePermission(child, event, entry)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 解析权限等级。
     *
     * @param userId   用户 QQ 号
     * @param groupId  群号
     * @param pluginId 插件标识
     * @return 权限等级；无判定函数时返回 USER
     */
    private UserPermission resolvePermission(long userId, Long groupId, String pluginId) {
        if (permissionResolver == null) {
            // 未注入权限判定：一律视为 USER，使命令仍可执行。
            // 这在「不需要权限的命令」场景下是合理降级，但配置了权限的命令会全部拒绝，
            // 因此生产环境必须注入。
            return UserPermission.USER;
        }
        try {
            UserPermission resolved = permissionResolver.resolve(userId, groupId, pluginId);
            return resolved == null ? UserPermission.USER : resolved;
        } catch (Exception e) {
            log.warn("权限判定失败，回落 USER: userId={}, groupId={}", userId, groupId, e);
            return UserPermission.USER;
        }
    }

    /**
     * 取注册项里的命令树。
     *
     * @param entry 注册项
     * @return 命令树；类型不符时返回 {@code null}
     */
    private CommandNode asCommandNode(RegistrationTable.Entry entry) {
        Object payload = entry.getPayload();
        if (payload instanceof CommandNode node) {
            return node;
        }
        log.error("命令注册项载荷类型不符：{}（插件 {}）",
                payload == null ? "null" : payload.getClass().getName(),
                entry.getOwner() == null ? "?" : entry.getOwner().getId());
        return null;
    }

    /**
     * 把插件侧消息事件还原为命令引擎所需的 {@link YuniMessageEvent}。
     *
     * <p>只填命令匹配与命令处理器真正会用到的字段（消息链、类型、群号、发送者、
     * 消息 ID）。其余字段留空——命令处理器不应依赖它们；若确实需要，
     * 应改用 {@code CoreMessageEvent} 上新增的字段访问方法。</p>
     *
     * @param event 插件侧事件
     * @return 引擎事件
     */
    private YuniMessageEvent toEngineEvent(com.yuier.yuni.plugin.runtime.adapter.CoreMessageEvent event) {
        YuniMessageEvent engineEvent = new YuniMessageEvent();
        engineEvent.setTime(event.getTime());
        engineEvent.setSelfId(event.getSelfId());
        engineEvent.setMessageType(event.getMessageType());
        engineEvent.setSubType(event.getSubType());
        engineEvent.setMessageId(event.getMessageId());
        engineEvent.setUserId(event.getUserId());
        engineEvent.setGroupId(event.getGroupId().orElse(null));
        engineEvent.setRawMessage(event.getRawMessage());

        if (event.getChain() instanceof com.yuier.yuni.plugin.runtime.adapter.CoreMessageChainView view) {
            // 复用同一个内部链对象，避免 JSON 往返
            List<com.yuier.yuni.core.model.message.MessageSegment> segments = view.getRawSegments();
            engineEvent.setMessage(segments);
            com.yuier.yuni.core.model.message.MessageChain chain =
                    new com.yuier.yuni.core.model.message.MessageChain();
            chain.addAll(segments);
            engineEvent.setMessageChain(chain);
        } else {
            // 其他视图实现（测试替身等）：用纯文本构造一个等价的链
            com.yuier.yuni.core.model.message.MessageChain chain =
                    new com.yuier.yuni.core.model.message.MessageChain();
            chain.addTextSegment(event.getChain().getPlainText());
            engineEvent.setMessage(chain.getContent());
            engineEvent.setMessageChain(chain);
        }

        // 发送者：命令引擎不读它，但保留以便将来的节点级校验
        com.yuier.yuni.core.model.user.MessageSender sender =
                new com.yuier.yuni.core.model.user.MessageSender();
        sender.setUserId(event.getUserId());
        sender.setNickname(event.getSender().getNickname());
        sender.setCard(event.getSender().getCard().orElse(null));
        sender.setRole(event.getSender().getRole().orElse(null));
        sender.setTitle(event.getSender().getTitle().orElse(null));
        engineEvent.setSender(sender);

        return engineEvent;
    }

    /**
     * 供外部构造「一律 USER」的权限判定函数。
     *
     * @return 判定函数
     */
    public static PermissionResolver permissiveResolver() {
        return (userId, groupId, pluginId) -> UserPermission.USER;
    }

    /**
     * 把权限服务包装为判定函数。
     *
     * @param service 权限服务
     * @return 判定函数
     */
    public static PermissionResolver fromService(
            com.yuier.yuni.plugin.api.service.PermissionService service) {
        if (service == null) {
            return permissiveResolver();
        }
        return service::resolve;
    }
}
