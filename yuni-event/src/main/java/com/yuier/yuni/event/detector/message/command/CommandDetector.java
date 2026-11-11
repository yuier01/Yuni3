package com.yuier.yuni.event.detector.message.command;
import lombok.AllArgsConstructor;

import com.yuier.yuni.core.bot.BotMessageInfo;
import com.yuier.yuni.core.bot.YuniBot;
import com.yuier.yuni.core.constants.MessageSegmentTypes;
import com.yuier.yuni.core.enums.MessageType;
import com.yuier.yuni.core.event.YuniMessageEvent;
import com.yuier.yuni.core.event.matched.CommandResult;
import com.yuier.yuni.core.model.message.MessageChain;
import com.yuier.yuni.core.model.message.MessageSegment;
import com.yuier.yuni.core.model.message.segment.AtSegment;
import com.yuier.yuni.core.model.message.segment.ReplySegment;
import com.yuier.yuni.core.model.message.segment.TextSegment;
import com.yuier.yuni.core.util.SpringContextUtil;
import com.yuier.yuni.core.model.bot.BotApp;
import com.yuier.yuni.event.detector.message.MessageDetector;
import com.yuier.yuni.event.detector.message.command.model.CommandNode;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

import static com.yuier.yuni.core.constants.SystemConstants.BLANK_SPACE;
import static com.yuier.yuni.core.constants.SystemConstants.FIRST_INDEX;
import static com.yuier.yuni.core.constants.YuniMessageType.GROUP_MESSAGE;
import static com.yuier.yuni.core.constants.YuniMessageType.PRIVATE_MESSAGE;

/**
 * 命令节点探测器 —— 使用 CommandNode + CommandMatcher 替代旧的 CommandModel + CommandMatcher。
 *
 * <p>插件在 {@code getDetector()} 中返回本探测器即可迁移到新命令系统。</p>
 *
 * <h2>依赖注入（重构 P4 新增）</h2>
 * <p>本类原先通过 {@code SpringContextUtil.getBean(...)} 静态取
 * {@code YuniBot} 与 {@code BotApp}。这让它在 Spring 容器之外完全无法使用——
 * 单元测试必须启动整个容器。新插件系统通过构造器注入这两个依赖，
 * 静态取 Bean 仅作为<b>兼容回退</b>保留（旧插件路径仍在用无参构造）。</p>
 */
@AllArgsConstructor
public class CommandDetector implements MessageDetector {

    /**
     * -- GETTER --
     *  命令树根节点。
     *
     * @return 根节点
     */
    @Getter
    private CommandNode rootNode;

    /**
     * 机器人实例，用于在 tokenize 阶段查询被回复人。
     *
     * <p>{@code null} 时回退到静态容器查找。</p>
     */
    private YuniBot bot;

    /**
     * 机器人业务配置，提供命令前缀。
     *
     * <p>{@code null} 时回退到静态容器查找。</p>
     */
    private BotApp botApp;

    /**
     * 兼容构造器：依赖在首次使用时经静态容器解析。
     *
     * <p>供旧插件系统与既有插件代码使用，行为与重构前完全一致。</p>
     *
     * @param rootNode 命令树根节点
     */
    public CommandDetector(CommandNode rootNode) {
        this.rootNode = rootNode;
    }


    /**
     * 设置命令树根节点。
     *
     * @param rootNode 根节点
     */
    public void setRootNode(CommandNode rootNode) {
        this.rootNode = rootNode;
    }

    @Override
    public Boolean match(YuniMessageEvent event) {
        if (!messageTypeMatches(event)) {
            return false;
        }

        // tokenize → CommandTokens（含抽离的回复消息段）
        // 传入实例化的 bot：新插件系统在 Spring 容器之外也能正常工作
        CommandTokens tokens = tokenize(event.getMessageChain(), resolveBot());
        if (tokens.isEmpty()) {
            return false;
        }

        // 检查命令头是否以 commandFlag 开头
        String commandFlag = resolveBotApp().getCommandFlag();
        if (!(tokens.get(FIRST_INDEX) instanceof TextSegment)) {
            return false;
        }
        String first = ((TextSegment) tokens.get(FIRST_INDEX)).getText();
        if (!first.startsWith(commandFlag)) {
            return false;
        }

        // 去掉命令前缀（将 "/echo" 变为 "echo"）
        String stripped = first.substring(commandFlag.length());
        if (stripped.isEmpty()) {
            return false;
        }
        List<MessageSegment> mutableTokens = new ArrayList<>(tokens.getTokens());
        mutableTokens.set(FIRST_INDEX, new TextSegment(stripped));
        CommandTokens commandTokens = new CommandTokens(mutableTokens, tokens.getReply());

        // 匹配
        CommandMatcher matcher = new CommandMatcher();
        CommandResult result = matcher.match(commandTokens, rootNode);

        if (result.isMatchSuccess()) {
            event.setCommandResult(result);
            return true;
        }
        return false;
    }

