package dev.multiloader.mixin.platform;

import org.spongepowered.asm.service.IClassProvider;

import java.net.URL;

/**
 * 让 Mixin 通过**游戏层类加载器**加载类.
 * <p>为什么不能在服务里直接引用 {@code TransformingClassLoader}:
 * 这个服务是 Mixin 用反射/ServiceLoader 实例化的,可能在游戏层类加载器
 * 尚未就绪时就被构造.所以我们只持有 {@code ClassLoader} 视图,
 * 由 {@link MultiLoaderMixinService#installGameLoader} 注入.
 * <p>两条路径的取舍:
 * <ul>
 *   <li>{@link #findClass}(Mixin 加载**混入类**):走 gameLoader.
 *       混入类在 mod jar 里,只有游戏层看得到.</li>
 *   <li>{@link #findAgentClass}(Mixin 加载**平台代理类**):走服务自己的加载器.
 *       代理类属于平台(我们),必须和 Mixin 本体在同一层,
 *       否则 Mixin 无法把代理类当成自己人.</li>
 * </ul>
*/
final class GameClassProvider implements IClassProvider {

    @Override
    @SuppressWarnings("deprecation")   // 见下方说明：这是接口强制的
    public URL[] getClassPath() {
        // Mixin 自己把 IClassProvider.getClassPath() 标了 @Deprecated
        // ("use of this method is not a sensible way to access available containers"),
        // 但它在接口里仍是 **abstract**  实现这个接口就绕不过去.
        // 这里显式抑制警告并记录理由,而不是让一条"已废弃 API"的提示长期挂在构建里
        // 掩盖真正需要注意的废弃用法.
        // 返回空数组是安全的:我们基于 ClassPathIndex 而非 URLClassLoader,
        // 拿不到 URL 列表;Mixin 只在少数诊断路径读这个值.
        return new URL[0];
    }

    @Override
    public Class<?> findClass(String name) throws ClassNotFoundException {
        return findClass(name, false);
    }

    @Override
    public Class<?> findClass(String name, boolean initialize) throws ClassNotFoundException {
        // 用 Class.forName(name, initialize, gameLoader) 而不是 gameLoader.loadClass():
        // 显式控制是否初始化,避免 Mixin 在探测阶段意外触发静态初始化器.
        return Class.forName(name, initialize, MultiLoaderMixinService.gameLoader());
    }

    @Override
    public Class<?> findAgentClass(String name, boolean initialize) throws ClassNotFoundException {
        // 代理类住在 Mixin 自己那一层(app 层)
        return Class.forName(name, initialize, getClass().getClassLoader());
    }
}
