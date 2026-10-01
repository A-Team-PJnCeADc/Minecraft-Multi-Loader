package dev.multiloader.mixin;

import dev.multiloader.api.locating.IModFile;
import dev.multiloader.api.locating.MixinConfigRef;
import dev.multiloader.api.locating.ModFileCandidate;
import dev.multiloader.api.metadata.Environment;
import dev.multiloader.api.metadata.ModMetadata;
import dev.multiloader.common.Side;
import dev.multiloader.core.discovery.ModFileFactory;
import dev.multiloader.core.resolve.LoadingModList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mixin 配置聚合与冲突检测.
 * <p>fixture 用 {@link SyntheticMixinJars} 合成:目标是验证
 * "从 mixins.json + mixin 类注解里读出的东西是否正确",
 * 而不是依赖某个真实 mod 恰好长成什么样.
*/
class MixinConflictDetectorTest {

    private static final String TARGET = "net/minecraft/client/Minecraft";

    @Test
    void parsesMixinConfigIntoDeclarations(@TempDir Path tmp) throws IOException {
        Path jar = SyntheticMixinJars.modJar(tmp, "moda", "moda.mixins.json", Map.of(
                "com/example/mixin/TickMixin.class",
                SyntheticMixinJars.mixinClass("com/example/mixin/TickMixin", TARGET,
                        "tick", "()V", SyntheticMixinJars.OVERWRITE)));

        MixinConfigManager.CollectionResult result = collect(List.of(jar));

        assertFalse(result.hasProblems(), () -> result.problems().toString());
        assertEquals(1, result.configs().size());

        MixinConfigManager.MixinConfig config = result.configs().get(0);
        assertEquals("moda", config.modId());
        assertEquals("moda.mixins.json", config.configName());
        assertTrue(config.required());
        assertEquals("com.example.mixin", config.packageName());
        assertEquals("JAVA_25", config.compatibilityLevel());
        assertEquals(1, config.declarations().size());

        MixinConfigManager.MixinDeclaration declaration = config.declarations().get(0);
        assertEquals("com.example.mixin.TickMixin", declaration.mixinClass());
        assertEquals(List.of(TARGET), declaration.targetClasses());
        assertEquals(1, declaration.members().size());
        assertEquals(MixinConfigManager.ConflictPolicy.OVERWRITE, declaration.members().get(0).policy());
        assertEquals("tick", declaration.members().get(0).name());
    }

    @Test
    void targetOnlyFormIsParsed(@TempDir Path tmp) throws IOException {
        // @Mixin(targets = "...") 与 @Mixin(X.class) 两种写法都要认
        Path jar = SyntheticMixinJars.modJar(tmp, "moda", "moda.mixins.json", Map.of(
                "com/example/mixin/TargetsMixin.class",
                SyntheticMixinJars.mixinClassWithTargets("com/example/mixin/TargetsMixin", TARGET,
                        "tick", "()V", SyntheticMixinJars.INJECT)));

        MixinConfigManager.MixinConfig config = collect(List.of(jar)).configs().get(0);

        assertEquals(List.of(TARGET), config.declarations().get(0).targetClasses());
    }

    @Test
    void overwriteByTwoModsIsAFatalConflict(@TempDir Path tmp) throws IOException {
        Path mods = tmp.resolve("two");
        Files.createDirectories(mods);
        Path jarA = SyntheticMixinJars.modJar(mods, "moda", "moda.mixins.json", Map.of(
                "com/example/mixin/TickMixin.class",
                SyntheticMixinJars.mixinClass("com/example/mixin/TickMixin", TARGET,
                        "tick", "()V", SyntheticMixinJars.OVERWRITE)));
        Path jarB = SyntheticMixinJars.modJar(mods, "modb", "modb.mixins.json", Map.of(
                "com/example/mixin/TickMixin.class",
                SyntheticMixinJars.mixinClass("com/example/mixin/TickMixin", TARGET,
                        "tick", "()V", SyntheticMixinJars.OVERWRITE)));

        List<MixinConflictDetector.Conflict> conflicts =
                new MixinConflictDetector().detect(collect(List.of(jarA, jarB)).configs());

        assertTrue(MixinConflictDetector.hasFatal(conflicts), "两个 mod 覆盖同一成员必须是致命冲突");
        MixinConflictDetector.Conflict overwrite = conflicts.stream()
                .filter(c -> c.kind() == MixinConflictDetector.ConflictKind.OVERWRITE_COLLISION)
                .findFirst().orElseThrow();
        assertEquals(TARGET + "#tick()V", overwrite.target());
        assertEquals(2, overwrite.sources().size());
    }

