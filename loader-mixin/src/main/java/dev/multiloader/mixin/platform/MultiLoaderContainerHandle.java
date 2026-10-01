package dev.multiloader.mixin.platform;

import org.spongepowered.asm.launch.platform.container.IContainerHandle;

import java.util.Collection;
import java.util.List;

/**
 * 给 Mixin 的"主容器"句柄.
 * <p><b>为什么必须返回非 null:</b>{@code MixinPlatformManager.init()} 拿到
 * {@code getPrimaryContainer()} 之后会立刻调用 {@code addNestedContainers(handle)},
 * 而它的第一行就是对 handle 解引用.返回 null 会直接 NPE,
 * 而且堆栈落在 Mixin 内部,看起来像是 Mixin 自己的 bug.
 * <p><b>为什么 {@link #getAttribute} 恒返回 null:</b>Mixin 的平台约定里,
 * 容器可以用 {@code MixinConfigs} 属性上报它的 mixin 配置列表.这条路径我们**必须避开** 
 * {@code MixinPlatformAgentDefault.prepare()} 会在 {@code MixinBootstrap.init()} **内部**
 * 读取该属性并立刻物化配置对象,而那一刻环境侧别还是 {@code UNKNOWN}.
 * 而 Mixin 的配置只在**侧别已知**时才收集 {@code server} / {@code client} 段里的 mixin,
 * 于是配置读出来了,包名也解析对了,{@code targets} 却是空的 
 * 症状是"mixin 完全没生效,且没有任何报错".
 * <p>我们改为在设置好侧别之后调用 {@code Mixins.addConfiguration()},
 * 配置会在首次转换时才物化,那时侧别已经正确.
*/
final class MultiLoaderContainerHandle implements IContainerHandle {

    private final String id;
    private final String description;

    MultiLoaderContainerHandle(String id, String description) {
        this.id = id;
        this.description = description;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public String getAttribute(String name) {
        // 恒为 null  理由见类注释:走属性上报会让配置在侧别未知时被提前物化,
        // 结果是 server/client 段里的 mixin 全部丢失且不报错.
        return null;
    }

    @Override
    public Collection<IContainerHandle> getNestedContainers() {
        // mod 的嵌套 jar(Fabric 的 jars[])将来在这里展开;
        // 现在返回空集合,而不是 null  调用方会直接对它做 for-each.
        return List.of();
    }

    @Override
    public String toString() {
        return "MultiLoaderContainer[" + id + "]";
    }
}
