package dev.multiloader.adapt.neoforge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @Mod} 扫描的单元测试.
 * <p>这里用 ASM **合成**类而不是拿编译好的类:需要精确控制注解的保留通道
 * (visible / invisible),而 javac 只会按注解自身的 {@code @Retention} 产出一种.
 * 保留策略正是本项目踩过的坑  必须能独立地造出两种形态来测.
*/
class NeoForgeModClassScannerTest {

    private static final String MOD_DESCRIPTOR = "Lnet/neoforged/fml/common/Mod;";

    @Test
    void findsModClassAndItsModId(@TempDir Path tmp) throws IOException {
        Path mod = writeJar(tmp, "a.jar", Map.of(
                "com/example/MyMod.class",
                modClass("com/example/MyMod", "mymod", true,
                        new String[]{"Lnet/neoforged/bus/api/IEventBus;"})));

        List<NeoForgeModClassScanner.ModClass> found = NeoForgeModClassScanner.scan(mod);

        assertEquals(1, found.size());
        assertEquals("com.example.MyMod", found.get(0).className());
        assertEquals("mymod", found.get(0).declaredModId());
        assertEquals("RUNTIME", found.get(0).annotationRetention());
    }

    @Test
    void readsConstructorParameterTypes(@TempDir Path tmp) throws IOException {
        Path mod = writeJar(tmp, "a.jar", Map.of(
                "com/example/MyMod.class",
                modClass("com/example/MyMod", "mymod", true, new String[]{
                        "Lnet/neoforged/bus/api/IEventBus;",
                        "Lnet/neoforged/fml/ModContainer;"})));

        List<NeoForgeModClassScanner.ModClass> found = NeoForgeModClassScanner.scan(mod);

        assertEquals(
                List.of("net.neoforged.bus.api.IEventBus", "net.neoforged.fml.ModContainer"),
                found.get(0).primaryConstructor());
        assertTrue(found.get(0).requiresServices());
    }

    @Test
    void noArgConstructorMeansNoInjectionNeeded(@TempDir Path tmp) throws IOException {
        Path mod = writeJar(tmp, "a.jar", Map.of(
                "com/example/Plain.class",
                modClass("com/example/Plain", "plain", true, new String[]{})));

        List<NeoForgeModClassScanner.ModClass> found = NeoForgeModClassScanner.scan(mod);

        assertFalse(found.get(0).requiresServices(),
                "无参构造器不需要注入 这决定 2b 要不要走服务解析");
    }

    @Test
    void primaryConstructorPrefersTheOneWithMostParameters(@TempDir Path tmp) throws IOException {
        // 无参构造器是 javac 生成的最弱形态;NeoForge 的注入构造器必然带参数.
        // 若取"第一个",一个同时有两种构造器的类会被识别成"不需要注入",
        // 于是 2b 静默不注入  这正是这条测试要挡住的.
        Path mod = writeJar(tmp, "a.jar", Map.of(
                "com/example/Both.class",
                classWithTwoConstructors("com/example/Both", "both")));

        List<NeoForgeModClassScanner.ModClass> found = NeoForgeModClassScanner.scan(mod);

        assertEquals(2, found.get(0).constructorSignatures().size());
        assertEquals(List.of("net.neoforged.bus.api.IEventBus"),
                found.get(0).primaryConstructor());
    }

    @Test
    void readsModAnnotationFromClassRetentionChannelToo(@TempDir Path tmp) throws IOException {
        // 真实的 @Mod 是 RUNTIME(已核实).但这里刻意造一个 CLASS 保留的变异体,
        // 确认两条通道都被读  "只看 visible" 是本项目踩过的坑
        // (@Mixin 正是 CLASS 保留,只读 visible 的实现在它上面完全失明).
        Path mod = writeJar(tmp, "a.jar", Map.of(
                "com/example/Invisible.class",
                modClass("com/example/Invisible", "inv", false, new String[]{})));

        List<NeoForgeModClassScanner.ModClass> found = NeoForgeModClassScanner.scan(mod);

        assertEquals(1, found.size(), "CLASS 保留的 @Mod 也必须被发现");
        assertEquals("CLASS", found.get(0).annotationRetention());
        assertEquals("inv", found.get(0).declaredModId());
    }

