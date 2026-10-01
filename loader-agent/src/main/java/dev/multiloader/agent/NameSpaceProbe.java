package dev.multiloader.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 命名空间探针.
 * <p>四个探针,加权投票.设计依据(对真实 jar 实测得出):
 * <pre>
 *   jar                     总类数   默认包类          net/minecraft/**
 *   1.21.1(混淆期)         6148    6126 (99.6%)      22
 *   26.1.2(未混淆)         7351    0                 7340 (99.9%)
 *   26.2  (未混淆)         7445    0                 7434 (99.9%)
 * </pre>
 * <p>"默认包类占比"与"net/minecraft 占比"是最强判据,因为 Mojang 的混淆会
 * 把绝大多数类拍平到默认包并取 {@code a}/{@code aa}/{@code aab} 这类短名.
 * <p>刻意不做的事:不按版本号判定(1.21.11 存在 {@code _unobfuscated} 构建),
 * 也不把结果缓存到全局探测只跑一次,且必须在任何类加载之前完成.
*/
public final class NameSpaceProbe {

/** 单条探针结论.{@code obfuscatedVote} 为 null 表示弃权. */
    public record Outcome(String probe, Boolean obfuscatedVote, int weight, String detail) {

        public String render() {
            String vote = obfuscatedVote == null
                    ? "abstain"
                    : (obfuscatedVote ? "OBFUSCATED" : "OFFICIAL");
            return String.format("  [%-20s] w=%-2d %-10s %s", probe, weight, vote, detail);
        }
    }

/** 权重:实测判据给高权重,弱信号给低权重. */
    static final int W_CLASS_LAYOUT = 5;
    static final int W_BYTECODE_READABILITY = 4;
    static final int W_PACKAGE_STRUCTURE = 2;

/** 字节码采样上限.全量读 7000+ 个 class 太慢,采样已足够稳定. */
    private static final int BYTECODE_SAMPLE_LIMIT = 64;

/** 补丁提供方标记.Forge/NeoForge 的补丁会在 vanilla 类的常量池里留下自己的包引用. */
    static final String MARKER_NEOFORGE = "net/neoforged/";
    static final String MARKER_FORGE = "net/minecraftforge/";

/** 判定"疑似混淆名"的长度阈值.实测混淆方法名集中在 1~3 字符. */
    private static final int SHORT_NAME_LIMIT = 3;

    private NameSpaceProbe() {
    }

/** 跑三个命名空间探针. */
    public static List<Outcome> run(List<Path> classpath) {
        List<Outcome> outcomes = new ArrayList<>(3);
        outcomes.add(classLayout(classpath));
        outcomes.add(packageStructure(classpath));
        outcomes.add(bytecodeReadability(classpath));
        return List.copyOf(outcomes);
    }

    // 探针 1:类名形态 / 类布局(最强判据)
    static Outcome classLayout(List<Path> classpath) {
        int total = 0;
        int defaultPackage = 0;
        int minecraftPackage = 0;

        for (Path entry : classpath) {
            for (String name : classNames(entry)) {
                total++;
                if (name.indexOf('/') < 0) {
                    defaultPackage++;
                } else if (name.startsWith("net/minecraft/")) {
                    minecraftPackage++;
                }
            }
        }

        if (total == 0) {
            return new Outcome("class-layout", null, 0, "no class entries on classpath");
        }

        double defaultRatio = (double) defaultPackage / total;
        double minecraftRatio = (double) minecraftPackage / total;
        String detail = String.format(Locale.ROOT,
                "total=%d defaultPkg=%d(%.1f%%) netMinecraft=%d(%.1f%%)",
                total, defaultPackage, defaultRatio * 100, minecraftPackage, minecraftRatio * 100);

        if (defaultRatio > 0.5) {
            return new Outcome("class-layout", Boolean.TRUE, W_CLASS_LAYOUT, detail);
        }
        if (minecraftRatio > 0.5) {
            return new Outcome("class-layout", Boolean.FALSE, W_CLASS_LAYOUT, detail);
        }
        return new Outcome("class-layout", null, W_CLASS_LAYOUT, detail + " (inconclusive)");
    }

    // 探针 2:包结构(弱信号,只投正向票)
/**
     * 未混淆版本存在真实的 {@code com/mojang/&lt;pkg&gt;} 结构(如 blaze3d).
     * 混淆版本会把它一并混淆掉,因此"存在"是强正向证据,
     * 但"不存在"不可作为反向证据分体 jar(common)本来就不含客户端包.
*/
    static Outcome packageStructure(List<Path> classpath) {
        int comMojangPackages = 0;

        for (Path entry : classpath) {
            Set<String> packages = new java.util.LinkedHashSet<>();
            for (String name : classNames(entry)) {
                if (name.startsWith("com/mojang/")) {
                    int secondSlash = name.indexOf('/', "com/mojang/".length());
                    packages.add(secondSlash < 0 ? name : name.substring(0, secondSlash));
                }
            }
            comMojangPackages += packages.size();
        }

        if (comMojangPackages > 0) {
            return new Outcome("package-structure", Boolean.FALSE, W_PACKAGE_STRUCTURE,
                    "com/mojang packages present=" + comMojangPackages);
        }
        return new Outcome("package-structure", null, W_PACKAGE_STRUCTURE,
                "no com/mojang packages (weak: split jars omit them)");
    }

