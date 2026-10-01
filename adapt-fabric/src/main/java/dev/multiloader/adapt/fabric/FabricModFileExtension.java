package dev.multiloader.adapt.fabric;

import dev.multiloader.api.lifecycle.LifecyclePhase;
import dev.multiloader.api.metadata.Environment;
import dev.multiloader.api.metadata.IModFileMetadata;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Fabric 格式的私有数据载体.
 * <p>这些字段都只对 Fabric 有意义,因此不往 {@code IModFile} 上加方法.
 * 需要它们的组件(如 {@link FabricEntrypointProvider})显式声明依赖本类型,
 * 核心层则完全不认识它.
 * <p><b>为什么同时实现 {@link IModFileMetadata}</b>:其中一部分字段
 * (provides / contributors / icon / custom)是**桥接层也需要**的,而桥接层
 * 按 INV 约束不能依赖 {@code adapt-*} 数据此前就卡在这道墙后面.
 * {@code IModFileMetadata} 声明在 loader-api,双方都只依赖它:
 * 适配器实现,桥接消费,谁都不用认识对方.
 * 本类里 {@code provides()} 同时满足两个接口 它们签名相同,
 * 不需要桥接代码,Java 的多继承会让它一次实现两处契约.
 * <p><b>为什么 implements 列表里只有 {@code IModFileMetadata}</b>:它已经
 * {@code extends IModFileExtension}.更重要的是,这解决了查找路径问题
 * {@code IModFile.getExtension(Class<T>)} 的泛型上限是 {@code IModFileExtension},
 * 所以只有继承了它,桥接层才**取得到**这个扩展:
 * <pre>
 *   file.getExtension(IModFileMetadata.class)   // 现在成立
 * </pre>
 * 此前两者是并列关系,桥接层没有任何合法途径拿到元数据视图.
*/
public final class FabricModFileExtension implements IModFileMetadata {

/** 一条入口点声明.{@code className} 可能由 languageAdapter 再转换一次. */
    public record Entrypoint(String namespace, String className, LifecyclePhase phase,
                             Environment environment) {
    }

/** "任意尺寸"的哨兵键:{@code fabric.mod.json} 里 {@code icon} 写成单个字符串时用它. */
    private static final int ANY_SIZE = 0;

    private final int schemaVersion;
    private final String accessWidener;
    private final Map<String, String> languageAdapters;
    private final Set<String> provides;
    private final List<Entrypoint> entrypoints;
    private final List<Path> nestedJars;
    private final List<String> contributors;
    private final Map<Integer, String> iconPaths;
    private final Map<String, Object> customValues;

    public FabricModFileExtension(int schemaVersion,
                                  String accessWidener,
                                  Map<String, String> languageAdapters,
                                  Set<String> provides,
                                  List<Entrypoint> entrypoints,
                                  List<Path> nestedJars,
                                  List<String> contributors,
                                  Map<Integer, String> iconPaths,
                                  Map<String, Object> customValues) {
        this.schemaVersion = schemaVersion;
        this.accessWidener = accessWidener;
        this.languageAdapters = Map.copyOf(languageAdapters);
        this.provides = Set.copyOf(provides);
        this.entrypoints = List.copyOf(entrypoints);
        this.nestedJars = List.copyOf(nestedJars);
        this.contributors = List.copyOf(contributors);
        this.iconPaths = Map.copyOf(iconPaths);
        this.customValues = Map.copyOf(customValues);
    }

    public int schemaVersion() {
        return schemaVersion;
    }

/** AccessWidener 路径,相对 mod 根;未声明时为 null. */
    public String accessWidener() {
        return accessWidener;
    }

    public boolean hasAccessWidener() {
        return accessWidener != null && !accessWidener.isBlank();
    }

/** 命名空间 ->language adapter 类名.Kotlin 等非 Java mod 靠它把入口点实例化. */
    public Map<String, String> languageAdapters() {
        return languageAdapters;
    }

/** 虚拟 mod id.依赖解析时其它 mod 可以依赖这些 id. */
    @Override
    public Set<String> provides() {
        return provides;
    }

    public List<Entrypoint> entrypoints() {
        return entrypoints;
    }

/**
     * 嵌套 jar 列表.
     * <p>路径形式为 {@code <modjar>!/<entry>} 伪路径:嵌套 jar 位于 mod jar
     * **内部**,不是文件系统上的真实路径.类加载阶段负责把它们解出来.
*/
    public List<Path> nestedJars() {
        return nestedJars;
    }

    @Override
    public List<String> contributors() {
        return contributors;
    }

/**
     * 按请求尺寸挑最合适的图标.
     * <p>三级择优,顺序即语义:
     * <ol>
     *   <li><b>精确尺寸</b>要 32 就有 32,直接给</li>
     *   <li><b>任意尺寸</b>({@code icon} 写成单字符串的形态)所有尺寸都用它</li>
     *   <li><b>最接近的尺寸</b>没有精确匹配也没有通配时挑差值最小的</li>
     * </ol>
     * <p>为什么需要第 3 级:mod 常只提供 16 与 128 两档,而调用方可能问 32.
     * 直接返回 empty 会让"有图标但尺寸不齐"的 mod 显示成没有图标.
*/
    @Override
    public Optional<String> iconPath(int size) {
        if (iconPaths.isEmpty()) {
            return Optional.empty();
        }
        String exact = iconPaths.get(size);
        if (exact != null) {
            return Optional.of(exact);
        }
        String anySize = iconPaths.get(ANY_SIZE);
        if (anySize != null) {
            return Optional.of(anySize);
        }
        return iconPaths.entrySet().stream()
                .min(Comparator.comparingInt(entry -> Math.abs(entry.getKey() - size)))
                .map(Map.Entry::getValue);
    }

    @Override
    public Map<String, Object> customValues() {
        return customValues;
    }
}
