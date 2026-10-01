package dev.multiloader.bridge.fabric.meta;

import dev.multiloader.api.locating.IModFile;
import dev.multiloader.api.metadata.IModFileMetadata;
import dev.multiloader.common.Log;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.ContactInformation;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.Person;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

/**
 * 把统一模型的元数据翻译成 Fabric 的 {@link ModMetadata}.
 * <p>两个数据源,各承担一半:
 * <pre>
 *   统一模型 ModMetadata(9)  跨加载器共同分母:id/name/description/version/authors/
 *                              license/contacts/environment(+ getType 来自 IModFile.getFormat)
 *   共享扩展 IModFileMetadata(3)  加载器专有:provides / contributors / icon
 * </pre>
 * 第二个来源正是"数据卡在 adapt-* 墙后"那个缺口的出口 -- 桥接层按 INV 不能依赖
 * {@code adapt-*},但可以依赖 loader-api 里的这个接口.
 * <p><b>扩展缺失时降级为空,而不是抛异常</b>:非 Fabric 格式的文件(或未实现该接口的
 * 适配器)也会走到这里,而"没有 provides/contributors/icon"对它们**是正确的语义**,
 * 不是错误.这与"我们应该有却拿不到"是两回事 -- 后者才该响亮失败.
*/
public final class MinimalFabricModMetadata implements ModMetadata {

/** 版本串无法解析时的回退值. */
    private static final String FALLBACK_VERSION = "0.0.0";

    private final String format;
    private final dev.multiloader.api.metadata.ModMetadata unified;
    private final Optional<IModFileMetadata> extras;

    public MinimalFabricModMetadata(IModFile file) {
        this.format = file.getFormat();
        this.unified = file.getMetadata();
        this.extras = file.getExtension(IModFileMetadata.class);
    }

    // 来自统一模型(9)

    @Override
    public String getType() {
        // Fabric 的 getType() 语义是"加载器类型"(javafml / lowcodefml 等),
        // 而不是文件格式名.我们只有格式名,所以如实返回它 -- 编一个 "javafml"
        // 会假装我们知道 language adapter 的选择,而那是解析层的知识.
        return format;
    }

    @Override
    public String getId() {
        return unified.modId();
    }

    @Override
    public String getName() {
        return unified.displayName();
    }

    @Override
    public String getDescription() {
        return unified.description();
    }

/**
     * 版本对象.
     * <p><b>为什么用接口自己的静态 {@code Version.parse} 而不是 {@code VersionParser}</b>:
     * 前者是公开契约(接口里的静态方法),后者的包路径未核实.用公开入口就不必猜.
     * <p><b>解析失败不抛异常,也不返回 null</b>:{@code getVersion()} 在元数据展示与
     * 依赖求解里都会被调用,为一个格式异常让整条链路炸掉是不成比例的.
     * 回退到 0.0.0 并**记 error**:回退值让比较可继续,日志让问题可见 --
     * 只做其中一半都会很难查.
     * <p><b>实测:这条回退分支很难触发.</b> Fabric 的版本解析器非常宽松 --
     * {@code "${file.jarVersion}"} 这类未替换的占位符它也会当作普通版本字符串接受,
     * 不抛异常(见 {@code MinimalFabricModMetadataTest} 的
     * {@code permissiveParserAcceptsNonVersionStrings}).所以本分支是**防御性**的,
     * 不是常规路径.保留它的理由:解析器行为随上游版本变化,而失败后果
     * (炸掉整条元数据链)远大于几行防御代码的成本.
*/
    @Override
    public Version getVersion() {
        try {
            return Version.parse(unified.version());
        } catch (Exception e) {
            Log.error("mod '" + unified.modId() + "' 的版本串无法解析为 Fabric 版本：'"
                    + unified.version() + "'，回退为 " + FALLBACK_VERSION, e);
            try {
                return Version.parse(FALLBACK_VERSION);
            } catch (Exception fatal) {
                // 0.0.0 都解析不了说明 Fabric 的解析器契约变了,属于环境级故障.
                throw new IllegalStateException("Fabric 无法解析回退版本 " + FALLBACK_VERSION, fatal);
            }
        }
    }

    @Override
    public Collection<Person> getAuthors() {
        return unified.authors().stream().<Person>map(FabricPerson::new).toList();
    }

    @Override
    public ContactInformation getContact() {
        return new FabricContactInformation(unified.contacts());
    }

