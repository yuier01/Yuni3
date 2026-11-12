package com.yuier.yuni.plugin.runtime;

import com.yuier.yuni.plugin.api.PluginDeclaration;
import com.yuier.yuni.plugin.api.PluginDescriptor;
import com.yuier.yuni.plugin.api.PluginMetadata;
import com.yuier.yuni.plugin.api.service.PermissionService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务注册表——框架内置服务与插件提供的服务共用的命名空间。
 *
 * <h2>为什么与 Spring 容器分开</h2>
 * <p>现行插件通过 {@code SpringContextUtil.getBean(Class)} 直接访问 Spring 容器。
 * 这带来两个问题：</p>
 * <ol>
 *   <li><b>依赖不可见</b>：插件能拿到任意 Bean，框架无法得知「这个插件依赖什么」，
 *       因此也无法做依赖驱动的加载顺序与 PENDING 状态；</li>
 *   <li><b>与插件加载器冲突</b>：插件提供的服务由插件 ClassLoader 加载的类实现，
 *       注册进 Spring 容器会给容器的类加载语义带来麻烦。</li>
 * </ol>
 * <p>本表是一个独立、扁平的「类型 → 注册项」表，专供插件系统使用。
 * 框架内置服务（bot / cache / http / …）在启动时预注册；插件通过
 * {@code ctx.registerService(...)} 注册自己的服务。</p>
 *
 * <h2>为什么只有一个 Map</h2>
 * <p>「服务实例」与「提供者 Fiber」是同一次注册的两个属性，因此合并为
 * {@link ServiceEntry} 存进同一个表：</p>
 * <ul>
 *   <li>注册是原子的——不会出现「新实例 + 旧提供者」的中间态；</li>
 *   <li>注销用 {@code remove(key, expectedEntry)} 做 CAS，避免并发注册后被误删；</li>
 *   <li>不存在「忘同步另一张表」的隐患。</li>
 * </ul>
 *
 * <h2>覆盖语义</h2>
 * <p>同一类型重复注册时<b>后者覆盖前者</b>，并返回被覆盖的实例供调用方决定
 * 是否告警。这与现行 {@code PluginEnableProcessor} 里「同一插件重复启用」
 * 的静默覆盖不同——这里把决策权交给调用方。</p>
 *
 * <h2>线程安全</h2>
 * <p>插件加载与卸载可能并发发生，使用 {@link ConcurrentHashMap}；
 * 注册项的替换与移除都以单条 map 操作为准。</p>
 *
 * @see com.yuier.yuni.plugin.api.YuniContext#findService(Class)
 */
public final class ServiceRegistry {

    /**
     * 内置服务的占位提供者。
     *
     * <p>框架内置服务（bot / cache / http / …）没有对应的插件 Fiber。不能直接用
     * {@code null} 表示——{@link ConcurrentHashMap} 的 value 不接受 {@code null}，
     * 且 {@code null} 在注销路径上还有别的含义。这里用一个私有哨兵对象
     * 区分「内置」与「插件提供」。</p>
     */
    private static final PluginFiber BUILTIN = new PluginFiber(
            ctx -> { },
            PluginMetadata.of(
                    "",
                    PluginDeclaration.of("<builtin>").build(),
                    PluginDescriptor.empty(),
                    List.of()),
            null);

    /**
     * 一个注册项：服务实例 + 提供者。
     *
     * @param instance 服务实现
     * @param provider 提供者 Fiber；框架内置服务为 {@link #BUILTIN}
     */
    private record ServiceEntry(Object instance, PluginFiber provider) {
    }

    /** 服务类型 → 注册项。 */
    private final Map<Class<?>, ServiceEntry> entries = new ConcurrentHashMap<>();

    /**
     * 注册一个<b>框架内置</b>服务。
     *
     * <p>内置服务没有提供者 Fiber，不会被任何插件的卸载流程连带注销。
     * 该方法与三参版本分开，是为了让「内置」这个语义由<b>调用的方法</b>
     * 表达，而不是靠传 {@code null} 这种约定。</p>
     *
     * @param type 服务类型（通常是接口）
     * @param impl 实现实例
     * @return 被覆盖的旧实例；无冲突时为空
     */
    public Optional<Object> register(Class<?> type, Object impl) {
        return register(type, impl, BUILTIN);
    }

    /**
     * 注册一个<b>插件提供</b>的服务。
     *
     * @param type     服务类型（通常是接口）
     * @param impl     实现实例
     * @param provider 提供者 Fiber，不能为 {@code null}
     *                 （内置服务请用 {@link #register(Class, Object)}）
     * @return 被覆盖的旧实例；无冲突时为空
     */
    public Optional<Object> register(Class<?> type, Object impl, PluginFiber provider) {
        if (type == null || impl == null) {
            return Optional.empty();
        }
        Objects.requireNonNull(provider,
                "provider 不能为 null；框架内置服务请用 register(type, impl)");
        ServiceEntry previous = entries.put(type, new ServiceEntry(impl, provider));
        return previous == null ? Optional.empty() : Optional.of(previous.instance());
    }

