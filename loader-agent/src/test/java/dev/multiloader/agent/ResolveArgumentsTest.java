package dev.multiloader.agent;

import dev.multiloader.api.lifecycle.EntrypointRef;
import dev.multiloader.api.lifecycle.LifecyclePhase;
import dev.multiloader.api.locating.IModFile;
import dev.multiloader.api.metadata.Environment;
import dev.multiloader.api.service.IEntrypointServiceProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LoaderBootstrap#resolveArguments} 的测试.
 * <p>被测的是**纯函数**部分(参数解析 + provider 查找 + 失败报错),
 * 反射调用本身不在这里 那需要真实类加载器与真实 mod 类,交给端到端.
 * 这样切分的理由:这两条性质(顺序,失败可见)是最容易悄悄错的,
 * 而它们不需要任何运行环境就能测;把它们和反射绑在一起测,
 * 等于每次都要跑一遍加载器,最终没人愿意改.
*/
class ResolveArgumentsTest {

    private static final String BUS = "net.neoforged.bus.api.IEventBus";
    private static final String CONTAINER = "net.neoforged.fml.ModContainer";

/** 一个可编程的 provider;记下每次 provide 收到的 file,用于验证透传. */
    private static final class FakeProvider implements IEntrypointServiceProvider {

        private final String name;
        private final Set<String> fqns;
        private final Map<String, Object> values;
        private IModFile lastFile;

        FakeProvider(String name, Set<String> fqns, Map<String, Object> values) {
            this.name = name;
            this.fqns = fqns;
            this.values = values;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Set<String> providedFqns() {
            return fqns;
        }

        @Override
        public Object provide(String parameterFqn, IModFile file) {
            if (!fqns.contains(parameterFqn)) {
                // 实现自己的防御:被问了不归自己的 FQN 应当响亮失败,
                // 而不是返回 null 否则调用方无从分辨"没有"与"有但为空".
                throw new UnsupportedOperationException("not mine: " + parameterFqn);
            }
            this.lastFile = file;
            return values.get(parameterFqn);
        }
    }

    private static EntrypointRef refWith(String... fqns) {
        return EntrypointRef.constructorWithServices("moda", LifecyclePhase.CONSTRUCT,
                "com.example.NeoMod", List.of(fqns), Environment.BOTH);
    }

    // 顺序

    @Test
    void argumentsFollowParameterFqnOrder() {
        // 顺序错位是最隐蔽的一类错误:参数类型都对,只是位置不对 
        // 要么抛 NoSuchMethodException(指向错因),要么命中另一个
        // 参数集相同的构造器(更糟).所以这条必须钉死.
        FakeProvider provider = new FakeProvider("fake", Set.of(BUS, CONTAINER),
                Map.of(BUS, "the-bus", CONTAINER, "the-container"));

        Object[] args = LoaderBootstrap.resolveArguments(
                refWith(CONTAINER, BUS), null, List.of(provider));   // 刻意反序

        assertEquals(2, args.length);
        assertEquals("the-container", args[0], "第一个形参是 ModContainer");
        assertEquals("the-bus", args[1], "第二个形参是 IEventBus");
    }

    @Test
    void argumentsAreResolvedAcrossMultipleProviders() {
        // 能力分散在多个 provider 时,按 FQN 逐个找对应者  而不是只问第一个.
        FakeProvider busProvider = new FakeProvider("bus-bridge", Set.of(BUS), Map.of(BUS, "bus"));
        FakeProvider containerProvider = new FakeProvider("container-bridge", Set.of(CONTAINER),
                Map.of(CONTAINER, "container"));

        Object[] args = LoaderBootstrap.resolveArguments(refWith(BUS, CONTAINER), null,
                List.of(busProvider, containerProvider));

        assertEquals("bus", args[0]);
        assertEquals("container", args[1]);
    }

    @Test
    void providerReceivesTheOwningModFile() {
        // file 是"按 mod 区分状态"的依据(每个 mod 的 ModContainer).
        // 若透传丢失,provider 只能退化成全局实例.
        FakeProvider provider = new FakeProvider("fake", Set.of(BUS), Map.of(BUS, "bus"));
        IModFile file = null;   // 本测试只关心"传的是同一个引用"

        LoaderBootstrap.resolveArguments(refWith(BUS), file, List.of(provider));

        assertSame(file, provider.lastFile);
    }

    @Test
    void emptyParameterListYieldsEmptyArguments() {
        // 边界:注入形态要求至少一个形参(EntrypointRef 的不变量保证),
        // 但纯函数本身不该因此崩掉.
        Object[] args = LoaderBootstrap.resolveArguments(
                EntrypointRef.noArgConstructor("moda", LifecyclePhase.CONSTRUCT, "c", Environment.BOTH),
                null, List.of());

        assertEquals(0, args.length);
    }

    // 失败可见

    @Test
    void missingProviderThrowsInsteadOfFillingNull() {
        // 静默塞 null 会让 mod 的构造器收到 null 参数,失败点远在别处,
        // 堆栈不指向"没人能提供这个类型".
        FakeProvider onlyBus = new FakeProvider("bus-bridge", Set.of(BUS), Map.of(BUS, "bus"));

        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                () -> LoaderBootstrap.resolveArguments(refWith(CONTAINER), null, List.of(onlyBus)));

        assertTrue(e.getMessage().contains(CONTAINER), e.getMessage());
    }

    @Test
    void missingProviderMessageListsEachProviderCapability() {
        // 这是 providedFqns() 存在的理由:只列 provider 名字的话,
        // "缺 provider"与"provider 在场但漏了这个 FQN"在日志里长得一样,
        // 而两者修法完全不同.
        FakeProvider onlyBus = new FakeProvider("bus-bridge", Set.of(BUS), Map.of(BUS, "bus"));

        String message = assertThrows(UnsupportedOperationException.class,
                () -> LoaderBootstrap.resolveArguments(refWith(CONTAINER), null, List.of(onlyBus)))
                .getMessage();

        assertTrue(message.contains("bus-bridge"), "应列出 provider 名字: " + message);
        assertTrue(message.contains(BUS), "应列出该 provider 的能力清单: " + message);
        assertTrue(message.contains("com.example.NeoMod"), "应指明是哪个 @Mod 类: " + message);
        assertTrue(message.contains("moda"), "应指明是哪个 mod: " + message);
    }

    @Test
    void missingProviderMessageDistinguishesNoProvidersAtAll() {
        // provider 列表为空 ->桥接层不在运行时类路径上,这是**另一种**故障.
        // 报错必须能区分,否则会去查 provider 的能力清单(而根本没人注册).
        String message = assertThrows(UnsupportedOperationException.class,
                () -> LoaderBootstrap.resolveArguments(refWith(BUS), null, List.of()))
                .getMessage();

        assertTrue(message.contains("（无）"), "应明确说没有任何 provider: " + message);
        assertTrue(message.contains("运行时类路径"), "应提示桥接层缺失这一可能: " + message);
    }

    @Test
    void canProvideDefaultsToProvidedFqns() {
        // 默认方法必须与能力清单一致  否则报错清单会与实际判定脱节,
        // 那是最难查的一类不一致(日志说的和代码做的不是一回事).
        FakeProvider provider = new FakeProvider("fake", Set.of(BUS), Map.of(BUS, "bus"));

        assertTrue(provider.canProvide(BUS));
        assertTrue(!provider.canProvide(CONTAINER));
    }
}
