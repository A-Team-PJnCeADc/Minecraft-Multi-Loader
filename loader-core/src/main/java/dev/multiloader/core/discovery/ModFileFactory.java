package dev.multiloader.core.discovery;

import dev.multiloader.api.locating.IModFile;
import dev.multiloader.api.locating.IModFileExtension;
import dev.multiloader.api.locating.IModFileFactory;
import dev.multiloader.api.locating.MixinConfigRef;
import dev.multiloader.api.locating.ModFileCandidate;
import dev.multiloader.api.metadata.ModDependency;
import dev.multiloader.api.metadata.ModMetadata;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 适配器用来构造 {@link IModFile} 的工厂实现.
 * <p>默认行为:类路径根自动包含 jar 自身(绝大多数 mod 都是"自己的类在自己的 jar 里"),
 * 适配器只需在有额外根(Fabric 的嵌套 jar)时显式追加.
*/
public final class ModFileFactory implements IModFileFactory {

    @Override
    public IModFileFactory.Builder builder(ModFileCandidate candidate, String format) {
        // 注意返回接口类型而不是私有嵌套类:否则调用方拿到的是不可访问的类型,
        // 连 builder() 都调不通.
        return new Builder(candidate, format);
    }

    private static final class Builder implements IModFileFactory.Builder {

        private final ModFileCandidate candidate;
        private final String format;
        private final List<ModMetadata> metadata = new ArrayList<>();
        private final List<ModDependency> dependencies = new ArrayList<>();
/** 以配置名为键去重:同一个配置被声明两次没有意义,且会让诊断输出出现重复项. */
        private final Map<String, MixinConfigRef> mixinConfigRefs = new LinkedHashMap<>();
        private final List<Path> classpathRoots = new ArrayList<>();
        private final List<Path> nestedLibraries = new ArrayList<>();
        private final List<IModFileExtension> extensions = new ArrayList<>();

        private Builder(ModFileCandidate candidate, String format) {
            this.candidate = candidate;
            this.format = format;
            this.classpathRoots.add(candidate.path());
        }

        @Override
        public IModFileFactory.Builder metadata(ModMetadata single) {
            metadata.add(single);
            return this;
        }

        @Override
        public IModFileFactory.Builder metadata(List<ModMetadata> many) {
            metadata.addAll(many);
            return this;
        }

        @Override
        public IModFileFactory.Builder dependencies(List<ModDependency> deps) {
            dependencies.addAll(deps);
            return this;
        }

        @Override
        public IModFileFactory.Builder mixinConfigRefs(List<MixinConfigRef> refs) {
            for (MixinConfigRef ref : refs) {
                mixinConfigRefs.put(ref.configName(), ref);
            }
            return this;
        }

        @Override
        public IModFileFactory.Builder mixinConfigs(Set<String> configs) {
            for (String config : configs) {
                mixinConfigRefs.putIfAbsent(config, new MixinConfigRef(config));
            }
            return this;
        }

        @Override
        public IModFileFactory.Builder classpathRoot(Path root) {
            classpathRoots.add(root);
            return this;
        }

        @Override
        public IModFileFactory.Builder nestedLibrary(Path nested) {
            nestedLibraries.add(nested);
            return this;
        }

        @Override
        public IModFileFactory.Builder extension(IModFileExtension extension) {
            extensions.add(extension);
            return this;
        }

        @Override
        public IModFile build() {
            if (metadata.isEmpty()) {
                throw new IllegalStateException(
                        "Mod file declares no mods: " + candidate.path());
            }
            return new ModFileImpl(candidate.path(), format, metadata, dependencies,
                    List.copyOf(mixinConfigRefs.values()), classpathRoots, nestedLibraries, extensions);
        }
    }
}
