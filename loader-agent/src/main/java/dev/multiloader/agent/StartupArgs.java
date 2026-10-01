package dev.multiloader.agent;

import dev.multiloader.common.Side;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * javaagent 启动参数.
 * <p>格式:{@code key=value;key=value;...}(沿用 Aprism 的风格).
 * 键值对按**第一个** {@code =} 切分,因此值里可以再出现 {@code =}
 * (Windows 路径,URL 都不会被破坏).
 * <p>未指定的键保持默认值,而不是报错,便于单测与诊断工具复用同一个解析器.
*/
public record StartupArgs(
        Path gameRoot,
        Path modsDir,
        String minecraftVersion,
        Side side,
        boolean development,
        List<Path> gameClasspath,
        boolean tolerateFailure,
        Map<String, String> raw) {

    public static final String KEY_GAME_ROOT = "gameRoot";
    public static final String KEY_MODS_DIR = "modsDir";
    public static final String KEY_MC_VERSION = "mcVersion";
    public static final String KEY_SIDE = "side";
    public static final String KEY_DEV = "dev";
    public static final String KEY_GAME_JAR = "gameJar";
    public static final String KEY_GAME_CLASSPATH = "gameClasspath";
    public static final String KEY_TOLERATE_FAILURE = "tolerateFailure";
/** Q1:真正的游戏主类.agent 初始化完成后由 launcher.Main 反射调用它. */
    public static final String KEY_GAME_MAIN = "gameMain";

/** 值域列表的分隔符.刻意不用 {@code File.pathSeparator}: */
/** agent 参数自身用 ';' 分隔键值对,Windows 上 pathSeparator 也是 ';',会打架. */
    private static final String LIST_SEPARATOR = ",";

    public static StartupArgs parse(String agentArgs) {
        Map<String, String> raw = parsePairs(agentArgs);

        Path gameRoot = path(raw.get(KEY_GAME_ROOT), Path.of(System.getProperty("user.dir")));
        Path modsDir = path(raw.get(KEY_MODS_DIR), gameRoot.resolve("mods"));
        String mcVersion = blankToNull(raw.get(KEY_MC_VERSION));
        Side side = Side.parse(raw.get(KEY_SIDE));
        boolean dev = Boolean.parseBoolean(raw.getOrDefault(KEY_DEV, "false"));
        boolean tolerate = Boolean.parseBoolean(raw.getOrDefault(KEY_TOLERATE_FAILURE, "false"));

        List<Path> classpath = resolveClasspath(raw);

        return new StartupArgs(gameRoot, modsDir, mcVersion, side, dev, classpath, tolerate, raw);
    }

/**
     * 解析 {@code gameClasspath} / {@code gameJar},都缺省时回落到
     * {@code java.class.path}(Q1 决策下启动器会把游戏类路径交给 JVM,
     * 所以这个回落是可靠的).
*/
    private static List<Path> resolveClasspath(Map<String, String> raw) {
        String explicit = raw.get(KEY_GAME_CLASSPATH);
        if (explicit != null && !explicit.isBlank()) {
            return splitPaths(explicit, LIST_SEPARATOR);
        }

        String singleJar = raw.get(KEY_GAME_JAR);
        if (singleJar != null && !singleJar.isBlank()) {
            return List.of(Path.of(singleJar.trim()));
        }

        String systemClasspath = System.getProperty("java.class.path", "");
        if (systemClasspath.isBlank()) {
            return List.of();
        }
        return splitPaths(systemClasspath, File.pathSeparator);
    }

    private static List<Path> splitPaths(String value, String separator) {
        List<Path> out = new ArrayList<>();
        for (String part : value.split(java.util.regex.Pattern.quote(separator))) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(Path.of(trimmed));
            }
        }
        return List.copyOf(out);
    }

    private static Map<String, String> parsePairs(String agentArgs) {
        Map<String, String> raw = new LinkedHashMap<>();
        if (agentArgs == null || agentArgs.isBlank()) {
            return raw;
        }
        for (String entry : agentArgs.split(";")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq < 0) {
                // 无值的键视为 true,与常见 -D 风格一致
                raw.put(trimmed, "true");
            } else {
                raw.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
            }
        }
        return raw;
    }

    private static Path path(String value, Path fallback) {
        return value == null || value.isBlank() ? fallback : Path.of(value.trim());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

/**
     * 真正的游戏主类(Q1:launcher.Main 初始化完成后反射调用它).
     * <p>做成派生访问器而非 record 分量:它完全可以从 {@code raw} + {@code side} 推出,
     * 加成分量会让所有构造点都要改,却不增加任何信息.
*/
    public String gameMain() {
        String explicit = raw.get(KEY_GAME_MAIN);
        if (explicit != null && !explicit.isBlank()) {
            return explicit.trim();
        }
        return side.isServer() ? "net.minecraft.server.Main" : "net.minecraft.client.main.Main";
    }

/** 是否存在可探测的游戏类路径. */
    public boolean hasGameClasspath() {
        return !gameClasspath.isEmpty();
    }

/** 只保留实际存在的类路径条目,用于探测与诊断. */
    public List<Path> existingClasspath() {
        return gameClasspath.stream().filter(Files::exists).toList();
    }

/** 单行摘要,供日志与诊断输出. */
    public String summary() {
        return "gameRoot=%s modsDir=%s mcVersion=%s side=%s dev=%s classpathEntries=%d tolerateFailure=%s"
                .formatted(gameRoot, modsDir, minecraftVersion, side, development,
                        gameClasspath.size(), tolerateFailure);
    }
}