    @Test
    void injectIntoSameTargetIsOnlyAWarning(@TempDir Path tmp) throws IOException {
        // 同目标类,不同注入 ->不算硬冲突(这是很常见且合法的用法)
        Path mods = tmp.resolve("two");
        Files.createDirectories(mods);
        Path jarA = SyntheticMixinJars.modJar(mods, "moda", "moda.mixins.json", Map.of(
                "com/example/mixin/TickMixin.class",
                SyntheticMixinJars.mixinClass("com/example/mixin/TickMixin", TARGET,
                        "onTick", "()V", SyntheticMixinJars.INJECT)));
        Path jarB = SyntheticMixinJars.modJar(mods, "modb", "modb.mixins.json", Map.of(
                "com/example/mixin/TickMixin.class",
                SyntheticMixinJars.mixinClass("com/example/mixin/TickMixin", TARGET,
                        "onTick", "()V", SyntheticMixinJars.INJECT)));

        List<MixinConflictDetector.Conflict> conflicts =
                new MixinConflictDetector().detect(collect(List.of(jarA, jarB)).configs());

        assertFalse(MixinConflictDetector.hasFatal(conflicts),
                "@Inject 撞车不是硬冲突，方法名是处理器名而非目标成员");
        assertTrue(conflicts.stream()
                        .anyMatch(c -> c.kind() == MixinConflictDetector.ConflictKind.TARGET_CLASS_OVERLAP),
                "但目标类重叠必须被告警");
    }

    @Test
    void sameModTouchingSameTargetIsNotAConflict(@TempDir Path tmp) throws IOException {
        // 一个 mod 的一个配置里,两个 mixin 类作用于同一个目标类 
        // 这是完全正常甚至推荐的写法,不该报任何冲突.
        Path jar = SyntheticMixinJars.modJar(tmp, "modc", "modc.mixins.json",
                "com/example/mixin", List.of("FirstMixin", "SecondMixin"), Map.of(
                        "com/example/mixin/FirstMixin.class",
                        SyntheticMixinJars.mixinClass("com/example/mixin/FirstMixin", TARGET, "a", "()V",
                                SyntheticMixinJars.OVERWRITE),
                        "com/example/mixin/SecondMixin.class",
                        SyntheticMixinJars.mixinClass("com/example/mixin/SecondMixin", TARGET, "b", "()V",
                                SyntheticMixinJars.OVERWRITE)));

        List<MixinConflictDetector.Conflict> conflicts =
                new MixinConflictDetector().detect(collect(List.of(jar)).configs());

        assertTrue(conflicts.isEmpty(),
                () -> "同一 mod 内部不该报冲突，实际报了: " + conflicts);
    }

    @Test
    void duplicateConfigNameAcrossModsIsReported(@TempDir Path tmp) throws IOException {
        Path mods = tmp.resolve("two");
        Files.createDirectories(mods);
        // 两个 mod 用同一个配置名:Mixin 按名字注册,后者会被静默忽略
        Path jarA = SyntheticMixinJars.modJar(mods, "moda", "shared.mixins.json", Map.of(
                "com/a/MixinOne.class",
                SyntheticMixinJars.mixinClass("com/a/MixinOne", TARGET, "a", "()V",
                        SyntheticMixinJars.INJECT)));
        Path jarB = SyntheticMixinJars.modJar(mods, "modb", "shared.mixins.json", Map.of(
                "com/b/MixinTwo.class",
                SyntheticMixinJars.mixinClass("com/b/MixinTwo", TARGET, "b", "()V",
                        SyntheticMixinJars.INJECT)));

        List<MixinConflictDetector.Conflict> conflicts =
                new MixinConflictDetector().detect(collect(List.of(jarA, jarB)).configs());

        assertTrue(conflicts.stream().anyMatch(
                        c -> c.kind() == MixinConflictDetector.ConflictKind.DUPLICATE_CONFIG_NAME),
                () -> "应报配置重名，实际: " + conflicts);
    }

