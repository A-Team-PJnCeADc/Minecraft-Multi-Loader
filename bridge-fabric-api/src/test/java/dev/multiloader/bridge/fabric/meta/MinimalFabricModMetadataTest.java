package dev.multiloader.bridge.fabric.meta;

import dev.multiloader.api.locating.IModFile;
import dev.multiloader.api.locating.IModFileExtension;
import dev.multiloader.api.locating.MixinConfigRef;
import dev.multiloader.api.metadata.Environment;
import dev.multiloader.api.metadata.IModFileMetadata;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import net.fabricmc.loader.api.metadata.Person;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MinimalFabricModMetadata} 的映射测试.
 * <p>顺带验证一条**上一轮只做了类型层验证**的东西:{@code file.getExtension(IModFileMetadata.class)}
 * 能否真的取到扩展(`IModFileMetadata extends IModFileExtension` 这个修复的行为面).
 * 这条路径的首次实际调用就发生在本类的构造器里.
*/
class MinimalFabricModMetadataTest {

    private static final String MOD_ID = "testmod";

/** 一个最小 IModFile 替身,可挂/不挂元数据扩展. */
    private static final class FakeModFile implements IModFile {

        private final dev.multiloader.api.metadata.ModMetadata unified;
        private final IModFileMetadata extras;

        FakeModFile(dev.multiloader.api.metadata.ModMetadata unified, IModFileMetadata extras) {
            this.unified = unified;
            this.extras = extras;
        }

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
            return List.of(unified);
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
            return List.of();
        }

        @Override
        public List<Path> getNestedLibraries() {
            return List.of();
        }

        @Override
        public <T extends IModFileExtension> Optional<T> getExtension(Class<T> extensionType) {
            return extensionType.isInstance(extras)
                    ? Optional.of(extensionType.cast(extras))
                    : Optional.empty();
        }
    }

    private static dev.multiloader.api.metadata.ModMetadata unified(String version,
            Environment environment, String license) {
        return new dev.multiloader.api.metadata.ModMetadata(MOD_ID, version, "Test Mod",
                "desc", List.of("Alice", "Bob"), license, Map.of("homepage", "https://x.invalid"),
                environment);
    }

