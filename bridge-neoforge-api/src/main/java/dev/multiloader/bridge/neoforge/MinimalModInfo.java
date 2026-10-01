package dev.multiloader.bridge.neoforge;

import net.neoforged.neoforgespi.language.IConfigurable;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.language.IModLanguageLoader;
import net.neoforged.neoforgespi.locating.ForgeFeature;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 用本加载器已解析的元数据实现的 {@link IModInfo}.
 * <p><b>这不是桩</b>:桩是"为了编译而填的假东西",而这里是把我们
 * {@code neoforge.mods.toml} 的真实解析结果适配成 NeoForge 的 SPI 
 * 正是桥接层该做的事.
 * <p><b>取值形状为什么是 5 个字符串而不是直接接 {@code ModEntry}</b>:
 * {@code NeoForgeModFileExtension.ModEntry} 住在 {@code adapt-neoforge},
 * 而 INV 约束({@code docs/02-bridge-design.md} §4)规定本模块只允许依赖
 * {@code bridge-api-common} + NeoForge 产物.让 bridge 依赖 adapt 会破坏那条边界.
 * 由**加载器层**(可同时依赖两侧)把 {@code ModEntry} 的值传进来即可,效果等价.
 * <p><b>三类方法的处理各不相同</b>,这是刻意区分的:
 * <ul>
 *   <li><b>有真实值</b>  从构造参数取.返回真实数据.</li>
 *   <li><b>类型本身允许"没有"</b>({@code Optional} / {@code List} / {@code Map})
 *        返回空.这类接口的语义就包含"可能为空",抛异常反而让调用方无从判断
 *       "是不支持还是确实没有".</li>
 *   <li><b>需要真实对象才有意义</b>({@code getOwningFile} / {@code getLoader} /
 *       {@code getConfig}) 抛 {@code UnsupportedOperationException} 并记日志.
 *       静默返回 null 会把问题推到调用方:它在别处 NPE,堆栈指向 NeoForge 内部
 *       而不是"我们没提供这个能力".</li>
 * </ul>
*/
public final class MinimalModInfo implements IModInfo {

    private final String modId;
    private final String namespace;
    private final String displayName;
    private final String description;
    private final ArtifactVersion version;

/**
     * @param modId       来自 {@code [[mods]] modId}
     * @param displayName 来自 {@code displayName};缺失时回退为 modId(见下)
     * @param description 来自 {@code description};可为 null
     * @param version     来自 {@code version};缺失时调用方已回退为 {@code 0.0.0}
*/
    public MinimalModInfo(String modId, String displayName, String description, String version) {
        if (modId == null || modId.isBlank()) {
            throw new IllegalArgumentException("modId must not be blank");
        }
        this.modId = modId;
        // NeoForge 的 namespace 就是小写 modId.这里**推导**而不是另设参数:
        // 让调用方传一个"可能与 modId 不一致"的 namespace,只会制造一个
        // 需要额外校验的不变量,而真实 mods.toml 里并不存在这个字段.
        this.namespace = modId.toLowerCase(Locale.ROOT);
        // displayName 缺失时回退为 modId:NeoForge 的界面与日志都用它显示.
        // 返回 null 会让每处使用者各写一遍回退,且样式不一.
        this.displayName = (displayName == null || displayName.isBlank()) ? modId : displayName;
        this.description = description;
        // 版本解析失败不能让构造失败:一个 mod 的版本号写得不规范,
        // 不该阻断整个加载流程.DefaultArtifactVersion 对无法解析的串会得到
        // 一个"不可比较"的版本对象,交由依赖求解去处理.
        this.version = new DefaultArtifactVersion(version == null ? "0.0.0" : version);
    }

    // 有真实值(从已解析的元数据取)

    @Override
    public String getModId() {
        return modId;
    }

    @Override
    public String getNamespace() {
        return namespace;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public ArtifactVersion getVersion() {
        return version;
    }

    // 类型允许"没有"的方法:返回空 

    @Override
    public List<? extends ModVersion> getDependencies() {
        // 统一依赖模型里已经有这些信息(ModDependency),但它的形状与
        // NeoForge 的 ModVersion 不同.映射是独立的一步(需要 versionRange 解析),
        // 现在返回空而不是塞一个半成品  半成品会被依赖求解当成真实约束.
        return List.of();
    }

    @Override
    public List<? extends ForgeFeature.Bound> getForgeFeatures() {
        return List.of();
    }

    @Override
    public Map<String, Object> getModProperties() {
        return Map.of();
    }

    @Override
    public Optional<URL> getUpdateURL() {
        return Optional.empty();
    }

    @Override
    public Optional<URL> getModURL() {
        return Optional.empty();
    }

    @Override
    public Optional<String> getLogoFile() {
        return Optional.empty();
    }

    @Override
    public boolean getLogoBlur() {
        // boolean 无法表达"没有".取 false 是唯一合理的中性值,
        // 且与 getLogoFile() 为空一致(没有 logo 就无所谓模糊).
        return false;
    }

    // 需要真实对象才有意义的方法:明确拒绝 

    @Override
    public IModFileInfo getOwningFile() {
        throw unsupported("getOwningFile：需要 NeoForge 的 IModFileInfo，"
                + "而我们用的是自己的 IModFile 模型（两者结构不同，映射是独立工作）");
    }

    @Override
    public IModLanguageLoader getLoader() {
        throw unsupported("getLoader：需要 NeoForge 的语言加载器（javafml 等），"
                + "而本加载器自己负责实例化 @Mod 类，不经过它");
    }

    @Override
    public IConfigurable getConfig() {
        throw unsupported("getConfig：需要 NeoForge 的 IConfigurable（TOML 配置系统的视图），"
                + "本期的配置桥接尚未实现");
    }

    private static UnsupportedOperationException unsupported(String reason) {
        // 同时记日志:异常可能被上层吞掉,而"我们没提供这个能力"这件事
        // 必须在日志里留下痕迹,否则表现为"NeoForge 内部莫名其妙失败".
        dev.multiloader.common.Log.warn("MinimalModInfo: {}", reason);
        return new UnsupportedOperationException("MinimalModInfo: " + reason);
    }

    @Override
    public String toString() {
        return "MinimalModInfo[" + modId + " " + version + "]";
    }
}