    /**
     * 注销一个服务（不校验提供者）。
     *
     * @param type 服务类型
     * @return 实际移除了服务时返回 {@code true}
     */
    public boolean unregister(Class<?> type) {
        return remove(type, null);
    }

    /**
     * 注销一个由指定 Fiber 提供的服务。
     *
     * <p>只有当前提供者与传入的 Fiber 一致时才移除——避免「A 插件卸载时
     * 误删了 B 插件后来覆盖注册的同类型服务」。内置服务不会被任何插件的
     * 卸载流程注销（它们的提供者是 {@link #BUILTIN}）。</p>
     *
     * @param type     服务类型
     * @param provider 期望的提供者，不能为 {@code null}
     *                 （不校验提供者请用 {@link #unregister(Class)}）
     * @return 实际移除了服务时返回 {@code true}
     */
    public boolean unregister(Class<?> type, PluginFiber provider) {
        Objects.requireNonNull(provider,
                "provider 不能为 null；不校验提供者请用 unregister(type)");
        return remove(type, provider);
    }

    /**
     * 移除注册项。
     *
     * @param type             服务类型
     * @param expectedProvider 期望的提供者；{@code null} 表示不校验
     * @return 实际移除了服务时返回 {@code true}
     */
    private boolean remove(Class<?> type, PluginFiber expectedProvider) {
        if (type == null) {
            return false;
        }
        ServiceEntry current = entries.get(type);
        if (current == null) {
            return false;
        }
        if (expectedProvider != null && current.provider() != expectedProvider) {
            // 已被其他插件覆盖注册，本插件的卸载不应影响它
            return false;
        }
        // 两参 remove 是 CAS：若期间被并发覆盖，则不移除新注册项
        return entries.remove(type, current);
    }

    /**
     * 解析服务。
     *
     * @param type 服务类型
     * @param <T>  服务类型
     * @return 服务实例；未注册时为空
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> resolve(Class<T> type) {
        if (type == null) {
            return Optional.empty();
        }
        ServiceEntry entry = entries.get(type);
        return entry == null ? Optional.empty() : Optional.of((T) entry.instance());
    }

    /**
     * 服务是否已注册。
     *
     * @param type 服务类型
     * @return 已注册返回 {@code true}
     */
    public boolean contains(Class<?> type) {
        return type != null && entries.containsKey(type);
    }

    /**
     * 注销指定 Fiber 提供的全部服务。
     *
     * <p>由 {@code PluginRuntime} 在 Fiber 卸载时调用，避免逐个追踪。</p>
     *
     * @param provider 提供者 Fiber
     * @return 被注销的服务类型列表
     */
    public List<Class<?>> unregisterAll(PluginFiber provider) {
        List<Class<?>> removed = new ArrayList<>();
        if (provider == null) {
            return removed;
        }
        /*
         * 遍历的是 entrySet 的<b>副本</b>（new ArrayList<>(...)），不是 entries 本身：
         * 循环体里 remove 作用于 entries，而迭代器属于这个副本，两者是不同的对象。
         * 这样既不会边遍历边改同一个集合，也保证「快照时刻的每个注册项恰好被
         * 考虑一次」——不受遍历期间新增/删除的影响。
         *
         * 两参 remove 是 CAS：若期间该类型被并发覆盖注册，旧 entry 不再匹配，
         * 这里返回 false，既不会误删新注册项，也不会把它算进 removed。
         */
        for (Map.Entry<Class<?>, ServiceEntry> entry : new ArrayList<>(entries.entrySet())) {
            if (entry.getValue().provider() == provider
                    && entries.remove(entry.getKey(), entry.getValue())) {
                removed.add(entry.getKey());
            }
        }
        return removed;
    }

    /**
     * 已注册的服务类型集合。
     *
     * @return 不可变快照
     */
    public Set<Class<?>> getRegisteredTypes() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(entries.keySet()));
    }

    /**
     * 已注册的服务数量。
     *
     * @return 数量
     */
    public int size() {
        return entries.size();
    }

    /**
     * 清空全部服务。
     *
     * <p>仅用于测试。</p>
     */
    public void clear() {
        entries.clear();
    }

    // ==================== 便捷工厂 ====================

    /**
     * 创建一个预置了权限服务的注册表。
     *
     * <p>便于在测试与渐进式迁移中使用：只要给出一个权限服务实现，
     * 就能构造出可用的 {@code YuniContext}。</p>
     *
     * @param permissionService 权限服务实现
     * @return 注册表
     */
    public static ServiceRegistry withPermission(PermissionService permissionService) {
        ServiceRegistry registry = new ServiceRegistry();
        registry.register(PermissionService.class, permissionService);
        return registry;
    }
}
