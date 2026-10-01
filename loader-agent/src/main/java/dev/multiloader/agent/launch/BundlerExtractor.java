package dev.multiloader.agent.launch;

import dev.multiloader.common.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Mojang server bundler 的解包器.
 * <p>为什么需要它:现代的 {@code server.jar} **不是**可直接放进类路径的 jar,
 * 它是个 bundler真身嵌套在里面:
 * <pre>
 *   META-INF/main-class          真实主类(net.minecraft.server.Main)
 *   META-INF/versions.list       &lt;sha256&gt;\t&lt;版本&gt;\t&lt;相对路径&gt;  ->META-INF/versions/&lt;路径&gt;
 *   META-INF/libraries.list      &lt;sha256&gt;\t&lt;maven 坐标&gt;\t&lt;相对路径&gt; ->META-INF/libraries/&lt;路径&gt;
 * </pre>
 * JVM 的 {@code -cp} 无法指向 jar 内部条目,所以必须解包成真实文件.
 * <p>这是加载器 installer 的真实职责(服务端安装),不是一次性脚手架.
 * <p>解包时**逐条校验摘要**:bundler 自带校验值却不用它,等于把"下载损坏"
 * 这类问题推迟到运行期以莫名其妙的崩溃形式出现.
 * <p>注意算法是 <b>SHA-256</b>(64 位十六进制),不是 SHA-1.
 * 这一点靠猜是猜不到的bundler 的 {@code checkIntegrity} 里硬编码了
 * {@code MessageDigest.getInstance("SHA-256")}.用错算法会得到"文件明明是好的
 * 却校验失败"这种最误导的报错.
*/
public final class BundlerExtractor {

    private static final String PREFIX_VERSIONS = "META-INF/versions/";
    private static final String PREFIX_LIBRARIES = "META-INF/libraries/";
    private static final String ENTRY_MAIN_CLASS = "META-INF/main-class";
    private static final String ENTRY_VERSIONS_LIST = "META-INF/versions.list";
    private static final String ENTRY_LIBRARIES_LIST = "META-INF/libraries.list";

/** bundler 的 checkIntegrity 里硬编码的算法:SHA-256,不是 SHA-1. */
    private static final String DIGEST_ALGORITHM = "SHA-256";

/**
     * 解包结果.
     * @param mainClass 真实主类
     * @param version   版本号(来自 versions.list 第二列)
     * @param classpath 可直接放进 {@code -cp} 的真实文件:内层 server jar 在前,库在后
*/
    public record Bundle(String mainClass, String version, List<Path> classpath) {
    }

    private BundlerExtractor() {
    }

/** 判断一个 jar 是否是 Mojang bundler. */
    public static boolean isBundler(Path jar) {
        if (!Files.isRegularFile(jar)) {
            return false;
        }
        try (JarFile file = new JarFile(jar.toFile())) {
            return file.getEntry(ENTRY_MAIN_CLASS) != null
                    && file.getEntry(ENTRY_VERSIONS_LIST) != null
                    && file.getEntry(ENTRY_LIBRARIES_LIST) != null;
        } catch (IOException e) {
            return false;
        }
    }

/**
     * 解包到 {@code targetDir},已存在且校验通过的文件不重复解.
     * @param targetDir 解包根目录(会创建 versions/ 与 libraries/ 子目录)
*/
    public static Bundle extract(Path bundlerJar, Path targetDir) throws IOException {
        if (!isBundler(bundlerJar)) {
            throw new IOException("not a Mojang bundler (missing META-INF/{main-class,versions.list,libraries.list}): "
                    + bundlerJar);
        }

        try (JarFile jar = new JarFile(bundlerJar.toFile())) {
            String mainClass = readTextEntry(jar, ENTRY_MAIN_CLASS).trim();

            List<Path> classpath = new ArrayList<>();
            String version = null;

            for (ListEntry entry : parseList(readTextEntry(jar, ENTRY_VERSIONS_LIST))) {
                Path target = targetDir.resolve("versions").resolve(entry.relativePath());
                extractEntry(jar, PREFIX_VERSIONS + entry.relativePath(), target, entry.digest());
                classpath.add(0, target);   // 内层 server jar 必须排最前
                version = entry.id();
            }

            for (ListEntry entry : parseList(readTextEntry(jar, ENTRY_LIBRARIES_LIST))) {
                Path target = targetDir.resolve("libraries").resolve(entry.relativePath());
                extractEntry(jar, PREFIX_LIBRARIES + entry.relativePath(), target, entry.digest());
                classpath.add(target);
            }

            Log.info("Bundler extracted: version={} mainClass={} classpath={} entries (root={})",
                    version, mainClass, classpath.size(), targetDir);
            return new Bundle(mainClass, version, List.copyOf(classpath));
        }
    }

/**
     * 一条清单项.
     * @param digest       第一列:内容的 SHA-256(64 位十六进制)
     * @param id           第二列:versions.list 里是版本号,libraries.list 里是 maven 坐标
     * @param relativePath 第三列:相对 META-INF/{versions,libraries}/ 的路径
*/
    record ListEntry(String digest, String id, String relativePath) {
    }

    static List<ListEntry> parseList(String content) throws IOException {
        List<ListEntry> entries = new ArrayList<>();
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split("\t");
            if (parts.length != 3) {
                throw new IOException("malformed bundler list line (expected 3 tab-separated fields): " + trimmed);
            }
            entries.add(new ListEntry(parts[0].trim(), parts[1].trim(), parts[2].trim()));
        }
        return entries;
    }

    private static void extractEntry(JarFile jar, String entryName, Path target, String expectedDigest)
            throws IOException {
        if (Files.isRegularFile(target) && expectedDigest.equalsIgnoreCase(digestOf(target))) {
            return;   // 已解包且完好
        }

        JarEntry entry = jar.getJarEntry(entryName);
        if (entry == null) {
            throw new IOException("bundler entry missing: " + entryName);
        }

        Files.createDirectories(target.getParent());
        try (InputStream in = jar.getInputStream(entry)) {
            Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        String actual = digestOf(target);
        if (!expectedDigest.equalsIgnoreCase(actual)) {
            // 校验失败必须删掉产物:留着半损坏的文件会让下次运行以为已经解包好了
            Files.deleteIfExists(target);
            throw new IOException("digest mismatch (" + DIGEST_ALGORITHM + ") for " + entryName
                    + " (expected " + expectedDigest + ", got " + actual + ")");
        }
    }

    static String digestOf(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(DIGEST_ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(DIGEST_ALGORITHM + " unavailable", e);   // JDK 必然提供
        }
        try (InputStream in = Files.newInputStream(file);
             DigestInputStream digestIn = new DigestInputStream(in, digest)) {
            byte[] buffer = new byte[64 * 1024];
            while (digestIn.read(buffer) != -1) {
                // 读完即可,摘要由流累积
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String readTextEntry(JarFile jar, String name) throws IOException {
        JarEntry entry = jar.getJarEntry(name);
        if (entry == null) {
            throw new IOException("bundler entry missing: " + name);
        }
        try (InputStream in = jar.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