    @Test
    void emptyValueMeansUseTomlModId(@TempDir Path tmp) throws IOException {
        // @Mod 留空是**有意义**的:表示"用 neoforge.mods.toml 里声明的 modId".
        // 返回空串而不是 null,调用方按此语义处理.
        Path mod = writeJar(tmp, "a.jar", Map.of(
                "com/example/Blank.class",
                modClass("com/example/Blank", "", true, new String[]{})));

        List<NeoForgeModClassScanner.ModClass> found = NeoForgeModClassScanner.scan(mod);

        assertEquals("", found.get(0).declaredModId());
    }

    @Test
    void classesWithoutModAnnotationAreIgnored(@TempDir Path tmp) throws IOException {
        Path mod = writeJar(tmp, "a.jar", Map.of(
                "com/example/MyMod.class",
                modClass("com/example/MyMod", "mymod", true, new String[]{}),
                "com/example/Helper.class",
                plainClass("com/example/Helper"),
                "com/example/AlsoPlain.class",
                plainClass("com/example/AlsoPlain")));

        List<NeoForgeModClassScanner.ModClass> found = NeoForgeModClassScanner.scan(mod);

        assertEquals(1, found.size());
        assertEquals("com.example.MyMod", found.get(0).className());
    }

    @Test
    void scanResultIsSortedByNameForStableDiagnostics(@TempDir Path tmp) throws IOException {
        // JarEntries.list 明确不保证顺序,而依赖枚举顺序的代码
        // 会在不同打包工具产出的 jar 上表现不同.扫描器负责排序.
        Path mod = writeJar(tmp, "a.jar", Map.of(
                "com/example/Zeta.class", modClass("com/example/Zeta", "z", true, new String[]{}),
                "com/example/Alpha.class", modClass("com/example/Alpha", "a", true, new String[]{}),
                "com/example/Mid.class", modClass("com/example/Mid", "m", true, new String[]{})));

        List<String> names = NeoForgeModClassScanner.scan(mod).stream()
                .map(NeoForgeModClassScanner.ModClass::className).toList();

        assertEquals(List.of("com.example.Alpha", "com.example.Mid", "com.example.Zeta"), names);
    }

    //
    // ASM 合成
    //

/** 造一个带 {@code @Mod} 与单个构造器的类. */
    private static byte[] modClass(String internalName, String modId, boolean visible,
            String[] constructorParams) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);

        AnnotationVisitor annotation = cw.visitAnnotation(MOD_DESCRIPTOR, visible);
        if (!modId.isEmpty()) {
            annotation.visit("value", modId);
        }
        annotation.visitEnd();

        writeConstructor(cw, "java/lang/Object", constructorParams);
        cw.visitEnd();
        return cw.toByteArray();
    }

/** 造一个同时有无参与注入构造器的类. */
    private static byte[] classWithTwoConstructors(String internalName, String modId) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        AnnotationVisitor annotation = cw.visitAnnotation(MOD_DESCRIPTOR, true);
        annotation.visit("value", modId);
        annotation.visitEnd();
        writeConstructor(cw, "java/lang/Object", new String[]{});
        writeConstructor(cw, "java/lang/Object",
                new String[]{"Lnet/neoforged/bus/api/IEventBus;"});
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] plainClass(String internalName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        writeConstructor(cw, "java/lang/Object", new String[]{});
        cw.visitEnd();
        return cw.toByteArray();
    }

/** 一个把参数直接丢弃的构造器:只为让描述符里带上参数类型. */
    private static void writeConstructor(ClassWriter cw, String superName, String[] parameterTypes) {
        StringBuilder desc = new StringBuilder("(");
        for (String parameter : parameterTypes) {
            desc.append(parameter);
        }
        desc.append(")V");

        MethodVisitor method = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", desc.toString(),
                null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
        method.visitInsn(Opcodes.RETURN);
        // 栈深度 1 + 参数数;局部变量槽 1 + 参数数(全是引用类型,各占 1 槽)
        method.visitMaxs(1 + parameterTypes.length, 1 + parameterTypes.length);
        method.visitEnd();
    }

    private static Path writeJar(Path dir, String name, Map<String, byte[]> entries)
            throws IOException {
        Path jar = dir.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return jar;
    }
}
