package dev.multiloader.core.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * 核心层唯一的服务发现入口.
 * <p>语义刻意分成两种,这是架构上的关键区分:
 * <ul>
 *   <li>{@link #loadAll}  "有几个用几个".适配器,扩展走这条.
 *       没有实现时返回空列表,不报错.</li>
 *   <li>{@link #loadOptional} "有没有都行".旧版兼容层走这条.
 *       核心层拿不到实现时**静默跳过**,这正是"核心层不认识兼容层"的落地方式.</li>
 * </ul>
 * <p>每次调用都重新走 ServiceLoader:加载器层初始化期间会有组件陆续注册
 * service 文件,缓存会让后注册的组件永远看不见.
*/
public final class ServiceRegistry {

    private ServiceRegistry() {
    }

    public static <T> List<T> loadAll(Class<T> serviceType) {
        return loadAll(serviceType, defaultClassLoader());
    }

    public static <T> List<T> loadAll(Class<T> serviceType, ClassLoader classLoader) {
        List<T> found = new ArrayList<>();
        for (T provider : ServiceLoader.load(serviceType, classLoader)) {
            found.add(provider);
        }
        return List.copyOf(found);
    }

/**
     * 加载 0 或 1 个实现.
     * @throws IllegalStateException 存在多个实现时(语义上"唯一"的服务不该有多个)
*/
    public static <T> Optional<T> loadOptional(Class<T> serviceType) {
        return loadOptional(serviceType, defaultClassLoader());
    }

    public static <T> Optional<T> loadOptional(Class<T> serviceType, ClassLoader classLoader) {
        List<T> all = loadAll(serviceType, classLoader);
        if (all.isEmpty()) {
            return Optional.empty();
        }
        if (all.size() > 1) {
            throw new IllegalStateException("Expected at most one " + serviceType.getName()
                    + " but found " + all.size() + ": " + all);
        }
        return Optional.of(all.get(0));
    }

/** 加载且必须存在.用于核心层无法降级的依赖. */
    public static <T> T loadRequired(Class<T> serviceType) {
        return loadOptional(serviceType).orElseThrow(() -> new IllegalStateException(
                "No implementation of " + serviceType.getName() + " on the loader classpath"));
    }

    private static ClassLoader defaultClassLoader() {
        ClassLoader cl = ServiceRegistry.class.getClassLoader();
        return cl != null ? cl : ClassLoader.getSystemClassLoader();
    }
}
