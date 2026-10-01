package dev.multiloader.bridge.fabric.meta;

import net.fabricmc.loader.api.metadata.ModOrigin;
// 显式 import 嵌套枚举 Kind  不依赖"从 implements 的接口继承成员类型"这条规则的解析.
// javac 接受未限定写法,但 IDE 的增量索引与其他编译器(如 ECJ)未必,
// 症状是"找不到符号 Kind"或类型不兼容,而 javac 构建全绿.
import net.fabricmc.loader.api.metadata.ModOrigin.Kind;

import java.nio.file.Path;
import java.util.List;

/**
 * {@link ModOrigin} 的映射实现  本期只覆盖"顶层 mod 文件"这一形态.
 * <p>来源:{@code IModFile.getFilePath()}(统一模型).
 * <p><b>为什么只实现 {@link Kind#PATH} 形态</b>:Fabric 的 ModOrigin 有三种:
 * <pre>
 *   PATH     直接来自磁盘上的一个文件/目录(本期唯一支持的形态)
 *   NESTED   来自另一个 mod 的嵌套 jar(jar-in-jar)
 *   UNKNOWN  来源不明
 * </pre>
 * NESTED 需要嵌套 jar 的父子关系({@code getParentModId} / {@code getParentSubLocation}),
 * 那依赖本工程尚未实现的嵌套库模型.本期嵌套库虽会被解析({@code getNestedLibraries()}),
 * 但不作为独立的 mod 文件产出,所以不会走到这个分支.
 * <p><b>为什么 NESTED 用拒绝而不是降级成 PATH</b>:降级会让上层把"嵌套来源"
 * 误认成顶层文件,进而给出错误的路径与父子关系  而调用方无从分辨.
 * {@code getParentModId()} 对顶层文件返回 null 是 Fabric 的既有约定
 * (只有 NESTED 才有父).
*/
public final class FabricModOrigin implements ModOrigin {

    private final List<Path> paths;

    public FabricModOrigin(Path modFilePath) {
        this.paths = List.of(modFilePath);
    }

    @Override
    public Kind getKind() {
        return Kind.PATH;
    }

    @Override
    public List<Path> getPaths() {
        return paths;
    }

    @Override
    public String getParentModId() {
        // 顶层文件没有父  Fabric 对 PATH 形态返回 null.
        return null;
    }

    @Override
    public String getParentSubLocation() {
        return null;
    }

    @Override
    public String toString() {
        return "FabricModOrigin[PATH " + paths + "]";
    }
}
