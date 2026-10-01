package dev.multiloader.bridge.fabric.meta;

import net.fabricmc.loader.api.metadata.ContactInformation;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * {@link ContactInformation} 的映射实现:一个不可变的字符串表.
 * <p>来源:统一模型的 {@code ModMetadata.contacts}({@code Map<String, String>},
 * 对应 {@code fabric.mod.json} 的 {@code contact} 段,如 homepage / sources / issues).
 * <p><b>为什么直接持有 Map 而不是拆成字段</b>:Fabric 的 contact 是一组**开放键**,
 * 不同 mod 写不同的键(有的写 discord,有的写 email).拆字段会把我们自己
 * 认识的键固定下来,其余静默丢失  而 {@code asMap()} 的调用方恰恰是要看全部.
*/
public final class FabricContactInformation implements ContactInformation {

/** 无联系方式的共享实例  绝大多数作者条目没有 contact,避免逐条 new. */
    public static final ContactInformation EMPTY = new FabricContactInformation(Map.of());

    private final Map<String, String> values;

    public FabricContactInformation(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(values.get(key));
    }

    @Override
    public Map<String, String> asMap() {
        // 已经是不可变副本,直接返回即可(Collections.unmodifiableMap 只是再包一层,
        // 反而让调用方多一层包装,且 equals 语义仍按内容判断).
        return Collections.unmodifiableMap(values);
    }

    @Override
    public String toString() {
        return "FabricContactInformation" + values;
    }
}
