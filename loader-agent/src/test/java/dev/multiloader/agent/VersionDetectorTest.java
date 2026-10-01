package dev.multiloader.agent;

import dev.multiloader.common.GameNamespace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 版本/命名空间探测的回归测试.
 * <p>覆盖四个验收用例.前两个用**合成的**形态 jar(单测不能依赖开发机上的
 * Loom 缓存),真实 jar 的验证走 {@code :loader-agent:probeTool}.
*/
class VersionDetectorTest {

    @Test
    void officialShapedJarIsDetectedAsOfficial(@TempDir Path dir) throws IOException {
        Path jar = SyntheticJars.officialJar(dir, 64);

        VersionDetector.ProbeResult result = VersionDetector.detect(List.of(jar), "26.3");

        assertEquals(GameNamespace.OFFICIAL, result.namespace());
        assertEquals("26.3", result.minecraftVersion());
        assertEquals(VersionDetector.PatchProvider.NONE, result.patchProvider());
    }

    @Test
    void obfuscatedShapedJarIsDetectedAsObfuscated(@TempDir Path dir) throws IOException {
        Path jar = SyntheticJars.obfuscatedJar(dir, 64);

        VersionDetector.ProbeResult result = VersionDetector.detect(List.of(jar), null);

        assertEquals(GameNamespace.OBFUSCATED, result.namespace());
        assertEquals(VersionDetector.UNKNOWN_VERSION, result.minecraftVersion());
    }

    @Test
    void neoForgePatchMarkersWinOverForge(@TempDir Path dir) throws IOException {
        // 两个标记同时出现时必须判 NEOFORGE  NeoForge 由 Forge 分叉而来,
        // 兼容代码里仍会引用 net/minecraftforge,先判 Forge 会误判.
        Path jar = SyntheticJars.patchedJar(dir, NameSpaceProbe.MARKER_NEOFORGE);

        VersionDetector.ProbeResult result = VersionDetector.detect(List.of(jar), "26.3");

        assertEquals(GameNamespace.OFFICIAL, result.namespace());
        assertEquals(VersionDetector.PatchProvider.NEOFORGE, result.patchProvider());
    }

    @Test
    void forgePatchMarkersAreDetected(@TempDir Path dir) throws IOException {
        Path jar = SyntheticJars.patchedJar(dir, NameSpaceProbe.MARKER_FORGE);

        VersionDetector.ProbeResult result = VersionDetector.detect(List.of(jar), "26.3");

        assertEquals(GameNamespace.OFFICIAL, result.namespace());
        assertEquals(VersionDetector.PatchProvider.FORGE, result.patchProvider());
    }

    @Test
    void emptyClasspathFallsBackToObfuscated(@TempDir Path dir) {
        // 无任何可探测内容时必须落到 OBFUSCATED(fail-safe):
        // 以"未混淆"为前提去加载混淆游戏,失败形态极难诊断.
        VersionDetector.ProbeResult result = VersionDetector.detect(List.of(), null);

        assertEquals(GameNamespace.OBFUSCATED, result.namespace());
        assertEquals(VersionDetector.PatchProvider.NONE, result.patchProvider());
    }

    @Test
    void summaryLineMatchesAcceptanceFormat(@TempDir Path dir) throws IOException {
        Path jar = SyntheticJars.officialJar(dir, 8);

        VersionDetector.ProbeResult result = VersionDetector.detect(List.of(jar), "26.3");

        assertEquals("namespace=OFFICIAL mcVersion=26.3 patchProvider=NONE", result.summaryLine());
    }
}