    @Test
    void missingConfigFileIsReportedNotThrown(@TempDir Path tmp) throws IOException {
        Path jar = SyntheticMixinJars.jarWith(tmp, "moda.jar", Map.of(
                "fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"moda\",\"version\":\"1.0.0\"}"));

        IModFile modFile = new ModFileFactory()
                .builder(new ModFileCandidate(jar), "test")
                .metadata(ModMetadata.of("moda", "1.0.0"))
                .mixinConfigs(Set.of("absent.mixins.json"))
                .build();

        MixinConfigManager.CollectionResult result =
                MixinConfigManager.collect(LoadingModList.of(List.of(modFile)));

        assertTrue(result.configs().isEmpty());
        assertTrue(result.problems().get(0).contains("absent.mixins.json"),
                () -> "应指出缺失的配置名: " + result.problems());
    }

    @Test
    void serviceReportsRemapDisabledForModernNamespace(@TempDir Path tmp) throws Exception {
        MixinService service = new MixinService();

        // 无游戏类加载器也要能初始化:那时还没有 mod 配置,不做 bootstrap
        service.init(null, dev.multiloader.common.GameNamespace.OFFICIAL);

        assertTrue(service.isInitialized());
        assertFalse(service.isRemapEnabled(), "26.3 未混淆 -> remap 必须为 false");
        assertFalse(service.isBootstrapped(), "没有 mixin 配置时不应启动 Mixin 子系统");
    }

