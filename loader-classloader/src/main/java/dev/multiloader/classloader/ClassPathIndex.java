package dev.multiloader.classloader;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 类路径索引.
 * <p>职责:把"一个内部名 ->原始字节码"这件事与"谁来 defineClass"解耦.
 * 这么拆的直接好处是 {@link AsmClassHierarchy} 能在**不触发类加载**的前提下
 * 读到目标类字节码  这正是 Mixin bootstrap 的逃逸口所需要的.
 * <p>根的顺序即查找优先级:游戏 jar 在前,mod 在后(mod 不能覆盖游戏类).
*/
public final class ClassPathIndex implements AutoCloseable {

/** 类路径根的来源.仅用于诊断与冲突排查,不参与查找逻辑. */
    public enum Scope {
        GAME,
        MOD,
        LIBRARY
    }

    public record Root(Path path, Scope scope) {
    }

    private final List<Root> roots;
    private final Map<Path, ZipFile> openJars = new ConcurrentHashMap<>();

    public ClassPathIndex(List<Root> roots) {
        this.roots = List.copyOf(roots);
    }

    public List<Root> roots() {
        return roots;
    }

/**
     * 读取一个类的原始字节码,不做任何转换,不 defineClass.
     * @param internalName 内部名,如 {@code net/minecraft/client/Minecraft}
     * @return 找不到时返回 null
*/
    public byte[] findClassBytes(String internalName) {
        String entryName = internalName + ".class";
        for (Root root : roots) {
            byte[] bytes = readFromRoot(root.path(), entryName);
            if (bytes != null) {
                return bytes;
            }
        }
        return null;
    }

/** 类路径上是否存在该类. */
    public boolean contains(String internalName) {
        return findClassBytes(internalName) != null;
    }

/** 按 classpath 顺序收集某个资源的全部 URL. */
    public List<URL> findResources(String resourcePath) {
        List<URL> urls = new ArrayList<>();
        for (Root root : roots) {
            Path path = root.path();
            if (Files.isDirectory(path)) {
                Path file = path.resolve(resourcePath);
                if (Files.isRegularFile(file)) {
                    try {
                        urls.add(file.toUri().toURL());
                    } catch (MalformedURLException ignored) {
                        // 目录路径不会产生非法 URL,忽略
                    }
                }
            } else if (Files.isRegularFile(path)) {
                ZipFile zip = openJar(path);
                if (zip != null && zip.getEntry(resourcePath) != null) {
                    // 不用 new URL(String):该构造器在 Java 20 起已废弃.
                    // URI.create(...).toURL() 是替代写法.
                    try {
                        urls.add(URI.create("jar:" + path.toUri().toURL() + "!/" + resourcePath).toURL());
                    } catch (MalformedURLException | IllegalArgumentException e) {
                        // 路径含 URI 保留字符时会走到这里,跳过该条目即可
                    }
                }
            }
        }
        return urls;
    }

    public Enumeration<URL> findResourcesEnumeration(String resourcePath) {
        return Collections.enumeration(findResources(resourcePath));
    }

    private byte[] readFromRoot(Path root, String entryName) {
        if (Files.isDirectory(root)) {
            Path file = root.resolve(entryName);
            if (!Files.isRegularFile(file)) {
                return null;
            }
            try {
                return Files.readAllBytes(file);
            } catch (IOException e) {
                return null;
            }
        }
        if (!Files.isRegularFile(root)) {
            return null;
        }
        ZipFile zip = openJar(root);
        if (zip == null) {
            return null;
        }
        ZipEntry entry = zip.getEntry(entryName);
        if (entry == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    private ZipFile openJar(Path path) {
        return openJars.computeIfAbsent(path, p -> {
            try {
                return new ZipFile(p.toFile());
            } catch (IOException e) {
                return null;
            }
        });
    }

    @Override
    public void close() {
        for (ZipFile zip : openJars.values()) {
            if (zip != null) {
                try {
                    zip.close();
                } catch (IOException ignored) {
                    // 关闭失败不影响进程退出
                }
            }
        }
        openJars.clear();
    }
}