/** 一个实现了 IModFileMetadata 的最小扩展. */
    private static IModFileMetadata extras() {
        return new IModFileMetadata() {
            @Override
            public Set<String> provides() {
                return Set.of("virtual-mod");
            }

            @Override
            public List<String> contributors() {
                return List.of("Carol");
            }

            @Override
            public Optional<String> iconPath(int size) {
                return Optional.of("icon-" + size + ".png");
            }

            @Override
            public Map<String, Object> customValues() {
                return Map.of("k1", "v1", "k2", true);
            }
        };
    }

    private static MinimalFabricModMetadata metadata(IModFileMetadata extras) {
        return new MinimalFabricModMetadata(
                new FakeModFile(unified("1.2.3", Environment.BOTH, "MIT"), extras));
    }

    // 来自统一模型

    @Test
    void unifiedFieldsMapStraightThrough() {
        var meta = metadata(extras());

        assertEquals("fabric", meta.getType(), "getType 返回格式名（不编 javafml）");
        assertEquals(MOD_ID, meta.getId());
        assertEquals("Test Mod", meta.getName());
        assertEquals("desc", meta.getDescription());
    }

    @Test
    void versionIsParsedByFabric() {
        assertEquals("1.2.3", metadata(extras()).getVersion().getFriendlyString());
    }

    @Test
    void permissiveParserAcceptsNonVersionStrings() {
        // **实测推翻了我的假设**:我原以为 "${file.jarVersion}" 这类串会让
        // Fabric 的版本解析失败,从而走到回退分支.实际上 Fabric 的解析器非常宽松,
        // 它把这类串当普通的版本字符串接受,**不抛异常**.
        // 所以 getVersion() 里的回退分支是**防御性**的(现实中很难触发),
        // 而这条测试记录的是真实行为:不抛,且拿得到非 null 的版本对象.
        // 这条测试的价值不在于它断言了什么,而在于它把"解析器有多宽松"这件事
        // 固定了下来  否则后人会像我一样假设"奇怪串一定失败",并据此写出
        // 永远不会执行的错误处理,或误以为回退分支已被覆盖.
        var meta = new MinimalFabricModMetadata(
                new FakeModFile(unified("${file.jarVersion}", Environment.BOTH, "MIT"), extras()));

        var version = assertDoesNotThrow(meta::getVersion);
        assertNotNull(version);
    }

    @Test
    void authorsBecomeFabricPersons() {
        Collection<Person> authors = metadata(extras()).getAuthors();

        assertEquals(List.of("Alice", "Bob"),
                authors.stream().map(Person::getName).toList());
    }

    @Test
    void blankLicenseBecomesEmptyCollection() {
        // [" "] 这种会在展示层变成空条目,所以空白串要当"没有"处理.
        assertTrue(new MinimalFabricModMetadata(
                new FakeModFile(unified("1.0.0", Environment.BOTH, "  "), extras()))
                .getLicense().isEmpty());
        assertEquals(List.of("MIT"), metadata(extras()).getLicense());
    }

    @Test
    void environmentMapsBothToUniversal() {
        // BOTH -> UNIVERSAL 不是降级,是同一语义的两种叫法.
        assertEquals(ModEnvironment.UNIVERSAL, metadata(extras()).getEnvironment());
        assertEquals(ModEnvironment.CLIENT, new MinimalFabricModMetadata(
                new FakeModFile(unified("1.0.0", Environment.CLIENT, "MIT"), extras()))
                .getEnvironment());
        assertEquals(ModEnvironment.SERVER, new MinimalFabricModMetadata(
                new FakeModFile(unified("1.0.0", Environment.SERVER, "MIT"), extras()))
                .getEnvironment());
    }

    @Test
    void contactComesFromUnifiedMap() {
        assertEquals("https://x.invalid", metadata(extras()).getContact().get("homepage").orElseThrow());
    }

    // 来自 IModFileMetadata(**这条同时验证查找路径修复**)

    @Test
    void extrasAreReachableThroughTheSharedInterface() {
        var meta = metadata(extras());

        assertEquals(Set.of("virtual-mod"), Set.copyOf(meta.getProvides()));
        assertEquals(List.of("Carol"),
                meta.getContributors().stream().map(Person::getName).toList());
        assertEquals("icon-32.png", meta.getIconPath(32).orElseThrow());
    }

    @Test
    void missingExtrasDegradeToEmpty() {
        // 非 Fabric 格式(或未实现该接口的适配器)也会走到这里,
        // "没有 provides/contributors/icon"对它们是**正确语义**,不是错误.
        var meta = metadata(null);

        assertTrue(meta.getProvides().isEmpty());
        assertTrue(meta.getContributors().isEmpty());
        assertEquals(Optional.empty(), meta.getIconPath(32));
    }

    // 拒绝:两类理由必须不同

    @Test
    void dependenciesRejectionNamesTheMissingTypes() {
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                () -> metadata(extras()).getDependencies());

        assertTrue(e.getMessage().contains("VersionPredicate"), e.getMessage());
        assertTrue(e.getMessage().contains("VersionInterval"), e.getMessage());
    }

    @Test
    void customValuesAreServedFromThePlainValueSource() {
        // 增量 1b 后,这一族不再是拒绝而是真实实现:数据来自
        // IModFileMetadata.customValues() 的纯 JDK 值,经 FabricCustomValue 适配.
        var meta = metadata(extras());   // extras 提供 {"k1":"v1", "k2":true}

        assertTrue(meta.containsCustomValue("k1"));
        assertTrue(meta.containsCustomElement("k2"));
        assertFalse(meta.containsCustomValue("absent"));

        assertEquals("v1", meta.getCustomValue("k1").getAsString());
        assertEquals(CustomValue.CvType.BOOLEAN, meta.getCustomValue("k2").getType());
        assertTrue(meta.getCustomValue("k2").getAsBoolean());
        assertEquals(2, meta.getCustomValues().size());
    }

    @Test
    void missingCustomValueKeyIsLoudAndListsAvailableKeys() {
        // 缺键抛异常而不是返回 null:调用方通常直接对返回值调 getAsString(),
        // 返回 null 会让 NPE 落在调用方那一行,归因指向调用方而不是"键不存在".
        NoSuchElementException e = assertThrows(NoSuchElementException.class,
                () -> metadata(extras()).getCustomValue("absent"));

        assertTrue(e.getMessage().contains("k1"), "信息里应列出可用键: " + e.getMessage());
    }

    @Test
    void missingExtrasMakeEveryCustomValueAbsentButNotFatal() {
        // 扩展缺失时这一族退化为"什么都没有",而不是抛异常 
        // 与 provides/contributors 的降级语义一致.
        var meta = metadata(null);

        assertFalse(meta.containsCustomValue("k1"));
        assertTrue(meta.getCustomValues().isEmpty());
    }

    @Test
    void toStringIsDiagnostic() {
        // toString 会被打进日志与错误信息,必须能定位到是哪个 mod.
        String text = metadata(extras()).toString();
        assertNotNull(text);
        assertTrue(text.contains(MOD_ID), text);
    }
}
