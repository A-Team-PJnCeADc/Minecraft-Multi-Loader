package dev.multiloader.adapt.neoforge;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.core.io.ParsingException;
import com.electronwill.nightconfig.toml.TomlParser;
import dev.multiloader.api.locating.IModFile;
import dev.multiloader.api.locating.IModFileFactory;
import dev.multiloader.api.locating.IModFileReader;
import dev.multiloader.api.locating.MixinConfigRef;
import dev.multiloader.api.locating.ModFileCandidate;
import dev.multiloader.api.locating.ModFileException;
import dev.multiloader.api.metadata.DependencyKind;
import dev.multiloader.api.metadata.Environment;
import dev.multiloader.api.metadata.ModDependency;
import dev.multiloader.api.metadata.ModMetadata;
import dev.multiloader.api.metadata.Ordering;
import dev.multiloader.common.io.JarEntries;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 读取 {@code META-INF/neoforge.mods.toml}.
 * <p>与 Fabric 的三处结构性差异(都直接影响模型,不是细节):
 * <ol>
 *   <li>锚点在 {@code META-INF/} 下,不是 jar 根</li>
 *   <li>格式是 TOML,不是 JSON  所以用 night-config 而不是 gson</li>
 *   <li>一个文件可声明**多个** {@code [[mods]]}(多个 modId),
 *       因此产出的是元数据**列表**,走 {@code IModFileFactory.Builder.metadata(List)}</li>
 * </ol>
 * <p>本读取器**不认领** {@code META-INF/mods.toml}(那是旧版 Forge 的锚点).
 * 认领了会假装能读,而字段语义已经不同.
*/
public final class NeoForgeModFileReader implements IModFileReader {

/** NeoForge 的锚点;位于 jar 内 {@code META-INF/} 下. */
    public static final String ANCHOR = "META-INF/neoforge.mods.toml";

/** 旧版 Forge 的锚点.刻意不认领  见类注释. */
    public static final String LEGACY_FORGE_ANCHOR = "META-INF/mods.toml";

/**
     * 高于 Fabric 的 100.
     * <p>同一 jar 同时含两种元数据时,两个读取器都会认领并各自产出一个 {@code IModFile},
     * 所以这个值不影响"能否被发现";它只决定顺序,便于日志按更完整的格式先呈现.
*/
    private static final int PRIORITY = 200;

/**
     * NeoForge 的依赖类型 ->统一依赖类型.
     * <p>{@code discouraged} 与 {@code embedded} 都映射为 {@code OPTIONAL}:
     * 前者是"能跑但不推荐",后者是"已内嵌"  两者都不构成缺失即失败.
     * 映射成 {@code REQUIRED} 会让本该启动的实例被拒.
*/
    private static final Map<String, DependencyKind> DEPENDENCY_KINDS = Map.of(
            "required", DependencyKind.REQUIRED,
            "optional", DependencyKind.OPTIONAL,
            "incompatible", DependencyKind.INCOMPATIBLE,
            "discouraged", DependencyKind.OPTIONAL,
            "embedded", DependencyKind.OPTIONAL);

    private static final Map<String, Ordering> ORDERINGS = Map.of(
            "BEFORE", Ordering.BEFORE,
            "AFTER", Ordering.AFTER,
            "NONE", Ordering.NONE);

    private static final Map<String, Environment> SIDES = Map.of(
            "BOTH", Environment.BOTH,
            "CLIENT", Environment.CLIENT,
            "SERVER", Environment.SERVER);

/**
     * 无法解析版本占位符时的回退值.
     * <p>mods.toml 里常见 {@code version="${file.jarVersion}"}  那是**加载器在装配期**
     * 替换的占位符.我们没有那套替换表,所以只能回退.
     * 回退成空串或原样保留都不行:前者会让版本比较误判,后者会把
     * 字面量 {@code ${file.jarVersion}} 当成版本号带进依赖求解.
*/
    private static final String VERSION_FALLBACK = "0.0.0";

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    public boolean canRead(ModFileCandidate candidate) {
        return readAnchor(candidate.path()).isPresent();
    }

