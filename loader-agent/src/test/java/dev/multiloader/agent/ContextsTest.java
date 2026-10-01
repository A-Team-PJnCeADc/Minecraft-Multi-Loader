package dev.multiloader.agent;

import dev.multiloader.api.locating.IModFile;
import dev.multiloader.api.metadata.GameSide;
import dev.multiloader.api.service.IGameContext;
import dev.multiloader.api.service.ILoaderContext;
import dev.multiloader.common.Side;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LoaderContextImpl} 与 {@link GameContextImpl} 的行为测试.
 * <p>用 {@code Side.parse("server")} 而不是 {@code Side.SERVER}:常量名没核实过,
 * 而 {@code parse} 是已在 {@code StartupArgs} 里用到的入口  不猜没看过的符号.
*/
class ContextsTest {

    private static final String SERVICE_NAME = "test.service";

/** 测试用的加载器:JUnit 5 不会注入 ClassLoader 参数,所以取本类的. */
    private static final ClassLoader TEST_LOADER = ContextsTest.class.getClassLoader();

    private static StartupArgs args(Path gameRoot) {
        return new StartupArgs(
                gameRoot,
                gameRoot.resolve("mods"),
                "26.3",
                Side.parse("server"),
                true,                       // development
                List.of(),
                false,                      // tolerateFailure
                Map.of());
    }

    // LoaderContextImpl:字段读取

    @Test
    void loaderContextExposesTheStartupArgsFields(@TempDir Path tmp) {
        ILoaderContext ctx = new LoaderContextImpl(args(tmp), List::of);

        assertEquals(tmp, ctx.gameRoot());
        assertEquals(tmp.resolve("mods"), ctx.modsDir());
        assertEquals("26.3", ctx.minecraftVersion());
        assertTrue(ctx.isDevelopment());
    }

    @Test
    void loaderContextReportsNonDevelopmentToo(@TempDir Path tmp) {
        // 反向:development=false 时必须报 false,而不是恒 true.
        StartupArgs args = new StartupArgs(tmp, tmp.resolve("mods"), "26.3",
                Side.parse("server"), false, List.of(), false, Map.of());

        assertFalse(new LoaderContextImpl(args, List::of).isDevelopment());
    }

    @Test
    void loaderContextExposesTheProcessSide(@TempDir Path tmp) {
        // 实现边界的映射:runtime-common 的 Side ->loader-api 的 GameSide.
        // args(tmp) 用的是 Side.parse("server"),所以这里应为 SERVER.
        assertEquals(GameSide.SERVER, new LoaderContextImpl(args(tmp), List::of).side());
    }

    @Test
    void modFilesAreReadLazilyNotSnapshotted(@TempDir Path tmp) {
        // 这是本类最容易写错的一处.契约要求 modFiles() 随生命周期变化:
        // onLoaderInit 阶段可能为空,onGameBoot 阶段一定已填充.
        // 若构造时取快照,早于发现阶段创建的上下文会把"空"永久固化 
        // 扩展永远看不到任何 mod,且毫无线索.
        AtomicReference<List<IModFile>> current = new AtomicReference<>(List.of());
        ILoaderContext ctx = new LoaderContextImpl(args(tmp), current::get);

        assertTrue(ctx.modFiles().isEmpty(), "发现之前应为空");

        IModFile fake = new FakeModFile();
        current.set(List.of(fake));           // 模拟"发现阶段之后"
        assertEquals(1, ctx.modFiles().size(), "发现之后必须能看到 快照实现会在这里失败");
        assertSame(fake, ctx.modFiles().get(0));
    }

    @Test
    void loaderLayerIsTheDefiningClassLoaderNotTheContextClassLoader(@TempDir Path tmp) {
        // 不用 TCCL:TCCL 会被调用点改变,游戏启动后可能已是别的加载器,
        // 从而让扩展拿到错误的一层.
        ILoaderContext ctx = new LoaderContextImpl(args(tmp), List::of);

        assertSame(LoaderContextImpl.class.getClassLoader(), ctx.loaderLayer());
    }

    // GameContextImpl:服务注册与获取

    @Test
    void serviceRoundTrips() {
        IGameContext ctx = new GameContextImpl(TEST_LOADER);
        String service = "hello";

        ctx.registerService(String.class, service);

        assertSame(service, ctx.getService(String.class));
    }

    @Test
    void unregisteredServiceIsNullNotAnException() {
        // 查询"可选服务是否存在"是正常用法,不该抛异常打断调用方.
        assertNull(new GameContextImpl(TEST_LOADER).getService(Runnable.class));
    }

    @Test
    void duplicateRegistrationIsRejectedInsteadOfSilentlyOverwritten() {
        // 静默覆盖会让**先注册者的行为凭空消失**:两个扩展同时注册同一服务时,
        // 症状是"某个扩展的功能时有时无",极难归因.
        IGameContext ctx = new GameContextImpl(TEST_LOADER);
        ctx.registerService(String.class, "first");

        assertThrows(IllegalStateException.class, () -> ctx.registerService(String.class, "second"));
        assertSame("first", ctx.getService(String.class), "被拒的注册不得改变已有值");
    }

    @Test
    void nullServiceArgumentsAreRejected() {
        IGameContext ctx = new GameContextImpl(TEST_LOADER);

        assertThrows(IllegalArgumentException.class, () -> ctx.registerService(null, "x"));
        assertThrows(IllegalArgumentException.class, () -> ctx.registerService(String.class, null));
    }

    @Test
    void gameLoaderIsTheInjectedOne() {
        assertSame(TEST_LOADER, new GameContextImpl(TEST_LOADER).gameLoader());
    }

    // 明确拒绝:不静默收下

    @Test
    void registerClassProcessorIsRejectedLoudly() {
        // 收进一个没人读的列表 = 静默 no-op:扩展会以为转换已生效,实际永不调用.
        // 记警告也不行  会被日志淹没,而调用方**必须**知道它不会生效.
        IGameContext ctx = new GameContextImpl(TEST_LOADER);

        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                () -> ctx.registerClassProcessor(null));
        assertTrue(e.getMessage().contains("pipeline-probe"),
                "信息里应说明当前管道形状，便于判断该怎么做: " + e.getMessage());
    }

/** 一个最小的 IModFile 替身  本测试只用它验证"同一引用被透传". */
    private static final class FakeModFile implements IModFile {

        @Override
        public Path getFilePath() {
            return Path.of("fake.jar");
        }

        @Override
        public String getFormat() {
            return "test";
        }

        @Override
        public List<dev.multiloader.api.metadata.ModMetadata> getMetadataList() {
            return List.of();
        }

        @Override
        public List<dev.multiloader.api.metadata.ModDependency> getDependencies() {
            return List.of();
        }

        @Override
        public List<dev.multiloader.api.locating.MixinConfigRef> getMixinConfigRefs() {
            return List.of();
        }

        @Override
        public List<Path> getClasspathRoots() {
            return List.of();
        }

        @Override
        public List<Path> getNestedLibraries() {
            return List.of();
        }

        @Override
        public <T extends dev.multiloader.api.locating.IModFileExtension> java.util.Optional<T>
                getExtension(Class<T> extensionType) {
            return java.util.Optional.empty();
        }
    }
}
