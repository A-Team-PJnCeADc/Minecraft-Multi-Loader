package dev.multiloader.classloader;

import dev.multiloader.api.transform.ClassContext;
import dev.multiloader.api.transform.IClassHierarchy;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * {@link ClassContext} 的实现.
 * <p>{@code current} 是可变字段:管道里每个处理器都能改写字节码,
 * 后一个处理器看到的必须是前一个处理器的产物.这正是
 * {@link ClassProcessorSet#processAll(ClassContextImpl)} 存在的意义.
*/
public final class ClassContextImpl implements ClassContext {

    private final String className;
    private final ClassLoader gameLoader;
    private final IClassHierarchy hierarchy;
    private final Map<Class<?>, Object> extensions = new HashMap<>();

    private byte[] current;

    public ClassContextImpl(String className, byte[] input,
                            ClassLoader gameLoader, IClassHierarchy hierarchy) {
        this.className = className;
        this.current = input;
        this.gameLoader = gameLoader;
        this.hierarchy = hierarchy;
    }

    @Override
    public String className() {
        return className;
    }

    @Override
    public byte[] input() {
        return current;
    }

    @Override
    public ClassLoader gameLoader() {
        return gameLoader;
    }

    @Override
    public IClassHierarchy hierarchy() {
        return hierarchy;
    }

    @Override
    public <T> Optional<T> get(Class<T> key) {
        return Optional.ofNullable(key.cast(extensions.get(key)));
    }

    @Override
    public <T> void put(Class<T> key, T value) {
        extensions.put(key, value);
    }

/** 由管道在每次处理器返回新字节码后调用. */
    void update(byte[] bytes) {
        if (bytes != null) {
            this.current = bytes;
        }
    }
}
