package dev.multiloader.bridge.fabric.loader;

import dev.multiloader.api.locating.IModFile;
import dev.multiloader.api.locating.IModFileExtension;
import dev.multiloader.api.locating.MixinConfigRef;
import dev.multiloader.api.metadata.Environment;
import dev.multiloader.api.service.ILoaderContext;
import dev.multiloader.bridge.fabric.FabricMultiLoaderExtension;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code FabricLoader} 静态访问的实现测试.
 * <p><b>最要紧的一条是 {@link #getInstanceIsCallable()}</b>:它是唯一能捕获
 * "漏了 {@code net.fabricmc.loader.impl.FabricLoaderImpl}" 这个缺陷的判据.
 * 上游接口的静态方法体直接读 {@code FabricLoaderImpl.INSTANCE},只实现接口是不够的 
 * 而"实现了 17 个方法"这类断言在漏掉那个类时**照样全绿**.
 * <p>数据注入走**真实的钩子**({@code FabricMultiLoaderExtension.onLoaderInit}),
 * 不是测试专用的 setter  这样测的是装配路径本身.
*/
class MultiLoaderFabricLoaderTest {

    private static final String MOD_ID = "testmod";

    // 硬判据

    @Test
    void getInstanceIsCallable() {
        // 若 FabricLoaderImpl 缺失或 INSTANCE 为 null,上游接口会抛
        // "Accessed FabricLoader too early!"  这条断言就是挡它的.
        FabricLoader loader = assertDoesNotThrowCall(FabricLoader::getInstance);

        assertNotNull(loader);
    }

    @Test
    void getInstanceIsStableAcrossCalls() {
        assertSame(FabricLoader.getInstance(), FabricLoader.getInstance());
    }

    private interface LoaderSupplier {
        FabricLoader get();
    }

    private static FabricLoader assertDoesNotThrowCall(LoaderSupplier supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException e) {
            throw new AssertionError("FabricLoader.getInstance() 失败：" + e, e);
        }
    }

    // 注入上下文后:真实方法

    private static void injectContext(Path gameRoot, List<IModFile> modFiles) {
        // 走真实钩子,不用测试专用 setter.
        new FabricMultiLoaderExtension().onLoaderInit(new ILoaderContext() {
            @Override
            public Path gameRoot() {
                return gameRoot;
            }

            @Override
            public Path modsDir() {
                return gameRoot.resolve("mods");
            }

            @Override
            public String minecraftVersion() {
                return "26.3";
            }

            @Override
            public boolean isDevelopment() {
                return true;
            }

            @Override
            public dev.multiloader.api.metadata.GameSide side() {
                return dev.multiloader.api.metadata.GameSide.SERVER;
            }

            @Override
            public List<IModFile> modFiles() {
                return modFiles;
            }

            @Override
            public ClassLoader loaderLayer() {
                return MultiLoaderFabricLoaderTest.class.getClassLoader();
            }
        });
    }

    @Test
    void loaderServesContextFacts(@TempDir Path tmp) {
        injectContext(tmp, List.of());

        FabricLoader loader = FabricLoader.getInstance();
        assertEquals(tmp, loader.getGameDir());
        assertEquals(tmp.toFile(), loader.getGameDirectory());
        assertTrue(loader.isDevelopmentEnvironment());
        assertEquals("26.3", loader.getRawGameVersion());
    }

    @Test
    void configDirIsUnderGameRootNotModsDir(@TempDir Path tmp) {
        // 路径错误是"跑很久才发现"的类型:mod 的配置写进 mods/ 里谁也不会去那儿找.
        injectContext(tmp, List.of());

        assertEquals(tmp.resolve("config"), FabricLoader.getInstance().getConfigDir());
        assertEquals(tmp.resolve("config").toFile(), FabricLoader.getInstance().getConfigDirectory());
        assertFalse(FabricLoader.getInstance().getConfigDir().equals(tmp.resolve("mods")));
    }

    @Test
    void modLookupSeesDiscoveredMods(@TempDir Path tmp) {
        injectContext(tmp, List.of(new FakeModFile()));

        FabricLoader loader = FabricLoader.getInstance();
        assertTrue(loader.isModLoaded(MOD_ID));
        assertFalse(loader.isModLoaded("nonexistent"));

        Optional<ModContainer> container = loader.getModContainer(MOD_ID);
        assertTrue(container.isPresent());
        // 端到端调用链的那一段:container ->metadata ->version
        assertEquals("1.0.0", container.orElseThrow().getMetadata().getVersion().getFriendlyString());

        assertEquals(1, loader.getAllMods().size());
    }

    @Test
    void missingModYieldsEmptyOptionalNotException(@TempDir Path tmp) {
        injectContext(tmp, List.of(new FakeModFile()));

        assertEquals(Optional.empty(), FabricLoader.getInstance().getModContainer("nonexistent"));
    }

    @Test
    void nullModIdIsNotAMatch(@TempDir Path tmp) {
        injectContext(tmp, List.of(new FakeModFile()));

        assertFalse(FabricLoader.getInstance().isModLoaded(null));
        assertEquals(Optional.empty(), FabricLoader.getInstance().getModContainer(null));
    }

    // 拒绝:三类理由互不相同

    @Test
    void environmentTypeComesFromTheContextSide(@TempDir Path tmp) {
        // 第 1 步后不再是拒绝:GameSide ->EnvType 的真实映射.
        injectContext(tmp, List.of());

        assertEquals(EnvType.SERVER, FabricLoader.getInstance().getEnvironmentType());
    }

    @Test
    void mappingResolverRejectionIsArchitectural(@TempDir Path tmp) {
        injectContext(tmp, List.of());

        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                () -> FabricLoader.getInstance().getMappingResolver());
        assertTrue(e.getMessage().contains("无混淆"), e.getMessage());
        assertTrue(e.getMessage().contains("架构"), e.getMessage());
    }

    @Test
    void launchArgumentsRejectionWarnsAboutAgentArgs(@TempDir Path tmp) {
        injectContext(tmp, List.of());

        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                () -> FabricLoader.getInstance().getLaunchArguments(true));
        assertTrue(e.getMessage().contains("agent"), e.getMessage());
    }

/** 一个带元数据的最小 mod 文件替身. */
    private static final class FakeModFile implements IModFile {

        @Override
        public Path getFilePath() {
            return Path.of("testmod.jar");
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
            return List.of(Path.of("testmod.jar"));
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
}