    @Override
    public IModFile read(ModFileCandidate candidate, IModFileFactory factory) throws ModFileException {
        Path path = candidate.path();
        String toml = readAnchor(path).orElseThrow(
                () -> ModFileException.at(path, "missing " + ANCHOR));

        UnmodifiableConfig root = parseToml(toml, path);

        // 这两个是必填:它们决定用哪个 mod 语言加载器,以及该加载器的版本区间.
        // 缺了就无法决定怎么装配这个 mod,宁可明确报错也不要猜.
        String modLoader = requireString(root, "modLoader", path);
        String loaderVersion = requireString(root, "loaderVersion", path);
        String license = stringOrNull(root, "license");
        String featureFlags = stringOrNull(root, "featureFlags");

        List<NeoForgeModFileExtension.ModEntry> entries = parseMods(root, path);
        if (entries.isEmpty()) {
            throw ModFileException.at(path, ANCHOR + " declares no [[mods]] entry");
        }

        List<ModMetadata> metadata = new ArrayList<>();
        for (NeoForgeModFileExtension.ModEntry entry : entries) {
            metadata.add(new ModMetadata(
                    entry.modId(),
                    entry.version(),
                    entry.displayName(),
                    entry.description(),
                    entry.authors(),
                    license,
                    Map.of(),
                    Environment.BOTH));
        }

        List<MixinConfigRef> mixinRefs = parseMixinConfigs(root, path);
        List<NeoForgeModFileExtension.DependencyEntry> dependencyEntries = parseDependencyEntries(root, path);
        List<ModDependency> dependencies = toModDependencies(dependencyEntries);
        List<String> accessTransformers = parseAccessTransformers(root, path);

        // 扫描 @Mod 类.放在这里而不是"需要时再扫":扫描结果是扩展的一部分,
        // 与元数据同一生命周期,避免下游各自决定何时扫,扫几次.
        List<NeoForgeModClassScanner.ModClass> modClasses = NeoForgeModClassScanner.scan(path);

        NeoForgeModFileExtension extension = new NeoForgeModFileExtension(
                modLoader, loaderVersion, license, featureFlags,
                entries, dependencyEntries, accessTransformers, modClasses);

        return factory.builder(candidate, "neoforge")
                .metadata(metadata)
                .dependencies(dependencies)
                .mixinConfigRefs(mixinRefs)
                .extension(extension)
                .build();
    }

