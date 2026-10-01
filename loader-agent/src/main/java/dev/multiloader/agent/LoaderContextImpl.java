package dev.multiloader.agent;

import dev.multiloader.agent.StartupArgs;
import dev.multiloader.api.locating.IModFile;
import dev.multiloader.api.metadata.GameSide;
import dev.multiloader.api.service.ILoaderContext;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@link ILoaderContext} 的核心侧实现:把 {@link StartupArgs} 与当前的 mod 列表
 * 暴露给扩展(bridge-* / adapt-*).
 * <p><b>为什么存在</b>:Fabric 的桥接走**静态无参访问**
 * (`FabricLoader.getInstance().getConfigDir()`),不像 NeoForge 那条每次调用都能拿到
 * `provide(fqn, file)` 的 `file` 参数.所以 Fabric 侧**没有任何途径**得知
 * gameRoot / modsDir / MC 版本 / mod 列表  必须有这条反向通道把加载器事实推给扩展.
 * <p><b>为什么 mod 列表是 {@code Supplier} 而不是构造时的快照</b>:
 * `ILoaderContext` 的契约写明 `modFiles()` 在 `onLoaderInit` 阶段可能为空,
 * 在 `onGameBoot` 阶段一定已填充.若构造时取快照,早于发现阶段创建的上下文
 * 会把"空"永久固化  表现为扩展永远看不到任何 mod,且毫无线索.
*/
final class LoaderContextImpl implements ILoaderContext {

    private final StartupArgs args;
    private final Supplier<List<IModFile>> modFiles;

    LoaderContextImpl(StartupArgs args, Supplier<List<IModFile>> modFiles) {
        this.args = args;
        this.modFiles = modFiles;
    }

    @Override
    public GameSide side() {
        // 唯一的映射点:runtime-common 的 Side ->loader-api 的 GameSide.
        // loader-api 不能依赖 runtime-common(INV-1 零依赖),所以转换只在这里发生.
        // 用穷尽 switch 而不是 side.isClient() 三元式:Side 将来若多出一个取值,
        // 这里会编译失败从而强制复核,而不是默默把新值归到 SERVER.
        return switch (args.side()) {
            case CLIENT -> GameSide.CLIENT;
            case SERVER -> GameSide.SERVER;
        };
    }

    @Override
    public Path gameRoot() {
        return args.gameRoot();
    }

    @Override
    public Path modsDir() {
        return args.modsDir();
    }

    @Override
    public String minecraftVersion() {
        return args.minecraftVersion();
    }

    @Override
    public boolean isDevelopment() {
        return args.development();
    }

    @Override
    public List<IModFile> modFiles() {
        // 惰性读取:契约要求它随生命周期变化(onLoaderInit 可能空,onGameBoot 必已填充)
        List<IModFile> current = modFiles.get();
        return current == null ? List.of() : List.copyOf(current);
    }

    @Override
    public ClassLoader loaderLayer() {
        // 定义本类(因而定义所有加载器侧代码)的加载器  扩展自身的类由它加载.
        // 用本类的加载器而不是 Thread.currentThread() 的:后者会被调用点改变,
        // 在游戏启动后可能已经是别的加载器,从而让扩展拿到错误的一层.
        return LoaderContextImpl.class.getClassLoader();
    }
}
