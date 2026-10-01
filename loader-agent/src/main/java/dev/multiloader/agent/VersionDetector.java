package dev.multiloader.agent;

import dev.multiloader.common.GameNamespace;
import dev.multiloader.common.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 版本与命名空间探测.
 * <p>把 {@link NameSpaceProbe} 的若干条探针结论做加权投票,产出唯一的
 * {@link ProbeResult},并保留完整证链(evidence)便于排障.
 * <p>为什么不看版本号:1.21.11 存在 {@code _unobfuscated} 的预发布构建
 * (NeoForm 仓库里有 {@code v1.21.11-pre2_unobfuscated-*} 这类 tag),
 * 混淆与否不是版本号的函数.版本号只作为"探测失败时的兜底输入".
*/
public final class VersionDetector {

/** 游戏二进制是否被某加载器打过补丁. */
    public enum PatchProvider {
        NONE,
        FORGE,
        NEOFORGE
    }

/**
     * @param namespace       探测到的命名空间
     * @param minecraftVersion 版本号;无法判定时为 {@code "unknown"}
     * @param patchProvider   补丁提供方
     * @param evidence        逐条证链,按可读顺序排列
*/
    public record ProbeResult(GameNamespace namespace,
                              String minecraftVersion,
                              PatchProvider patchProvider,
                              List<String> evidence) {

/** 验收格式:{@code namespace=OFFICIAL mcVersion=26.3 patchProvider=NONE} */
        public String summaryLine() {
            return "namespace=%s mcVersion=%s patchProvider=%s"
                    .formatted(namespace, minecraftVersion, patchProvider);
        }
    }

/** 无法判定版本号时的占位值. */
    public static final String UNKNOWN_VERSION = "unknown";

    private static final Pattern JSON_VERSION = Pattern.compile(
            "\"(?:id|name)\"\\s*:\\s*\"([^\"]+)\"");

    private VersionDetector() {
    }

    public static ProbeResult detect(StartupArgs args) {
        return detect(args.existingClasspath(), args.minecraftVersion());
    }

/**
     * @param classpath       游戏类路径(jar 列表)
     * @param declaredVersion 调用方已知的版本号,可为 null
*/
    public static ProbeResult detect(List<Path> classpath, String declaredVersion) {
        List<String> evidence = new ArrayList<>();

        List<NameSpaceProbe.Outcome> outcomes = NameSpaceProbe.run(classpath);
        int obfuscatedScore = 0;
        int officialScore = 0;

        evidence.add("probes:");
        for (NameSpaceProbe.Outcome outcome : outcomes) {
            evidence.add(outcome.render());
            if (outcome.obfuscatedVote() != null) {
                if (outcome.obfuscatedVote()) {
                    obfuscatedScore += outcome.weight();
                } else {
                    officialScore += outcome.weight();
                }
            }
        }

        GameNamespace namespace = vote(obfuscatedScore, officialScore, evidence);

        Map<String, Integer> markers = NameSpaceProbe.scanPatchMarkers(classpath);
        PatchProvider patchProvider = classifyPatchProvider(markers, evidence);

        String version = resolveVersion(classpath, declaredVersion, evidence);

        evidence.add(String.format(Locale.ROOT,
                "verdict: officialScore=%d obfuscatedScore=%d -> %s",
                officialScore, obfuscatedScore, namespace));

        return new ProbeResult(namespace, version, patchProvider, List.copyOf(evidence));
    }

/**
     * 加权投票.
     * <p>按约定,两个方向的票都无法得出结论时落到 {@link GameNamespace#OBFUSCATED}:
     * 既然不能确认游戏是未混淆的,就必须假设需要走重映射路径,
     * 否则会以"未混淆"为前提去加载一个混淆游戏,失败形态极难诊断.
*/
    private static GameNamespace vote(int obfuscatedScore, int officialScore, List<String> evidence) {
        if (obfuscatedScore == 0 && officialScore == 0) {
            evidence.add("verdict-note: no probe produced a vote -> fallback OBFUSCATED (fail-safe)");
            Log.warn("Version detection inconclusive; falling back to OBFUSCATED (fail-safe)");
            return GameNamespace.OBFUSCATED;
        }
        if (obfuscatedScore > officialScore) {
            return GameNamespace.OBFUSCATED;
        }
        return GameNamespace.OFFICIAL;
    }

    private static PatchProvider classifyPatchProvider(Map<String, Integer> markers, List<String> evidence) {
        int neo = markers.getOrDefault(NameSpaceProbe.MARKER_NEOFORGE, 0);
        int forge = markers.getOrDefault(NameSpaceProbe.MARKER_FORGE, 0);

        evidence.add(String.format(Locale.ROOT,
                "patch-markers: %s=%d %s=%d",
                NameSpaceProbe.MARKER_NEOFORGE, neo, NameSpaceProbe.MARKER_FORGE, forge));

        // NeoForge 由 Forge 分叉而来,某些兼容代码仍会引用 net/minecraftforge,
        // 因此先判 NeoForge,避免把 NeoForge 误判成 Forge.
        if (neo > 0) {
            return PatchProvider.NEOFORGE;
        }
        if (forge > 0) {
            return PatchProvider.FORGE;
        }
        return PatchProvider.NONE;
    }

    private static String resolveVersion(List<Path> classpath, String declaredVersion, List<String> evidence) {
        if (declaredVersion != null && !declaredVersion.isBlank()) {
            evidence.add("version-source: agent argument (" + declaredVersion + ")");
            return declaredVersion.trim();
        }
        String embedded = readEmbeddedVersion(classpath);
        if (embedded != null) {
            evidence.add("version-source: embedded version.json (" + embedded + ")");
            return embedded;
        }
        evidence.add("version-source: unavailable (pass mcVersion=... to the agent)");
        return UNKNOWN_VERSION;
    }

/**
     * 从 jar 内的 {@code version.json} 读取版本号.
     * <p>并非所有工程产物都带这个文件(Loom 的 {@code minecraft-common.jar}
     * 这类分体产物就没有),读不到时返回 null 交给上层兜底.
*/
    static String readEmbeddedVersion(List<Path> classpath) {
        for (Path entry : classpath) {
            if (!Files.isRegularFile(entry)) {
                continue;
            }
            try (ZipFile zip = new ZipFile(entry.toFile())) {
                ZipEntry ze = zip.getEntry("version.json");
                if (ze == null) {
                    continue;
                }
                String json;
                try (InputStream in = zip.getInputStream(ze)) {
                    json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                Matcher m = JSON_VERSION.matcher(json);
                if (m.find()) {
                    return m.group(1);
                }
            } catch (IOException e) {
                // 继续看下一个 jar
            }
        }
        return null;
    }
}
