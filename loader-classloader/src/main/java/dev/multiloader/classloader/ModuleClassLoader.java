package dev.multiloader.classloader;

import java.io.IOException;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 我们自己的模块感知类加载器基类.
 * <p>为什么不直接继承 JDK 的 {@code ModuleClassLoader}:**JDK 没有这个公开类**.
 * FancyModLoader 的 {@code net.neoforged.fml.classloading.ModuleClassLoader}
 * 是 NeoForge 自己写的 562 行实现({@code extends ClassLoader implements AutoCloseable}),
 * 并不是 JDK API.所以这里按同样思路写一份精简版.
 * <p>本类提供三件事:
 * <ol>
 *   <li>child-first 委派:命中的前缀先查自己的根,再问父加载器</li>
 *   <li>并行能力({@code registerAsParallelCapable})</li>
 *   <li>把"取原始字节"与"转换"两个动作拆成可覆写的钩子</li>
 * </ol>
 * <p>本轮(S4)刻意不做的:完整的 JPMS {@code Configuration} / {@code ModuleLayer}
 * 装配.{@link #findClass(String, String)} 已按模块签名重写以便后续接入,
 * 但真正把游戏拆成模块层留到需要时再做26.3 的游戏 jar 目前是普通 classpath 形态.
*/
public class ModuleClassLoader extends ClassLoader implements AutoCloseable {

    static {
        // 必须在任何实例化之前调用,否则并发 loadClass 会在 findLoadedClass 上串行化
        ClassLoader.registerAsParallelCapable();
    }

/** 永远交给平台加载器的前缀.这些包在任何情况下都不该被游戏类覆盖. */
    private static final List<String> PLATFORM_ONLY_PREFIXES = List.of(
            "java.", "javax.", "jdk.", "sun.", "com.sun.", "org.w3c.", "org.xml.");

    private final String loaderName;
    private final ClassPathIndex index;
    private final Set<String> childFirstPrefixes;

/**
     * 正在转换中的类名(按线程隔离).
     * <p>递归保护:转换器读目标类的层级信息时可能触发同类再次进入 findClass,
     * 一旦递归就会 StackOverflow 或产出半个类.
*/
    protected final ThreadLocal<Set<String>> transforming =
            ThreadLocal.withInitial(HashSet::new);

    protected ModuleClassLoader(String loaderName,
                                ClassPathIndex index,
                                Set<String> childFirstPrefixes,
                                ClassLoader parent) {
        super(loaderName, parent);
        this.loaderName = loaderName;
        this.index = index;
        this.childFirstPrefixes = Set.copyOf(childFirstPrefixes);
    }

    public String loaderName() {
        return loaderName;
    }

    protected ClassPathIndex index() {
        return index;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) {
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }

            if (isChildFirst(name)) {
                try {
                    Class<?> c = findClass(name);
                    if (resolve) {
                        resolveClass(c);
                    }
                    return c;
                } catch (ClassNotFoundException childFailed) {
                    return super.loadClass(name, resolve);
                }
            }
            return super.loadClass(name, resolve);
        }
    }

/**
     * 是否走 child-first.平台包永远不走,其余看前缀配置.
*/
    protected boolean isChildFirst(String className) {
        // 平台类永远交给平台:java.* 不可能出现在我们的根里,
        // 而且交给平台是 JVM 的硬要求(否则 java.lang.* 会被重复定义).
        for (String prefix : PLATFORM_ONLY_PREFIXES) {
            if (className.startsWith(prefix)) {
                return false;
            }
        }

        // 显式指定的前缀强制 child-first(调用方可以据此把某个包钉在本层)
        for (String prefix : childFirstPrefixes) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }

        // 核心规则:**凡是本层根里存在的类,一律由本加载器定义.**
        // 为什么这条规则比"枚举包名前缀"可靠:游戏 jar 里的类分布在
        // net.minecraft.*,com.mojang.blaze3d.* 等多个前缀下,人工枚举必然漏.
        // 而漏掉的后果极其隐蔽  那些类会被父加载器从 JVM 类路径上的同名 jar 载入,
        // 绕过整条转换管道,表现为"mixin/AT 对某几个包下的类就是不生效",
        // 且没有任何报错.
        // contains() 只做 zip 条目存在性检查,不解压,所以这个判断足够廉价.
        return index.contains(className.replace('.', '/'));
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        String internalName = name.replace('.', '/');
        byte[] raw = getClassBytes(internalName);
        if (raw == null) {
            throw new ClassNotFoundException(name + " (not found on " + loaderName + " classpath)");
        }
        byte[] code = transformClassBytes(name, raw);
        return defineClass(name, code, 0, code.length);
    }

/**
     * Mixin 逃逸口之一:取原始字节码,**不转换,不 defineClass**.
     * @return 找不到时返回 null
*/
    public byte[] getClassBytes(String internalName) {
        return index.findClassBytes(internalName);
    }

/**
     * 转换钩子.基类不做任何转换.
     * @param className 点分名({@link dev.multiloader.api.transform.ClassContext#className()}
     *                  用的是内部名,转换实现里注意别搞混)
*/
    protected byte[] transformClassBytes(String className, byte[] raw) {
        return raw;
    }

/** JPMS 形式的重写,为后续模块层接入预留;当前直接落到普通 findClass. */
    @Override
    protected Class<?> findClass(String moduleName, String name) {
        try {
            return findClass(name);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    @Override
    public URL getResource(String name) {
        List<URL> found = index.findResources(name);
        if (!found.isEmpty()) {
            return found.get(0);
        }
        return super.getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        List<URL> found = index.findResources(name);
        if (found.isEmpty()) {
            return super.getResources(name);
        }
        return index.findResourcesEnumeration(name);
    }

    @Override
    public void close() {
        index.close();
    }
}
