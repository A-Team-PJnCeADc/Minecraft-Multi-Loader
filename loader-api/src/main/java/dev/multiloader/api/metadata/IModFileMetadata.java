package dev.multiloader.api.metadata;

import dev.multiloader.api.locating.IModFileExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 单个 mod 文件的**加载器专有元数据**视图(统一模型之外的那部分).
 * <p><b>为什么需要这个接口</b>:统一模型 {@link ModMetadata} 是**跨加载器的共同分母**
 * (modId / version / name / description / authors / license / contacts / environment).
 * 各加载器还有自己的专有字段Fabric 的 {@code provides} / {@code contributors} /
 * {@code icon} / {@code custom},NeoForge 的 {@code featureFlags} / {@code modLoader} 等.
 * <p>这些数据**已经被解析出来了**,但此前停在 {@code adapt-*} 的格式扩展上,
 * 而桥接层按 INV 约束不能依赖 {@code adapt-*}  数据就在手边,却隔着一道依赖墙.
 * 本接口把它搬到共享位置:{@code adapt-*} 实现它,{@code bridge-*} 经
 * {@code IModFile} 读取,双方都只依赖 loader-api.
 * <p><b>为什么不直接加宽 {@link ModMetadata}</b>:那会让共同分母变成并集
 * (每个 adapter 填自己那块,其余留空),而且部分字段的返回类型是**加载器类型**
 * (Fabric 的 {@code CustomValue}),放进核心 API 会违反 INV-2(核心不得引用加载器类型).
 * 因此这里刻意只用 JDK 类型.
 * <p><b>为什么 {@code customValues()} 的值是 {@code Object}</b>:自定义值的结构由
 * 各加载器定义(Fabric 是 TOML/JSON 式的嵌套值),统一层不该认识它的模型.
 * 需要强类型的桥接层负责解包把"认识它"的责任留在知道它的那一侧.
 * 返回 {@code Map} 而非深嵌套定义,是因为调用方(如 Fabric 的 {@code getCustomValues()})
 * 按顶层键取值,嵌套部分由值自身表达.
*/
public interface IModFileMetadata extends IModFileExtension {

/** 本 mod 声明它同时提供的**其他** modId(Fabric 的 {@code provides}). */
    Set<String> provides();

/** 贡献者(与作者分开的另一个人群). */
    List<String> contributors();

/**
     * 指定尺寸的图标资源路径(相对 mod 文件内).
     * <p>参数是"请求的尺寸"而不是"图标列表":Fabric 的语义是按尺寸挑最合适的一张,
     * 返回单个路径.用 {@code Optional} 表达"没有这个尺寸的图标",
     * 而不是返回 null(null 会让调用方无从判断是"没有"还是"没实现").
*/
    Optional<String> iconPath(int size);

/**
     * 自定义元数据的顶层键值.值可能是字符串,布尔,数字,列表或嵌套表.
     * <p>不可为 null(无自定义数据时返回空 Map).
*/
    Map<String, Object> customValues();
}
