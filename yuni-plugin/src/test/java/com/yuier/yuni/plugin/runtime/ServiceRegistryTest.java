package com.yuier.yuni.plugin.runtime;

import com.yuier.yuni.plugin.api.PluginMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ServiceRegistry} 的归属与覆盖语义测试。
 *
 * <p>这些不变量是「合并成单表」后仍然必须保持的行为：</p>
 * <ul>
 *   <li>内置服务不会被任何插件的卸载连带删除；</li>
 *   <li>覆盖注册返回被覆盖的实例，且归属转给新提供者；</li>
 *   <li>插件注销只影响自己提供的服务。</li>
 * </ul>
 */
class ServiceRegistryTest {

    private static PluginFiber fiber(String id) {
        return new PluginFiber(ctx -> { }, PluginMetadata.of(id), null);
    }

    @Test
    @DisplayName("内置服务不会被插件注销")
    void builtinCannotBeRemovedByPlugin() {
        ServiceRegistry registry = new ServiceRegistry();
        registry.register(Runnable.class, (Runnable) () -> { });

        assertTrue(registry.contains(Runnable.class));
        assertFalse(registry.unregister(Runnable.class, fiber("plugin-a")));
        assertTrue(registry.contains(Runnable.class), "内置服务必须仍在");
    }

    @Test
    @DisplayName("覆盖注册返回旧实例，归属转给新提供者")
    void overwriteReturnsPreviousAndTransfersOwnership() {
        ServiceRegistry registry = new ServiceRegistry();
        PluginFiber a = fiber("plugin-a");
        PluginFiber b = fiber("plugin-b");

        assertTrue(registry.register(Runnable.class, (Runnable) () -> { }, a).isEmpty());
        Object first = registry.resolve(Runnable.class).orElseThrow();

        Optional<Object> previous = registry.register(Runnable.class, (Runnable) () -> { }, b);
        assertTrue(previous.isPresent());
        assertEquals(first, previous.get(), "应返回被覆盖的旧实例");

        // a 已经不是提供者：它的注销不能删掉 b 的服务
        assertFalse(registry.unregister(Runnable.class, a));
        assertTrue(registry.contains(Runnable.class));

        assertTrue(registry.unregister(Runnable.class, b));
        assertFalse(registry.contains(Runnable.class));
    }

    @Test
    @DisplayName("unregisterAll 只注销指定提供者的服务")
    void unregisterAllOnlyRemovesOwnServices() {
        ServiceRegistry registry = new ServiceRegistry();
        PluginFiber a = fiber("plugin-a");
        PluginFiber b = fiber("plugin-b");

        registry.register(Runnable.class, (Runnable) () -> { }, a);
        registry.register(CharSequence.class, "a", a);
        registry.register(Comparable.class, 1, b);

        assertEquals(2, registry.unregisterAll(a).size());
        assertFalse(registry.contains(Runnable.class));
        assertFalse(registry.contains(CharSequence.class));
        assertTrue(registry.contains(Comparable.class), "b 的服务不受影响");
    }

    @Test
    @DisplayName("插件注册必须提供 provider")
    void nullProviderRejected() {
        ServiceRegistry registry = new ServiceRegistry();
        assertThrows(NullPointerException.class,
                () -> registry.register(Runnable.class, (Runnable) () -> { }, null));
    }
}