    // 各区块

/**
     * {@code [[mods]]}:每个段一个 modId.
     * <p>{@code modId} 必填  没有它这个段没有任何意义,且下游(依赖求解,冲突检测)
     * 都靠它做键.{@code version} 缺失或含未解析占位符时回退,并把"这是回退值"
     * 记在 {@link NeoForgeModFileExtension.ModEntry#versionUnresolved()} 上,
     * 让下游能区分"真的是 0.0.0"和"我们不知道".
*/
    private static List<NeoForgeModFileExtension.ModEntry> parseMods(UnmodifiableConfig root, Path path)
            throws ModFileException {
        List<NeoForgeModFileExtension.ModEntry> entries = new ArrayList<>();
        for (UnmodifiableConfig section : tables(root, "mods")) {
            String modId = requireString(section, "modId", path);
            String rawVersion = stringOrNull(section, "version");
            boolean unresolved = rawVersion == null || rawVersion.contains("${");
            entries.add(new NeoForgeModFileExtension.ModEntry(
                    modId,
                    unresolved ? VERSION_FALLBACK : rawVersion,
                    stringOrNull(section, "displayName"),
                    stringOrNull(section, "description"),
                    authorsOf(section),
                    stringOrNull(section, "logoFile"),
                    stringOrNull(section, "enumExtensions"),
                    unresolved));
        }
        return entries;
    }

/**
     * {@code [[mixins]]}:NeoForge 只声明 {@code config},没有 per-config 侧别字段.
     * <p>所以一律按双侧处理.这与 Fabric 的对象形态(可带 {@code environment})不同,
     * 不能照搬那边的解析  凭空接受一个 NeoForge 不存在的字段,
     * 只会让"写错了却不报错"成为常态.
*/
    private static List<MixinConfigRef> parseMixinConfigs(UnmodifiableConfig root, Path path)
            throws ModFileException {
        List<MixinConfigRef> refs = new ArrayList<>();
        for (UnmodifiableConfig section : tables(root, "mixins")) {
            refs.add(new MixinConfigRef(requireString(section, "config", path)));
        }
        return refs;
    }

/**
     * {@code [[dependencies.<modId>]]}:依赖挂在**声明它的那个 modId** 之下.
     * <p>这是 TOML 的嵌套数组表({@code dependencies} 是表,其每个键对应一个数组表).
     * 保留 ownerModId 是因为"谁依赖谁"在诊断时是必要信息  一个 jar 可能含多个 mod,
     * 只说"某处依赖了 minecraft"没有用.
*/
    private static List<NeoForgeModFileExtension.DependencyEntry> parseDependencyEntries(
            UnmodifiableConfig root, Path path) throws ModFileException {
        UnmodifiableConfig dependencies = table(root, "dependencies");
        if (dependencies == null) {
            return List.of();
        }
        List<NeoForgeModFileExtension.DependencyEntry> entries = new ArrayList<>();
        for (var owner : dependencies.entrySet()) {
            String ownerModId = owner.getKey();
            for (UnmodifiableConfig section : asTables(owner.getValue())) {
                entries.add(new NeoForgeModFileExtension.DependencyEntry(
                        ownerModId,
                        requireString(section, "modId", path),
                        stringOrNull(section, "type"),
                        stringOrNull(section, "versionRange"),
                        stringOrNull(section, "ordering"),
                        stringOrNull(section, "side")));
            }
        }
        return entries;
    }

/**
     * 转成统一依赖模型.
     * <p>{@code type} 缺失时按 {@code required} 处理?**不**.缺失就按 {@code OPTIONAL}:
     * 把没写清楚的东西当成硬依赖,会让一个本来能启动的实例被拒 
     * 默认拒绝在这里是错的,因为缺失的是**我们的解读**而不是 mod 的必要条件.
*/
    private static List<ModDependency> toModDependencies(
            List<NeoForgeModFileExtension.DependencyEntry> entries) {
        List<ModDependency> dependencies = new ArrayList<>();
        for (NeoForgeModFileExtension.DependencyEntry entry : entries) {
            DependencyKind kind = entry.type() == null
                    ? DependencyKind.OPTIONAL
                    : DEPENDENCY_KINDS.getOrDefault(entry.type(), DependencyKind.OPTIONAL);
            Ordering ordering = entry.ordering() == null
                    ? Ordering.NONE
                    : ORDERINGS.getOrDefault(entry.ordering(), Ordering.NONE);
            Environment environment = entry.side() == null
                    ? Environment.BOTH
                    : SIDES.getOrDefault(entry.side(), Environment.BOTH);
            dependencies.add(new ModDependency(
                    entry.modId(),
                    entry.versionRange() == null ? "*" : entry.versionRange(),
                    kind, ordering, environment));
        }
        return dependencies;
    }

/** {@code [[accessTransformers]]} 的 {@code file}. */
    private static List<String> parseAccessTransformers(UnmodifiableConfig root, Path path)
            throws ModFileException {
        List<String> files = new ArrayList<>();
        for (UnmodifiableConfig section : tables(root, "accessTransformers")) {
            files.add(requireString(section, "file", path));
        }
        return files;
    }

/**
     * {@code authors} 的两种形态都接受.
     * <p>NeoForge 规范里是逗号分隔的字符串,但真实 mod 里数组形态也出现过.
     * 只认一种会让我们在另一种上静默得到空作者列表.
*/
    private static List<String> authorsOf(UnmodifiableConfig section) {
        Object raw = section.getRaw("authors");
        if (raw instanceof String text) {
            List<String> authors = new ArrayList<>();
            for (String part : text.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    authors.add(trimmed);
                }
            }
            return authors;
        }
        if (raw instanceof List<?> list) {
            List<String> authors = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof String text && !text.isBlank()) {
                    authors.add(text);
                }
            }
            return authors;
        }
        return List.of();
    }

    // TOML 取值助手

    private static UnmodifiableConfig parseToml(String content, Path path) throws ModFileException {
        try {
            return new TomlParser().parse(content);
        } catch (ParsingException | IllegalArgumentException e) {
            throw ModFileException.at(path, ANCHOR + " is not valid TOML", e);
        }
    }

/** 读数组表({@code [[name]]});不存在或形态不符时返回空列表. */
    private static List<UnmodifiableConfig> tables(UnmodifiableConfig config, String key) {
        return asTables(config.getRaw(key));
    }

    private static List<UnmodifiableConfig> asTables(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<UnmodifiableConfig> tables = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof UnmodifiableConfig table) {
                tables.add(table);
            }
        }
        return tables;
    }

/** 读单表({@code [name]});不存在或形态不符时返回 null. */
    private static UnmodifiableConfig table(UnmodifiableConfig config, String key) {
        Object raw = config.getRaw(key);
        return raw instanceof UnmodifiableConfig table ? table : null;
    }

    private static String requireString(UnmodifiableConfig config, String key, Path path)
            throws ModFileException {
        String value = stringOrNull(config, key);
        if (value == null || value.isBlank()) {
            throw ModFileException.at(path, "missing required TOML key '" + key + "'");
        }
        return value;
    }

    private static String stringOrNull(UnmodifiableConfig config, String key) {
        Object raw = config.getRaw(key);
        return raw instanceof String text ? text : null;
    }

    // 锚点读取

/** 读出锚点内容;不存在或不可读时返回空. */
    static Optional<String> readAnchor(Path path) {
        byte[] bytes = JarEntries.read(path, ANCHOR);
        return bytes == null ? Optional.empty() : Optional.of(new String(bytes, StandardCharsets.UTF_8));
    }

/** 供测试与其它适配器复用的条目读取(公式只存在一处). */
    public static byte[] readEntry(Path path, String entryName) {
        return JarEntries.read(path, entryName);
    }
}
