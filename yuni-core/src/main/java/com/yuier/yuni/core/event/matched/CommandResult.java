package com.yuier.yuni.core.event.matched;

import com.yuier.yuni.core.enums.UserPermission;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 命令树匹配结果 —— CommandNodeMatcher 的输出。
 */
@Data
@NoArgsConstructor
public class CommandResult {

    /** 匹配到的节点名 */
    private String matchedKey;

    /** 匹配到的节点所需权限（匹配器从 CommandNode 复制，供权限校验使用） */
    private UserPermission requiredPermission = UserPermission.USER;

    /** 匹配是否成功 */
    private boolean matchSuccess = false;

    /** 消耗的 token 数量 */
    private int consumedCount = 0;

    /** 位置参数匹配结果（key = 参数名） */
    private Map<String, ArgResult> args = new LinkedHashMap<>();

    /** 匹配到的选项（裸标志） */
    private Set<String> options = new LinkedHashSet<>();

    /** 子命令匹配结果；一次调用最多一个子命令 */
    private CommandResult child;

    // ---- 查询 ----

    public boolean hasArg(String name) {
        return args.containsKey(name);
    }

    public ArgResult getArg(String name) {
        return args.get(name);
    }

    public boolean hasOption(String name) {
        return options.contains(name);
    }

    /** 是否有任何选项被匹配 */
    public boolean hasAnyOption() {
        return !options.isEmpty();
    }

    public boolean hasChild(String name) {
        return child != null && name != null && name.equals(child.getMatchedKey());
    }

    public CommandResult getChild(String name) {
        return hasChild(name) ? child : null;
    }

    public Set<String> getChildNames() {
        return child == null ? Set.of() : Set.of(child.getMatchedKey());
    }

    /** 是否有子命令被匹配 */
    public boolean hasAnyChild() {
        return child != null;
    }

    /** 是否匹配了至少一个选项或子命令 */
    public boolean hasAnyOptionOrChild() {
        return !options.isEmpty() || child != null;
    }

    // ---- 静态工厂 ----

    public static CommandResult success(String key, int consumed) {
        CommandResult r = new CommandResult();
        r.matchedKey = key;
        r.matchSuccess = true;
        r.consumedCount = consumed;
        return r;
    }

    public static CommandResult fail() {
        return new CommandResult();
    }
}
