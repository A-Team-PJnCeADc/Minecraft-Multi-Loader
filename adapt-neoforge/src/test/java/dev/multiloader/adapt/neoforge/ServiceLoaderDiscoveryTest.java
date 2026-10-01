package dev.multiloader.adapt.neoforge;

import dev.multiloader.api.lifecycle.IEntrypointProvider;
import dev.multiloader.api.locating.IModFileReader;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证本模块的 SPI **真的能被 ServiceLoader 发现**.
 * <p><b>为什么这条测试不可省</b>:其它单测都直接 {@code new} 具体类,绕过了
 * {@code META-INF/services} 这一环.而服务注册有三种"写错就静默失效"的形态:
 * <ol>
 *   <li>条目路径拼错(目录名 / 接口 FQN 错一个字)</li>
 *   <li>文件里的实现类 FQN 拼错</li>
 *   <li>FQN 对应的类不在产物里</li>
 * </ol>
 * 三者任一出错,**所有其它测试照样全绿**,而加载器永远发现不到这个适配器 
 * 表现为"NeoForge 格式的 mod 完全不被识别",且没有任何异常.
 * <p>这正是本项目反复遇到的"静默失效"形态.用一条会失败的断言把它钉住,
 * 比每次手工 unzip 核对产物可靠.
 * <p>这条测试之所以有效:测试运行时,本模块的 {@code main} 输出
 * (含 META-INF/services)就在测试类路径上,所以 ServiceLoader 的发现过程
 * 与加载器真实运行时一致.
*/
class ServiceLoaderDiscoveryTest {

    @Test
    void entrypointProviderIsDiscoverable() {
        List<IEntrypointProvider> providers = ServiceLoader.load(IEntrypointProvider.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .filter(p -> p.getClass().getName().startsWith("dev.multiloader.adapt.neoforge."))
                .toList();

        assertTrue(providers.stream().anyMatch(p -> p instanceof NeoForgeEntrypointProvider),
                "ServiceLoader 未发现 NeoForgeEntrypointProvider"
                        + "检查 META-INF/services/dev.multiloader.api.lifecycle.IEntrypointProvider "
                        + "的文件名与内容。已发现: " + providers);
    }

    @Test
    void modFileReaderIsDiscoverable() {
        List<IModFileReader> readers = ServiceLoader.load(IModFileReader.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .filter(r -> r.getClass().getName().startsWith("dev.multiloader.adapt.neoforge."))
                .toList();

        assertTrue(readers.stream().anyMatch(r -> r instanceof NeoForgeModFileReader),
                "ServiceLoader 未发现 NeoForgeModFileReader "
                        + "检查 META-INF/services/dev.multiloader.api.locating.IModFileReader。"
                        + "已发现: " + readers);
    }
}
