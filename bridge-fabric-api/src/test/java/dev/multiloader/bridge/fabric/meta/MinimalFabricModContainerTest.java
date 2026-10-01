package dev.multiloader.bridge.fabric.meta;

import dev.multiloader.api.locating.IModFile;
import dev.multiloader.api.locating.IModFileExtension;
import dev.multiloader.api.locating.MixinConfigRef;
import dev.multiloader.api.metadata.Environment;
import dev.multiloader.api.metadata.IModFileMetadata;
import net.fabricmc.loader.api.metadata.ModOrigin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MinimalFabricModContainer} 的适配测试.
 * <p>重点是**根的语义**:{@code getRootPaths()} 必须来自
 * {@code IModFile.getClasspathRoots()} 而不是文件路径  目录型 mod 与 jar 型 mod
 * 的"根"不同,绕开统一模型会让目录型 mod 拿到错误的根,而那种错误在测试里
 * 只有**用多根**才能暴露(单根时两者恰好相同).
*/
class MinimalFabricModContainerTest {

    private static final String MOD_ID = "testmod";

/** 可配置根路径的 IModFile 替身. */
    private static final class FakeModFile implements IModFile {

        private final List<Path> roots;

        FakeModFile(List<Path> roots) {
            this.roots = roots;
        }

        @Override
        public Path getFilePath() {
            // 刻意与 roots 不同:若实现误用文件路径作为根,测试会红.
            return Path.of("somewhere-else.jar");
        }

        @Override
        public String getFormat() {
            return "fabric";
        }

        @Override
        public List<dev.multiloader.api.metadata.ModMetadata> getMetadataList() {
            return List.of(new dev.multiloader.api.metadata.ModMetadata(MOD_ID, "1.0.0",
                    "Test Mod", "desc", List.of("Alice"), "MIT", Map.of(), Environment.BOTH));
        }

        @Override
        public List<dev.multiloader.api.metadata.ModDependency> getDependencies() {
            return List.of();
        }

        @Override
        public List<MixinConfigRef> getMixinConfigRefs() {
            return List.of();
        }

        @Override
        public List<Path> getClasspathRoots() {
            return roots;
        }

        @Override
        public List<Path> getNestedLibraries() {
            return List.of();
        }

        @Override
        public <T extends IModFileExtension> Optional<T> getExtension(Class<T> extensionType) {
            return Optional.empty();
        }
    }

    @Test
    void metadataIsTheFabricAdapter(@TempDir Path tmp) {
        var container = new MinimalFabricModContainer(new FakeModFile(List.of(tmp)));

        assertInstanceOf(MinimalFabricModMetadata.class, container.getMetadata());
        assertEquals(MOD_ID, container.getMetadata().getId(), "值应真的流过去");
    }

    @Test
    void rootsComeFromClasspathRootsNotTheFilePath() {
        // 这条断言的关键在 FakeModFile:它的 getFilePath() 与 roots 刻意不同.
        // 若实现误把文件路径当根,这里会拿到 "somewhere-else.jar".
        Path first = Path.of("/mods/a");
        Path second = Path.of("/mods/b");

        var container = new MinimalFabricModContainer(new FakeModFile(List.of(first, second)));

        assertEquals(List.of(first, second), container.getRootPaths());
        assertEquals(first, container.getRootPath(), "主根 = 首个根");
    }

    @Test
    void getPathResolvesAgainstThePrimaryRoot(@TempDir Path tmp) {
        var container = new MinimalFabricModContainer(new FakeModFile(List.of(tmp)));

        assertEquals(tmp.resolve("assets/icon.png"), container.getPath("assets/icon.png"));
    }

    @Test
    void noRootsIsLoudWhenAPathIsRequested() {
        // 抛异常而不是凭空拼一个看起来合法的路径  那种路径指向不存在的位置,
        // 调用方要隔很远才发现.
        var container = new MinimalFabricModContainer(new FakeModFile(List.of()));

        assertThrows(NoSuchElementException.class, container::getRootPath);
        assertThrows(NoSuchElementException.class, () -> container.getPath("x"));
        // 但 getRootPaths() 本身不抛:返回空集合是如实反映"没有根".
        assertTrue(container.getRootPaths().isEmpty());
    }

    @Test
    void originIsAPathOriginOfTheModFile(@TempDir Path tmp) {
        var container = new MinimalFabricModContainer(new FakeModFile(List.of(tmp)));

        assertEquals(ModOrigin.Kind.PATH, container.getOrigin().getKind());
        assertEquals(List.of(Path.of("somewhere-else.jar")), container.getOrigin().getPaths());
    }

    @Test
    void topLevelModHasNoParentAndNoChildren(@TempDir Path tmp) {
        // 这是**正确答案**,不是缺失的能力:嵌套 jar 在本工程不作为独立 mod 文件产出.
        var container = new MinimalFabricModContainer(new FakeModFile(List.of(tmp)));

        assertEquals(Optional.empty(), container.getContainingMod());
        assertTrue(container.getContainedMods().isEmpty());
    }

    @Test
    void toStringIsDiagnostic(@TempDir Path tmp) {
        String text = new MinimalFabricModContainer(new FakeModFile(List.of(tmp))).toString();

        assertTrue(text.contains(MOD_ID), text);
        assertTrue(text.contains("roots=1"), text);
    }
}
