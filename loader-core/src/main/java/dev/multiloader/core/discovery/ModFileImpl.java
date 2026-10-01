package dev.multiloader.core.discovery;

import dev.multiloader.api.locating.IModFile;
import dev.multiloader.api.locating.IModFileExtension;
import dev.multiloader.api.locating.MixinConfigRef;
import dev.multiloader.api.metadata.ModDependency;
import dev.multiloader.api.metadata.ModMetadata;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link IModFile} 的唯一实现.
 * <p>为什么把实现钉在 loader-core 而不是让适配器各自实现:
 * 适配器的职责是"把某种格式的元数据读出来",不是"实现加载器的数据模型".
 * 一份实现也让跨格式的公共行为(比如 {@code getMixinConfigs()} 的派生逻辑)
 * 只有一处,不必在三个适配器里各写一遍.
*/
final class ModFileImpl implements IModFile {

    private final Path filePath;
    private final String format;
    private final List<ModMetadata> metadataList;
    private final List<ModDependency> dependencies;
    private final List<MixinConfigRef> mixinConfigRefs;
    private final List<Path> classpathRoots;
    private final List<Path> nestedLibraries;
    private final Map<Class<? extends IModFileExtension>, IModFileExtension> extensions;

    ModFileImpl(Path filePath,
                String format,
                List<ModMetadata> metadataList,
                List<ModDependency> dependencies,
                List<MixinConfigRef> mixinConfigRefs,
                List<Path> classpathRoots,
                List<Path> nestedLibraries,
                List<IModFileExtension> extensions) {
        this.filePath = filePath;
        this.format = format;
        this.metadataList = List.copyOf(metadataList);
        this.dependencies = List.copyOf(dependencies);
        this.mixinConfigRefs = List.copyOf(mixinConfigRefs);
        this.classpathRoots = List.copyOf(classpathRoots);
        this.nestedLibraries = List.copyOf(nestedLibraries);

        Map<Class<? extends IModFileExtension>, IModFileExtension> byType = new LinkedHashMap<>();
        for (IModFileExtension extension : extensions) {
            byType.put(extension.getClass(), extension);
        }
        this.extensions = Map.copyOf(byType);
    }

    @Override
    public Path getFilePath() {
        return filePath;
    }

    @Override
    public String getFormat() {
        return format;
    }

    @Override
    public List<ModMetadata> getMetadataList() {
        return metadataList;
    }

    @Override
    public List<ModDependency> getDependencies() {
        return dependencies;
    }

    @Override
    public List<MixinConfigRef> getMixinConfigRefs() {
        return mixinConfigRefs;
    }

    @Override
    public List<Path> getClasspathRoots() {
        return classpathRoots;
    }

    @Override
    public List<Path> getNestedLibraries() {
        return nestedLibraries;
    }

    @Override
    public <T extends IModFileExtension> Optional<T> getExtension(Class<T> extensionType) {
        return Optional.ofNullable(extensionType.cast(extensions.get(extensionType)));
    }

    @Override
    public String toString() {
        return "%s[%s, mods=%d, mixins=%d]".formatted(
                format, filePath.getFileName(), metadataList.size(), mixinConfigRefs.size());
    }
}
