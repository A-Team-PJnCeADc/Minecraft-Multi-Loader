package dev.multiloader.adapt.neoforge;

import dev.multiloader.api.lifecycle.EntrypointKind;
import dev.multiloader.api.lifecycle.EntrypointRef;
import dev.multiloader.api.lifecycle.LifecyclePhase;
import dev.multiloader.api.locating.IModFile;
import dev.multiloader.api.locating.IModFileExtension;
import dev.multiloader.api.locating.MixinConfigRef;
import dev.multiloader.api.metadata.ModDependency;
import dev.multiloader.api.metadata.ModMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NeoForgeEntrypointProvider} 的分类测试.
 * <p>直接构造 {@link NeoForgeModClassScanner.ModClass} 而不合成字节码:
 * 扫描器已由 {@code NeoForgeModClassScannerTest} 单独覆盖,这里要测的是
 * **"扫描结果 ->EntrypointRef"的翻译**.两层分开测的理由:
 * 扫描对了但翻译错了,两个单测都会绿,而功能没生效.
*/
class NeoForgeEntrypointProviderTest {

    private static final NeoForgeEntrypointProvider PROVIDER = new NeoForgeEntrypointProvider();

    private static final String BUS = "net.neoforged.bus.api.IEventBus";
    private static final String CONTAINER = "net.neoforged.fml.ModContainer";

/** modId 留空 ->用文件级 modId(NeoForge 的 `@Mod` 留空语义). */
    private static NeoForgeModClassScanner.ModClass modClass(String className, String declaredModId,
            String... ctorParams) {
        return new NeoForgeModClassScanner.ModClass(className, declaredModId,
                List.of(List.of(ctorParams)), "RUNTIME");
    }

    private static Map<LifecyclePhase, List<EntrypointRef>> entrypointsOf(
            NeoForgeModClassScanner.ModClass... modClasses) {
        return PROVIDER.getEntrypoints(new FakeModFile(List.of(modClasses)));
    }

    private static EntrypointRef only(NeoForgeModClassScanner.ModClass... modClasses) {
        List<EntrypointRef> refs = entrypointsOf(modClasses).get(LifecyclePhase.CONSTRUCT);
        assertEquals(1, refs.size());
        return refs.get(0);
    }

    // 按构造器签名分类

    @Test
    void noArgConstructorBecomesNoArgConstructorKind() {
        EntrypointRef ref = only(modClass("com.example.Plain", ""));

        assertEquals(EntrypointKind.NO_ARG_CONSTRUCTOR, ref.kind());
        assertFalse(ref.requiresServiceInjection());
        assertTrue(ref.constructorParamFQNs().isEmpty());
    }

    @Test
    void singleServiceConstructorBecomesInjectionKind() {
        EntrypointRef ref = only(modClass("com.example.One", "", BUS));

        assertEquals(EntrypointKind.CONSTRUCTOR_WITH_SERVICES, ref.kind());
        assertTrue(ref.requiresServiceInjection());
        assertEquals(List.of(BUS), ref.constructorParamFQNs());
    }

    @Test
    void twoServiceConstructorKeepsParameterOrder() {
        // 顺序必须原样保留:调度器按**位置**逐个解析形参,
        // 顺序错了会拿去构造器的 NoSuchMethodException,而那不是真因.
        EntrypointRef ref = only(modClass("com.example.Two", "", BUS, CONTAINER));

        assertEquals(List.of(BUS, CONTAINER), ref.constructorParamFQNs());
    }

    // @SubscribeEvent 的存在不影响分类

    @Test
    void subscribeEventMethodsDoNotAffectClassification() {
        // 扫描器只读**构造器**形参;监听器方法(@SubscribeEvent)与方法名无关.
        // 但必须测:若有人把它也扫进来,会产出一条多余或错误的 ref.
        // 这里用一个"既有无参构造器,又有监听器方法"的类来代表该形态.
        EntrypointRef ref = only(modClass("com.example.Listener", ""));

        assertEquals(EntrypointKind.NO_ARG_CONSTRUCTOR, ref.kind());
        assertEquals(LifecyclePhase.CONSTRUCT, ref.phase());
    }

