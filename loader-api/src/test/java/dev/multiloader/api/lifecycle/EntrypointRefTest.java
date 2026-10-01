package dev.multiloader.api.lifecycle;

import dev.multiloader.api.metadata.Environment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EntrypointRef} 的契约测试.
 * <p>为什么值得单独测:这个 record 是**冻结的 API**三个适配器都要按它的形状
 * 填数据,改它的形状要同时动三处.所以"什么样的组合是合法的"必须在模块内钉死,
 * 而不是靠适配器作者自觉.
 * <p>核心设计意图:**让不合法的状态造不出来**.kind 要求方法名却没给,
 * 或者 kind 不要方法名却给了,都应当在构造期就抛异常,而不是等到调度时
 * 以 {@code NullPointerException} 或"方法不存在"的形式在用户机器上炸.
*/
class EntrypointRefTest {

    @Test
    void noArgMethodFactoryCarriesMethodName() {
        EntrypointRef ref = EntrypointRef.noArgMethod("moda", LifecyclePhase.INIT,
                "com.example.Mod", "onInitialize", Environment.BOTH);

        assertEquals("moda", ref.modId());
        assertEquals(LifecyclePhase.INIT, ref.phase());
        assertEquals("com.example.Mod", ref.className());
        assertEquals(EntrypointKind.NO_ARG_METHOD, ref.kind());
        assertEquals("onInitialize", ref.methodName());
        assertEquals(Environment.BOTH, ref.environment());
    }

    @Test
    void noArgConstructorFactoryCarriesNoMethodName() {
        EntrypointRef ref = EntrypointRef.noArgConstructor("moda", LifecyclePhase.INIT,
                "com.example.NeoMod", Environment.BOTH);

        assertEquals(EntrypointKind.NO_ARG_CONSTRUCTOR, ref.kind());
        assertNull(ref.methodName(), "构造器即入口点，不该带方法名");
    }

