package com.yuier.yuni.plugin.runtime.admin;

import com.yuier.yuni.plugin.api.PluginModuleView;
import com.yuier.yuni.plugin.api.PluginView;
import com.yuier.yuni.plugin.runtime.FiberRegistry;
import lombok.Getter;

import java.util.List;

/**
 * {@link PluginModuleView} 的默认实现。
 *
 * <p>与 {@link FiberPluginView} 同样是「快照 + 投影」：构造时把
 * {@link FiberRegistry.ModuleHandle} 与它的插件 Fiber 翻译成公共契约对象，
 * 之后不再引用运行时内部类型。</p>
 *

 * @see PluginModuleView
 */
@Getter
public final class FiberModuleView implements PluginModuleView {

    /** 模块 ID。 */
    private final String id;

    /** 模块显示名。 */
    private final String name;

    /** jar 文件名。 */
    private final String jarFileName;

    /** 本模块的插件。 */
    private final List<PluginView> plugins;

    /**
     * 从模块句柄构造视图。
     *
     * @param handle 模块句柄
     * @param fibers Fiber 容器（用于取出该模块的插件 Fiber）
     */
    public FiberModuleView(FiberRegistry.ModuleHandle handle, FiberRegistry fibers) {
        this.id = handle.getModuleId();
        this.name = handle.getModuleName();
        this.jarFileName = handle.getJarFileName();
        this.plugins = fibers.ofModule(handle.getModuleId()).stream()
                .map(fiber -> (PluginView) new FiberPluginView(fiber, handle.getModuleId()))
                .toList();
    }



    @Override
    public String toString() {
        return "ModuleView{" + id + ", plugins=" + plugins.size() + "}";
    }
}
