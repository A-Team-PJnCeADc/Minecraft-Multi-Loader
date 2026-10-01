package dev.multiloader.adapt.fabric;

import dev.multiloader.api.lifecycle.EntrypointRef;
import dev.multiloader.api.lifecycle.LifecyclePhase;
import dev.multiloader.api.locating.IModFile;
import dev.multiloader.api.locating.IModFileExtension;
import dev.multiloader.api.locating.MixinConfigRef;
import dev.multiloader.api.metadata.Environment;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 断言**入口点 key 真的流到了 {@link EntrypointRef#sourceKey()}**.
 * <p>为什么值得单独一条:编译通过**证明不了**这一点  只证明那个重载能被调用.
 * 若参数传错位置,或将来有人在 adapt 层把 namespace 换成别的值,编译照样通过,
 * 而 {@code IEntrypointQuery.query(key)} 会安静地查不到任何入口点.
*/
class FabricEntrypointProviderKeyTest {

    private static final String MOD_ID = "keytest";

    private static IModFile fileWith(List<FabricModFileExtension.Entrypoint> entrypoints) {
        FabricModFileExtension extension = new FabricModFileExtension(
                1,                       // schemaVersion
                null,                    // accessWidener
                Map.of(),                // languageAdapters
                Set.of(),                // provides
                entrypoints,
                List.of(),               // nestedJars
                List.of(),               // contributors
                Map.of(),                // iconPaths
                Map.of());               // customValues
        return new FakeModFile(extension);
    }

    @Test
    void entrypointKeyIsCarriedOntoTheRef() {
        IModFile file = fileWith(List.of(new FabricModFileExtension.Entrypoint(
                "main", "com.example.Mod", LifecyclePhase.INIT, Environment.BOTH)));

        Map<LifecyclePhase, List<EntrypointRef>> byPhase =
                new FabricEntrypointProvider().getEntrypoints(file);

        EntrypointRef ref = byPhase.get(LifecyclePhase.INIT).get(0);
        assertEquals("main", ref.sourceKey(), "key 必须随 Ref 带走，否则按 key 查询永远查不到");
    }

    @Test
    void eachKeyKeepsItsOwnValue() {
        IModFile file = fileWith(List.of(
                new FabricModFileExtension.Entrypoint(
                        "client", "com.example.ClientMod", LifecyclePhase.SIDED_SETUP, Environment.CLIENT),
                new FabricModFileExtension.Entrypoint(
                        "server", "com.example.ServerMod", LifecyclePhase.SIDED_SETUP, Environment.SERVER)));

        List<EntrypointRef> refs = new FabricEntrypointProvider().getEntrypoints(file)
                .get(LifecyclePhase.SIDED_SETUP);

        // 两个 key 的 Ref 必须各自保留自己的值  若实现把 key 写死成常量,
        // 这里会两条都相等而暴露出来.
        assertEquals(List.of("client", "server"), refs.stream().map(EntrypointRef::sourceKey).toList());
    }

    @Test
    void neoForgeStyleRefsHaveNoKey() {
        // NeoForge 的 @Mod 没有 key 概念 ->sourceKey 为 null 是**正确**状态,不是遗漏.
        // 这条断言同时钉住"兼容构造器默认 null"这一行为.
        assertNull(EntrypointRef.noArgConstructor(
                MOD_ID, LifecyclePhase.CONSTRUCT, "com.example.NeoMod", Environment.BOTH).sourceKey());
    }

/** 只带扩展的最小 IModFile 替身. */
    private record FakeModFile(FabricModFileExtension extension) implements IModFile {

        @Override
        public Path getFilePath() {
            return Path.of("keytest.jar");
        }

        @Override
        public String getFormat() {
            return "fabric";
        }

        @Override
        public List<dev.multiloader.api.metadata.ModMetadata> getMetadataList() {
            return List.of(new dev.multiloader.api.metadata.ModMetadata(MOD_ID, "1.0.0",
                    "Key Test", "desc", List.of(), "MIT", Map.of(), Environment.BOTH));
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
            return List.of(Path.of("keytest.jar"));
        }

        @Override
        public List<Path> getNestedLibraries() {
            return List.of();
        }

        @Override
        public <T extends IModFileExtension> Optional<T> getExtension(Class<T> extensionType) {
            return extensionType.isInstance(extension)
                    ? Optional.of(extensionType.cast(extension))
                    : Optional.empty();
        }
    }
}
