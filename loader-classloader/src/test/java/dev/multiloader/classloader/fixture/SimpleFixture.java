package dev.multiloader.classloader.fixture;

/**
 * 类加载器测试用的夹具类.
 * <p>刻意保持零依赖:它会被一个以平台类加载器为父的
 * {@code TransformingClassLoader} 重新加载,任何对其他类的引用都会
 * 因为父加载器解析不到而失败,从而掩盖真正要测的东西.
*/
public final class SimpleFixture {

    public static final String GREETING = "fixture-hello";

    private SimpleFixture() {
    }

    public static String greet() {
        return GREETING;
    }

    public int instanceValue() {
        return 42;
    }
}
