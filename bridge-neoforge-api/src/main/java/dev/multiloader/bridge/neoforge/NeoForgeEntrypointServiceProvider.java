package dev.multiloader.bridge.neoforge;

import dev.multiloader.api.locating.IModFile;
import dev.multiloader.api.metadata.ModMetadata;
import dev.multiloader.api.service.IEntrypointServiceProvider;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 为 NeoForge 的 {@code @Mod} 构造器形参提供服务.
 * <p>按已定的设计({@code docs/02-bridge-design.md} §7):**全局 game bus**.
 * NeoForge 的真实语义就是全局 bus  多个 mod 共享一个 bus 不会互相干扰,
 * 因为注销按 owner 匹配({@code unregister(owner)}),不是按 bus 实例.
 * per-mod 隔离会引入不必要的隔离语义,还得处理父子关系与事件冒泡.
 * <p><b>但 {@code ModContainer} 必须按 mod 区分</b>:它的语义就是"某个 mod 的容器"
 * (modId / 版本 / 事件总线持有者).所以两者策略不同:
 * <pre>
 *   IEventBus      ->全局唯一实例(跨 mod 共享)
 *   ModContainer   ->按 modId 缓存
 * </pre>
 * <p><b>实例一致性是硬要求</b>:同一 mod 的 {@code @Mod} 构造器注入的 bus 与
 * {@code container.getEventBus()} 返回的,必须是**同一个对象**  否则 mod 在
 * 构造器里注册的监听器会进到一个没人用的总线,表现为"注册成功但永不触发".
 * 这正是 {@link MinimalModContainer} 的测试
 * {@code eventBusIsTheInjectedOne} 钉住的性质,本类必须保住它:
 * 两处都返回同一个 {@link #gameBus} 字段.
*/
public final class NeoForgeEntrypointServiceProvider implements IEntrypointServiceProvider {

    public static final String EVENT_BUS_FQN = "net.neoforged.bus.api.IEventBus";
    public static final String MOD_CONTAINER_FQN = "net.neoforged.fml.ModContainer";

/**
     * 全局唯一的 game bus.
     * <p>为什么在 provider 内部持有而不是从外部注入:本期没有"多个桥接实例"的
     * 场景,注入一个只会多出一个"谁来持有"的问题.等将来需要按加载器分实例时再改 
     * 那时改动点也只有这一处.
*/
    private final NeoForgeEventBus gameBus = new NeoForgeEventBus();

/** 按 modId 缓存的容器.用 computeIfAbsent 保证同一 mod 恒得同一实例. */
    private final Map<String, MinimalModContainer> containers = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "neoforge";
    }

    @Override
    public Set<String> providedFqns() {
        // 不可变集合(SPI 契约要求):调用方可能长期持有,且这是能力清单,
        // 被外部改动会让"报错里说的能力"与"实际判定"脱节.
        return Set.of(EVENT_BUS_FQN, MOD_CONTAINER_FQN);
    }

    @Override
    public Object provide(String parameterFqn, IModFile file) {
        return switch (parameterFqn) {
            case EVENT_BUS_FQN -> gameBus;
            case MOD_CONTAINER_FQN -> containerFor(file);
            // 不可达:调用方保证先过 canProvide.但**不能**返回 null 
            // 若哪天调用方改了顺序,这里静默返回 null 会让失败点跑到 mod 的构造器里,
            // 堆栈不指向"问错了 provider".
            default -> {
                // TODO(bridge): [非法输入] 前置条件 调用方必须先过 canProvide.
                // 这一条与 [未实现机制] 不同:这个调用**永远**不该成功,不是"等某个前置".
                String message = "NeoForgeEntrypointServiceProvider.provide 输入不合法：不能提供 "
                        + parameterFqn + "。它能提供的只有 " + providedFqns()
                        + "（调用方应先过 canProvide 判定）";
                dev.multiloader.common.Log.warn("{}", message);
                throw new UnsupportedOperationException(message);
            }
        };
    }

/** 全局 game bus  也供测试断言"注入的与容器返回的是同一个". */
    public NeoForgeEventBus gameBus() {
        return gameBus;
    }

    private MinimalModContainer containerFor(IModFile file) {
        if (file == null) {
            throw new IllegalArgumentException(
                    "ModContainer 需要知道属于哪个 mod，但收到的 file 为 null");
        }
        // getPrimaryModId() 与 provider 侧一致(容器代表该文件的主 mod).
        // 若将来要支持"一个文件含多个 mod 各自的容器",键要改成 (file, modId).
        String modId = file.getPrimaryModId();
        return containers.computeIfAbsent(modId, id -> new MinimalModContainer(modInfoFor(file, id), gameBus));
    }

/**
     * 从统一元数据构造 NeoForge 的 {@code IModInfo}.
     * <p><b>数据来源是 {@code ModMetadata}(loader-api 类型),不是
     * {@code NeoForgeModFileExtension.ModEntry}</b>:后者在 adapt-neoforge,
     * 而 INV 约束禁止 bridge 依赖 adapt.统一模型里这几个字段本来就有.
*/
    private static MinimalModInfo modInfoFor(IModFile file, String modId) {
        ModMetadata metadata = file.getMetadataList().stream()
                .filter(candidate -> candidate.modId().equals(modId))
                .findFirst()
                .orElseGet(file::getMetadata);
        return new MinimalModInfo(metadata.modId(), metadata.displayName(),
                metadata.description(), metadata.version());
    }
}
