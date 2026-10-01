package dev.multiloader.bridge.neoforge;

import dev.multiloader.api.locating.IModFile;
import dev.multiloader.api.locating.IModFileExtension;
import dev.multiloader.api.locating.MixinConfigRef;
import dev.multiloader.api.metadata.Environment;
import dev.multiloader.api.metadata.ModDependency;
import dev.multiloader.api.metadata.ModMetadata;
import dev.multiloader.api.service.IEntrypointServiceProvider;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NeoForgeEntrypointServiceProvider} 的行为测试.
 * <p>最要紧的一条是{@link #injectedBusIsTheSameInstanceAsContainerEventBus()}:
 * 它是"全局 bus + 按 mod 建 container"这个组合是否自洽的唯一保证.
*/
class NeoForgeEntrypointServiceProviderTest {

    private static final String BUS = NeoForgeEntrypointServiceProvider.EVENT_BUS_FQN;
    private static final String CONTAINER = NeoForgeEntrypointServiceProvider.MOD_CONTAINER_FQN;

    private static IModFile modFile(String modId) {
        return new FakeModFile(modId);
    }

    // 能力清单(Q2 裁定新增的那一节)

    @Test
    void providedFqnsListsExactlyTheSupportedTypes() {
        // 清单必须是**精确**的:多写一个 FQN 会让调度器把参数交给一个
        // 其实提供不了的 provider(然后在 provide 里炸);少写一个则永远
        // 走到"找不到 provider",而报错清单还会误导人去查别处.
        assertEquals(Set.of(BUS, CONTAINER),
                new NeoForgeEntrypointServiceProvider().providedFqns());
    }

    @Test
    void canProvideDelegatesToTheCapabilityList() {
        NeoForgeEntrypointServiceProvider provider = new NeoForgeEntrypointServiceProvider();

        assertTrue(provider.canProvide(BUS));
        assertTrue(provider.canProvide(CONTAINER));
        assertFalse(provider.canProvide("net.minecraft.server.MinecraftServer"),
                "不该声称能提供不归它管的东西");
    }

    // 全局 game bus

    @Test
    void eventBusIsGlobalAcrossMods() {
        // 按已定设计(§7):NeoForge 的真实语义就是全局 bus.
        // 跨 mod 共享不会互相干扰  注销按 owner 匹配,不是按 bus 实例.
        NeoForgeEntrypointServiceProvider provider = new NeoForgeEntrypointServiceProvider();

        Object forModA = provider.provide(BUS, modFile("mod-a"));
        Object forModB = provider.provide(BUS, modFile("mod-b"));

        assertSame(forModA, forModB, "bus 必须跨 mod 是同一个实例");
        assertSame(provider.gameBus(), forModA);
    }

    // ModContainer 按 modId 缓存

    @Test
    void containerIsCachedPerModId() {
        // 同一个 mod 反复取必须得同一实例:否则 mod 在别处拿到 container A,
        // 这里拿到 container B,两者的 getEventBus() 虽然相同(全局 bus),
        // 但"容器"本身代表 mod 身份,重复实例会让将来加状态时出现两份.
        NeoForgeEntrypointServiceProvider provider = new NeoForgeEntrypointServiceProvider();

        Object first = provider.provide(CONTAINER, modFile("mod-a"));
        Object again = provider.provide(CONTAINER, modFile("mod-a"));

        assertSame(first, again, "同一 modId 必须命中缓存");
    }

    @Test
    void containerDiffersBetweenMods() {
        // 反向:不同 mod 必须是不同容器,否则两个 mod 的 modId/版本会互相串.
        NeoForgeEntrypointServiceProvider provider = new NeoForgeEntrypointServiceProvider();

        Object forA = provider.provide(CONTAINER, modFile("mod-a"));
        Object forB = provider.provide(CONTAINER, modFile("mod-b"));

        assertNotSame(forA, forB, "不同 mod 的容器不能是同一个");
        assertEquals("mod-a", ((MinimalModContainer) forA).getModId());
        assertEquals("mod-b", ((MinimalModContainer) forB).getModId());
    }

    // 跨类一致性:这一条是整段设计的"接缝"

    @Test
    void injectedBusIsTheSameInstanceAsContainerEventBus() {
        // @Mod 构造器拿到 bus 并注册监听器;同一 mod 的 container.getEventBus()
        // 若返回**另一个** bus,监听器就进了一个没人用的总线 
        // 表现为"注册成功但永不触发",是极难诊断的失效形态.
        // 这条断言把 provider 与容器两个类之间的接缝钉住.
        NeoForgeEntrypointServiceProvider provider = new NeoForgeEntrypointServiceProvider();
        IModFile file = modFile("mod-a");

        Object injectedBus = provider.provide(BUS, file);
        MinimalModContainer container = (MinimalModContainer) provider.provide(CONTAINER, file);

        assertSame(injectedBus, container.getEventBus(),
                "注入的 bus 与 container.getEventBus() 必须是同一个对象");
    }

    // 未知 FQN

    @Test
    void unknownFqnIsRejectedLoudly() {
        // 不可达路径(调用方保证先过 canProvide),但不能静默返回 null 
        // 那会让失败点跑到 mod 的构造器里,堆栈不指向"问错了 provider".
        NeoForgeEntrypointServiceProvider provider = new NeoForgeEntrypointServiceProvider();

        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                () -> provider.provide("com.example.Unknown", modFile("mod-a")));
        assertTrue(e.getMessage().contains("com.example.Unknown"), e.getMessage());
        assertTrue(e.getMessage().contains(BUS), "应提示它到底能提供什么: " + e.getMessage());
    }

    // 服务注册(与 adapt-* 同一类静默失效的防护)

    @Test
    void providerIsDiscoverableByServiceLoader() {
        // 单测都直接 new,绕过 META-INF/services.服务文件名或 FQN 写错时,
        // 上面的断言全绿而加载器永远发现不到这个 provider 
        // 表现为"@Mod 构造器一律报找不到 provider",且没有任何线索.
        List<IEntrypointServiceProvider> discovered = ServiceLoader
                .load(IEntrypointServiceProvider.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .filter(p -> p.getClass().getName().startsWith("dev.multiloader.bridge.neoforge."))
                .toList();

        assertTrue(discovered.stream().anyMatch(p -> p instanceof NeoForgeEntrypointServiceProvider),
                "ServiceLoader 未发现 NeoForgeEntrypointServiceProvider 检查 "
                        + "META-INF/services/dev.multiloader.api.service.IEntrypointServiceProvider。"
                        + "已发现: " + discovered);
    }

    // 脚手架

    private static final class FakeModFile implements IModFile {

        private final ModMetadata metadata;

        FakeModFile(String modId) {
            this.metadata = new ModMetadata(modId, "1.0.0", modId + " display", "desc",
                    List.of(), "MIT", Map.of(), Environment.BOTH);
        }

        @Override
        public Path getFilePath() {
            return Path.of("fake.jar");
        }

        @Override
        public String getFormat() {
            return "neoforge";
        }

        @Override
        public List<ModMetadata> getMetadataList() {
            return List.of(metadata);
        }

        @Override
        public List<ModDependency> getDependencies() {
            return List.of();
        }

        @Override
        public List<MixinConfigRef> getMixinConfigRefs() {
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
        public <T extends IModFileExtension> Optional<T> getExtension(Class<T> extensionType) {
            return Optional.empty();
        }
    }
}
