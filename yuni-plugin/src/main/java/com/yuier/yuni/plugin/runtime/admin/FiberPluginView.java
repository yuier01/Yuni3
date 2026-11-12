package com.yuier.yuni.plugin.runtime.admin;

import com.yuier.yuni.plugin.api.PluginState;
import com.yuier.yuni.plugin.api.PluginView;
import com.yuier.yuni.plugin.runtime.FiberState;
import com.yuier.yuni.plugin.runtime.PluginFiber;
import lombok.Getter;

import java.util.List;
import java.util.Optional;

/**
 * {@link PluginView} 的默认实现——对 {@link PluginFiber} 的只读投影。
 *
 * <h2>为什么是「投影」而不是「把 Fiber 直接暴露出去」</h2>
 * <p>本类是 {@code yuni-plugin} 内部类型到公共契约的<b>单向翻译层</b>。
 * 这样做的收益有三条：</p>
 * <ol>
 *   <li><b>不泄漏内部类型</b>：{@link PluginFiber} 有 {@code dispose()}、
 *       {@code effects()}、{@code rollbackEffects()} 等仅供运行时使用的成员。
 *       管理端拿到 Fiber 就能调它们，而契约里没有这些承诺；</li>
 *   <li><b>不泄漏插件实例</b>：{@link PluginFiber#getPlugin()} 返回
 *       {@code YuniPlugin} 实例。视图<b>不</b>把它暴露出去，因此管理端无法持有
 *       一个已卸载插件的实例引用——那会让它的 ClassLoader 无法回收
 *       （设计文档 §9.4 的 P0 事故正是这类问题）；</li>
 *   <li><b>状态枚举可以不同</b>：内部 {@link FiberState} 有 6 个值，公共
 *       {@link PluginState} 按管理端需要重新组织了它们。翻译在这里完成，
 *       消费方不需要知道两套枚举的存在。</li>
 * </ol>
 *
 * <h2>它是快照</h2>
 * <p>构造时拷贝全部字段。因此视图对象的生命周期与 Fiber 无关，
 * 管理端可以安全地缓存、序列化、跨线程传递——不会因为一次热重载而读到
 * 半更新的状态。这也意味着<b>它不会自动反映后续状态变化</b>，
 * 需要最新状态时重新向 {@code PluginAdmin} 查询。</p>
 *
 * @see PluginView
 */
@Getter
public final class FiberPluginView implements PluginView {

    /** 插件标识。 */
    private final String id;

    /** 所属加载单元标识。 */
    private final String artifactId;

    /** 平铺的元数据字段。 */
    private final String name;
    private final String version;
    private final String description;
    private final List<String> tips;
    private final String author;
    private final List<String> requiredServices;
    private final String homepage;
    private final String repository;
    private final String icon;
    private final boolean buildIn;
    private final boolean defaultEnable;

    /** 运行状态（已翻译为公共枚举）。 */
    private final PluginState state;

    /** 缺失的依赖服务类型名；仅 PENDING 时非空。 */
    private final List<String> missingServices;

    /** 失败摘要；仅 FAILED 时非空。 */
    private final String failureSummary;

    /**
     * 从 Fiber 构造视图。
     *
     * @param fiber 插件 Fiber
     */
    public FiberPluginView(PluginFiber fiber) {
        this.id = fiber.getId();
        this.artifactId = fiber.getArtifactId() == null ? "" : fiber.getArtifactId();

        var metadata = fiber.getMetadata();
        this.name = metadata.getName();
        this.version = metadata.getVersion();
        this.description = metadata.getDescription();
        this.tips = metadata.getTips();
        this.author = metadata.getAuthor();
        this.requiredServices = metadata.getRequiredServices().stream()
                .map(Class::getName)
                .toList();
        this.homepage = metadata.getHomepage();
        this.repository = metadata.getRepository();
        this.icon = metadata.getIcon();
        this.buildIn = metadata.isBuildIn();
        this.defaultEnable = metadata.isDefaultEnable();

        this.state = translate(fiber.getState());
        this.missingServices = fiber.getPendingDependencies().stream()
                .map(Class::getName)
                .toList();
        this.failureSummary = fiber.getFailureCause()
                .map(t -> t.getClass().getSimpleName()
                        + (t.getMessage() == null ? "" : ": " + t.getMessage()))
                .orElse(null);
    }

    /**
     * 内部状态枚举翻译为公共状态枚举。
     *
     * <p>两个枚举一一对应，没有归并——因为 {@code LOADING} 与 {@code UNLOADING}
     * 虽然瞬态，但「长时间停在这两个状态」本身就是重要信号
     * （{@code apply} 阻塞 / Disposer 卡住），保留它们比归并更有诊断价值。</p>
     *
     * @param internal 内部状态
     * @return 公共状态
     */
    private static PluginState translate(FiberState internal) {
        return switch (internal) {
            case PENDING -> PluginState.PENDING;
            case LOADING -> PluginState.LOADING;
            case ACTIVE -> PluginState.ACTIVE;
            case FAILED -> PluginState.FAILED;
            case UNLOADING -> PluginState.UNLOADING;
            case DISPOSED -> PluginState.DISPOSED;
        };
    }

    /*
     * 契约里的 id() / artifactId() / name() / version() / description() / tips() /
     * author() / buildIn() / defaultEnable() / state() / missingServices() 全部由
     * 类上的 @Getter + @Accessors(fluent) 生成：契约方法名是 record 风格，
     * 与 fluent getter 的命名完全一致。
     *
     * failureSummary() 不能生成——字段是 String，契约要求 Optional，
     * 需要保留下面这个手工方法做包装。
     */

    @Override
    public Optional<String> getFailureSummary() {
        return Optional.ofNullable(failureSummary);
    }

    @Override
    public String toString() {
        return "PluginView{" + id + "[" + state.getDisplayName() + "]}";
    }
}
