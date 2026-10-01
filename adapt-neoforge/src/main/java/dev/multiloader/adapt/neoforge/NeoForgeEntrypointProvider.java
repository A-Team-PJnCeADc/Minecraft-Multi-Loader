package dev.multiloader.adapt.neoforge;

import dev.multiloader.api.lifecycle.EntrypointRef;
import dev.multiloader.api.lifecycle.IEntrypointProvider;
import dev.multiloader.api.lifecycle.LifecyclePhase;
import dev.multiloader.api.locating.IModFile;
import dev.multiloader.api.metadata.Environment;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 把扫描到的 {@code @Mod} 类翻译成统一的 {@link EntrypointRef}.
 * <p>与 {@code FabricEntrypointProvider} 同构:加载器专有的知识(这里是
 * {@code @Mod} 注解与构造器签名)留在这里,<b>核心层只认 {@code EntrypointRef}</b>.
 * <p>为什么必须扫字节码而不读元数据:{@code neoforge.mods.toml} **不声明**
 * {@code @Mod} 类名(见 {@link NeoForgeModClassScanner}).这是格式的事实.
*/
public final class NeoForgeEntrypointProvider implements IEntrypointProvider {

    @Override
    public Map<LifecyclePhase, List<EntrypointRef>> getEntrypoints(IModFile file) {
        Map<LifecyclePhase, List<EntrypointRef>> byPhase = new EnumMap<>(LifecyclePhase.class);

        NeoForgeModFileExtension extension = file.getExtension(NeoForgeModFileExtension.class)
                .orElse(null);
        if (extension == null) {
            return byPhase;
        }

        String fileModId = file.getPrimaryModId();

        for (NeoForgeModClassScanner.ModClass modClass : extension.modClasses()) {
            // modId 归属:@Mod 注解的 value 非空时以它为准.
            // 为什么不能一律用 file.getPrimaryModId():一个 neoforge.mods.toml 可以声明
            // **多个** [[mods]](见 2a 的 multipleModsEntriesProduceMultipleMetadata),
            // 而 primary 只是其中一个.若某个 @Mod 类属于非 primary 的那个 mod,
            // 一律用 primary 会把它的入口点归错 mod 
            // 表现为"mod B 的初始化被算成 mod A 的",且依赖求解/冲突检测都会跟着错.
            String modId = modClass.declaredModId().isEmpty() ? fileModId : modClass.declaredModId();

            List<String> parameters = modClass.primaryConstructor();
            EntrypointRef ref = parameters.isEmpty()
                    // 无参构造器形态:实例化即完成,调用契约与 NO_ARG_CONSTRUCTOR 一致.
                    ? EntrypointRef.noArgConstructor(modId, LifecyclePhase.CONSTRUCT,
                            modClass.className(), Environment.BOTH)
                    // 注入形态:把形参类型交给调度器,由 IEntrypointServiceProvider 解析.
                    : EntrypointRef.constructorWithServices(modId, LifecyclePhase.CONSTRUCT,
                            modClass.className(), parameters, Environment.BOTH);

            byPhase.computeIfAbsent(ref.phase(), phase -> new ArrayList<>()).add(ref);
        }

        // 冻结:调用方拿到不可变视图,避免下游误改
        Map<LifecyclePhase, List<EntrypointRef>> frozen = new EnumMap<>(LifecyclePhase.class);
        byPhase.forEach((phase, refs) -> frozen.put(phase, List.copyOf(refs)));
        return frozen;
    }
}
