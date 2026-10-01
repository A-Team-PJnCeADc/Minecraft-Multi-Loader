package dev.multiloader.bridge.fabric;

import dev.multiloader.api.service.MultiLoaderExtension;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@link FabricMultiLoaderExtension} 能被 ServiceLoader 发现.
 * <p><b>这条测试挡住的是本项目最贵的一类静默失效</b>:
 * 服务文件名或实现类 FQN 写错 ->核心层 `loadAll(MultiLoaderExtension.class)`
 * 返回空列表 ->钩子不执行 ->**日志里什么都不会出现**.
 * 而那个症状与"核心层根本没接 loadAll 调用点"**完全一致** 
 * 修法却完全不同(前者查 {@code META-INF/services},后者查 {@code LoaderBootstrap}).
 * <p>其余三条(钩子被调用 / context 字段正确 / 时机)**刻意不写成单测**:
 * 它们已被端到端验证,而且是更强的证据 
 * <ul>
 *   <li>{@code mods=0} 证明 onLoaderInit 早于发现(插错位置会显示 mods=2)</li>
 *   <li>{@code gameLoader=MultiLoader Game} 证明 onGameBoot 晚于建图(提前会显示 app)</li>
 * </ul>
 * 要把它们写成单测,就得在测试里调 {@code LoaderBootstrap.bootstrap(...)},
 * 那会牵扯游戏 jar 探测,三层类加载图,Mixin bootstrap  等于把端到端塞进单测:
 * 慢,脆弱,测的还是同一件事,而判据从"真实运行时"退化成"桩环境".
*/
class ServiceLoaderDiscoveryTest {

    @Test
    void multiLoaderExtensionIsDiscoverable() {
        List<MultiLoaderExtension> discovered = ServiceLoader
                .load(MultiLoaderExtension.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .filter(e -> e.getClass().getName().startsWith("dev.multiloader.bridge.fabric."))
                .toList();

        assertTrue(discovered.stream().anyMatch(e -> e instanceof FabricMultiLoaderExtension),
                "ServiceLoader 未发现 FabricMultiLoaderExtension 检查 "
                        + "META-INF/services/dev.multiloader.api.service.MultiLoaderExtension "
                        + "的文件名与内容。已发现: " + discovered);
    }

    @Test
    void extensionNameIsStableForDiagnostics() {
        // name() 会被核心层打进"扩展失败"的错误信息里(LoaderBootstrap 的 catch 分支),
        // 所以它是诊断契约的一部分:改了它,日志里的定位线索就跟着变.
        assertTrue(new FabricMultiLoaderExtension().name().contains("fabric"),
                "扩展名应能让人看出是 Fabric 侧");
    }
}