    @Test
    void methodKindWithoutMethodNameIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new EntrypointRef("moda", LifecyclePhase.INIT, "com.example.Mod",
                        EntrypointKind.NO_ARG_METHOD, null, Environment.BOTH, List.of()));
        assertTrue(e.getMessage().contains("non-blank method name"), e.getMessage());
    }

    @Test
    void blankMethodNameIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new EntrypointRef("moda", LifecyclePhase.INIT, "com.example.Mod",
                        EntrypointKind.NO_ARG_METHOD, "   ", Environment.BOTH, List.of()));
    }

    @Test
    void nonMethodKindWithMethodNameIsRejected() {
        // 结构性约束的反向:不该有方法名的形态带了方法名,说明适配器填错了字段.
        // 静默忽略会让"我明明写了方法名为什么没被调用"变成无从下手的谜题.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new EntrypointRef("moda", LifecyclePhase.INIT, "com.example.NeoMod",
                        EntrypointKind.NO_ARG_CONSTRUCTOR, "onInitialize", Environment.BOTH, List.of()));
        assertTrue(e.getMessage().contains("must not carry a method name"), e.getMessage());
    }

    @Test
    void constructorWithServicesFactoryCarriesTheParameterTypes() {
        // 注入形态靠构造器签名决定调用方式,不需要方法名;但要带形参类型.
        EntrypointRef ref = EntrypointRef.constructorWithServices("moda", LifecyclePhase.CONSTRUCT,
                "com.example.NeoMod",
                List.of("net.neoforged.bus.api.IEventBus"), Environment.BOTH);

        assertEquals(EntrypointKind.CONSTRUCTOR_WITH_SERVICES, ref.kind());
        assertNull(ref.methodName(), "注入形态不需要方法名");
        assertEquals(List.of("net.neoforged.bus.api.IEventBus"), ref.constructorParamFQNs());
        assertTrue(ref.requiresServiceInjection());
    }

    @Test
    void injectionKindWithMethodNameIsStillRejected() {
        // 方法名校验排在形参校验**之前**,所以这里即使形参合法,
        // 也必须因为带了方法名而被拒  且错误信息说的是"不能带方法名".
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new EntrypointRef("moda", LifecyclePhase.INIT, "com.example.NeoMod",
                        EntrypointKind.CONSTRUCTOR_WITH_SERVICES, "onInitialize", Environment.BOTH,
                        List.of("net.neoforged.bus.api.IEventBus")));
        assertTrue(e.getMessage().contains("must not carry a method name"), e.getMessage());
    }

    //
    // constructorParamFQNs 的不变量(正反两向)
    //

    @Test
    void injectionKindRequiresAtLeastOneParameter() {
        // 注入形态却没有形参 = 自相矛盾(那就该用 NO_ARG_CONSTRUCTOR).
        // 允许它存在会让调度器拿到一个空参数列表,构造成无参调用 
        // 而类里没有无参构造器,最终失败在 NoSuchMethodException,指向错因.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new EntrypointRef("moda", LifecyclePhase.CONSTRUCT, "com.example.NeoMod",
                        EntrypointKind.CONSTRUCTOR_WITH_SERVICES, null, Environment.BOTH, List.of()));
        assertTrue(e.getMessage().contains("at least one constructor parameter"), e.getMessage());
    }

    @Test
    void otherKindsMustNotCarryConstructorParameters() {
        // 反向:别的 kind 带了形参说明适配器填错了字段.若不禁止,
        // 就会出现一个没人读的字段  下一个人会以为它有意义,而调度器根本不看.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new EntrypointRef("moda", LifecyclePhase.INIT, "com.example.Mod",
                        EntrypointKind.NO_ARG_METHOD, "onInitialize", Environment.BOTH,
                        List.of("net.neoforged.bus.api.IEventBus")));
        assertTrue(e.getMessage().contains("must not carry constructor parameters"), e.getMessage());
    }

    @Test
    void everyComponentIsRequired() {
        assertThrows(NullPointerException.class, () -> new EntrypointRef(
                null, LifecyclePhase.INIT, "c", EntrypointKind.NO_ARG_METHOD, "m", Environment.BOTH,
                List.of()));
        assertThrows(NullPointerException.class, () -> new EntrypointRef(
                "moda", null, "c", EntrypointKind.NO_ARG_METHOD, "m", Environment.BOTH, List.of()));
        assertThrows(NullPointerException.class, () -> new EntrypointRef(
                "moda", LifecyclePhase.INIT, null, EntrypointKind.NO_ARG_METHOD, "m", Environment.BOTH,
                List.of()));
        assertThrows(NullPointerException.class, () -> new EntrypointRef(
                "moda", LifecyclePhase.INIT, "c", null, "m", Environment.BOTH, List.of()));
        assertThrows(NullPointerException.class, () -> new EntrypointRef(
                "moda", LifecyclePhase.INIT, "c", EntrypointKind.NO_ARG_METHOD, "m", null, List.of()));
        // 新字段同样是必需项:null 与"空列表"是两回事,前者是漏填,后者是"确实没有".
        assertThrows(NullPointerException.class, () -> new EntrypointRef(
                "moda", LifecyclePhase.INIT, "c", EntrypointKind.NO_ARG_METHOD, "m", Environment.BOTH,
                null));
    }

    @Test
    void kindReportsWhetherMethodNameIsRequired() {
        assertTrue(EntrypointKind.NO_ARG_METHOD.requiresMethodName());
        assertFalse(EntrypointKind.NO_ARG_CONSTRUCTOR.requiresMethodName());
        assertFalse(EntrypointKind.CONSTRUCTOR_WITH_SERVICES.requiresMethodName(),
                "注入形态靠构造器签名决定调用方式，不需要方法名");
    }

    @Test
    void sideFilteringUsesTheRefsOwnEnvironment() {
        // 分发侧的实际过滤逻辑:ref 自带的 environment 决定它在哪一侧生效.
        // client 命名空间产出的 ref 在这里必须被滤掉  这条链路
        // (解析 ->环境 ->过滤)任何一环漏掉,都会让客户端类在服务端被实例化.
        EntrypointRef clientOnly = EntrypointRef.noArgMethod("moda", LifecyclePhase.SIDED_SETUP,
                "com.example.Client", "onInitializeClient", Environment.CLIENT);

        assertFalse(clientOnly.environment().appliesTo(false), "服务端不得选中 client-only 入口点");
        assertTrue(clientOnly.environment().appliesTo(true));
    }
}
