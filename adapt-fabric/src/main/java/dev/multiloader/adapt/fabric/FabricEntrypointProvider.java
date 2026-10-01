package dev.multiloader.adapt.fabric;

import dev.multiloader.api.lifecycle.EntrypointRef;
import dev.multiloader.api.lifecycle.IEntrypointProvider;
import dev.multiloader.api.lifecycle.LifecyclePhase;
import dev.multiloader.api.locating.IModFile;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 把 {@code fabric.mod.json} 的 entrypoints 翻译成统一的 {@link EntrypointRef}.
 * <p>本类是"Fabric 语义"的落点:命名空间 ->(生命周期阶段, 方法名) 的对应关系
 * 是 Fabric 的知识,因此留在这里而不是核心层.<b>核心层只认 EntrypointRef.</b>
 * <p>{@code EntrypointRef} 里存方法名而不是接口名,就避免了核心层
 * {@code instanceof net.fabricmc.api.ModInitializer}  那会让 INV-2
 * (核心层无 net.fabricmc.* 符号引用)失效,也会把加载器的版本兼容性
 * 焊死在 fabric-loader 上.
*/
public final class FabricEntrypointProvider implements IEntrypointProvider {

/**
     * 入口点命名空间 ->要调用的无参方法名.
     * <p>注意四个命名空间的方法名**各不相同**:Fabric 为每种用途定义了不同的接口
     * ({@code ModInitializer#onInitialize},{@code ClientModInitializer#onInitializeClient}...).
     * 这正是方法名必须逐条存在 ref 里,不能硬编码的原因.
*/
    private static final Map<String, String> METHOD_BY_NAMESPACE = Map.of(
            "main", "onInitialize",
            "client", "onInitializeClient",
            "server", "onInitializeServer",
            "preLaunch", "onPreLaunch");

    @Override
    public Map<LifecyclePhase, List<EntrypointRef>> getEntrypoints(IModFile file) {
        Map<LifecyclePhase, List<EntrypointRef>> byPhase = new EnumMap<>(LifecyclePhase.class);

        FabricModFileExtension extension = file.getExtension(FabricModFileExtension.class).orElse(null);
        if (extension == null) {
            return byPhase;
        }

        String modId = file.getPrimaryModId();

        for (FabricModFileExtension.Entrypoint entrypoint : extension.entrypoints()) {
            String methodName = METHOD_BY_NAMESPACE.get(entrypoint.namespace());
            if (methodName == null) {
                // 解析阶段已经拒绝过未知命名空间,这里只是防御:
                // 静默跳过会让 mod 的初始化代码永远不被调用,且没有任何线索.
                throw new IllegalStateException("no method mapping for entrypoint namespace '"
                        + entrypoint.namespace() + "' in " + file.getFilePath());
            }

            byPhase.computeIfAbsent(entrypoint.phase(), phase -> new ArrayList<>())
                    .add(EntrypointRef.noArgMethod(modId, entrypoint.phase(),
                            entrypoint.className(), methodName, entrypoint.environment(),
                            // 原始 entrypoint key(main / client / server)随 Ref 一起带走.
                            // 此前它只用于"key -> (phase, methodName)"的映射,映射做完就被丢弃,
                            // 于是 IEntrypointQuery 无法按 key 查询(只能按 phase,而那是**另一个键**).
                            // 现在把它原样保留:查询按 key,不需要任何 phase 兜底映射.
                            entrypoint.namespace()));
        }

        // 冻结:调用方拿到的是不可变视图,避免下游误改
        Map<LifecyclePhase, List<EntrypointRef>> frozen = new EnumMap<>(LifecyclePhase.class);
        byPhase.forEach((phase, refs) -> frozen.put(phase, List.copyOf(refs)));
        return frozen;
    }
}
