package dev.multiloader.api.lifecycle;

import dev.multiloader.api.locating.IModFile;

import java.util.List;
import java.util.Map;

/**
 * 把某个格式的入口点声明翻译成统一的 {@link EntrypointRef} 列表.
 * <p>三个格式适配器各实现一个,通过 {@code META-INF/services} 注册.
 * 核心层的 {@code EntrypointDispatcher} 只认这个接口,不认识任何格式细节.
*/
public interface IEntrypointProvider {

/**
     * @return 阶段 ->入口点列表.没有入口点的阶段可以不出现该键.
*/
    Map<LifecyclePhase, List<EntrypointRef>> getEntrypoints(IModFile file);
}
