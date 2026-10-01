package dev.multiloader.core.service;

import dev.multiloader.api.compat.LegacyCompatProvider;
import dev.multiloader.api.compat.RemapProcessorProvider;
import dev.multiloader.api.lifecycle.IEntrypointProvider;
import dev.multiloader.api.locating.IModFileCandidateLocator;
import dev.multiloader.api.locating.IModFileReader;
import dev.multiloader.core.discovery.DirectoryModCandidateLocator;
import dev.multiloader.core.discovery.ModDiscoverer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ServiceLoader SPI 装配的验证.
 * <p>为什么需要这个测试类:现有测试都是直接 {@code new FabricModFileReader()},
 * 因此**从来没有**验证过 {@code META-INF/services} 注册文件是否真的生效.
 * 那正是"加一个加载器格式 = 加一个 service 文件"这个架构承诺的落点
 * 注册文件写错(路径,类名拼错)在编译期和直接 new 的测试里都发现不了.
 * <p>这里的 ServiceLoader 走的是 Gradle 把 main 的 resources 放进测试 classpath 的那份输出,
 * 与打进 jar 的是同一份内容.
*/
class ServiceRegistryTest {

    @Test
    void fabricReaderIsDiscoveredViaServiceLoader() {
        List<IModFileReader> readers = ServiceRegistry.loadAll(IModFileReader.class);

        assertFalse(readers.isEmpty(),
                "没有发现任何 IModFileReader META-INF/services 注册文件没生效");
        assertTrue(readers.stream().anyMatch(r -> r instanceof dev.multiloader.adapt.fabric.FabricModFileReader),
                () -> "应通过 ServiceLoader 发现 FabricModFileReader，实际: "
                        + readers.stream().map(r -> r.getClass().getName()).toList());
    }

    @Test
    void entrypointProviderIsDiscoveredViaServiceLoader() {
        List<IEntrypointProvider> providers = ServiceRegistry.loadAll(IEntrypointProvider.class);

        assertTrue(providers.stream()
                        .anyMatch(p -> p instanceof dev.multiloader.adapt.fabric.FabricEntrypointProvider),
                () -> "应发现 FabricEntrypointProvider，实际: "
                        + providers.stream().map(p -> p.getClass().getName()).toList());
    }

    @Test
    void adaptersAreSortedByPriorityDeterministically() {
        // 同一份 classpath 每次必须得到同一个顺序,否则加载顺序会变成掷骰子
        List<IModFileReader> first = ServiceRegistry.loadAll(IModFileReader.class);
        List<IModFileReader> second = ServiceRegistry.loadAll(IModFileReader.class);

        assertEquals(
                first.stream().map(r -> r.getClass().getName()).toList(),
                second.stream().map(r -> r.getClass().getName()).toList());
    }

/**
     * 兼容层预留机制的核心验证.
     * <p>本轮没有实现 {@code compat-*} 模块,所以这两个 SPI **必须**返回空 Optional
     * 而不是抛异常,也不是报错.这就是"核心层不需要知道兼容层存在"的落地断言:
     * 将来兼容层只要加一个 service 文件就能接上,核心层一行都不用改.
*/
    @Test
    void compatSpiIsEmptyAndNonThrowingWhenNoCompatLayerIsPresent() {
        assertTrue(ServiceRegistry.loadOptional(LegacyCompatProvider.class).isEmpty(),
                "本轮不该有 LegacyCompatProvider 实现；如果有，说明有东西意外混进了 classpath");
        assertTrue(ServiceRegistry.loadOptional(RemapProcessorProvider.class).isEmpty(),
                "本轮不该有 RemapProcessorProvider 实现");
    }

    @Test
    void loadRequiredFailsLoudlyWhenNothingIsRegistered() {
        // 反向验证:确实"必须有"的服务缺失时要明确失败,不能返回 null
        IllegalStateException e = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> ServiceRegistry.loadRequired(LegacyCompatProvider.class));
        assertTrue(e.getMessage().contains(LegacyCompatProvider.class.getName()), e.getMessage());
    }

    @Test
    void modDiscovererFromServicesFindsTheAdapterEndToEnd(@TempDir Path tmp) throws IOException {
        // 完整走一遍装配路径:ServiceLoader 找 reader,发现真实 mod
        Path mods = Files.createDirectories(tmp.resolve("mods"));
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(mods.resolve("m.jar")))) {
            out.putNextEntry(new JarEntry("fabric.mod.json"));
            out.write("{\"schemaVersion\":1,\"id\":\"servicemod\",\"version\":\"1.0.0\"}"
                    .getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        ModDiscoverer.DiscoveryResult result = ModDiscoverer.fromServices(
                List.<IModFileCandidateLocator>of(new DirectoryModCandidateLocator(mods))).run();

        assertTrue(result.problems().isEmpty(), () -> result.problems().toString());
        assertEquals(1, result.modFiles().size(),
                "ServiceLoader 装配路径没能发现 mod ,reader 注册可能失效");
        assertEquals("servicemod", result.modFiles().get(0).getPrimaryModId());
    }
}
