package dev.multiloader.core.discovery;

import dev.multiloader.adapt.fabric.FabricEntrypointProvider;
import dev.multiloader.adapt.fabric.FabricModFileExtension;
import dev.multiloader.adapt.fabric.FabricModFileReader;
import dev.multiloader.api.lifecycle.EntrypointKind;
import dev.multiloader.api.lifecycle.EntrypointRef;
import dev.multiloader.api.lifecycle.LifecyclePhase;
import dev.multiloader.api.locating.IModFile;
import dev.multiloader.api.locating.IModFileReader;
import dev.multiloader.api.locating.MixinConfigRef;
import dev.multiloader.api.locating.ModFileCandidate;
import dev.multiloader.api.metadata.DependencyKind;
import dev.multiloader.api.metadata.Environment;
import dev.multiloader.common.io.JarEntries;
import dev.multiloader.core.resolve.LoadingModList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端:{@code fabric.mod.json -> IModFileReader -> IModFile -> LoadingModList}.
 * <p>为什么这个测试住在 loader-core 而不是 adapt-fabric:
 * 它要用 {@link ModDiscoverer} 这条核心链路,而适配器的依赖边界被限定为
 * {@code loader-api + runtime-common}.把它放在 core 的测试集里,
 * 用 {@code testImplementation project(':adapt-fabric')} 把适配器当夹具引入,
 * 既验证了整条链路,又不让适配器反向依赖 core.
 * <p>fixture 由本测试自己合成,不依赖真实 mod被验证的是"适配器是否真的按内容识别,
 * 是否真的把各字段读出来",而不是"这台机器上恰好有某个 mod".
*/
class FabricDiscoveryIntegrationTest {

    private static final String FULL_MOD_JSON = """
            {
              "schemaVersion": 1,
              "id": "examplemod",
              "version": "1.0.0",
              "name": "Example Mod",
              "description": "A test mod",
              "authors": ["Someone"],
              "license": "MIT",
              "icon": "examplemod.png",
              "environment": "*",
              "entrypoints": {
                "preLaunch": ["com.example.examplemod.PreLaunch"],
                "main": ["com.example.examplemod.ExampleMod"],
                "client": [{ "value": "com.example.examplemod.ExampleModClient", "adapter": "kotlin" }]
              },
              "mixins": [
                "examplemod.mixins.json",
                { "config": "examplemod.client.mixins.json", "environment": "client" }
              ],
              "accessWidener": "examplemod.accesswidener",
              "languageAdapters": { "kotlin": "com.example.KotlinAdapter" },
              "provides": ["examplemod-api"],
              "jars": [ { "file": "META-INF/jars/lib.jar" } ],
              "depends": {
                "fabricloader": ">=0.19.5",
                "fabric-api": "*",
                "minecraft": "~26.3",
                "java": ">=25"
              },
              "conflicts": { "sodium": "*" },
              "contact": { "homepage": "https://example.invalid" }
            }
            """;

