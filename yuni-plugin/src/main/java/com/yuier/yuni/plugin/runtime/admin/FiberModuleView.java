package com.yuier.yuni.plugin.runtime.admin;

import com.yuier.yuni.plugin.api.PluginModuleView;
import com.yuier.yuni.plugin.api.PluginView;
import com.yuier.yuni.plugin.runtime.FiberRegistry;

import java.util.List;

/**
 * {@link PluginModuleView} 的默认实现。
 *
 * <p>与 {@link FiberPluginView} 同样是「快照 + 投影」：构造时把
 * {@link FiberRegistry.ModuleHandle} 与它的插件 Fiber 翻译成公共契约对象，
 * 之后不再引用运行时内部类型。</p>
 *
 * <h2>为什么只包含顶层插件</h2>
 * <p>本视图过滤掉有父 Fiber 的插件（即 {@code ctx.plugin(child)} 挂载的子插件）。
 * 理由见 {@link PluginModuleView} 的类注释：子插件是父插件的实现细节，
 * 它不能独立启停（随父插件卸载），在管理端单独列出只会造成「我能单独关掉它吗」
 * 的困惑。</p>
 *
 * @see PluginModuleView
 */
public final class FiberModuleView implements PluginModuleView {

    /** 模块 ID。 */
    private final String id;

    /** 模块显示名。 */
    private final String name;

    /** jar 文件名。 */
    private final String jarFileName;

    /** 本模块的顶层插件。 */
    private final List<PluginView> plugins;

    /**
     * 从模块句柄构造视图。
     *
     * @param handle 模块句柄
     * @param fibers Fiber 容器（用于取出该模块的插件 Fiber）
     */
    public FiberModuleView(FiberRegistry.ModuleHandle handle, FiberRegistry fibers) {
        this.id = handle.moduleId();
        this.name = handle.moduleName();
        this.jarFileName = handle.jarFileName();
        this.plugins = fibers.ofModule(handle.moduleId()).stream()
                // 只保留顶层插件：子插件随父插件卸载，不是独立的运维对象
                .filter(fiber -> fiber.parent() == null)
                .map(fiber -> (PluginView) new FiberPluginView(fiber, handle.moduleId()))
                .toList();
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String jarFileName() {
        return jarFileName;
    }

    @Override
    public List<PluginView> plugins() {
        return plugins;
    }

    @Override
    public String toString() {
        return "ModuleView{" + id + ", plugins=" + plugins.size() + "}";
    }
}
