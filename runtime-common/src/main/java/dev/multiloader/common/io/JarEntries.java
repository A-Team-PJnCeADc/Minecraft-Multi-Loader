package dev.multiloader.common.io;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * jar / 目录条目的统一读取.
 * <p>放在 runtime-common 而不是各层各写一份:适配器要读锚点文件,
 * Mixin 层要读 mixins.json 与 mixin 类字节码,读的是同一回事.
 * 一个"读 jar 条目"的公式只应存在一处.
 * <p>刻意不抛出受检异常:读不到就是 {@code null},让调用方按自己的语义处理
 * (是"文件不存在"还是"格式错误",只有调用方知道).
*/
public final class JarEntries {

    private JarEntries() {
    }

/**
     * 读取一个条目.
     * <p>{@code path} 既可以是 jar,也可以是目录(目录型 mod).
     * @return 条目不存在或不可读时返回 null
*/
    public static byte[] read(Path path, String entryName) {
        if (Files.isDirectory(path)) {
            Path file = path.resolve(entryName);
            if (!Files.isRegularFile(file)) {
                return null;
            }
            try {
                return Files.readAllBytes(file);
            } catch (IOException e) {
                return null;
            }
        }
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try (ZipFile zip = new ZipFile(path.toFile())) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                return null;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return in.readAllBytes();
            }
        } catch (IOException e) {
            return null;
        }
    }

    public static boolean exists(Path path, String entryName) {
        return read(path, entryName) != null;
    }

/**
     * 列出 jar 中所有以 {@code suffix} 结尾的条目名(目录型 mod 则遍历其文件树).
     * <p>为什么放在这里:jar 条目的读取公式只应有一处实现.适配器要扫描
     * {@code @Mod} 类,要枚举 mixin 配置,各自用 {@code ZipFile} 写一遍遍历逻辑,
     * 就会出现"目录型 mod 支持不一致"这类只在某些 mod 上复现的差异.
     * <p>返回顺序**不保证**(取决于 zip 条目顺序).调用方若需要稳定顺序必须自行排序 
     * 这一点必须显式:依赖枚举顺序的代码会在不同打包工具产出的 jar 上表现不同.
*/
    public static List<String> list(Path path, String suffix) {
        if (!Files.isRegularFile(path)) {
            // 目录型 mod:走文件树,条目名用 '/' 分隔,与 jar 内形态保持一致
            if (!Files.isDirectory(path)) {
                return List.of();
            }
            try (Stream<Path> walk = Files.walk(path)) {
                Path root = path;
                return walk.filter(Files::isRegularFile)
                        .map(file -> root.relativize(file).toString().replace(File.separatorChar, '/'))
                        .filter(name -> name.endsWith(suffix))
                        .toList();
            } catch (IOException e) {
                return List.of();
            }
        }
        List<String> entries = new ArrayList<>();
        try (ZipFile zip = new ZipFile(path.toFile())) {
            Enumeration<? extends ZipEntry> it = zip.entries();
            while (it.hasMoreElements()) {
                ZipEntry entry = it.nextElement();
                if (!entry.isDirectory() && entry.getName().endsWith(suffix)) {
                    entries.add(entry.getName());
                }
            }
        } catch (IOException e) {
            return List.of();
        }
        return entries;
    }

/**
     * 构造"jar 内部条目的伪路径".
     * <p>嵌套 jar(Fabric 的 {@code jars[]})位于 mod jar 内部,没有文件系统路径.
     * 统一用 {@code <jar>!/<entry>} 表示,解包工作交给类加载阶段.
*/
    public static Path pseudoPath(Path container, String entryName) {
        return Path.of(container.toString().replace('\\', '/') + "!/" + entryName);
    }

/** 伪路径是否指向 jar 内部条目. */
    public static boolean isPseudoPath(Path path) {
        return path.toString().contains("!/");
    }

/** 拆分伪路径为 (容器, 条目名);非伪路径返回 null. */
    public static String[] splitPseudoPath(Path path) {
        String value = path.toString();
        int index = value.indexOf("!/");
        if (index < 0) {
            return null;
        }
        return new String[]{value.substring(0, index), value.substring(index + 2)};
    }
}
