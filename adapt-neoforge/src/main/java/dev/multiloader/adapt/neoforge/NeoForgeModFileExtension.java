package dev.multiloader.adapt.neoforge;

import dev.multiloader.api.locating.IModFileExtension;

import java.util.List;

/**
 * {@code neoforge.mods.toml} 中**不属于统一元数据模型**的那部分.
 * <p>统一模型({@code ModMetadata} / {@code ModDependency} / {@code MixinConfigRef})
 * 承载三种格式的公共信息;这里只放 NeoForge 特有的东西,
 * 供后续加载器逻辑(loaderVersion 校验,AT 应用,feature flag)取用.
*/
public final class NeoForgeModFileExtension implements IModFileExtension {

/**
     * 一条 {@code [[mods]]} 声明.
     * <p>与 Fabric 的**结构性差异**:一个 {@code neoforge.mods.toml} 可以声明多个 modId
     * (多个 {@code [[mods]]} 段).这正是 {@code IModFile.getMetadataList()} 返回列表的理由 
     * 若模型只允许一个元数据,这类 jar 必然被读残.
*/
    public record ModEntry(
            String modId,
            String version,
            String displayName,
            String description,
            List<String> authors,
            String logoFile,
            String enumExtensions,
            boolean versionUnresolved) {
    }

/** 一条 {@code [[dependencies.<modId>]]} 声明,保留"是谁依赖的". */
    public record DependencyEntry(String ownerModId, String modId, String type,
                                  String versionRange, String ordering, String side) {
    }

    private final String modLoader;
    private final String loaderVersion;
    private final String license;
    private final String featureFlags;
    private final List<ModEntry> modEntries;
    private final List<DependencyEntry> dependencyEntries;
    private final List<String> accessTransformers;
    private final List<NeoForgeModClassScanner.ModClass> modClasses;

    public NeoForgeModFileExtension(String modLoader,
                                    String loaderVersion,
                                    String license,
                                    String featureFlags,
                                    List<ModEntry> modEntries,
                                    List<DependencyEntry> dependencyEntries,
                                    List<String> accessTransformers,
                                    List<NeoForgeModClassScanner.ModClass> modClasses) {
        this.modLoader = modLoader;
        this.loaderVersion = loaderVersion;
        this.license = license;
        this.featureFlags = featureFlags;
        this.modEntries = List.copyOf(modEntries);
        this.dependencyEntries = List.copyOf(dependencyEntries);
        this.accessTransformers = List.copyOf(accessTransformers);
        this.modClasses = List.copyOf(modClasses);
    }

/** 例如 {@code javafml}.决定用哪个 mod 语言加载器. */
    public String modLoader() {
        return modLoader;
    }

/** 形如 {@code [1,)} 的版本区间,针对 {@link #modLoader()}. */
    public String loaderVersion() {
        return loaderVersion;
    }

    public String license() {
        return license;
    }

/** 可选的 feature flag 清单路径;未声明时为 null. */
    public String featureFlags() {
        return featureFlags;
    }

    public List<ModEntry> modEntries() {
        return modEntries;
    }

    public List<DependencyEntry> dependencyEntries() {
        return dependencyEntries;
    }

/**
     * {@code [[accessTransformers]]} 的 {@code file} 值,相对 mod 根.
     * <p>本轮只解析不应用:AT 的实际施加需要先定下补丁/转换机制
     * (见 AGENTS.md 里那个待决策点).
*/
    public List<String> accessTransformers() {
        return accessTransformers;
    }

    public boolean hasAccessTransformers() {
        return !accessTransformers.isEmpty();
    }

/**
     * 扫描到的 {@code @Mod} 类(含构造器签名).
     * <p>为什么必须扫描而不是从 toml 读:NeoForge 的 {@code neoforge.mods.toml}
     * **不声明** {@code @Mod} 类名.FancyModLoader 遍历 jar 里的 class 找注解,
     * 我们照做(见 {@link NeoForgeModClassScanner}).
*/
    public List<NeoForgeModClassScanner.ModClass> modClasses() {
        return modClasses;
    }

/** 是否有类需要注入服务(构造器带参数) 决定要不要走服务解析. */
    public boolean requiresServiceInjection() {
        return modClasses.stream().anyMatch(NeoForgeModClassScanner.ModClass::requiresServices);
    }
}
