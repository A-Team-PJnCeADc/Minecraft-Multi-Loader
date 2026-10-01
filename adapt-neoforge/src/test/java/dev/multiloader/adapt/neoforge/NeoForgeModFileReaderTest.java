package dev.multiloader.adapt.neoforge;

import dev.multiloader.api.locating.IModFile;
import dev.multiloader.api.locating.IModFileExtension;
import dev.multiloader.api.locating.IModFileFactory;
import dev.multiloader.api.locating.MixinConfigRef;
import dev.multiloader.api.locating.ModFileCandidate;
import dev.multiloader.api.locating.ModFileException;
import dev.multiloader.api.metadata.DependencyKind;
import dev.multiloader.api.metadata.Environment;
import dev.multiloader.api.metadata.ModDependency;
import dev.multiloader.api.metadata.ModMetadata;
import dev.multiloader.api.metadata.Ordering;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 适配器级单元测试.完全自包含:不依赖 loader-core / loader-classloader
 * (适配器的依赖边界只有 {@code loader-api + runtime-common}).
 * <p>失败路径刻意传 {@code factory = null}:那些校验都发生在
 * {@code factory.builder(...)} **之前**,所以"抛的是 ModFileException 而不是
 * NullPointerException"本身就是"没提前碰工厂"的证据.
 * <p>这里的重点不是覆盖率数字,而是把 NeoForge 与 Fabric **结构性不同**的那几处
 * 钉住 一个 jar 多个 modId,TOML 嵌套数组表,无 per-config 侧别字段.
 * 照搬 Fabric 的解析会在这些点上静默读错.
*/
class NeoForgeModFileReaderTest {

    private static final NeoForgeModFileReader READER = new NeoForgeModFileReader();

    private static final String MINIMAL = """
            modLoader="javafml"
            loaderVersion="[1,)"
            [[mods]]
                modId="a"
            """;

    // 认领

    @Test
    void canReadRequiresNeoForgeAnchor(@TempDir Path tmp) throws Exception {
        Path withAnchor = writeJar(tmp, "neo.jar",
                Map.of(NeoForgeModFileReader.ANCHOR, MINIMAL));
        Path withoutAnchor = writeJar(tmp, "none.jar", Map.of("some/Class.class", "x"));

        assertTrue(READER.canRead(new ModFileCandidate(withAnchor)));
        assertFalse(READER.canRead(new ModFileCandidate(withoutAnchor)));
    }

    @Test
    void doesNotClaimLegacyForgeAnchor(@TempDir Path tmp) throws Exception {
        // 旧版 Forge 用 META-INF/mods.toml.字段语义已经不同,
        // 认领了就是"假装能读" 会让一个 Forge mod 以错误语义被装配.
        Path legacy = writeJar(tmp, "legacy.jar", Map.of(
                NeoForgeModFileReader.LEGACY_FORGE_ANCHOR, MINIMAL));

        assertFalse(READER.canRead(new ModFileCandidate(legacy)),
                "不应认领旧版 Forge 的 mods.toml");
    }

    // 必填字段(失败路径:断言"没提前碰工厂")

    @Test
    void missingModLoaderIsRejected(@TempDir Path tmp) throws Exception {
        Path jar = writeJar(tmp, "a.jar", Map.of(NeoForgeModFileReader.ANCHOR,
                "loaderVersion=\"[1,)\"\n[[mods]]\n modId=\"a\"\n"));
        assertThrows(ModFileException.class,
                () -> READER.read(new ModFileCandidate(jar), null));
    }

    @Test
    void missingLoaderVersionIsRejected(@TempDir Path tmp) throws Exception {
        Path jar = writeJar(tmp, "a.jar", Map.of(NeoForgeModFileReader.ANCHOR,
                "modLoader=\"javafml\"\n[[mods]]\n modId=\"a\"\n"));
        assertThrows(ModFileException.class,
                () -> READER.read(new ModFileCandidate(jar), null));
    }

    @Test
    void noModsEntryIsRejected(@TempDir Path tmp) throws Exception {
        // 一个没有 [[mods]] 的文件没有 modId,下游(依赖求解,冲突检测)无从下手.
        Path jar = writeJar(tmp, "a.jar", Map.of(NeoForgeModFileReader.ANCHOR,
                "modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\n"));
        assertThrows(ModFileException.class,
                () -> READER.read(new ModFileCandidate(jar), null));
    }

