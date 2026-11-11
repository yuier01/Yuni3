package com.yuier.yuni.plugin.runtime.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuier.yuni.plugin.domain.entity.GroupPluginAbilityEntity;
import com.yuier.yuni.plugin.runtime.PluginScopeStore;
import com.yuier.yuni.plugin.service.GroupPluginAbilityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link PluginScopeStore} 的 MyBatis 实现——读写 {@code group_plugin_ability} 表。
 *
 * <h2>为什么复用旧表而不是建新表</h2>
 * <p>该表的结构 {@code (id, groupId, pluginId, ability)} 恰好是群粒度启停所需的
 * 完整三态数据，且已在实际部署中积累配置。建新表意味着要么写迁移脚本、
 * 要么接受「升级后用户的群配置全部丢失」——两者都不如直接复用。</p>
 *
 * <h2>与旧系统的插件标识差异（升级影响，重要）</h2>
 * <p>{@code pluginId} 的含义变了：</p>
 * <table border="1">
 *   <caption>标识格式变化</caption>
 *   <tr><th></th><th>格式</th><th>示例</th></tr>
 *   <tr><td>旧系统</td><td>{@code 模块ID-插件类全限定名}</td>
 *       <td>{@code steam-steamstatus.MemberSteamStatus}</td></tr>
 *   <tr><td>新系统</td><td>{@code 模块ID/插件显示名}</td>
 *       <td>{@code steam/群友 steam 游戏状态监控}</td></tr>
 * </table>
 * <p>因此<b>升级后旧表里的记录不会命中任何插件</b>，表现为「群配置回到默认值」。
 * 这是有意接受的代价——新标识不再暴露类名（改包名不应导致标识变化），
 * 而按旧格式反查新标识需要在加载时维护一张映射表，收益不足以抵消复杂度。</p>
 * <p>升级后需要重新配置一次群启停。旧记录不会被删除，便于人工比对。</p>
 *
 * <h2>{@code save} 的覆盖语义</h2>
 * <p>实现为「先删该插件的全部记录，再插入新记录」。这保证
 * 「用户把某群拨回默认值」之后旧记录不会残留并在下次启动时复活——
 * 那正是三态模型要避免的一类 bug。</p>
 *
 * @see PluginScopeStore
 */
@Slf4j
@RequiredArgsConstructor
public class MybatisPluginScopeStore implements PluginScopeStore {

    /** 群插件启停表的数据访问。 */
    private final GroupPluginAbilityService abilityService;

    /* 构造器由 @RequiredArgsConstructor 生成：(GroupPluginAbilityService abilityService)。 */

    @Override
    public Map<String, PluginScopeRecord> loadAll() {
        List<GroupPluginAbilityEntity> rows = abilityService.list();
        if (rows == null || rows.isEmpty()) {
            return Map.of();
        }
        /*
         * 先按插件分组，再区分「全局行」与「群例外行」。
         *
         * groupId 为 null 的行是全局开关——这与旧系统一致：
         * PluginEnableProcessor 在 groupId 为 null 时查的就是「全局例外」。
         */
        Map<String, Boolean> globals = new LinkedHashMap<>();
        Map<String, Set<Long>> exceptions = new LinkedHashMap<>();
        for (GroupPluginAbilityEntity row : rows) {
            if (row.getPluginId() == null) {
                // 脏数据（历史手工插入等）：跳过而不是让整次装载失败
                log.debug("跳过缺少 pluginId 的群插件启停记录: id={}", row.getId());
                continue;
            }
            boolean ability = Boolean.TRUE.equals(row.getAbility());
            if (row.getGroupId() == null) {
                globals.put(row.getPluginId(), ability);
            } else {
                exceptions.computeIfAbsent(row.getPluginId(), k -> new LinkedHashSet<>())
                        .add(row.getGroupId());
            }
        }

        Map<String, PluginScopeRecord> result = new LinkedHashMap<>();
        Set<String> pluginIds = new LinkedHashSet<>(globals.keySet());
        pluginIds.addAll(exceptions.keySet());
        for (String pluginId : pluginIds) {
            result.put(pluginId, new PluginScopeRecord(
                    globals.get(pluginId),
                    exceptions.getOrDefault(pluginId, Set.of())));
        }
        log.debug("从 group_plugin_ability 装载 {} 个插件的作用域配置", result.size());
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void save(String pluginId, PluginScopeRecord record) {
        if (pluginId == null) {
            return;
        }
        // 先删后插：保证「拨回默认值的群」不会残留旧记录并在下次启动时复活
        LambdaQueryWrapper<GroupPluginAbilityEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GroupPluginAbilityEntity::getPluginId, pluginId);
        abilityService.remove(wrapper);

        if (record == null || record.isEmpty()) {
            // 无任何显式设置：该插件全部按默认值，表里不需要任何行
            log.debug("插件 {} 无显式作用域配置，已清空其记录", pluginId);
            return;
        }

        int saved = 0;
        if (record.getGlobalEnabled() != null) {
            // 全局开关：用 groupId = null 的行表达，与旧系统写法一致
            GroupPluginAbilityEntity globalRow = new GroupPluginAbilityEntity();
            globalRow.setGroupId(null);
            globalRow.setPluginId(pluginId);
            globalRow.setAbility(record.getGlobalEnabled());
            abilityService.save(globalRow);
            saved++;
        }
        for (Long groupId : record.getExceptionGroups()) {
            GroupPluginAbilityEntity row = new GroupPluginAbilityEntity();
            row.setGroupId(groupId);
            row.setPluginId(pluginId);
            /*
             * 例外群的 ability 与全局开关相反。
             *
             * 注意：本方法拿不到「该群是否生效」的直接答案——因为记录里存的是
             * 「与全局相反的群集合」。当全局开关为 null（用户没设置过，取插件默认）
             * 时，这里只能如实写入「与默认相反」，由运行时在装载时结合插件默认值
             * 还原。因此真正的判定始终由 PluginScopes 负责，本层只做搬运。
             */
            row.setAbility(!Boolean.TRUE.equals(record.getGlobalEnabled()));
            abilityService.save(row);
            saved++;
        }
        log.debug("插件 {} 的 {} 条作用域记录已保存", pluginId, saved);
    }
}
