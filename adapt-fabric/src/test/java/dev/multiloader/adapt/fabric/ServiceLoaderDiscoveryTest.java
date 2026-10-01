package dev.multiloader.adapt.fabric;

import dev.multiloader.api.lifecycle.IEntrypointProvider;
import dev.multiloader.api.locating.IModFileReader;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证本模块的 SPI **真的能被 ServiceLoader 发现**(与 adapt-neoforge 的对称测试同源).
 * <p><b>为什么不能省</b>:其它单测都直接 {@code new} 具体类,绕过了
 * {@code META-INF/services}.而服务注册有三种"写错就静默失效"的形态:
 * <ol>
 *   <li>条目路径拼错(目录名 / 接口 FQN 错一个字)</li>
 *   <li>文件里的实现类 FQN 拼错</li>
 *   <li>FQN 对应的类不在产物里</li>
 * </ol>
 * 三者任一出错,**所有其它测试照样全绿**,而加载器永远发现不到这个适配器 
 * 表现为"Fabric 格式的 mod 完全不被识别",且没有任何异常.
 * <p>本条目前是"防将来改动",不是修现存缺陷:Fabric 侧的端到端已经跑通,
 * 说明当前注册是对的.但端到端不能替代它  端到端只在有人跑的时候才提醒.
*/
class ServiceLoaderDiscoveryTest {

    private static final String PACKAGE_PREFIX = "dev.multiloader.adapt.fabric.";

    @Test
    void modFileReaderIsDiscoverable() {
        List<IModFileReader> readers = ServiceLoader.load(IModFileReader.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .filter(r -> r.getClass().getName().startsWith(PACKAGE_PREFIX))
                .toList();

        assertTrue(readers.stream().anyMatch(r -> r instanceof FabricModFileReader),
                "ServiceLoader 未发现 FabricModFileReader 检查 "
                        + "META-INF/services/dev.multiloader.api.locating.IModFileReader "
                        + "的文件名与内容。已发现: " + readers);
    }

    @Test
    void entrypointProviderIsDiscoverable() {
        List<IEntrypointProvider> providers = ServiceLoader.load(IEntrypointProvider.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .filter(p -> p.getClass().getName().startsWith(PACKAGE_PREFIX))
                .toList();

        assertTrue(providers.stream().anyMatch(p -> p instanceof FabricEntrypointProvider),
                "ServiceLoader 未发现 FabricEntrypointProvider 检查 "
                        + "META-INF/services/dev.multiloader.api.lifecycle.IEntrypointProvider。"
                        + "已发现: " + providers);
    }
}