    @Test
    void discoversFabricModAndProducesModFileAndLoadingModList(@TempDir Path tmp) throws IOException {
        Path mods = Files.createDirectories(tmp.resolve("mods"));
        writeJar(mods.resolve("examplemod-1.0.0.jar"), Map.of(
                "fabric.mod.json", FULL_MOD_JSON,
                "examplemod.mixins.json", "{\"package\":\"com.example.examplemod.mixin\",\"mixins\":[]}"));

        ModDiscoverer.DiscoveryResult result = runDiscovery(mods);

        assertFalse(result.hasProblems(), () -> "unexpected problems: " + result.problems());
        assertEquals(1, result.modFiles().size());

        IModFile modFile = result.modFiles().get(0);
        assertEquals("fabric", modFile.getFormat());
        assertEquals("examplemod", modFile.getPrimaryModId());
        assertEquals("Example Mod", modFile.getMetadata().displayName());
        assertEquals("1.0.0", modFile.getMetadata().version());
        assertEquals(List.of("Someone"), modFile.getMetadata().authors());

        // 两种 mixins 声明形态都要被读出来
        assertEquals(2, modFile.getMixinConfigs().size());
        assertTrue(modFile.getMixinConfigs().contains("examplemod.mixins.json"));
        assertTrue(modFile.getMixinConfigs().contains("examplemod.client.mixins.json"));

        // 依赖分类
        assertTrue(hasDependency(modFile, "fabric-api", DependencyKind.REQUIRED));
        assertTrue(hasDependency(modFile, "sodium", DependencyKind.INCOMPATIBLE));

        // 适配器私有数据
        FabricModFileExtension extension = modFile.getExtension(FabricModFileExtension.class).orElseThrow();
        assertEquals(1, extension.schemaVersion());
        assertEquals("examplemod.accesswidener", extension.accessWidener());
        assertTrue(extension.hasAccessWidener());
        assertEquals(Map.of("kotlin", "com.example.KotlinAdapter"), extension.languageAdapters());
        assertTrue(extension.provides().contains("examplemod-api"));
        assertEquals(3, extension.entrypoints().size(), "preLaunch + main + client");

        // 嵌套 jar 以伪路径表达
        assertEquals(1, extension.nestedJars().size());
        assertTrue(JarEntries.isPseudoPath(extension.nestedJars().get(0)));
        assertNotNull(JarEntries.splitPseudoPath(extension.nestedJars().get(0)));

        // LoadingModList
        LoadingModList list = LoadingModList.of(result.modFiles());
        assertEquals(1, list.size());
        assertTrue(list.byModId("examplemod").isPresent());
        assertTrue(list.allMixinConfigs().contains("examplemod.client.mixins.json"));
    }

/**
     * D-b 的核心断言:对象形态的 {@code environment} 必须被读出来,
     * 而不是被压成一个裸配置名.
*/
    @Test
    void mixinConfigEnvironmentIsPreservedPerConfig(@TempDir Path tmp) throws IOException {
        Path mods = Files.createDirectories(tmp.resolve("mods"));
        writeJar(mods.resolve("examplemod.jar"), Map.of("fabric.mod.json", FULL_MOD_JSON));

        IModFile modFile = runDiscovery(mods).modFiles().get(0);
        List<MixinConfigRef> refs = modFile.getMixinConfigRefs();

        assertEquals(2, refs.size());

        MixinConfigRef common = refs.stream()
                .filter(r -> r.configName().equals("examplemod.mixins.json")).findFirst().orElseThrow();
        assertEquals(Environment.BOTH, common.environment(),
                "字符串形态按双侧处理");

        MixinConfigRef clientOnly = refs.stream()
                .filter(r -> r.configName().equals("examplemod.client.mixins.json")).findFirst().orElseThrow();
        assertEquals(Environment.CLIENT, clientOnly.environment(),
                "client-only 的侧别必须在解析阶段就被保留，否则服务端会加载它并崩溃");

        // 侧别判定:服务端必须排除 client-only 配置
        assertTrue(common.appliesToServer());
        assertFalse(clientOnly.appliesToServer());
        assertTrue(clientOnly.appliesToClient());
    }

    @Test
    void entrypointProviderMapsFabricNamespacesToLifecyclePhases(@TempDir Path tmp) throws IOException {
        Path mods = Files.createDirectories(tmp.resolve("mods"));
        writeJar(mods.resolve("examplemod.jar"), Map.of("fabric.mod.json", FULL_MOD_JSON));

        IModFile modFile = runDiscovery(mods).modFiles().get(0);
        Map<LifecyclePhase, List<EntrypointRef>> entrypoints =
                new FabricEntrypointProvider().getEntrypoints(modFile);

        EntrypointRef preLaunch = entrypoints.get(LifecyclePhase.PREINIT).get(0);
        assertEquals("com.example.examplemod.PreLaunch", preLaunch.className());
        assertEquals(EntrypointKind.NO_ARG_METHOD, preLaunch.kind());
        assertEquals("onPreLaunch", preLaunch.methodName());

        EntrypointRef main = entrypoints.get(LifecyclePhase.INIT).get(0);
        assertEquals("com.example.examplemod.ExampleMod", main.className());
        assertEquals(EntrypointKind.NO_ARG_METHOD, main.kind());
        // 方法名逐命名空间不同  这是方法名必须存在 ref 里,不能硬编码的原因
        assertEquals("onInitialize", main.methodName());

        EntrypointRef client = entrypoints.get(LifecyclePhase.SIDED_SETUP).get(0);
        assertEquals("com.example.examplemod.ExampleModClient", client.className());
        assertEquals("onInitializeClient", client.methodName());
    }

/**
     * 回归守卫:入口点命名空间**本身**隐含侧别.
     * <p>只信 mod 级 environment(这里是 {@code "*"} ->BOTH)会让 {@code client}
     * 入口点在专用服务端被实例化.那个类通常引用客户端专属类型,
     * 结果是服务端崩在 {@code NoClassDefFoundError},而堆栈指向 mod
     * 而不是我们的解析代码最难归因的一类故障.
*/
    @Test
    void entrypointNamespaceImpliesSide(@TempDir Path tmp) throws IOException {
        Path mods = Files.createDirectories(tmp.resolve("mods"));
        writeJar(mods.resolve("sided.jar"), Map.of("fabric.mod.json", """
                {
                  "schemaVersion": 1,
                  "id": "sidedmod",
                  "version": "1.0.0",
                  "environment": "*",
                  "entrypoints": {
                    "main": ["com.example.Main"],
                    "client": ["com.example.Client"],
                    "server": ["com.example.Server"],
                    "preLaunch": ["com.example.PreLaunch"]
                  }
                }
                """));

        IModFile modFile = runDiscovery(mods).modFiles().get(0);
        Map<LifecyclePhase, List<EntrypointRef>> entrypoints =
                new FabricEntrypointProvider().getEntrypoints(modFile);

        EntrypointRef main = entrypoints.get(LifecyclePhase.INIT).get(0);
        EntrypointRef client = entrypoints.get(LifecyclePhase.SIDED_SETUP).get(0);
        EntrypointRef server = entrypoints.get(LifecyclePhase.SIDED_SETUP).get(1);
        EntrypointRef preLaunch = entrypoints.get(LifecyclePhase.PREINIT).get(0);

        assertEquals(Environment.BOTH, main.environment(), "main 沿用 mod 级 environment");
        assertEquals(Environment.BOTH, preLaunch.environment(), "preLaunch 沿用 mod 级 environment");
        assertEquals(Environment.CLIENT, client.environment(), "client 命名空间必须隐含 CLIENT");
        assertEquals(Environment.SERVER, server.environment(), "server 命名空间必须隐含 SERVER");

        // 侧别过滤的实际效果:服务端侧只应选中 main / server / preLaunch
        assertFalse(client.environment().appliesTo(false),
                "client 入口点不得在专用服务端生效");
        assertTrue(server.environment().appliesTo(false));
        assertTrue(main.environment().appliesTo(false));
    }

