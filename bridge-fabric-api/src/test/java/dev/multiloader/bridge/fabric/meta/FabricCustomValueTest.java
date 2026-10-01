package dev.multiloader.bridge.fabric.meta;

import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.CustomValue.CvArray;
import net.fabricmc.loader.api.metadata.CustomValue.CvObject;
import net.fabricmc.loader.api.metadata.CustomValue.CvType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FabricCustomValue} 的适配测试.
 * <p>覆盖重点是**嵌套结构**与**类型分派**适配器里最可能有 bug 的两处,
 * 也是上一轮 {@code MinimalFabricModMetadataTest} 只经源 Map 间接碰到的部分
 * (那里只走过 STRING 与 BOOLEAN).
*/
class FabricCustomValueTest {

    // 标量:6 个 CvType 与 JDK 形状的一一对应

    @Test
    void scalarsMapOneToOne() {
        assertEquals(CvType.STRING, FabricCustomValue.of("s").getType());
        assertEquals(CvType.BOOLEAN, FabricCustomValue.of(true).getType());
        assertEquals(CvType.NUMBER, FabricCustomValue.of(42).getType());
        assertEquals(CvType.NULL, FabricCustomValue.of(null).getType());

        assertEquals("s", FabricCustomValue.of("s").getAsString());
        assertTrue(FabricCustomValue.of(true).getAsBoolean());
        assertEquals(42, FabricCustomValue.of(42).getAsNumber().intValue());
    }

    @Test
    void numberKeepsItsOriginalNumericKind() {
        // 不把 1.5 变成 1,也不把 long 变成 int:调用方可能依赖数值精度.
        assertEquals(1.5d, FabricCustomValue.of(1.5d).getAsNumber().doubleValue());
        assertEquals(9_000_000_000L, FabricCustomValue.of(9_000_000_000L).getAsNumber().longValue());
    }

    @Test
    void typeMismatchThrowsAndNamesTheActualType() {
        // 不做"尽力转换":若把 NUMBER 贴心转成字符串,调用方会因拿到看似合理的
        // 结果而不再检查类型,后续偏差无从归因.
        var number = FabricCustomValue.of(42);

        UnsupportedOperationException e =
                assertThrows(UnsupportedOperationException.class, number::getAsString);
        assertTrue(e.getMessage().contains("NUMBER"), e.getMessage());
        assertTrue(e.getMessage().contains("string"), e.getMessage());
    }

    @Test
    void unknownJdkShapeIsLoudNotSilentlyStringified() {
        // 上游 jsonToPlain 若增加了新形状而这里没跟上,必须响  硬塞成 STRING
        // 会让调用方拿到错误类型的结果.
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                () -> FabricCustomValue.of(new Object()));

        assertTrue(e.getMessage().contains("同步更新"), e.getMessage());
    }

    // ARRAY

    @Test
    void arraySupportsSizeIndexAndIteration() {
        CustomValue value = FabricCustomValue.of(List.of("a", "b", "c"));

        assertEquals(CvType.ARRAY, value.getType());
        CvArray array = value.getAsArray();
        assertEquals(3, array.size());
        assertEquals("a", array.get(0).getAsString());
        assertEquals("c", array.get(2).getAsString());

        Iterator<CustomValue> it = array.iterator();
        List<String> seen = new ArrayList<>();
        while (it.hasNext()) {
            seen.add(it.next().getAsString());
        }
        assertEquals(List.of("a", "b", "c"), seen);
    }

    @Test
    void outOfRangeArrayIndexYieldsNullValueNotException() {
        // 与 OBJECT 缺键同一套语义:调用方只需判类型,不必再包一层边界检查.
        CvArray array = FabricCustomValue.of(List.of("only")).getAsArray();

        assertEquals(CvType.NULL, array.get(5).getType());
        assertEquals(CvType.NULL, array.get(-1).getType());
    }

    @Test
    void emptyArrayIsValid() {
        CvArray array = FabricCustomValue.of(List.of()).getAsArray();

        assertEquals(0, array.size());
        assertFalse(array.iterator().hasNext());
    }

    // OBJECT

    @Test
    void objectSupportsSizeContainsAndGet() {
        CustomValue value = FabricCustomValue.of(Map.of("k1", "v1", "k2", 7));

        assertEquals(CvType.OBJECT, value.getType());
        CvObject object = value.getAsObject();
        assertEquals(2, object.size());
        assertTrue(object.containsKey("k1"));
        assertFalse(object.containsKey("absent"));
        assertEquals("v1", object.get("k1").getAsString());
        assertEquals(7, object.get("k2").getAsNumber().intValue());
    }

    @Test
    void missingObjectKeyYieldsNullValueNotNullReference() {
        // 返回 null 引用会让链式取值 NPE 在调用方,而归因指向调用方.
        CvObject object = FabricCustomValue.of(Map.of("k", "v")).getAsObject();

        assertEquals(CvType.NULL, object.get("absent").getType());
    }

    @Test
    void objectIterationExposesEntries() {
        CvObject object = FabricCustomValue.of(Map.of("a", 1, "b", 2)).getAsObject();

        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, CustomValue> entry : object) {
            keys.add(entry.getKey());
        }
        assertEquals(List.of("a", "b"), keys.stream().sorted().toList());
    }

    // 嵌套:这是适配器最可能出错的地方

    @Test
    void nestedStructuresSurviveRoundTrip() {
        // 结构: { "list": [ 1, {"inner": true} ], "flag": false }
        Map<String, Object> nested = Map.of(
                "list", List.of(1, Map.of("inner", true)),
                "flag", false);

        CvObject root = FabricCustomValue.of(nested).getAsObject();

        assertEquals(2, root.size());
        assertFalse(root.get("flag").getAsBoolean());

        CvArray list = root.get("list").getAsArray();
        assertEquals(2, list.size());
        assertEquals(1, list.get(0).getAsNumber().intValue());

        CvObject inner = list.get(1).getAsObject();
        assertTrue(inner.get("inner").getAsBoolean());
    }

    @Test
    void deepNullInsideNestedArrayIsPreserved() {
        // JSON 里 [1, null, 2] 合法(jsonToPlain 用 unmodifiableList 而非
        // List.copyOf 正是为此).这里的 null 必须变成 NULL 值而不是丢失.
        CvArray array = FabricCustomValue.of(java.util.Arrays.asList(1, null, 2)).getAsArray();

        assertEquals(3, array.size());
        assertEquals(CvType.NUMBER, array.get(0).getType());
        assertEquals(CvType.NULL, array.get(1).getType());
        assertEquals(CvType.NUMBER, array.get(2).getType());
    }

    @Test
    void mappingIsImmutableAgainstSourceMutation() {
        // 适配后改动源结构不得影响已构造的值对象.
        Map<String, Object> source = new java.util.HashMap<>();
        source.put("k", "v");
        CvObject object = FabricCustomValue.of(source).getAsObject();

        source.put("later", "x");

        assertEquals(1, object.size());
        assertFalse(object.containsKey("later"));
    }
}
