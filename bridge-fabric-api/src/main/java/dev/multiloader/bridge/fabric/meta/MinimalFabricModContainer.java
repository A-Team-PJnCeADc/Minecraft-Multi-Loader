package dev.multiloader.bridge.fabric.meta;

import dev.multiloader.api.locating.IModFile;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.ModOrigin;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * 把 {@link IModFile} 适配成 Fabric 的 {@link ModContainer}.
 * <p>数据来源全部在统一模型与共享扩展里,不需要 Fabric 内部类型:
 * <pre>
 *   getMetadata()         ->{@link MinimalFabricModMetadata}(包着 IModFile)
 *   getRootPaths()        ->IModFile.getClasspathRoots()
 *   getOrigin()           ->{@link FabricModOrigin}(包着 IModFile.getFilePath())
 *   getPath(String)       ->相对主根解析
 *   getContainingMod()    ->empty(见下)
 *   getContainedMods()    ->empty(见下)
 * </pre>
 * <p><b>为什么父子容器恒为空</b>:Fabric 的这对方法描述的是 **jar-in-jar 嵌套**关系 
 * "这个 mod 装在哪个 mod 里面" / "这个 mod 里面装了哪些 mod".
 * 本工程虽会解析嵌套库({@code getNestedLibraries()}),但**不把嵌套 jar 当作独立
 * mod 文件产出**,所以不存在父子容器关系.
 * <p>返回空而**不是**抛异常:对"顶层 mod"而言"没有父,没有子"是**正确的答案**,
 * 不是缺失的能力  与 {@code MinimalFabricModMetadata} 里扩展缺失时的降级同一道理.
 * 真正需要报错的是"我们有嵌套却没建模",而那个前提当前不成立.
*/
public final class MinimalFabricModContainer implements ModContainer {

    private final IModFile file;
    private final ModMetadata metadata;
    private final List<Path> rootPaths;
    private final ModOrigin origin;

    public MinimalFabricModContainer(IModFile file) {
        this.file = file;
        this.metadata = new MinimalFabricModMetadata(file);
        this.rootPaths = List.copyOf(file.getClasspathRoots());
        this.origin = new FabricModOrigin(file.getFilePath());
    }

    @Override
    public ModMetadata getMetadata() {
        return metadata;
    }

/**
     * mod 的根路径们.
     * <p>取自 {@code IModFile.getClasspathRoots()} 而不是 {@code getFilePath()}:
     * 目录型 mod 与 jar 型 mod 的"根"不同  jar 的根是 jar 本身,
     * 目录型 mod 的根是目录.统一模型已经把这个差异消化在 {@code getClasspathRoots()} 里,
     * 在这里绕过它去用文件路径会让目录型 mod 拿到错误的根.
*/
    @Override
    public List<Path> getRootPaths() {
        return rootPaths;
    }

    @Override
    public ModOrigin getOrigin() {
        return origin;
    }

/**
     * 解析 mod 内的文件路径.
     * <p>相对**第一个**根解析并返回  不检查存在性:Fabric 的这个方法语义是
     * "给定位路径",存在性由调用方按需检查(并且存在 {@code findPath} 走 Optional 那条路).
     * <p>没有任何根时抛异常而不是返回一个凭空拼出来的路径:那种路径看起来合法,
     * 但指向不存在的位置,调用方要隔很远才发现.
*/
    @Override
    public Path getPath(String file) {
        // 复用 getRootPath() 而不是再写一遍空检查  两处各自维护同一条前置条件,
        // 迟早会改歪一处(用户明确厌恶的重复公式).
        return getRootPath().resolve(file);
    }

/**
     * 主根路径.
     * <p><b>实测修正</b>:这是**抽象方法**.我原先以为它有默认实现 
     * 那是把 grep 出的第 106 行 {@code return getRootPath();} 当成了它自己的方法体,
     * 实际上那一行属于**另一个**默认方法.列出方法签名**不能**区分抽象与默认,
     * 只有读方法体才行.
     * <p>语义与 {@code getRootPaths()} 的首元素一致.
*/
    @Override
    public Path getRootPath() {
        if (rootPaths.isEmpty()) {
            throw new NoSuchElementException(
                    "mod '" + metadata.getId() + "' 没有任何根路径");
        }
        return rootPaths.get(0);
    }

    @Override
    public Optional<ModContainer> getContainingMod() {
        // 顶层文件:没有父.见类注释  这是正确答案,不是缺失的能力.
        return Optional.empty();
    }

    @Override
    public Collection<ModContainer> getContainedMods() {
        // 嵌套 jar 不作为独立 mod 文件产出,因此没有子容器.
        return List.of();
    }

    @Override
    public String toString() {
        return "MinimalFabricModContainer[" + metadata.getId() + " roots=" + rootPaths.size() + "]";
    }
}
