package dev.multiloader.bridge.neoforge;

import dev.multiloader.common.Log;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.IExtensionPoint;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@link ModContainer} 的最小实现(设计见 {@code docs/02-bridge-design.md}).
 * <p><b>为什么是"子类化"而不是"实现接口"</b>:NeoForge 的 {@code ModContainer}
 * 是**抽象类**(不是接口),构造器要求一个 {@link net.neoforged.neoforgespi.language.IModInfo}.
 * 这一点与最初"3 个方法的接口"的预期不同,实际代价主要落在
 * {@link MinimalModInfo}(15 个方法)上  那条链已单独完成并测试.
 * <p><b>哪些方法不需要覆写</b>:父类的 {@code getModId()} / {@code getNamespace()}
 * 是 {@code final},它们读构造器写入的字段(值来自我们传入的 {@code IModInfo});
 * {@code acceptEvent} 两个重载同样是 {@code final}.所以"让 {@code getModId()} 返回
 * 正确的值"这件事**靠的是传入正确的 IModInfo**,而不是在这里覆写 
 * 试图覆写会被编译器拒绝.
*/
public final class MinimalModContainer extends ModContainer {

    private final NeoForgeEventBus eventBus;

/**
     * @param modInfo 由本加载器已解析的 mods.toml 元数据构造
     * @param eventBus 本桥接的事件总线;{@code @Mod} 构造器注入的就是它
*/
    public MinimalModContainer(MinimalModInfo modInfo, NeoForgeEventBus eventBus) {
        super(modInfo);
        this.eventBus = eventBus;
    }

/** {@code @Mod} 类构造器注入的那个总线. */
    @Override
    public IEventBus getEventBus() {
        return eventBus;
    }

    // 需要真实 NeoForge 子系统才有意义的方法:明确拒绝 

    @Override
    public <T extends IExtensionPoint> Optional<T> getCustomExtension(Class<T> extensionPoint) {
        // 返回 empty 看起来更"安全",但那会与"确实没有注册"无法区分:
        // 调用方会以为自己没注册过,而真相是**这个能力不存在**.
        // 两类情况的处理方式不同(前者继续跑,后者要报错),所以必须区分.
        // TODO(bridge): [未实现机制] 前置条件 
        return reject("getCustomExtension", "IExtensionPoint 体系尚未桥接");
    }

    @Override
    public <T extends IExtensionPoint> void registerExtensionPoint(Class<T> extensionPoint,
            T implementation) {
        // TODO(bridge): [未实现机制] 前置条件 
        reject("registerExtensionPoint", "IExtensionPoint 体系尚未桥接");
    }

    @Override
    public <T extends IExtensionPoint> void registerExtensionPoint(Class<T> extensionPoint,
            Supplier<T> implementationSupplier) {
        // TODO(bridge): [未实现机制] 前置条件 
        reject("registerExtensionPoint", "IExtensionPoint 体系尚未桥接");
    }

    @Override
    public void registerConfig(ModConfig.Type type, IConfigSpec spec) {
        // TODO(bridge): [未实现机制] 前置条件 
        reject("registerConfig", "NeoForge 的配置系统尚未桥接（涉及 TOML 配置规格与加载时机）");
    }

    @Override
    public void registerConfig(ModConfig.Type type, IConfigSpec spec, String fileName) {
        // TODO(bridge): [未实现机制] 前置条件 
        reject("registerConfig", "NeoForge 的配置系统尚未桥接（涉及 TOML 配置规格与加载时机）");
    }

/**
     * 拒绝并在日志留痕.
     * <p>为什么抛异常而不是静默返回:这些方法被 mod 在 {@code @Mod} 构造器里调用.
     * 静默通过会让 mod 以为"配置注册好了",然后在整个运行期表现为配置不生效 
     * 那种症状会被归因到 mod 自己或配置文件,而不会想到是桥接层没实现.
     * <p>日志与异常**两个都要**:异常可能被上层 catch 掉(mod 自己的 try/catch),
     * 而"桥接层缺这个能力"这件事必须在日志里留下痕迹.
     * <p><b>解锁某个能力时</b>:删掉该方法上方的 {@code // TO DO(bridge):}
     * 把它改为真实实现,并在 commit message 里写
     * {@code unlock: <类别> <方法名>}  这样"哪个能力在哪次提交"可追溯,
*/
    private static <T> T reject(String method, String reason) {
        String message = "MinimalModContainer." + method
                + " 尚未支持：" + reason + "（见 docs/02-bridge-design.md §6 本期范围）";
        Log.warn("{}", message);
        throw new UnsupportedOperationException(message);
    }

    @Override
    public String toString() {
        return "MinimalModContainer[" + getModId() + "]";
    }
}
