package dev.multiloader.bridge.fabric.meta;

import dev.multiloader.common.Log;
import net.fabricmc.loader.api.metadata.CustomValue;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 {@code IModFileMetadata.customValues()} 的**纯 JDK 值**适配成 Fabric 的
 * {@link CustomValue}.
 * <p><b>为什么不复用上游的 {@code CustomValueImpl}</b>:它是**包私有**的
 * ({@code abstract class},无 {@code public}),桥接层无法引用;而且它的工厂
 * {@code readCustomValue(JsonReader)} 只接受 gson 的流式 reader,而我们的数据
 * 已经解析完毕  要用它就得先把 JDK 值反向装回 {@code JsonElement},
 * 绕一圈还多一层类型转换.
 * <p><b>类型映射是一一对应的</b>,不含任何启发式判断:
 * <pre>
 *   String              ->STRING
 *   Boolean             ->BOOLEAN
 *   Number              ->NUMBER
 *   List&lt;Object&gt;        ->ARRAY
 *   Map&lt;String,Object&gt;  ->OBJECT
 *   null                ->NULL
 * </pre>
 * <p><b>{@code getAs*()} 遇到类型不符时抛异常而不是尽力转换</b>:Fabric 的约定是
 * "按类型取值",调用方先看 {@code getType()} 再取.若我们"贴心"地把数字转成字符串,
 * 调用方会因为拿到看似合理的结果而不再检查类型  后面的逻辑偏差就无从归因.
*/
public abstract class FabricCustomValue implements CustomValue {

/** 从纯 JDK 值构造.{@code null} 是合法的(对应 {@link CvType#NULL}). */
    public static CustomValue of(Object plain) {
        if (plain == null) {
            return new NullValue();
        }
        if (plain instanceof String s) {
            return new StringValue(s);
        }
        if (plain instanceof Boolean b) {
            return new BooleanValue(b);
        }
        if (plain instanceof Number n) {
            return new NumberValue(n);
        }
        if (plain instanceof List<?> list) {
            return new ArrayValue(list.stream().map(FabricCustomValue::of).toList());
        }
        if (plain instanceof Map<?, ?> map) {
            Map<String, CustomValue> entries = new LinkedHashMap<>();
            map.forEach((key, value) ->
                    entries.put(String.valueOf(key), FabricCustomValue.of(value)));
            return new ObjectValue(entries);
        }
        // 未知类型说明上游的 jsonToPlain 增加了新形状,而这里没跟上.
        // 明确报出来,而不是硬塞成 STRING(那会让调用方拿到错误类型的结果).
        // TODO(bridge): [非法输入] 前置条件 上游若新增值形状,这里需要同步扩展
        String message = "IModFileMetadata.customValues() 出现了未知的值类型："
                + plain.getClass().getName() + "本适配器需要同步更新";
        Log.warn("{}", message);
        throw new UnsupportedOperationException(message);
    }

/**
     * 类型不符时的统一失败信息,指明实际类型.
     * <p><b>类别是 {@code [非法输入]} 而不是 {@code [未实现机制]}</b>:调用方按错误的
     * 类型取值是**用法错误**,不是"桥接层还没做这件事".两者混为一谈会让 TO DO 清单
     * 虚增,也会让读者以为"等某个前置就能用了"实际上这个调用**永远**不该成功.
     * <p>因此这里**不**指向 {@code docs/02 §6 本期范围}(那是"本期未做"清单),
     * 而是说明契约本身.
     * <p>这是**辅助方法**:被多个 {@code getAs*} 共用,所以标记只有这一处.
*/
    protected UnsupportedOperationException notOfType(String requested) {
        // TODO(bridge): [非法输入] 前置条件
        String message = "CustomValue." + requested + " 输入不合法：实际类型是 " + getType()
                + "，不能按 " + requested + " 取用（Fabric 的约定是先看 getType() 再取值）";
        Log.warn("{}", message);
        return new UnsupportedOperationException(message);
    }

