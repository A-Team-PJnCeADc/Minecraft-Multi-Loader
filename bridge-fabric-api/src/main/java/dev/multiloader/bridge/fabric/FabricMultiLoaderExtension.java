package dev.multiloader.bridge.fabric;

import dev.multiloader.api.locating.IModFile;
import dev.multiloader.api.service.IGameContext;
import dev.multiloader.api.service.ILoaderContext;
import dev.multiloader.api.service.MultiLoaderExtension;
import dev.multiloader.common.Log;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 把加载器的运行时事实捕获下来,供 {@code FabricLoader} 的静态访问读取.
 * <p><b>为什么必须存在这个类</b>:Fabric 的服务获取是**静态无参访问**
 * (`FabricLoader.getInstance().getConfigDir()`),不像 NeoForge 那条路每次调用都能拿到
 * `provide(fqn, file)` 的 `file` 参数.所以 Fabric 侧没有任何途径得知
 * gameRoot / modsDir / MC 版本 / mod 列表  必须由核心层在生命周期时点把上下文推过来,
 * 这是唯一的入口.
 * <p><b>用静态字段持有而不是实例字段</b>:Fabric 的访问点本身就是静态的
 * (`FabricLoader.getInstance()`),没有地方安放"扩展实例".核心层
 * `loadAll` 每次拿到的是新实例(或缓存实例,不保证),所以状态只能放静态.
 * 这也是 `MultiLoaderExtension` 的契约允许的:它没有承诺实例身份.
*/
public final class FabricMultiLoaderExtension implements MultiLoaderExtension {

    private static final AtomicReference<ILoaderContext> LOADER_CONTEXT = new AtomicReference<>();
    private static final AtomicReference<IGameContext> GAME_CONTEXT = new AtomicReference<>();

    @Override
    public String name() {
        return "fabric-bridge";
    }

    @Override
    public void onLoaderInit(ILoaderContext ctx) {
        LOADER_CONTEXT.set(ctx);
        // 显式打印,而不是只 set 字段:核心层"接了一半线"(调用了钩子但扩展没被
        // loadAll 发现)与"完全没接线"在日志里长得一样  这条日志把两者分开.
        Log.info("[fabric-bridge] onLoaderInit: gameRoot={} modsDir={} mcVersion={} dev={} mods={}",
                ctx.gameRoot(), ctx.modsDir(), ctx.minecraftVersion(), ctx.isDevelopment(),
                ctx.modFiles().size());
    }

    @Override
    public void onGameBoot(IGameContext ctx) {
        GAME_CONTEXT.set(ctx);
        Log.info("[fabric-bridge] onGameBoot: gameLoader={}", ctx.gameLoader().getName());
    }

/** 供 {@code FabricLoader} 实现读取.可能为 null  调用方负责给出可诊断的失败. */
    public static ILoaderContext loaderContext() {
        return LOADER_CONTEXT.get();
    }

    public static IGameContext gameContext() {
        return GAME_CONTEXT.get();
    }

/** 供诊断与测试:当前已发现的 mod 列表(未初始化时为空). */
    static List<IModFile> knownModFiles() {
        ILoaderContext ctx = LOADER_CONTEXT.get();
        return ctx == null ? List.of() : ctx.modFiles();
    }
}
