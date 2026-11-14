package com.yuier.yuni.plugin.runtime.dispatch;

import com.yuier.yuni.core.model.message.segment.TextSegment;
import com.yuier.yuni.event.detector.message.command.CommandMatcher;
import com.yuier.yuni.event.detector.message.command.CommandTokens;
import com.yuier.yuni.event.detector.message.command.model.CommandNode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令树单路径约束的回归测试。
 *
 * <p>同一层最多匹配一个子命令；否则会出现「结果里多个兄弟、分发只执行一个」
 * 的静默错误。</p>
 */
class CommandMatcherSinglePathTest {

    private final CommandMatcher matcher = new CommandMatcher();

    @Test
    @DisplayName("同一层的第二个兄弟子命令应导致整体匹配失败")
    void secondSiblingSubcommandIsRejected() {
        CommandNode root = CommandNode.builder("root")
                .child(CommandNode.builder("a").build())
                .child(CommandNode.builder("b").build())
                .build();

        var result = matcher.match(tokens("root", "a", "b"), root);

        assertFalse(result.isMatchSuccess(), "root a b 不应同时匹配 a 和 b");
    }

    @Test
    @DisplayName("单个子命令仍然正常匹配")
    void singleSubcommandStillMatches() {
        CommandNode root = CommandNode.builder("root")
                .child(CommandNode.builder("a").build())
                .child(CommandNode.builder("b").build())
                .build();

        var result = matcher.match(tokens("root", "a"), root);

        assertTrue(result.isMatchSuccess());
        assertTrue(result.hasAnyChild());
        assertTrue(result.hasChild("a"));
    }

    @Test
    @DisplayName("嵌套子命令仍然正常匹配")
    void nestedSubcommandStillMatches() {
        CommandNode root = CommandNode.builder("root")
                .child(CommandNode.builder("a")
                        .child(CommandNode.builder("a1").build())
                        .build())
                .build();

        var result = matcher.match(tokens("root", "a", "a1"), root);

        assertTrue(result.isMatchSuccess());
        CommandNode a = root.getChildren().get(0);
        assertTrue(result.hasChild("a"));
        assertTrue(result.getChild("a").hasChild("a1"));
        assertEquals("a1", a.getChildren().get(0).getName());
    }

    @Test
    @DisplayName("子命令之后的父选项仍然可以消费")
    void parentOptionAfterSubcommandStillWorks() {
        CommandNode root = CommandNode.builder("root")
                .option("-v")
                .child(CommandNode.builder("a").build())
                .build();

        var result = matcher.match(tokens("root", "a", "-v"), root);

        assertTrue(result.isMatchSuccess());
        assertTrue(result.getOptions().contains("-v"));
        assertTrue(result.hasAnyChild());
    }

    private static CommandTokens tokens(String... values) {
        java.util.List<com.yuier.yuni.core.model.message.MessageSegment> tokens =
                new java.util.ArrayList<>();
        for (String value : values) {
            tokens.add(new TextSegment(value));
        }
        return new CommandTokens(tokens, null);
    }
}