    @Test
    void serviceRejectsObfuscatedNamespace(@TempDir Path tmp) {
        MixinService service = new MixinService();

        // 混淆环境需要兼容层,核心层明确拒绝而不是假装能处理
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> service.init(null, dev.multiloader.common.GameNamespace.OBFUSCATED));
    }

    @Test
    void clientOnlyConfigIsSkippedOnServerBeforeParsing(@TempDir Path tmp) throws IOException {
        // D-b 的核心回归测试:client-only 配置在服务端必须被**跳过**,
        // 而且要在解析之前跳过  否则服务端会去加载引用客户端类的配置,
        // 崩的是整个服务端,不是某个 mod.
        Path jar = SyntheticMixinJars.modJar(tmp, "moda", "moda.client.mixins.json", Map.of(
                "com/example/mixin/ClientMixin.class",
                SyntheticMixinJars.mixinClass("com/example/mixin/ClientMixin",
                        "net/minecraft/client/gui/Gui", "a", "()V", SyntheticMixinJars.INJECT)));

        IModFile modFile = new ModFileFactory()
                .builder(new ModFileCandidate(jar), "test")
                .metadata(ModMetadata.of("moda", "1.0.0"))
                .mixinConfigRefs(List.of(
                        new MixinConfigRef("moda.client.mixins.json", Environment.CLIENT)))
                .build();

        // 服务端:跳过,且不产生任何"配置文件缺失"之类的问题
        MixinConfigManager.CollectionResult onServer =
                MixinConfigManager.collect(LoadingModList.of(List.of(modFile)), Side.SERVER);
        assertTrue(onServer.configs().isEmpty(), "服务端不得加载 client-only 配置");
        assertFalse(onServer.hasProblems(),
                () -> "跳过应当在解析之前发生，不该留下问题记录: " + onServer.problems());

        // 客户端:正常加载
        MixinConfigManager.CollectionResult onClient =
                MixinConfigManager.collect(LoadingModList.of(List.of(modFile)), Side.CLIENT);
        assertEquals(1, onClient.configs().size());
        assertEquals(Environment.CLIENT, onClient.configs().get(0).environment(),
                "侧别信息必须跟着配置传下去");
    }

    @Test
    void collectWithoutSideKeepsEverythingForDiagnostics(@TempDir Path tmp) throws IOException {
        Path jar = SyntheticMixinJars.modJar(tmp, "moda", "moda.client.mixins.json", Map.of(
                "com/example/mixin/ClientMixin.class",
                SyntheticMixinJars.mixinClass("com/example/mixin/ClientMixin",
                        "net/minecraft/client/gui/Gui", "a", "()V", SyntheticMixinJars.INJECT)));

        IModFile modFile = new ModFileFactory()
                .builder(new ModFileCandidate(jar), "test")
                .metadata(ModMetadata.of("moda", "1.0.0"))
                .mixinConfigRefs(List.of(
                        new MixinConfigRef("moda.client.mixins.json", Environment.CLIENT)))
                .build();

        // 传 null 侧别 = 诊断模式:不过滤,用于回答"这个实例一共声明了什么"
        MixinConfigManager.CollectionResult all =
                MixinConfigManager.collect(LoadingModList.of(List.of(modFile)), null);

        assertEquals(1, all.configs().size());
    }

    @Test
    void bothEnvironmentAppliesOnEitherSide(@TempDir Path tmp) throws IOException {
        // 回归守卫:BOTH 必须同时在两侧生效.
        // 如果 isClient() 被写成 `this == CLIENT`,双侧配置会在两边都被静默丢掉
        // 表现是"mod 的 mixin 完全没生效",且没有任何报错.
        Path jar = SyntheticMixinJars.modJar(tmp, "moda", "moda.mixins.json", Map.of(
                "com/example/mixin/CommonMixin.class",
                SyntheticMixinJars.mixinClass("com/example/mixin/CommonMixin", TARGET,
                        "tick", "()V", SyntheticMixinJars.INJECT)));

        IModFile modFile = new ModFileFactory()
                .builder(new ModFileCandidate(jar), "test")
                .metadata(ModMetadata.of("moda", "1.0.0"))
                .mixinConfigRefs(List.of(new MixinConfigRef("moda.mixins.json", Environment.BOTH)))
                .build();

        LoadingModList modList = LoadingModList.of(List.of(modFile));
        assertEquals(1, MixinConfigManager.collect(modList, Side.SERVER).configs().size(),
                "双侧配置必须能在服务端加载");
        assertEquals(1, MixinConfigManager.collect(modList, Side.CLIENT).configs().size(),
                "双侧配置必须能在客户端加载");
    }

    @Test
    void serverOnlyConfigIsSkippedOnClient(@TempDir Path tmp) throws IOException {
        Path jar = SyntheticMixinJars.modJar(tmp, "moda", "moda.server.mixins.json", Map.of(
                "com/example/mixin/ServerMixin.class",
                SyntheticMixinJars.mixinClass("com/example/mixin/ServerMixin",
                        "net/minecraft/server/MinecraftServer", "tick", "()V",
                        SyntheticMixinJars.INJECT)));

        IModFile modFile = new ModFileFactory()
                .builder(new ModFileCandidate(jar), "test")
                .metadata(ModMetadata.of("moda", "1.0.0"))
                .mixinConfigRefs(List.of(
                        new MixinConfigRef("moda.server.mixins.json", Environment.SERVER)))
                .build();

        LoadingModList modList = LoadingModList.of(List.of(modFile));
        assertEquals(1, MixinConfigManager.collect(modList, Side.SERVER).configs().size());
        assertTrue(MixinConfigManager.collect(modList, Side.CLIENT).configs().isEmpty(),
                "server-only 配置不得在客户端加载");
    }

    // helpers

    private static MixinConfigManager.CollectionResult collect(List<Path> jars) {
        List<IModFile> modFiles = new ArrayList<>();
        for (Path jar : jars) {
            String modId = jar.getFileName().toString().replace(".jar", "");
            modFiles.add(new ModFileFactory()
                    .builder(new ModFileCandidate(jar), "test")
                    .metadata(ModMetadata.of(modId, "1.0.0"))
                    .mixinConfigs(SyntheticMixinJars.configsOf(jar))
                    .build());
        }
        return MixinConfigManager.collect(LoadingModList.of(modFiles));
    }
}