    // 探针 3:字节码可读性
    static Outcome bytecodeReadability(List<Path> classpath) {
        int sampled = 0;
        int shortNames = 0;
        int totalNames = 0;
        int majorVersion = 0;

        outer:
        for (Path entry : classpath) {
            if (!Files.isRegularFile(entry)) {
                continue;
            }
            try (ZipFile zip = new ZipFile(entry.toFile())) {
                for (ZipEntry ze : sortEntries(zip)) {
                    if (sampled >= BYTECODE_SAMPLE_LIMIT) {
                        break outer;
                    }
                    if (!isCandidateForBytecodeSample(ze.getName())) {
                        continue;
                    }
                    byte[] bytes = readEntry(zip, ze);
                    if (bytes == null) {
                        continue;
                    }
                    ClassFileStrings.Info info;
                    try {
                        info = ClassFileStrings.read(bytes);
                    } catch (RuntimeException e) {
                        continue;   // 个别异常 class 不影响统计
                    }
                    sampled++;
                    majorVersion = Math.max(majorVersion, info.majorVersion());
                    for (String method : info.methodNames()) {
                        totalNames++;
                        if (looksObfuscated(method)) {
                            shortNames++;
                        }
                    }
                }
            } catch (IOException e) {
                // 读不了就跳过,交给 class-layout 探针兜底
            }
        }

        if (totalNames == 0) {
            return new Outcome("bytecode-readability", null, 0, "no method names sampled");
        }

        double ratio = (double) shortNames / totalNames;
        String detail = String.format(Locale.ROOT,
                "sampled=%d methods=%d short=%d(%.1f%%) maxClassFileVersion=%d",
                sampled, totalNames, shortNames, ratio * 100, majorVersion);

        if (ratio > 0.5) {
            return new Outcome("bytecode-readability", Boolean.TRUE, W_BYTECODE_READABILITY, detail);
        }
        if (ratio < 0.1) {
            return new Outcome("bytecode-readability", Boolean.FALSE, W_BYTECODE_READABILITY, detail);
        }
        return new Outcome("bytecode-readability", null, W_BYTECODE_READABILITY, detail + " (inconclusive)");
    }

/**
     * 方法/字段名是否像混淆产物.
     * <p>实测混淆名集中在 1~3 个字符且全为小写字母({@code a}, {@code aa}, {@code aab});
     * 未混淆的 vanilla 里几乎不存在这种名字(最短的常见名如 {@code tick} / {@code run} 也 ≥4 字符).
*/
    static boolean looksObfuscated(String name) {
        if (name == null || name.isEmpty() || name.length() > SHORT_NAME_LIMIT) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 'a' || c > 'z') {
                return false;
            }
        }
        return true;
    }

    // 探针 4:补丁提供方
/**
     * 判定游戏是否被 Forge / NeoForge 打过补丁.
     * <p>依据与 FancyModLoader 的 {@code FMLLoader.detectProduction()} 同源:
     * 26.1 未混淆后,"找一个混淆类"的老启发式失效了,改用
     * "读一个已知会被 patch 的 vanilla 类,检查其常量池里有没有加载器自己的包引用".
     * @return 标记 ->命中次数.空 map 表示未发现任何补丁痕迹.
*/
    public static Map<String, Integer> scanPatchMarkers(List<Path> classpath) {
        Map<String, Integer> hits = new LinkedHashMap<>();
        hits.put(MARKER_NEOFORGE, 0);
        hits.put(MARKER_FORGE, 0);

        for (Path entry : classpath) {
            if (!Files.isRegularFile(entry)) {
                continue;
            }
            try (ZipFile zip = new ZipFile(entry.toFile())) {
                for (ZipEntry ze : sortEntries(zip)) {
                    if (!isCandidateForBytecodeSample(ze.getName())) {
                        continue;
                    }
                    byte[] bytes = readEntry(zip, ze);
                    if (bytes == null) {
                        continue;
                    }
                    ClassFileStrings.Info info;
                    try {
                        info = ClassFileStrings.read(bytes);
                    } catch (RuntimeException e) {
                        continue;
                    }
                    for (String ref : info.referencedClasses()) {
                        for (String marker : hits.keySet()) {
                            if (ref.startsWith(marker)) {
                                hits.merge(marker, 1, Integer::sum);
                            }
                        }
                    }
                }
            } catch (IOException e) {
                // 忽略,探针 2 会兜底
            }
        }
        return hits;
    }

    // 工具

/** 列出 classpath 上全部 .class 的内部名(不含 .class 后缀). */
    static List<String> classNames(Path entry) {
        List<String> out = new ArrayList<>();
        if (!Files.isRegularFile(entry)) {
            return out;
        }
        try (ZipFile zip = new ZipFile(entry.toFile())) {
            for (ZipEntry ze : sortEntries(zip)) {
                String name = ze.getName();
                if (name.endsWith(".class") && !ze.isDirectory()) {
                    out.add(name.substring(0, name.length() - ".class".length()));
                }
            }
        } catch (IOException e) {
            // 不是 zip 或读不了:返回空,探针会弃权
        }
        return out;
    }

/**
     * 字节码采样只取 vanilla 自身的类,跳过 mod 与第三方库:
     * 否则一个用 Yarn 命名写的小 mod 就能把统计带偏.
*/
    private static boolean isCandidateForBytecodeSample(String entryName) {
        if (!entryName.endsWith(".class") || entryName.startsWith("META-INF/")) {
            return false;
        }
        return entryName.startsWith("net/minecraft/") || entryName.indexOf('/') < 0;
    }

/**
     * ZipFile.stream() 的元素类型是捕获通配符,直接 toList() 会得到
     * List&lt;? extends ZipEntry&gt;.这里如实声明返回类型,
     * 调用方的 for-each 依然按 ZipEntry 使用.
*/
    private static List<? extends ZipEntry> sortEntries(ZipFile zip) {
        return zip.stream().sorted(java.util.Comparator.comparing(ZipEntry::getName)).toList();
    }

    private static byte[] readEntry(ZipFile zip, ZipEntry entry) {
        try (InputStream in = zip.getInputStream(entry)) {
            return in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }
}
