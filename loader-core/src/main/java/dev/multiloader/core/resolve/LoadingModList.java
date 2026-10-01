package dev.multiloader.core.resolve;

import dev.multiloader.api.locating.IModFile;
import dev.multiloader.api.metadata.ModMetadata;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * 已确定要加载的 mod 集合.
 * <p>当前(S5)只做"按发现顺序固定下来 + 建立 modId 索引".
 * 依赖解析与拓扑排序属于 P3,届时 {@link #of} 会被
 * {@code DependencyResolver} 产出的有序列表取代,但本类的只读视图契约不变
 * 这是刻意让 P3 不需要改动下游(桥接层,生命周期)的原因.
 * <p>不可变;构造即校验.
*/
public final class LoadingModList {

    private final List<IModFile> modFiles;
    private final Map<String, IModFile> byModId;

    private LoadingModList(List<IModFile> modFiles, Map<String, IModFile> byModId) {
        this.modFiles = modFiles;
        this.byModId = byModId;
    }

    public static LoadingModList of(List<IModFile> modFiles) {
        Map<String, IModFile> index = new LinkedHashMap<>();
        for (IModFile modFile : modFiles) {
            for (ModMetadata metadata : modFile.getMetadataList()) {
                IModFile previous = index.put(metadata.modId(), modFile);
                if (previous != null) {
                    throw new IllegalArgumentException(
                            "Duplicate mod id '%s' declared by both %s and %s"
                                    .formatted(metadata.modId(),
                                            previous.getFilePath().getFileName(),
                                            modFile.getFilePath().getFileName()));
                }
            }
        }
        return new LoadingModList(List.copyOf(modFiles), Map.copyOf(index));
    }

    public static LoadingModList empty() {
        return new LoadingModList(List.of(), Map.of());
    }

/** 加载顺序.当前即发现顺序(文件名排序),P3 起为拓扑序. */
    public List<IModFile> modFiles() {
        return modFiles;
    }

    public Optional<IModFile> byModId(String modId) {
        return Optional.ofNullable(byModId.get(modId));
    }

    public Set<String> modIds() {
        return new TreeSet<>(byModId.keySet());
    }

    public int size() {
        return modFiles.size();
    }

    public boolean isEmpty() {
        return modFiles.isEmpty();
    }

/** 全部 mod 文件声明的全部 mixin 配置名(去重). */
    public Set<String> allMixinConfigs() {
        Set<String> configs = new TreeSet<>();
        for (IModFile modFile : modFiles) {
            configs.addAll(modFile.getMixinConfigs());
        }
        return configs;
    }

    @Override
    public String toString() {
        return "LoadingModList[mods=%d, ids=%s]".formatted(modFiles.size(), modIds());
    }
}