    @Test
    void invalidTomlIsRejectedAsModFileException(@TempDir Path tmp) throws Exception {
        Path jar = writeJar(tmp, "a.jar", Map.of(NeoForgeModFileReader.ANCHOR,
                "this is [not valid toml @@@\n"));
        assertThrows(ModFileException.class,
                () -> READER.read(new ModFileCandidate(jar), null));
    }

    // 与 Fabric 的结构性差异

    @Test
    void multipleModsEntriesProduceMultipleMetadata(@TempDir Path tmp) throws Exception {
        // 这是与 fabric.mod.json(一文件一 mod)最本质的差异.
        // 若模型只容得下一个元数据,这类 jar 必然被读残.
        RecordingFactory factory = new RecordingFactory();
        Path jar = writeJar(tmp, "multi.jar", Map.of(NeoForgeModFileReader.ANCHOR, """
                modLoader="javafml"
                loaderVersion="[1,)"
                license="MIT"
                [[mods]]
                    modId="first"
                    version="1.2.3"
                    displayName="First"
                [[mods]]
                    modId="second"
                    version="4.5.6"
                """));

        READER.read(new ModFileCandidate(jar), factory);

        assertEquals("neoforge", factory.format);
        assertEquals(2, factory.metadataList.size());
        assertEquals(List.of("first", "second"),
                factory.metadataList.stream().map(ModMetadata::modId).toList());
        assertEquals("1.2.3", factory.metadataList.get(0).version());
        assertEquals("First", factory.metadataList.get(0).displayName());
        // license 是文件级(顶层),应传播到每个 mod 的元数据
        assertEquals("MIT", factory.metadataList.get(1).license());
    }

    @Test
    void unresolvedVersionPlaceholderIsFlaggedNotSilentlyZero(@TempDir Path tmp) throws Exception {
        // ${file.jarVersion} 是装配期替换的占位符,我们没有替换表.
        // 关键是**可区分**:下游要能看出"这真的是 0.0.0"还是"我们不知道".
        RecordingFactory factory = new RecordingFactory();
        Path jar = writeJar(tmp, "ph.jar", Map.of(NeoForgeModFileReader.ANCHOR, """
                modLoader="javafml"
                loaderVersion="[1,)"
                [[mods]]
                    modId="a"
                    version="${file.jarVersion}"
                """));

        READER.read(new ModFileCandidate(jar), factory);

        assertEquals("0.0.0", factory.metadataList.get(0).version());
        NeoForgeModFileExtension extension = (NeoForgeModFileExtension) factory.extension;
        assertTrue(extension.modEntries().get(0).versionUnresolved(),
                "版本是回退值时必须被标记，否则下游无法区分占位符与真实版本");
    }

    @Test
    void authorsAcceptBothStringAndArrayForms(@TempDir Path tmp) throws Exception {
        RecordingFactory factory = new RecordingFactory();
        Path jar = writeJar(tmp, "authors.jar", Map.of(NeoForgeModFileReader.ANCHOR, """
                modLoader="javafml"
                loaderVersion="[1,)"
                [[mods]]
                    modId="asstring"
                    authors="Alice, Bob ,Carol"
                [[mods]]
                    modId="asarray"
                    authors=["Dan","Erin"]
                """));

        READER.read(new ModFileCandidate(jar), factory);

        assertEquals(List.of("Alice", "Bob", "Carol"), factory.metadataList.get(0).authors());
        assertEquals(List.of("Dan", "Erin"), factory.metadataList.get(1).authors());
    }

    // mixins / dependencies / accessTransformers

    @Test
    void mixinSectionMapsToMixinConfigRefs(@TempDir Path tmp) throws Exception {
        RecordingFactory factory = new RecordingFactory();
        Path jar = writeJar(tmp, "mixins.jar", Map.of(NeoForgeModFileReader.ANCHOR, """
                modLoader="javafml"
                loaderVersion="[1,)"
                [[mods]]
                    modId="a"
                [[mixins]]
                    config="a.mixins.json"
                [[mixins]]
                    config="b.mixins.json"
                """));

        READER.read(new ModFileCandidate(jar), factory);

        assertEquals(2, factory.mixinRefs.size());
    }