    // 未知参数类型:适配器不做判断(那是 provider 的知识)

    @Test
    void unknownParameterTypeIsPassedThroughNotRejectedHere() {
        // 适配器**不能**判断某个 FQN 是否可提供  那是桥接层
        // (IEntrypointServiceProvider.canProvide) 的知识.
        // 适配器若自作主张拒绝,会把"某个桥接恰好能提供"的扩展路径堵死,
        // 且错误发生得太早(解析期),看不出真正缺的是什么.
        // 拒绝发生在调度期,且会列出已注册 provider 的能力(见设计 §4).
        EntrypointRef ref = only(modClass("com.example.Odd", "",
                "com.example.unknown.SomeService"));

        assertEquals(EntrypointKind.CONSTRUCTOR_WITH_SERVICES, ref.kind());
        assertEquals(List.of("com.example.unknown.SomeService"), ref.constructorParamFQNs());
    }

    // modId 归属与阶段

    @Test
    void declaredModIdWinsOverFileLevelModId() {
        // 一个 mods.toml 可声明多个 [[mods]].若某个 @Mod 类属于非 primary 的那个,
        // 一律用文件级 modId 会把它的入口点归错 mod.
        assertEquals("declared", only(modClass("com.example.A", "declared", BUS)).modId());
    }

    @Test
    void emptyDeclaredModIdFallsBackToFileLevelModId() {
        assertEquals("file-mod", only(modClass("com.example.B", "", BUS)).modId());
    }

    @Test
    void allEntrypointsUseTheConstructPhase() {
        // NeoForge 的 @Mod 构造发生在 CONSTRUCT  早于任何 mod 代码执行.
        // 放到别的阶段会让构造晚于别的 mod 的静态初始化.
        assertEquals(LifecyclePhase.CONSTRUCT, only(modClass("com.example.C", "", BUS)).phase());
    }

    @Test
    void multipleModClassesProduceMultipleRefs() {
        List<EntrypointRef> refs = entrypointsOf(
                modClass("com.example.One", "", BUS),
                modClass("com.example.Two", "")).get(LifecyclePhase.CONSTRUCT);

        assertEquals(2, refs.size());
    }

    @Test
    void fileWithoutNeoForgeExtensionYieldsNoEntrypoints(@TempDir Path tmp) {
        // 防御:非 NeoForge 格式的文件流到这里时不该产任何入口点,
        // 也不该抛异常(它有可能是 Fabric mod).
        IModFile plain = new FakeModFile(List.of(), false);

        assertTrue(PROVIDER.getEntrypoints(plain).isEmpty());
    }

    // 脚手架:最小 IModFile

    private static final class FakeModFile implements IModFile {

        private final NeoForgeModFileExtension extension;

        FakeModFile(List<NeoForgeModClassScanner.ModClass> modClasses) {
            this(modClasses, true);
        }

        FakeModFile(List<NeoForgeModClassScanner.ModClass> modClasses, boolean withExtension) {
            this.extension = withExtension
                    ? new NeoForgeModFileExtension("javafml", "[1,)", "MIT", null,
                            List.of(), List.of(), List.of(), modClasses)
                    : null;
        }

        @Override
        public Path getFilePath() {
            return Path.of("fake.jar");
        }

        @Override
        public String getFormat() {
            return "neoforge";
        }

        @Override
        public List<ModMetadata> getMetadataList() {
            return List.of(new ModMetadata("file-mod", "1.0.0", "Fake", null,
                    List.of(), "MIT", Map.of(), dev.multiloader.api.metadata.Environment.BOTH));
        }

        @Override
        public List<ModDependency> getDependencies() {
            return List.of();
        }

        @Override
        public List<MixinConfigRef> getMixinConfigRefs() {
            return List.of();
        }

        @Override
        public List<Path> getClasspathRoots() {
            return List.of();
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
