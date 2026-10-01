package dev.multiloader.adapt.fabric;

import dev.multiloader.api.locating.ModFileCandidate;
import dev.multiloader.api.locating.ModFileException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 适配器级单元测试.完全自包含:不依赖 loader-core,也不依赖 loader-classloader.
 * <p>约束来源:适配器的依赖边界只有 {@code loader-api + runtime-common}.
 * 整条发现链路的端到端测试放在 loader-core(那里可以合法地依赖适配器).
 * <p>失败路径刻意传 {@code factory = null}:这些校验都发生在调用
 * {@code factory.builder(...)} **之前**,所以"抛的是 ModFileException 而不是
 * NullPointerException"本身就是"没提前碰工厂"的证据.
 * 如果将来有人把顺序改错,这些测试会以 NPE 失败.
*/
class FabricModFileReaderTest {

    private static final FabricModFileReader READER = new FabricModFileReader();

    @Test
    void canReadOnlyWhenAnchorFileIsPresent(@TempDir Path tmp) throws IOException {
        Path withAnchor = writeJar(tmp, "with.jar",
                Map.of("fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"a\",\"version\":\"1\"}"));
        Path withoutAnchor = writeJar(tmp, "without.jar", Map.of("some/Class.class", "x"));

        assertTrue(READER.canRead(new ModFileCandidate(withAnchor)));
        assertFalse(READER.canRead(new ModFileCandidate(withoutAnchor)));
    }

    @Test
    void canReadOnDirectoryTypeMod(@TempDir Path tmp) throws IOException {
        Path dirMod = Files.createDirectories(tmp.resolve("dirmod"));
        Files.writeString(dirMod.resolve("fabric.mod.json"),
                "{\"schemaVersion\":1,\"id\":\"d\",\"version\":\"1\"}", StandardCharsets.UTF_8);

        assertTrue(READER.canRead(new ModFileCandidate(dirMod)));
    }

    @Test
    void canReadReturnsFalseForCorruptJar(@TempDir Path tmp) throws IOException {
        Path corrupt = tmp.resolve("corrupt.jar");
        Files.write(corrupt, "this is not a zip".getBytes(StandardCharsets.UTF_8));

        // 损坏文件必须按"不认识"处理,而不是把发现流程炸掉
        assertFalse(READER.canRead(new ModFileCandidate(corrupt)));
    }

    @Test
    void priorityIsStableAndDocumented() {
        assertEquals(100, READER.priority());
    }

    @Test
    void unsupportedSchemaVersionFailsBeforeFactoryIsTouched(@TempDir Path tmp) throws IOException {
        Path jar = writeJar(tmp, "future.jar",
                Map.of("fabric.mod.json", "{\"schemaVersion\":99,\"id\":\"f\",\"version\":\"1\"}"));

        ModFileException e = assertThrows(ModFileException.class,
                () -> READER.read(new ModFileCandidate(jar), null));

        assertTrue(e.getMessage().contains("schemaVersion=99"),
                "错误信息必须点名违规的版本号: " + e.getMessage());
    }

    @Test
    void missingIdFailsBeforeFactoryIsTouched(@TempDir Path tmp) throws IOException {
        Path jar = writeJar(tmp, "noid.jar",
                Map.of("fabric.mod.json", "{\"schemaVersion\":1,\"version\":\"1.0.0\"}"));

        ModFileException e = assertThrows(ModFileException.class,
                () -> READER.read(new ModFileCandidate(jar), null));

        assertTrue(e.getMessage().contains("'id'"), e.getMessage());
    }

    @Test
    void missingVersionFailsBeforeFactoryIsTouched(@TempDir Path tmp) throws IOException {
        Path jar = writeJar(tmp, "nover.jar",
                Map.of("fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"a\"}"));

        ModFileException e = assertThrows(ModFileException.class,
                () -> READER.read(new ModFileCandidate(jar), null));

        assertTrue(e.getMessage().contains("'version'"), e.getMessage());
    }

    @Test
    void malformedJsonFailsBeforeFactoryIsTouched(@TempDir Path tmp) throws IOException {
        Path jar = writeJar(tmp, "badsyntax.jar", Map.of("fabric.mod.json", "{ not json"));

        assertThrows(ModFileException.class,
                () -> READER.read(new ModFileCandidate(jar), null));
    }

    @Test
    void unknownEnvironmentValueIsRejected(@TempDir Path tmp) throws IOException {
        Path jar = writeJar(tmp, "badenv.jar", Map.of("fabric.mod.json",
                "{\"schemaVersion\":1,\"id\":\"a\",\"version\":\"1\",\"environment\":\"dedicated\"}"));

        ModFileException e = assertThrows(ModFileException.class,
                () -> READER.read(new ModFileCandidate(jar), null));

        assertTrue(e.getMessage().contains("dedicated"), e.getMessage());
    }

    @Test
    void unknownEntrypointNamespaceIsRejected(@TempDir Path tmp) throws IOException {
        // Fabric 的 entrypoint 命名空间是开放集合,但我们只支持标准那几个.
        // 遇到不认识的必须明确报错,不能静默丢弃(静默丢弃 = mod 永远不被调用).
        Path jar = writeJar(tmp, "badep.jar", Map.of("fabric.mod.json",
                "{\"schemaVersion\":1,\"id\":\"a\",\"version\":\"1\","
                        + "\"entrypoints\":{\"custom\":[\"com.example.Foo\"]}}"));

        ModFileException e = assertThrows(ModFileException.class,
                () -> READER.read(new ModFileCandidate(jar), null));

        assertTrue(e.getMessage().contains("custom"), e.getMessage());
    }

    @Test
    void malformedMixinItemIsRejected(@TempDir Path tmp) throws IOException {
        Path jar = writeJar(tmp, "badmix.jar", Map.of("fabric.mod.json",
                "{\"schemaVersion\":1,\"id\":\"a\",\"version\":\"1\",\"mixins\":[42]}"));

        assertThrows(ModFileException.class,
                () -> READER.read(new ModFileCandidate(jar), null));
    }

    @Test
    void malformedNestedJarEntryIsRejected(@TempDir Path tmp) throws IOException {
        Path jar = writeJar(tmp, "badjars.jar", Map.of("fabric.mod.json",
                "{\"schemaVersion\":1,\"id\":\"a\",\"version\":\"1\",\"jars\":[\"not-an-object\"]}"));

        assertThrows(ModFileException.class,
                () -> READER.read(new ModFileCandidate(jar), null));
    }

    private static Path writeJar(Path dir, String name, Map<String, String> entries) throws IOException {
        Path jar = dir.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return jar;
    }
}