    @Test
    void nestedDependencyTablesAreParsedPerOwnerMod(@TempDir Path tmp) throws Exception {
        // [[dependencies.<modId>]] 是 TOML 的嵌套数组表  照抄 Fabric 的
        // "依赖是一个对象 map" 会完全读不到.
        RecordingFactory factory = new RecordingFactory();
        Path jar = writeJar(tmp, "deps.jar", Map.of(NeoForgeModFileReader.ANCHOR, """
                modLoader="javafml"
                loaderVersion="[1,)"
                [[mods]]
                    modId="owner"
                [[dependencies.owner]]
                    modId="minecraft"
                    type="required"
                    versionRange="[26.3,)"
                    ordering="AFTER"
                    side="CLIENT"
                """));

        READER.read(new ModFileCandidate(jar), factory);

        assertEquals(1, factory.dependencies.size());
        ModDependency dependency = factory.dependencies.get(0);
        assertEquals("minecraft", dependency.modId());
        assertEquals("[26.3,)", dependency.versionRange());
        assertEquals(DependencyKind.REQUIRED, dependency.kind());
        assertEquals(Ordering.AFTER, dependency.ordering());
        assertEquals(Environment.CLIENT, dependency.side());

        NeoForgeModFileExtension extension = (NeoForgeModFileExtension) factory.extension;
        assertEquals("owner", extension.dependencyEntries().get(0).ownerModId(),
                "\"谁依赖谁\"必须保留：一个 jar 可含多个 mod，丢掉归属就无法诊断");
    }

    @Test
    void nonRequiredDependencyTypesDoNotBecomeRequired(@TempDir Path tmp) throws Exception {
        // discouraged(能跑但不推荐)与 embedded(已内嵌)都不构成"缺失即失败".
        // 映射成 REQUIRED 会让本该启动的实例被拒  这是默认拒绝用错地方的典型.
        // 未知 type 同样按 OPTIONAL:缺的是**我们的解读**,不是 mod 的必要条件.
        RecordingFactory factory = new RecordingFactory();
        Path jar = writeJar(tmp, "kinds.jar", Map.of(NeoForgeModFileReader.ANCHOR, """
                modLoader="javafml"
                loaderVersion="[1,)"
                [[mods]]
                    modId="owner"
                [[dependencies.owner]]
                    modId="discouraged-lib"
                    type="discouraged"
                [[dependencies.owner]]
                    modId="embedded-lib"
                    type="embedded"
                [[dependencies.owner]]
                    modId="unknown-lib"
                    type="something-new"
                [[dependencies.owner]]
                    modId="hard"
                    type="required"
                """));

        READER.read(new ModFileCandidate(jar), factory);

        Map<String, DependencyKind> kinds = factory.dependencies.stream()
                .collect(java.util.stream.Collectors.toMap(ModDependency::modId, ModDependency::kind));
        assertEquals(DependencyKind.OPTIONAL, kinds.get("discouraged-lib"));
        assertEquals(DependencyKind.OPTIONAL, kinds.get("embedded-lib"));
        assertEquals(DependencyKind.OPTIONAL, kinds.get("unknown-lib"));
        assertEquals(DependencyKind.REQUIRED, kinds.get("hard"));
    }

    @Test
    void defaultDependencyRangeIsWildcardNotEmpty(@TempDir Path tmp) throws Exception {
        // 缺失的 versionRange 若变成空串,版本比较会把"未声明"读成"不匹配任何版本".
        RecordingFactory factory = new RecordingFactory();
        Path jar = writeJar(tmp, "range.jar", Map.of(NeoForgeModFileReader.ANCHOR, """
                modLoader="javafml"
                loaderVersion="[1,)"
                [[mods]]
                    modId="owner"
                [[dependencies.owner]]
                    modId="lib"
                    type="required"
                """));

        READER.read(new ModFileCandidate(jar), factory);

        assertEquals("*", factory.dependencies.get(0).versionRange());
    }

    @Test
    void accessTransformersAreCollected(@TempDir Path tmp) throws Exception {
        RecordingFactory factory = new RecordingFactory();
        Path jar = writeJar(tmp, "at.jar", Map.of(NeoForgeModFileReader.ANCHOR, """
                modLoader="javafml"
                loaderVersion="[1,)"
                [[mods]]
                    modId="a"
                [[accessTransformers]]
                    file="META-INF/accesstransformer.cfg"
                """));

        READER.read(new ModFileCandidate(jar), factory);

        NeoForgeModFileExtension extension = (NeoForgeModFileExtension) factory.extension;
        assertTrue(extension.hasAccessTransformers());
        assertEquals(List.of("META-INF/accesstransformer.cfg"), extension.accessTransformers());
        assertEquals("javafml", extension.modLoader());
        assertEquals("[1,)", extension.loaderVersion());
    }

