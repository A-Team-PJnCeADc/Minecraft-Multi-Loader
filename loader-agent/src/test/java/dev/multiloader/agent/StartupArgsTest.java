package dev.multiloader.agent;

import dev.multiloader.common.Side;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动参数解析.
 * <p>重点覆盖"值里含 = "与"退化输入"这两类容易写错的情形:
 * agent 参数里出现路径(可能含 = 或 Windows 盘符)是常态.
*/
class StartupArgsTest {

    @Test
    void emptyArgumentsYieldDefaults() {
        StartupArgs args = StartupArgs.parse(null);

        assertEquals(Path.of(System.getProperty("user.dir")), args.gameRoot());
        assertEquals(args.gameRoot().resolve("mods"), args.modsDir());
        assertNull(args.minecraftVersion());
        assertEquals(Side.CLIENT, args.side());
        assertFalse(args.development());
        assertFalse(args.tolerateFailure());
    }

    @Test
    void parsesFullArgumentSet(@TempDir Path dir) {
        String raw = "gameRoot=" + dir
                + ";mcVersion=26.3;side=server;dev=true;tolerateFailure=true";

        StartupArgs args = StartupArgs.parse(raw);

        assertEquals(dir, args.gameRoot());
        assertEquals(dir.resolve("mods"), args.modsDir());
        assertEquals("26.3", args.minecraftVersion());
        assertEquals(Side.SERVER, args.side());
        assertTrue(args.development());
        assertTrue(args.tolerateFailure());
    }

    @Test
    void valueMayContainEqualsSign() {
        // 只按第一个 '=' 切分:值里的 '=' 必须原样保留
        StartupArgs args = StartupArgs.parse("gameRoot=/tmp/a=b/c;mcVersion=26.3");

        assertEquals(Path.of("/tmp/a=b/c"), args.gameRoot());
        assertEquals("26.3", args.minecraftVersion());
    }

    @Test
    void keyWithoutValueIsTreatedAsTrue() {
        StartupArgs args = StartupArgs.parse("dev");

        assertTrue(args.development());
    }

    @Test
    void gameJarOverridesSystemClasspath(@TempDir Path dir) {
        Path jar = dir.resolve("client.jar");

        StartupArgs args = StartupArgs.parse("gameJar=" + jar);

        assertEquals(1, args.gameClasspath().size());
        assertEquals(jar, args.gameClasspath().get(0));
    }

    @Test
    void gameClasspathIsCommaSeparated(@TempDir Path dir) {
        Path a = dir.resolve("a.jar");
        Path b = dir.resolve("b.jar");

        StartupArgs args = StartupArgs.parse("gameClasspath=" + a + "," + b);

        assertEquals(2, args.gameClasspath().size());
        assertEquals(a, args.gameClasspath().get(0));
        assertEquals(b, args.gameClasspath().get(1));
    }

    @Test
    void unknownSideIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> StartupArgs.parse("side=dedicated"));
    }

    @Test
    void missingModsDirectoryIsReportedTrimmed(@TempDir Path dir) {
        StartupArgs args = StartupArgs.parse("gameRoot=" + dir);

        assertFalse(args.modsDir().toFile().exists());
        assertTrue(args.summary().contains("modsDir="));
    }
}
