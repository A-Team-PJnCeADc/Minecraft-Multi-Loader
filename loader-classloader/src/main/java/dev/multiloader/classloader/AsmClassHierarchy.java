package dev.multiloader.classloader;

import dev.multiloader.api.transform.IClassHierarchy;
import org.objectweb.asm.ClassReader;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 ASM 的只读类层级.
 * <p>全部数据来自 {@link ClassPathIndex}(原始字节码),**绝不调用
 * {@code Class.forName} / {@code loadClass}**.这是刻意的:
 * 处理器与 Mixin bootstrap 在转换期查询层级,一旦走类加载就会
 * 递归回转换管道,轻则 StackOverflow,重则产出半个类.
*/
public final class AsmClassHierarchy implements IClassHierarchy {

/** 用于探测 class file 版本的默认类.找不到则返回 0. */
    private static final String DEFAULT_VERSION_PROBE = "net/minecraft/SharedConstants";

    private static final int CLASS_FILE_VERSION_OFFSET = 6;

    private final ClassPathIndex index;
    private final String versionProbeClass;

    private final Map<String, Optional<byte[]>> bytesCache = new ConcurrentHashMap<>();
    private final Map<String, Optional<String>> superCache = new ConcurrentHashMap<>();
    private volatile int resolvedClassFileVersion = -1;

    public AsmClassHierarchy(ClassPathIndex index) {
        this(index, DEFAULT_VERSION_PROBE);
    }

    public AsmClassHierarchy(ClassPathIndex index, String versionProbeClass) {
        this.index = index;
        this.versionProbeClass = versionProbeClass;
    }

    @Override
    public Optional<byte[]> peekBytes(String internalName) {
        return bytesCache.computeIfAbsent(internalName,
                name -> Optional.ofNullable(index.findClassBytes(name)));
    }

    @Override
    public Optional<String> superName(String internalName) {
        return superCache.computeIfAbsent(internalName, name -> {
            Optional<byte[]> bytes = peekBytes(name);
            if (bytes.isEmpty()) {
                return Optional.empty();
            }
            try {
                return Optional.ofNullable(new ClassReader(bytes.get()).getSuperName());
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        });
    }

    @Override
    public List<String> superChain(String internalName) {
        Set<String> chain = new LinkedHashSet<>();
        String current = superName(internalName).orElse(null);
        int guard = 0;
        while (current != null && guard++ < 512) {
            if (!chain.add(current)) {
                break;      // 环（损坏的 jar）时保护
            }
            current = superName(current).orElse(null);
        }
        return List.copyOf(chain);
    }

/**
     * {@code child} 是否可赋值给 {@code ancestor}(含自身).
     * <p>只沿父类链查找,不遍历接口.接口赋值查询等到真正需要时再加
     * (Mixin 的注入点判定用不到接口方向).
*/
    @Override
    public boolean isAssignable(String childInternalName, String ancestorInternalName) {
        if (childInternalName.equals(ancestorInternalName)) {
            return true;
        }
        return superChain(childInternalName).contains(ancestorInternalName);
    }

    @Override
    public int classFileVersion() {
        int cached = resolvedClassFileVersion;
        if (cached >= 0) {
            return cached;
        }
        int detected = 0;
        Optional<byte[]> bytes = peekBytes(versionProbeClass);
        if (bytes.isPresent() && bytes.get().length > CLASS_FILE_VERSION_OFFSET + 2) {
            byte[] data = bytes.get();
            detected = ((data[CLASS_FILE_VERSION_OFFSET] & 0xFF) << 8)
                    | (data[CLASS_FILE_VERSION_OFFSET + 1] & 0xFF);
        }
        resolvedClassFileVersion = detected;
        return detected;
    }

/** 清空缓存.类路径根发生变化时必须调用. */
    public void invalidate() {
        bytesCache.clear();
        superCache.clear();
        resolvedClassFileVersion = -1;
    }
}