    @Test
    void jarWithoutFabricMetadataIsNotClaimed(@TempDir Path tmp) throws IOException {
        Path mods = Files.createDirectories(tmp.resolve("mods"));
        writeJar(mods.resolve("not-a-mod.jar"), Map.of("some/Class.class", "not really a class"));
        writeJar(mods.resolve("examplemod.jar"), Map.of("fabric.mod.json", FULL_MOD_JSON));

        ModDiscoverer.DiscoveryResult result = runDiscovery(mods);

        assertEquals(1, result.modFiles().size(), "只应认领含 fabric.mod.json 的那个");
        assertTrue(result.problems().stream().anyMatch(p -> p.contains("not-a-mod.jar")));
    }

    @Test
    void malformedMetadataIsReportedNotThrown(@TempDir Path tmp) throws IOException {
        Path mods = Files.createDirectories(tmp.resolve("mods"));
        writeJar(mods.resolve("broken.jar"), Map.of("fabric.mod.json",
                "{\"schemaVersion\":1,\"version\":\"1.0.0\"}"));

        ModDiscoverer.DiscoveryResult result = runDiscovery(mods);

        assertTrue(result.modFiles().isEmpty());
        assertTrue(result.hasProblems());
        assertTrue(result.problems().get(0).contains("broken.jar"));
    }

    @Test
    void unsupportedSchemaVersionIsRejectedExplicitly(@TempDir Path tmp) throws IOException {
        Path mods = Files.createDirectories(tmp.resolve("mods"));
        writeJar(mods.resolve("future.jar"), Map.of("fabric.mod.json",
                "{\"schemaVersion\":99,\"id\":\"future\",\"version\":\"1.0.0\"}"));

        ModDiscoverer.DiscoveryResult result = runDiscovery(mods);

        assertTrue(result.modFiles().isEmpty());
        assertTrue(result.problems().get(0).contains("schemaVersion=99"),
                () -> "should name the offending version: " + result.problems());
    }

    @Test
    void directoryTypeModIsReadable(@TempDir Path tmp) throws IOException {
        Path mods = Files.createDirectories(tmp.resolve("mods"));
        Path dirMod = Files.createDirectories(mods.resolve("dir-mod"));
        Files.writeString(dirMod.resolve("fabric.mod.json"),
                "{\"schemaVersion\":1,\"id\":\"dirmod\",\"version\":\"2.0.0\"}", StandardCharsets.UTF_8);

        // 目录型 mod 当前不在 DirectoryModCandidateLocator 的候选里(只收 .jar),
        // 但 reader 本身必须支持目录形态.
        IModFileReader reader = new FabricModFileReader();
        assertTrue(reader.canRead(new ModFileCandidate(dirMod)));

        IModFile modFile;
        try {
            modFile = reader.read(new ModFileCandidate(dirMod), new ModFileFactory());
        } catch (Exception e) {
            throw new AssertionError("reader failed on directory mod", e);
        }
        assertEquals("dirmod", modFile.getPrimaryModId());
        assertEquals("2.0.0", modFile.getMetadata().version());
    }

    // helpers

    private static ModDiscoverer.DiscoveryResult runDiscovery(Path mods) {
        ModDiscoverer discoverer = new ModDiscoverer(
                List.of(new DirectoryModCandidateLocator(mods)),
                List.of(new FabricModFileReader()),
                new ModFileFactory());
        return discoverer.run();
    }

    private static boolean hasDependency(IModFile modFile, String modId, DependencyKind kind) {
        return modFile.getDependencies().stream()
                .anyMatch(d -> d.modId().equals(modId) && d.kind() == kind);
    }

    private static void writeJar(Path jar, Map<String, String> entries) throws IOException {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
    }
}