    @Override
    public Collection<String> getLicense() {
        String license = unified.license();
        // 统一模型里 license 是单个字符串,Fabric 是集合.
        // 空白串要当"没有"处理 -- 否则 [" " ] 这种会在展示层变成空条目.
        return license == null || license.isBlank() ? List.of() : List.of(license);
    }

    @Override
    public ModEnvironment getEnvironment() {
        return switch (unified.environment()) {
            case CLIENT -> ModEnvironment.CLIENT;
            case SERVER -> ModEnvironment.SERVER;
            // BOTH ->UNIVERSAL:Fabric 里 UNIVERSAL 表示"两侧都装".
            // 这不是降级,是同一个语义的两种叫法.
            case BOTH -> ModEnvironment.UNIVERSAL;
        };
    }

    // 来自共享扩展 IModFileMetadata(3)

    @Override
    public Collection<String> getProvides() {
        return extras.map(IModFileMetadata::provides).orElse(Set.of());
    }

    @Override
    public Collection<Person> getContributors() {
        return extras.map(IModFileMetadata::contributors)
                .orElse(List.of())
                .stream()
                .<Person>map(FabricPerson::new)
                .toList();
    }

    @Override
    public Optional<String> getIconPath(int size) {
        return extras.flatMap(metadata -> metadata.iconPath(size));
    }

    // 明确拒绝(理由各自不同,不能合并成一句)

/**
     * 依赖声明.
     * <p>拒绝理由:需要 {@code VersionPredicate} / {@code VersionInterval} 与
     * **版本区间匹配语义**.上游有 {@code ModDependencyImpl},但它依赖 Fabric 内部类型.
     * <p>为什么不硬造一个区间实现:区间匹配的边界(开闭区间,通配,预发布版本)
     * 是那种"看起来能用但语义不同"的地方 -- 而它的错误后果是**依赖求解选错版本**,
     * 到时归因会指向别处.
*/
    @Override
    public Collection<ModDependency> getDependencies() {
        // TODO(bridge): [依赖链缺] 前置条件
        String message = "MinimalFabricModMetadata.getDependencies 尚未支持："
                + "需要 VersionPredicate / VersionInterval 与版本区间匹配语义"
                + "（上游 ModDependencyImpl 依赖 Fabric 内部类型）。"
                + "统一模型里的 ModDependency 形状不同，直接转换会得到语义偏差的依赖约束，"
                + "那比不返回更危险。mod: " + unified.modId()
                + "（见 docs/02-bridge-design.md §6 本期范围）";
        Log.warn("{}", message);
        throw new UnsupportedOperationException(message);
    }

/**
     * 自定义值族 -- 从 {@code IModFileMetadata.customValues()} 的纯 JDK 值适配而来.
     * <p>适配器见 {@link FabricCustomValue}:6 个 {@code CvType} 与 5 种 JDK 形状 + null
     * 一一对应,不含启发式判断.
*/
    @Override
    public boolean containsCustomValue(String key) {
        return customValuesSource().containsKey(key);
    }

    @Override
    public CustomValue getCustomValue(String key) {
        Map<String, Object> source = customValuesSource();
        if (!source.containsKey(key)) {
            // 缺键抛异常而不是返回 null:Fabric 的调用方通常直接对返回值
            // 调 getAsString() 之类,返回 null 会让 NPE 发生在**调用方那一行**,
            // 归因指向调用方的代码而不是"键不存在"这个事实.
            // 信息里列出可用键 -- 否则调用方只能靠自己翻 mod 文件.
            throw new NoSuchElementException(
                    "mod '" + unified.modId() + "' 没有自定义值键 '" + key + "'。可用键: "
                            + source.keySet());
        }
        return FabricCustomValue.of(source.get(key));
    }

    @Override
    public Map<String, CustomValue> getCustomValues() {
        Map<String, CustomValue> mapped = new java.util.LinkedHashMap<>();
        customValuesSource().forEach((key, value) -> mapped.put(key, FabricCustomValue.of(value)));
        return Map.copyOf(mapped);
    }

    @Override
    public boolean containsCustomElement(String key) {
        // 与 containsCustomValue 同一来源:Fabric 里"自定义元素"与"自定义值"
        // 指向同一段数据的两种叫法(前者强调存在性检查).
        return customValuesSource().containsKey(key);
    }

    private Map<String, Object> customValuesSource() {
        return extras.map(IModFileMetadata::customValues).orElse(Map.of());
    }

    @Override
    public String toString() {
        return "MinimalFabricModMetadata[" + unified.modId() + " " + unified.version() + "]";
    }
}
