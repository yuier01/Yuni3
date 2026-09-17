package com.yuier.yuni.webapi.service.group;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuier.yuni.core.bot.BotGroupInfo;
import com.yuier.yuni.core.bot.YuniBot;
import com.yuier.yuni.core.util.SpringContextUtil;
import com.yuier.yuni.event.domain.entity.ReceiveMessageEntity;
import com.yuier.yuni.event.service.ReceiveMessageService;
import com.yuier.yuni.plugin.api.PluginView;
import com.yuier.yuni.plugin.runtime.PluginRuntime;
import com.yuier.yuni.webapi.dto.group.GroupMessageItem;
import com.yuier.yuni.webapi.dto.group.GroupMessagesResp;
import com.yuier.yuni.webapi.dto.group.GroupPluginStatusItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 群组管理服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupService {

    private final ReceiveMessageService receiveMessageService;
    /**
     * 插件运行时。
     *
     * <p>取代旧的 {@code PluginContainer} + {@code PluginEnableProcessor} 两个依赖。
     * 管理服务经 {@code runtime.admin()} 取——那是<b>无状态的轻量适配器</b>，
     * 按需新建即可，因此这里不必把 {@code PluginAdmin} 声明成注入依赖
     * （它本身也不是 Spring Bean：它由运行时提供，与插件系统同一生命周期）。</p>
     */
    private final PluginRuntime pluginRuntime;
    private final ObjectMapper objectMapper;
    private final ImageRkeyService imageRkeyService;

    /**
     * 获取群组列表。
     * @return 群组信息列表
     */
    public List<BotGroupInfo> listGroups() {
        YuniBot yuniBot = SpringContextUtil.getBean(YuniBot.class);
        return yuniBot.getGroupList().orElse(List.of());
    }

    /**
     * 获取指定群的聊天记录。
     * @param groupId 群号
     * @param page    页码
     * @param size    每页条数
     * @return 消息列表 + 总数
     */
    public GroupMessagesResp getMessages(Long groupId, int page, int size) {
        List<ReceiveMessageEntity> entities = receiveMessageService.listMessagesByGroup(groupId, page, size);
        List<GroupMessageItem> items = entities.stream()
                .map(e -> {
                    List<Map<String, Object>> segments = parseSegments(e);
                    imageRkeyService.refreshSegments(segments);
                    return new GroupMessageItem(
                            e.getSenderName(),
                            e.getRawMessage() != null ? e.getRawMessage() : "",
                            e.getFormatTime(),
                            e.getIsSelfSent() != null && e.getIsSelfSent(),
                            e.getIsPlainText() != null && e.getIsPlainText(),
                            segments
                    );
                })
                .toList();
        long total = receiveMessageService.countMessagesByGroup(groupId);
        return new GroupMessagesResp(items, total);
    }

    /**
     * 解析 rawJson 为消息段数组。解析失败返回空列表。
     */
    private List<Map<String, Object>> parseSegments(ReceiveMessageEntity e) {
        String json = e.getMessageSegments();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ex) {
            log.debug("解析消息段失败: {}", e.getMessageId(), ex);
            return List.of();
        }
    }

    /**
     * 获取指定群的插件启用状态。
     * @param groupId 群号
     * @return 插件启用状态列表
     */
    public List<GroupPluginStatusItem> getPluginStatus(Long groupId) {
        /*
         * P6 迁移：数据来源从 PluginContainer + PluginEnableProcessor 换成
         * PluginAdmin（见设计文档 §8.10）。
         *
         * 顺带修掉两处旧实现的问题：
         *
         * 1. 原来用 try/catch 兜住 isPluginEnabled——因为它内部会读数据库，
         *    异常时回落 defaultEnable。现在 PluginAdmin.isEnabledIn 本身就是
         *    「例外 → 基线 → 默认值」的完整判定链，不需要调用方兜异常；
         * 2. 原来的 type 字段来自 instance.getPluginType().name()，即旧的八种
         *    插件类型（COMMAND / PATTERN / ...）。新系统里「插件类型」不再是
         *    声明，而是「注册了什么能力」的推论，因此这里改报运行状态
         *    （ACTIVE / FAILED / PENDING）——那才是管理端真正需要的信息。
         *    字段名保持 type 不变以免破坏前端契约。
         */
        List<GroupPluginStatusItem> result = new ArrayList<>();
        var admin = pluginRuntime.admin();
        for (PluginView plugin : admin.plugins()) {
            result.add(new GroupPluginStatusItem(
                    plugin.id(),
                    plugin.name(),
                    plugin.state().name(),
                    admin.isEnabledIn(plugin.id(), groupId)
            ));
        }
        return result;
    }
}
