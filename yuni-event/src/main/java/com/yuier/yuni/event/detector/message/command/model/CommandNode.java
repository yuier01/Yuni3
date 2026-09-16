package com.yuier.yuni.event.detector.message.command.model;

import com.yuier.yuni.core.enums.CommandArgRequireType;
import com.yuier.yuni.core.enums.UserPermission;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 命令节点 —— 命令树的统一节点类型。
 *
 * <p>每个节点有：</p>
 * <ul>
 *   <li><b>位置参数</b> {@code args}：按顺序消费 token</li>
 *   <li><b>选项</b> {@code options}：预定义的裸标志集合，如 {@code -f}、{@code -a}。
 *       选项是纯 token，无参数、无子结构。匹配即消耗 1 token</li>
 *   <li><b>子命令</b> {@code children}：可携带自身参数、选项、子命令的嵌套节点</li>
 * </ul>
 *
 * <pre>{@code
 * // gcc -O2 -Wall -o output file.c
 * CommandNode.builder("gcc")
 *     .option("-O2").option("-Wall")
 *     .child(CommandNode.builder("-o").arg(ArgDef.required("output", "输出文件")).build())
 *     .variadicArg("files", "源文件")
 *     .build()
 *
 * // git push -f
 * CommandNode.builder("push")
 *     .option("-f")
 *     .build()
 *
 * // git commit -m "message"   ( -m 有参数 → 是子命令，不是选项 )
 * CommandNode.builder("commit")
 *     .child(CommandNode.builder("-m").required().arg(ArgDef.required("msg", "提交信息")).build())
 *     .option("-a")
 *     .build()
 * }</pre>
 */
@Data
@NoArgsConstructor
public class CommandNode {

    /** 节点名 */
    private String name;

    /** 帮助文本 */
    private String description = "";

    /** 位置参数 */
    private List<ArgDef> args = new ArrayList<>();

    /** 选项（裸标志集合）。纯 token，无参数，无子结构 */
    private Set<String> options = new LinkedHashSet<>();

    /** 子命令 */
    private List<CommandNode> children = new ArrayList<>();

    /** 本节点所需权限 */
    private UserPermission permission = UserPermission.USER;

    /** 父节点是否要求本节点必须被匹配 */
    private boolean required = false;

    /** 是否要求至少一个选项或子命令被匹配 */
    private boolean requiresChild = false;

    /**
     * 节点绑定的命令处理器（不透明引用）。
     *
     * <p><b>本模块不认识这个对象的类型</b>：它由
     * {@code com.yuier.yuni.plugin.api.Commands#on} 写入，由
     * {@code CommandDispatcher} 读出并转型为 {@code CommandHandler}。</p>
     *
     * <p>之所以用 {@code Object} 而非具体类型：{@code CommandHandler} 需要
     * {@code Reply}，而 {@code Reply} 位于下游模块 yuni-plugin。
     * 依赖方向是 {@code yuni-plugin → yuni-event}，反向依赖会形成循环。
     * 因此这里只做「保管」，类型安全由 {@code Commands} 门面保证。</p>
     *
     * <p>语义：绑定处理器的节点是「可执行命令」；未绑定的是「纯帮助声明」——
     * 参与匹配但不短路模式插件，供帮助系统遍历展示。</p>
     */
    private Object handler;

    // ---- Builder ----

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static class Builder {
        private final CommandNode node;

        private Builder(String name) {
            node = new CommandNode();
            node.name = name;
        }

        public Builder description(String desc) {
            node.description = desc;
            return this;
        }

        public Builder permission(UserPermission perm) {
            node.permission = perm;
            return this;
        }

        /** 添加位置参数 */
        public Builder arg(ArgDef arg) {
            node.args.add(arg);
            return this;
        }

        /** 便捷：添加必选 PLAIN 参数 */
        public Builder arg(String name, String description) {
            node.args.add(ArgDef.required(name, description));
            return this;
        }

        /** 便捷：添加变长 PLAIN 参数 */
        public Builder variadicArg(String name, String description) {
            return variadicArg(name, description, CommandArgRequireType.PLAIN);
        }

        /** 便捷：添加指定类型的变长参数 */
        public Builder variadicArg(String name, String description, CommandArgRequireType type) {
            long variadicCount = node.args.stream().filter(ArgDef::isVariadic).count();
            if (variadicCount >= 1) {
                throw new IllegalArgumentException(
                        "命令 '" + node.name + "': 最多只能定义一个变长参数");
            }
            node.args.add(ArgDef.variadic(name, description, type));
            return this;
        }

        /** 添加选项（裸标志，无参数无子结构） */
        public Builder option(String flag) {
            node.options.add(flag);
            return this;
        }

        /** 添加子命令 */
        public Builder child(CommandNode child) {
            node.children.add(child);
            return this;
        }

        /** 标记本节点要求至少一个选项或子命令被匹配 */
        public Builder requiresChild() {
            node.requiresChild = true;
            return this;
        }

        /** 标记本节点自身必须被匹配（父节点用） */
        public Builder required() {
            node.required = true;
            return this;
        }

        public CommandNode build() {
            // 排序位置参数：必选 → 可选 → 变长
            List<ArgDef> sortedArgs = new ArrayList<>();
            List<ArgDef> requiredArgs = new ArrayList<>();
            List<ArgDef> optionalArgs = new ArrayList<>();
            ArgDef variadic = null;
            for (ArgDef a : node.args) {
                if (a.isVariadic()) {
                    variadic = a;
                } else if (a.isRequired()) {
                    requiredArgs.add(a);
                } else {
                    optionalArgs.add(a);
                }
            }
            sortedArgs.addAll(requiredArgs);
            sortedArgs.addAll(optionalArgs);
            if (variadic != null) {
                sortedArgs.add(variadic);
            }
            node.args = sortedArgs;

            // 排序子命令：必选在前，非必选在后
            List<CommandNode> sortedChildren = new ArrayList<>();
            for (CommandNode child : node.children) {
                if (child.isRequired()) {
                    sortedChildren.add(child);
                }
            }
            for (CommandNode child : node.children) {
                if (!child.isRequired()) {
                    sortedChildren.add(child);
                }
            }
            node.children = sortedChildren;

            return node;
        }
    }
}