    @Override
    public CvObject getAsObject() {
        throw notOfType("object");
    }

    @Override
    public CvArray getAsArray() {
        throw notOfType("array");
    }

    @Override
    public String getAsString() {
        throw notOfType("string");
    }

    @Override
    public Number getAsNumber() {
        throw notOfType("number");
    }

    @Override
    public boolean getAsBoolean() {
        throw notOfType("boolean");
    }

    @Override
    public String toString() {
        return getType() + "(" + describe() + ")";
    }

/** 供 toString 使用的简短值描述. */
    protected abstract String describe();

    //
    // 具体类型
    //

    private static final class StringValue extends FabricCustomValue {
        private final String value;

        StringValue(String value) {
            this.value = value;
        }

        @Override
        public CvType getType() {
            return CvType.STRING;
        }

        @Override
        public String getAsString() {
            return value;
        }

        @Override
        protected String describe() {
            return value;
        }
    }

    private static final class BooleanValue extends FabricCustomValue {
        private final boolean value;

        BooleanValue(boolean value) {
            this.value = value;
        }

        @Override
        public CvType getType() {
            return CvType.BOOLEAN;
        }

        @Override
        public boolean getAsBoolean() {
            return value;
        }

        @Override
        protected String describe() {
            return Boolean.toString(value);
        }
    }

    private static final class NumberValue extends FabricCustomValue {
        private final Number value;

        NumberValue(Number value) {
            this.value = value;
        }

        @Override
        public CvType getType() {
            return CvType.NUMBER;
        }

        @Override
        public Number getAsNumber() {
            return value;
        }

        @Override
        protected String describe() {
            return value.toString();
        }
    }

    private static final class NullValue extends FabricCustomValue {
        @Override
        public CvType getType() {
            return CvType.NULL;
        }

        @Override
        protected String describe() {
            return "null";
        }
    }

    private static final class ObjectValue extends FabricCustomValue implements CvObject {
        private final Map<String, CustomValue> entries;

        ObjectValue(Map<String, CustomValue> entries) {
            this.entries = Map.copyOf(entries);
        }

        @Override
        public CvType getType() {
            return CvType.OBJECT;
        }

        @Override
        public CvObject getAsObject() {
            return this;
        }

        @Override
        public int size() {
            return entries.size();
        }

        @Override
        public boolean containsKey(String key) {
            return entries.containsKey(key);
        }

        @Override
        public CustomValue get(String key) {
            // 与 Fabric 的约定一致:缺键返回 NULL 值而不是 null 引用 
            // 返回 null 会让调用方在链式取值时 NPE,而归因指向调用方.
            CustomValue value = entries.get(key);
            return value != null ? value : new NullValue();
        }

        @Override
        public Iterator<Map.Entry<String, CustomValue>> iterator() {
            return List.copyOf(entries.entrySet()).iterator();
        }

        @Override
        protected String describe() {
            return size() + " keys";
        }
    }

    private static final class ArrayValue extends FabricCustomValue implements CvArray {
        private final List<CustomValue> entries;

        ArrayValue(List<CustomValue> entries) {
            this.entries = List.copyOf(entries);
        }

        @Override
        public CvType getType() {
            return CvType.ARRAY;
        }

        @Override
        public CvArray getAsArray() {
            return this;
        }

        @Override
        public int size() {
            return entries.size();
        }

        @Override
        public CustomValue get(int index) {
            // 越界返回 NULL 而不是抛 IndexOutOfBounds:
            // 与 "缺键返回 NULL" 保持同一套语义,调用方只需判类型,不必再包一层边界检查.
            if (index < 0 || index >= entries.size()) {
                return new NullValue();
            }
            return entries.get(index);
        }

        @Override
        public Iterator<CustomValue> iterator() {
            return entries.iterator();
        }

        @Override
        protected String describe() {
            return size() + " items";
        }
    }
}
