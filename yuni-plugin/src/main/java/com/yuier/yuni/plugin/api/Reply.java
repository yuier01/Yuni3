package com.yuier.yuni.plugin.api;

import com.yuier.yuni.plugin.api.event.MessageChainView;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 消息构建门面。
 *
 * <h2>为什么要与 {@code MessageChain} 分开</h2>
 * <p>现行系统里插件发消息的入口是 {@code MessageChain} 本身——一个既可读又可写的
 * 对象。这带来两个问题：</p>
 * <ol>
 *   <li>读事件拿到的链与准备发送的链是同一类型，插件容易误改传入的事件内容；</li>
 *   <li>链式调用冗长：{@code eventContext.getChatSession().response(new MessageChain().addAt(...).addTextSegment(...))}。</li>
 * </ol>
 * <p>本接口只保留<b>构建与发送</b>，读取由 {@link MessageChainView} 负责。</p>
 *
 * <h2>链式调用</h2>
 * <p>全部构建方法返回 {@code this}，因此可以一路链到 {@link #send()}：</p>
 * <pre>{@code
 * reply.at(senderId).text(" 你今天已经很努力了").image(imgUrl).send();
 * }</pre>
 *
 * <h2>何时真正发送</h2>
 * <p>构建方法<b>不</b>产生网络调用；只有 {@link #send()}（或 {@link #sendAndGetId()}）
 * 才真正通过 {@code YuniBot} 发送。处理器若不调用 {@code send()}，则什么都不发——
 * 这使「条件不满足时静默返回」成为自然写法。</p>
 *
 * <h2>引用回复</h2>
 * <ul>
 *   <li>{@link #quote()}：把当前消息设为对触发事件的引用回复（等价现行
 *       {@code ChatSession.reply}）。</li>
 *   <li>{@link #quote(String)}：引用指定的历史消息 ID。</li>
 * </ul>
 * <p>引用段会被插入链首——这是 QQ 客户端的硬性要求（回复段必须在第一位），
 * 框架负责保证，插件无需关心插入位置。</p>
 *
 * @see MessageChainView
 */
public interface Reply {

    // ==================== 构建 ====================

    /**
     * 追加文本。
     *
     * @param text 文本内容；{@code null} 被视为空串
     * @return {@code this}，便于链式调用
     */
    Reply text(String text);

    /**
     * 追加 @某人。
     *
     * <p>框架会在 At 段之后自动补一个空格，避免「@某人你好」被客户端渲染成
     * 「@某人的你好」。这与现行 {@code MessageChain.addAt} 的行为一致。</p>
     *
     * @param userId 目标 QQ 号
     * @return {@code this}
     */
    Reply at(long userId);

    /**
     * 追加图片。
     *
     * @param file 图片来源。支持 OneBot 的多种形式：本地绝对路径、{@code http(s)://}
     *             URL、{@code base64://} 前缀的 Base64 数据、{@code file://} URI
     * @return {@code this}
     */
    Reply image(String file);

    /**
     * 追加表情（QQ 小黄脸）。
     *
     * @param faceId 表情 ID
     * @return {@code this}
     */
    Reply face(int faceId);

    /**
     * 追加语音。
     *
     * @param file 音频来源，形式同 {@link #image(String)}
     * @return {@code this}
     */
    Reply record(String file);

    /**
     * 追加视频。
     *
     * @param file 视频来源，形式同 {@link #image(String)}
     * @return {@code this}
     */
    Reply video(String file);

    /**
     * 追加戳一戳（窗口抖动）。
     *
     * <p>注意：这是<b>主动发送</b>一个戳一戳动作，与接收 {@code NoticeEvent}
     * 的戳一戳通知是两件事。</p>
     *
     * @param userId 目标 QQ 号
     * @return {@code this}
     */
    Reply poke(long userId);

    /**
     * 追加一个原始消息段。
     *
     * <p>逃生舱：当框架未提供专用构建方法时，插件可用本方法直接构造
     * OneBot 消息段。段以 {@code type} + {@code data} 描述：</p>
     * <pre>{@code
     * reply.segment("music", Map.of("type", "qq", "id", "123456")).send();
     * }</pre>
     *
     * @param type 消息段类型名
     * @param data 消息段数据；{@code null} 视为空
     * @return {@code this}
     */
    Reply segment(String type, Map<String, Object> data);

    /**
     * 追加一段已构建好的消息链。
     *
     * @param chain 待追加的链
     * @return {@code this}
     */
    Reply chain(MessageChainView chain);

    // ==================== 引用 ====================

    /**
     * 把本消息设为对「触发当前处理器的消息」的引用回复。
     *
     * <p>等价现行 {@code ChatSession.reply(...)}。当前处理器不是由消息事件触发时
     * （例如定时任务里），本方法退化为空操作并记录一条 debug 日志。</p>
     *
     * @return {@code this}
     */
    Reply quote();

    /**
     * 把本消息设为对指定历史消息的引用回复。
     *
     * @param messageId 被引用的消息 ID
     * @return {@code this}
     */
    Reply quote(String messageId);

    // ==================== 发送 ====================

    /**
     * 发送本消息。
     *
     * <p>本方法<b>不抛异常</b>：发送失败时返回一个失败态的 {@link SentResult}
     * 并记录 error 日志。这样插件在处理器里不必包 try/catch——发送失败不应该
     * 让插件逻辑中断。</p>
     *
     * @return 发送结果，永不为 {@code null}
     */
    SentResult send();

    /**
     * 发送本消息并返回消息 ID。
     *
     * <p>便捷方法，等价 {@code send().getMessageId()}。</p>
     *
     * @return 消息 ID；发送失败时返回 {@code 0}
     */
    default long sendAndGetId() {
        return send().getMessageId();
    }

    /**
     * 分条发送：每个元素单独发一条消息。
     *
     * <p>用于长文本分段。框架顺序发送并等待前一条完成，避免刷屏时乱序。</p>
     *
     * @param chains 逐条消息的内容
     * @return 各条的发送结果，顺序与入参一致
     */
    List<SentResult> sendEach(List<MessageChainView> chains);

    /**
     * 发送结果。
     *
     * <p>只暴露插件真正需要的信息，不泄漏 {@code MessageSentResult} 的内部结构。</p>
     */
    interface SentResult {

        /**
         * 发送是否成功。
         *
         * @return 成功返回 {@code true}
         */
        boolean isSuccess();

        /**
         * 消息 ID。
         *
         * @return 成功时为 OneBot 消息 ID；失败时为 {@code 0}
         */
        long getMessageId();

        /**
         * 失败原因。
         *
         * @return 失败描述；成功时为空
         */
        Optional<String> getErrorMessage();
    }
}
