package dev.multiloader.classloader;

import dev.multiloader.api.transform.ClassContext;
import dev.multiloader.api.transform.ClassProcessor;
import dev.multiloader.api.transform.ProcessPhase;
import dev.multiloader.api.transform.ProcessPhases;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 游戏层类加载器行为测试.
 * <p>覆盖三件事:
 * <ol>
 *   <li>child-first 生效 夹具类由我们的加载器定义,而不是被父加载器抢先</li>
 *   <li>管道真的跑到了目标类上</li>
 *   <li>两个 Mixin 逃逸口:{@code getClassBytes} 与 {@code loadClassNoTransform} 都跳过管道</li>
 * </ol>
*/
class TransformingClassLoaderTest {

    private static final String FIXTURE_BINARY = "dev.multiloader.classloader.fixture.SimpleFixture";
    private static final String FIXTURE_INTERNAL = "dev/multiloader/classloader/fixture/SimpleFixture";
    private static final String FIXTURE_PACKAGE = "dev.multiloader.classloader.fixture";

    @Test
    void fixtureIsLoadedByOurClassLoaderNotTheParent() throws Exception {
        RecordingProcessor recording = new RecordingProcessor();
        try (TransformingClassLoader loader = newLoader(recording)) {
            Class<?> fixture = loader.loadClass(FIXTURE_BINARY);

            assertSame(loader, fixture.getClassLoader(),
                    "child-first 未生效：类被父加载器抢走了");
            assertEquals("fixture-hello", fixture.getMethod("greet").invoke(null));
            assertTrue(recording.seen.contains(FIXTURE_INTERNAL),
                    "管道没有看到目标类，实际看到的是 " + recording.seen);
        }
    }

    @Test
    void noOpProcessorLeavesBytesByteEqual() throws Exception {
        byte[] raw = readFixtureBytes();

        try (TransformingClassLoader loader = newLoader(new RecordingProcessor())) {
            assertArrayEquals(raw, loader.transformClass(FIXTURE_INTERNAL, raw));
        }
    }

    @Test
    void emptyPipelineLeavesBytesByteEqual() throws Exception {
        byte[] raw = readFixtureBytes();

        try (TransformingClassLoader loader = newLoader()) {
            assertTrue(loader.processors().isEmpty());
            assertArrayEquals(raw, loader.transformClass(FIXTURE_INTERNAL, raw));
        }
    }

    @Test
    void modifyingProcessorAffectsPipelineOutput() throws Exception {
        try (TransformingClassLoader loader = newLoader(new AppendingProcessor())) {
            byte[] raw = readFixtureBytes();
            byte[] transformed = loader.transformClass(FIXTURE_INTERNAL, raw);

            assertEquals(raw.length + 1, transformed.length, "管道产物未被采用");
            assertArrayEquals(raw, loader.getClassBytes(FIXTURE_INTERNAL),
                    "getClassBytes 必须返回未转换的原始字节");
        }
    }

    @Test
    void loadClassNoTransformBypassesPipeline() throws Exception {
        RecordingProcessor recording = new RecordingProcessor();

        try (TransformingClassLoader loader = newLoader(recording)) {
            Class<?> fixture = loader.loadClassNoTransform(FIXTURE_BINARY);

            assertSame(loader, fixture.getClassLoader());
            assertEquals("fixture-hello", fixture.getMethod("greet").invoke(null));
            assertTrue(recording.seen.isEmpty(),
                    "loadClassNoTransform 必须跳过管道，实际调用了 " + recording.seen);
        }
    }

    @Test
    void getClassBytesReturnsNullForUnknownClass() throws Exception {
        try (TransformingClassLoader loader = newLoader()) {
            assertNull(loader.getClassBytes("does/not/Exist"));
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("does.not.Exist"));
        }
    }

    @Test
    void hierarchyReadsWithoutTriggeringClassLoading() throws Exception {
        try (TransformingClassLoader loader = newLoader()) {
            assertTrue(loader.hierarchy().peekBytes(FIXTURE_INTERNAL).isPresent());
            assertEquals("java/lang/Object", loader.hierarchy().superName(FIXTURE_INTERNAL).orElse(null));
            assertTrue(loader.hierarchy().isAssignable(FIXTURE_INTERNAL, "java/lang/Object"));
            assertTrue(loader.hierarchy().superChain(FIXTURE_INTERNAL).contains("java/lang/Object"));
        }
    }

    // helpers

    private static TransformingClassLoader newLoader(ClassProcessor... processors) throws Exception {
        ClassPathIndex index = new ClassPathIndex(List.of(
                new ClassPathIndex.Root(testClassesRoot(), ClassPathIndex.Scope.GAME)));

        TransformingClassLoader loader = new TransformingClassLoader(
                "test-game", index, Set.of(FIXTURE_PACKAGE), ClassLoader.getPlatformClassLoader());

        for (ClassProcessor p : processors) {
            loader.addProcessor(p);
        }
        return loader;
    }

    private static Path testClassesRoot() throws Exception {
        return Path.of(dev.multiloader.classloader.fixture.SimpleFixture.class
                .getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static byte[] readFixtureBytes() throws Exception {
        return Files.readAllBytes(testClassesRoot().resolve(FIXTURE_INTERNAL + ".class"));
    }

/** 只记录,不改动.用于验证"管道看到了哪些类"以及"未改动时字节码不变". */
    private static final class RecordingProcessor implements ClassProcessor {

        final List<String> seen = new CopyOnWriteArrayList<>();

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public ProcessPhase phase() {
            return ProcessPhases.MIXIN;
        }

        @Override
        public int priority() {
            return 0;
        }

        @Override
        public boolean handles(String className, byte[] input) {
            return true;
        }

        @Override
        public byte[] process(ClassContext ctx) {
            seen.add(ctx.className());
            return ctx.input();
        }
    }

/** 追加一个字节,用于证明产物确实来自管道. */
    private static final class AppendingProcessor implements ClassProcessor {

        @Override
        public String name() {
            return "appending";
        }

        @Override
        public ProcessPhase phase() {
            return ProcessPhases.MIXIN;
        }

        @Override
        public int priority() {
            return 0;
        }

        @Override
        public boolean handles(String className, byte[] input) {
            return true;
        }

        @Override
        public byte[] process(ClassContext ctx) {
            byte[] in = ctx.input();
            byte[] out = new byte[in.length + 1];
            System.arraycopy(in, 0, out, 0, in.length);
            return out;
        }
    }
}