    // 脚手架

    @Test
    void readerPopulatesModClassesFromBytecodeScan(@TempDir Path tmp) throws Exception {
        // 接线验证:@Mod 类名**不在** toml 里,只能靠扫字节码得到.
        // 这条测试走的是完整 reader 路径(toml 解析 + 扫描 + 组装 extension),
        // 而不是直接调扫描器  后者已由 NeoForgeModClassScannerTest 覆盖.
        // 分开的理由:扫描正确 + 接线漏了 = 功能没生效,而两个单测都会绿.
        RecordingFactory factory = new RecordingFactory();
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, "com/example/NeoMod", null, "java/lang/Object", null);
        AnnotationVisitor annotation = cw.visitAnnotation(
                "Lnet/neoforged/fml/common/Mod;", true);
        annotation.visit("value", "scanned-mod");
        annotation.visitEnd();
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
                "(Lnet/neoforged/bus/api/IEventBus;)V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(2, 2);
        ctor.visitEnd();
        cw.visitEnd();

        Path jar = writeJarBytes(tmp, "wired.jar", Map.of(
                NeoForgeModFileReader.ANCHOR, """
                        modLoader="javafml"
                        loaderVersion="[1,)"
                        [[mods]]
                            modId="scanned-mod"
                        """.getBytes(StandardCharsets.UTF_8),
                "com/example/NeoMod.class", cw.toByteArray()));

        READER.read(new ModFileCandidate(jar), factory);

        NeoForgeModFileExtension extension = (NeoForgeModFileExtension) factory.extension;
        assertEquals(1, extension.modClasses().size(),
                "reader 必须把扫描结果带进 extension，否则 @Mod 类永远不会被发现");
        assertEquals("com.example.NeoMod", extension.modClasses().get(0).className());
        assertEquals("scanned-mod", extension.modClasses().get(0).declaredModId());
        assertTrue(extension.requiresServiceInjection(),
                "带参构造器 ->需要服务注入，2b 必须走服务解析而不是 NO_ARG 路径");
    }

    // 脚手架

    private static Path writeJarBytes(Path dir, String name, Map<String, byte[]> entries)
            throws IOException {
        Path jar = dir.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return jar;
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

/**
     * 记录式假工厂:把 reader 交给 builder 的东西截下来供断言.
     * <p>刻意不实现真正的 IModFile  本模块不依赖 loader-core,
     * 而 IModFile 的真实实现住在那里.{@code build()} 返回 null,
     * 测试只读截获到的入参.
*/
    private static final class RecordingFactory implements IModFileFactory {

        String format;
        List<ModMetadata> metadataList = List.of();
        List<ModDependency> dependencies = List.of();
        List<MixinConfigRef> mixinRefs = List.of();
        IModFileExtension extension;

        @Override
        public Builder builder(ModFileCandidate candidate, String format) {
            this.format = format;
            assertNotNull(candidate, "候选不应为 null");
            return new Builder() {
                @Override
                public Builder metadata(ModMetadata metadata) {
                    metadataList = List.of(metadata);
                    return this;
                }

                @Override
                public Builder metadata(List<ModMetadata> metadata) {
                    metadataList = List.copyOf(metadata);
                    return this;
                }

                @Override
                public Builder dependencies(List<ModDependency> value) {
                    dependencies = List.copyOf(value);
                    return this;
                }

                @Override
                public Builder mixinConfigRefs(List<MixinConfigRef> refs) {
                    mixinRefs = List.copyOf(refs);
                    return this;
                }

                @Override
                public Builder mixinConfigs(Set<String> mixins) {
                    return this;
                }

                @Override
                public Builder classpathRoot(Path root) {
                    return this;
                }

                @Override
                public Builder nestedLibrary(Path nested) {
                    return this;
                }

                @Override
                public Builder extension(IModFileExtension value) {
                    extension = value;
                    return this;
                }

                @Override
                public IModFile build() {
                    return null;
                }
            };
        }
    }
}
