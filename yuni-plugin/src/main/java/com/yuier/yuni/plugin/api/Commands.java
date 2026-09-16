package com.yuier.yuni.plugin.api;

import com.yuier.yuni.event.detector.message.command.model.CommandNode;

/**
 * 命令树的流式绑定辅助。
 *
 * <h2>为什么需要这个类，而不是直接给 {@code CommandNode.Builder} 加 {@code .on()}</h2>
 * <p>设计文档 §6.3 想要的写法是：</p>
 * <pre>{@code
 * ctx.command(CommandNode.builder("安慕希")
 *     .description("MC 服务器管理")
 *     .on((ev, reply) -> MCStatus.query(ev))          // ← 处理器紧贴它的参数声明
 *     .child(CommandNode.builder("添加")
 *         .arg(ArgDef.required("服务器名称", "MC 服务器名称"))
 *         .on((ev, reply) -> MCServer.add(ev))
 *         .build())
 *     .build());
 * }</pre>
 *
 * <p>但模块依赖方向不允许把 {@code .on()} 直接加在 {@code CommandNode.Builder} 上：</p>
 * <pre>
 * yuni-plugin  →  yuni-event  →  yuni-core
 * </pre>
 * <p>{@link CommandHandler} 需要 {@link Reply}，而 {@code Reply} 在 yuni-plugin；
 * 若把 {@code CommandHandler} 下沉到 yuni-event，就得把 {@code Reply} 也下沉到
 * yuni-core，那是更大的污染。反过来让 yuni-event 依赖 yuni-plugin 会形成循环依赖。</p>
 *
 * <h2>采用的折中</h2>
 * <p>{@code CommandNode} 持有一个<b>不透明</b>的 {@code Object handler} 字段
 * （yuni-event 不认识它，只是保管），本类提供类型安全的流式设置入口。
 * 这样：</p>
 * <ul>
 *   <li>插件侧拿到的是设计文档承诺的流式写法，类型安全；</li>
 *   <li>yuni-event 不认识 {@code CommandHandler}，无循环依赖；</li>
 *   <li>分发器从 {@code node.getHandler()} 取出并转型（见 {@code CommandDispatcher}）。</li>
 * </ul>
 *
 * <h2>用法</h2>
 * <pre>{@code
 * import static com.yuier.yuni.plugin.api.Commands.command;
 *
 * ctx.command(command(CommandNode.builder("安慕希")
 *     .on((ev, reply) -> MCStatus.query(ev))
 *     .build()));
 * }</pre>
 * <p>或者只在需要绑处理器的那一层包一层：</p>
 * <pre>{@code
 * CommandNode.builder("安慕希")
 *     .child(command(CommandNode.builder("添加")
 *         .arg(ArgDef.required("服务器名称", "MC 服务器名称"))
 *         .on((ev, reply) -> MCServer.add(ev)))
 *         .build())
 *     .build()
 * }</pre>
 *
 * @see CommandHandler
 * @see com.yuier.yuni.plugin.api.YuniContext#command
 */
public final class Commands {

    /** 工具类不可实例化。 */
    private Commands() {
    }

    /**
     * 给命令节点绑定处理器，并返回该节点。
     *
     * @param node    命令节点
     * @param handler 处理器
     * @return 同一个节点（便于继续链式调用）
     */
    public static CommandNode on(CommandNode node, CommandHandler handler) {
        if (node == null) {
            throw new IllegalArgumentException("命令节点不能为 null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("命令处理器不能为 null");
        }
        node.setHandler(handler);
        return node;
    }

    /**
     * 从命令节点取出处理器。
     *
     * @param node 命令节点
     * @return 处理器；未绑定或绑定了错误类型时为空
     */
    public static java.util.Optional<CommandHandler> handlerOf(CommandNode node) {
        if (node == null) {
            return java.util.Optional.empty();
        }
        Object handler = node.getHandler();
        return handler instanceof CommandHandler commandHandler
                ? java.util.Optional.of(commandHandler)
                : java.util.Optional.empty();
    }
}