    /**
     * 解析机器人业务配置：优先用注入实例，回退到静态容器。
     *
     * <p>回退路径在 Spring 容器之外必然失败（{@code SpringContextUtil} 的
     * 静态字段为 {@code null}）。这里给出明确的错误信息，而不是让
     * {@code NullPointerException} 从容器内部抛出来——后者在插件里表现为
     * 「命令莫名其妙不生效」，极难定位。</p>
     *
     * @return 业务配置
     * @throws IllegalStateException 未注入且容器不可用时
     */
    private BotApp resolveBotApp() {
        if (botApp != null) {
            return botApp;
        }
        try {
            return SpringContextUtil.getBean(BotApp.class);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "CommandDetector 未注入 BotApp，且无法从 Spring 容器获取。"
                            + "请使用 CommandDetector(CommandNode, YuniBot, BotApp) 构造器，"
                            + "或在 Spring 容器内运行。", e);
        }
    }

    /**
     * 解析机器人实例：优先用注入实例，回退到静态容器。
     *
     * @return 机器人实例；两者都不可用时返回 {@code null}
     */
    private YuniBot resolveBot() {
        if (bot != null) {
            return bot;
        }
        try {
            return SpringContextUtil.getBean(YuniBot.class);
        } catch (Exception e) {
            // 无 bot 时仍可完成匹配，只是无法剔除「@被回复人」的 At 段
            return null;
        }
    }

    @Override
    public MessageType listenAt() {
        return MessageType.GROUP;
    }

    public Boolean messageTypeMatches(YuniMessageEvent event) {
        return (listenAt() == MessageType.GROUP && event.getMessageType().equals(GROUP_MESSAGE)) ||
                (listenAt() == MessageType.PRIVATE && event.getMessageType().equals(PRIVATE_MESSAGE));
    }

    /**
     * 将消息链 tokenize。
     *
     * <p>规则：</p>
     * <ul>
     *   <li>文本按空格拆分为多个 TextSegment</li>
     *   <li>非文本消息段原样保留</li>
     *   <li>首部回复消息段被抽离到 {@link CommandTokens#getReply()}，其对应的 @ 从 token 流中移除</li>
     * </ul>
     */
    static CommandTokens tokenize(MessageChain chain) {
        // 兼容入口：无注入依赖时回退到静态容器查找。
        // 静态方法签名保持不变，以免破坏既有调用点（设计文档 §6.8 承诺命令引擎零改动）。
        return tokenize(chain, null);
    }

    /**
     * 将消息链 tokenize（支持依赖注入）。
     *
     * <p>与单参版本的唯一区别是「查询被回复人」所用的机器人实例来源：
     * 本重载接收显式实例，供新插件系统在 Spring 容器之外使用。</p>
     *
     * @param chain 消息链
     * @param bot   机器人实例，用于查询被回复人；{@code null} 时回退到静态容器
     * @return token 列表 + 抽离的回复段
     */
    static CommandTokens tokenize(MessageChain chain, YuniBot bot) {
        List<MessageSegment> rawTokens = new ArrayList<>();

        // 初步处理，先按消息段对消息链进行拆分；其中，对于文本消息段，进一步按空格进行拆分。结果输出为 rawToken
        for (MessageSegment segment : chain.getContent()) {
            if (segment.typeOf(MessageSegmentTypes.TEXT)) {
                String[] parts = ((TextSegment) segment).getText().split(BLANK_SPACE);
                for (String part : parts) {
                    if (!part.isBlank()) {
                        rawTokens.add(new TextSegment(part.strip()));
                    }
                }
            } else {
                rawTokens.add(segment);
            }
        }

        /*
        QQ 消息链具有以下特性：
        1. 包含回复消息的消息链中，无论用户实际上编辑消息时，在何时对消息插入回复，发送到后端的消息链中，回复消息段必定会被放在完整消息链的第一个消息段处
        2. 一条完整消息链中至多可能存在一个回复消息段。
        为此，将首部回复消息单独提取保存；匹配流程中，当试图匹配回复消息段时，取此处单独保存下来的回复消息段进行匹配
         */
        ReplySegment reply = null;
        if (!rawTokens.isEmpty() && rawTokens.get(FIRST_INDEX).typeOf(MessageSegmentTypes.REPLY)) {
            reply = (ReplySegment) rawTokens.remove(FIRST_INDEX);
            YuniBot effectiveBot = bot != null ? bot : tryGetYuniBot();
            BotMessageInfo msg = effectiveBot == null
                    ? null
                    : effectiveBot.getMessage(reply.getId()).orElse(null);
            Long userId = msg != null ? msg.getUserId() : 0L;
            rawTokens.removeIf(seg ->
                    seg.typeOf(MessageSegmentTypes.AT) &&
                            ((AtSegment) seg).getQq().equals(String.valueOf(userId)));
        }

        return new CommandTokens(rawTokens, reply);
    }

    /**
     * 尝试从静态容器取机器人实例。
     *
     * @return 机器人实例；容器未就绪时返回 {@code null}
     */
    private static YuniBot tryGetYuniBot() {
        try {
            return SpringContextUtil.getBean(YuniBot.class);
        } catch (Exception e) {
            // 容器未启动或 bot 尚未注册：无法剔除「@被回复人」的 At 段，
            // 但匹配本身仍可继续
            return null;
        }
    }
}
