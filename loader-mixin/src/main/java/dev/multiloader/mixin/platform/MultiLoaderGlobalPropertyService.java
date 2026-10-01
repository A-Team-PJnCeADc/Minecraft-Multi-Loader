package dev.multiloader.mixin.platform;

import org.spongepowered.asm.service.IGlobalPropertyService;
import org.spongepowered.asm.service.IPropertyKey;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mixin 的全局属性服务.
 * <p><b>为什么必须自己提供一个:</b>Mixin 内部某个早期步骤
 * ({@code MixinBootstrap.isSubsystemRegistered()} ->{@code GlobalProperties.get()})
 * 就要用到这个 SPI,而且它是**唯一没有系统属性绕过**的 SPI 
 * {@code MixinService} 对 {@code IMixinServiceBootstrap} 和 {@code IMixinService}
 * 都支持 {@code mixin.bootstrapService}/{@code mixin.service} 属性指定,
 * 但对 {@code IGlobalPropertyService} 只走 ServiceLoader.
 * <p>而 sponge-mixin 自带的两个实现分别是 launchwrapper 版和 modlauncher 版
 * ({@code service.mojang.Blackboard} / {@code service.modlauncher.Blackboard}),
 * 它们都依赖我们这里不存在的启动器 API,实例化即失败 
 * 表现为 {@code ServiceNotAvailableError: No mixin global property service is available},
 * 而且这个错误发生在 Mixin 刚启动的瞬间,堆栈让人以为是 Mixin 自己坏了.
 * <p>实现就是一张 Map.{@code IPropertyKey} 是空标记接口,所以用 record 包一层名字即可.
*/
public class MultiLoaderGlobalPropertyService implements IGlobalPropertyService {

/** {@code IPropertyKey} 是空标记接口  名字就是全部信息. */
    private record Key(String name) implements IPropertyKey {
        @Override
        public String toString() {
            return name;
        }
    }

    private final Map<String, IPropertyKey> keys = new ConcurrentHashMap<>();
    private final Map<IPropertyKey, Object> properties = new ConcurrentHashMap<>();

    @Override
    public IPropertyKey resolveKey(String name) {
        // computeIfAbsent 保证同一个名字永远拿到同一个 key 实例 
        // Mixin 会拿 key 当 Map 的键,身份必须稳定
        return keys.computeIfAbsent(name, Key::new);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getProperty(IPropertyKey key) {
        return (T) properties.get(key);
    }

    @Override
    public void setProperty(IPropertyKey key, Object value) {
        properties.put(key, value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getProperty(IPropertyKey key, T defaultValue) {
        Object value = properties.get(key);
        return value == null ? defaultValue : (T) value;
    }

    @Override
    public String getPropertyString(IPropertyKey key, String defaultValue) {
        Object value = properties.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }
}
