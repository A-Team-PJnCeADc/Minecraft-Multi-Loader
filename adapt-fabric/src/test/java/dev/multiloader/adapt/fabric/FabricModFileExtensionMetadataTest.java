package dev.multiloader.adapt.fabric;

import dev.multiloader.api.metadata.IModFileMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FabricModFileExtension} 作为 {@link IModFileMetadata} 的行为测试.
 * <p>重点测 {@code iconPath(size)} 的**三级择优**它是这一步里唯一有分支逻辑的地方,
 * 也是唯一"写错了不会报错,只会显示错图标/没图标"的地方.
 * 其余三个方法(provides / contributors / customValues)是直通,只验证透传与不可变.
 * <p>刻意直接构造 extension 而不走 reader + 真 jar:这里要测的是**选择语义**,
 * 不是 JSON 解析.解析那层的正确性由 reader 的既有测试覆盖(fabric.mod.json 的
 * 各字段形态).两层分开测的理由与之前一样:解析对了但选择错了,两边单测都会绿.
*/
class FabricModFileExtensionMetadataTest {

    private static FabricModFileExtension extension(
            List<String> contributors, Map<Integer, String> icons, Map<String, Object> custom) {
        return new FabricModFileExtension(1, null, Map.of(), Set.of("provided-mod"),
                List.of(), List.of(), contributors, icons, custom);
    }

    //
    // iconPath:三级择优
    //

    @Test
    void exactSizeWins() {
        var ext = extension(List.of(), Map.of(16, "a.png", 32, "b.png", 128, "c.png"), Map.of());

        assertEquals("b.png", ext.iconPath(32).orElseThrow());
    }

    @Test
    void singleStringIconFormServesEverySize() {
        // "icon": "icon.png" 的形态  用 ANY_SIZE(0) 哨兵键表示"任意尺寸".
        // 这是更常见的写法,只处理对象形态会让这类 mod 静默没有图标.
        var ext = extension(List.of(), Map.of(0, "icon.png"), Map.of());

        assertEquals("icon.png", ext.iconPath(16).orElseThrow());
        assertEquals("icon.png", ext.iconPath(32).orElseThrow());
        assertEquals("icon.png", ext.iconPath(256).orElseThrow());
    }

    @Test
    void exactBeatsAnySizeSentinel() {
        // 两种形态混用时,精确尺寸优先于哨兵.
        var ext = extension(List.of(), Map.of(0, "icon.png", 32, "big.png"), Map.of());

        assertEquals("big.png", ext.iconPath(32).orElseThrow(), "精确 > 任意尺寸");
        assertEquals("icon.png", ext.iconPath(64).orElseThrow(), "无精确时回落到任意尺寸");
    }

    @Test
    void nearestSizeIsUsedWhenNothingMatchesExactly() {
        // 第 3 级:mod 常只给 16 与 128 两档,而调用方问 32.
        // 没有这一级的话,"有图标但尺寸不齐"的 mod 会显示成没有图标.
        var ext = extension(List.of(), Map.of(16, "small.png", 128, "large.png"), Map.of());

        assertEquals("small.png", ext.iconPath(32).orElseThrow(), "|32-16|=16 < |128-32|=96");
        assertEquals("large.png", ext.iconPath(100).orElseThrow(), "|100-128|=28 < |100-16|=84");
    }

    @Test
    void noIconAtAllIsEmptyOptionalNotNull() {
        assertEquals(Optional.empty(), extension(List.of(), Map.of(), Map.of()).iconPath(16));
    }

    //
    // 其余三个方法:透传 + 不可变
    //

    @Test
    void providesAndContributorsPassThrough() {
        var ext = extension(List.of("Alice", "Bob"), Map.of(), Map.of());

        assertEquals(Set.of("provided-mod"), ext.provides());
        assertEquals(List.of("Alice", "Bob"), ext.contributors());
    }

    @Test
    void customValuesPassThroughAndAreImmutable() {
        var ext = extension(List.of(), Map.of(), Map.of("key", "value"));

        assertEquals(Map.of("key", "value"), ext.customValues());
        // 不可变:调用方拿到的若是可变视图,一处误改会污染所有读者的元数据.
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> ext.customValues().put("injected", "x"));
    }

    @Test
    void extensionIsUsableThroughTheSharedInterface() {
        // 桥接层只会看到 IModFileMetadata(它不认识 adapt-fabric 的类型).
        // 这条断言把"实现确实挂在这个接口上"钉住  漏了 implements 子句时,
        // 桥接侧会取不到扩展,而其它测试全绿.
        IModFileMetadata asShared = extension(List.of("C"), Map.of(0, "i.png"), Map.of("k", 1));

        assertInstanceOf(FabricModFileExtension.class, asShared);
        assertEquals(List.of("C"), asShared.contributors());
        assertTrue(asShared.iconPath(8).isPresent());
    }
}
