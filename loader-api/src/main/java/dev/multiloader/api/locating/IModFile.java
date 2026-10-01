package dev.multiloader.api.locating;

import dev.multiloader.api.metadata.ModDependency;
import dev.multiloader.api.metadata.ModMetadata;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 统一 Mod 文件抽象.三种格式适配器各自实现私有子类,核心层只认这个接口.
 * <p>实现方一律通过 {@link IModFileFactory} 构造,不要自己 new.
*/
public interface IModFile {

/** 文件路径(jar 或目录). */
    Path getFilePath();

/** 格式标识:{@code "fabric"} / {@code "neoforge"} / {@code "forge"}.仅用于诊断输出. */
    String getFormat();

/**
     * 本文件声明的全部 mod.
     * <p>复数形式是必需的而不是便利:NeoForge / Forge 的 {@code [[mods]]}
     * 是数组表,一个 jar 可以声明多个 modId.Fabric 恒为单元素.
*/
    List<ModMetadata> getMetadataList();

/**
     * 便捷视图:返回第一个 mod.
     * @throws IllegalStateException 当文件未声明任何 mod
*/
    default ModMetadata getMetadata() {
        List<ModMetadata> list = getMetadataList();
        if (list.isEmpty()) {
            throw new IllegalStateException("Mod file declares no mods: " + getFilePath());
        }
        return list.get(0);
    }

/** 本文件声明的主 mod id(第一个 mod 的 id). */
    default String getPrimaryModId() {
        return getMetadata().modId();
    }

/** 全部声明的依赖(已按每个 mod 展开). */
    List<ModDependency> getDependencies();

/**
     * 本文件声明的全部 mixin 配置,含生效侧别.
     * <p>这是**抽象方法**而不是默认方法:侧别只能由格式适配器从各自的元数据里读出,
     * 无法从配置名推导.设为必实现项是为了逼适配器显式处理 side
     * 漏掉它会让服务端去加载 client-only 配置并崩溃.
*/
    List<MixinConfigRef> getMixinConfigRefs();

/**
     * 便捷视图:仅配置名.
     * <p>适用于不关心侧别的场景(如诊断输出).需要按侧别过滤时用
     * {@link #getMixinConfigRefs()}.
*/
    default Set<String> getMixinConfigs() {
        Set<String> names = new LinkedHashSet<>();
        for (MixinConfigRef ref : getMixinConfigRefs()) {
            names.add(ref.configName());
        }
        return names;
    }

/** 供类加载器使用的 classpath 根:jar 型返回自身,目录型返回目录. */
    List<Path> getClasspathRoots();

/**
     * 嵌套库路径(Fabric 的 {@code jars[]} / Forge 的 jarJar).
     * <p>这些是**位于 jar 内部**的条目,加载时需展开成额外的 classpath 根.
*/
    List<Path> getNestedLibraries();

/**
     * 取格式私有数据.
     * @return 该格式未提供此扩展类型时返回空 Optional
*/
    <T extends IModFileExtension> Optional<T> getExtension(Class<T> extensionType);
}
