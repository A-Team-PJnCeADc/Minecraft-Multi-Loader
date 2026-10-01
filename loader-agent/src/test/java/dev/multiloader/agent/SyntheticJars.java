package dev.multiloader.agent;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * 测试用 fixture jar 生成器.
 * <p>为什么需要它:真实 26.x / 1.21.x jar 只存在于开发机上(Gradle/Loom 缓存),
 * 单测不能依赖它们.这里用 ASM 合成"形态正确"的 jar,
 * 把探测判据的阈值固定下来,避免回归.
 * <p>合成的不是"真 class":只有常量池层面的形态(类名,方法名,class 版本,
 * 常量池引用),这已经足够覆盖全部四个探针,且从不真正加载这些类.
*/
final class SyntheticJars {

    private SyntheticJars() {
    }

/**
     * 未混淆形态:全部类位于 net/minecraft/ 下,方法名为读写名,class 版本 V25.
*/
    static Path officialJar(Path dir, int classCount) throws IOException {
        Path jar = dir.resolve("synthetic-official.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (int i = 0; i < classCount; i++) {
                String internal = "net/minecraft/world/level/block/Synthetic" + i;
                put(out, internal, readableClass(internal, Opcodes.V25));
            }
        }
        return jar;
    }

/**
     * 混淆形态:全部类位于默认包,名称为 a/aa/aaa...,方法名为 a/b/aa,class 版本 V21.
*/
    static Path obfuscatedJar(Path dir, int classCount) throws IOException {
        Path jar = dir.resolve("synthetic-obfuscated.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (int i = 0; i < classCount; i++) {
                String internal = defaultPackageName(i);
                put(out, internal, obfuscatedClass(internal, Opcodes.V21));
            }
        }
        return jar;
    }

/**
     * 被打过补丁的形态:未混淆布局 + 某个 vanilla 类的常量池引用加载器自己的包.
     * @param markerPrefix {@code "net/neoforged/"} 或 {@code "net/minecraftforge/"}
*/
    static Path patchedJar(Path dir, String markerPrefix) throws IOException {
        String safe = markerPrefix.replace('/', '_').replace('.', '_');
        Path jar = dir.resolve("synthetic-patched-" + safe + ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            // 普通 vanilla 类,保证 class-layout 仍判 OFFICIAL
            for (int i = 0; i < 8; i++) {
                String internal = "net/minecraft/world/entity/Synthetic" + i;
                put(out, internal, readableClass(internal, Opcodes.V25));
            }
            // 被 patch 的类:父类指向加载器自己的包,这是植入 CONSTANT_Class 引用最简单的方式,
            // 等价于真实补丁里 "把 hook 类写进字节码" 的效果.
            String patched = "net/minecraft/world/entity/PatchedEntity";
            put(out, patched, classWithSuper(patched, markerPrefix + "fml/hooks/Hook", Opcodes.V25));
        }
        return jar;
    }

/** 生成一个"混淆名".0→a, 1→b, 26→aa, 27→ab ... */
    private static String defaultPackageName(int index) {
        StringBuilder sb = new StringBuilder();
        int n = index;
        do {
            sb.insert(0, (char) ('a' + (n % 26)));
            n = n / 26 - 1;
        } while (n >= 0);
        return sb.toString();
    }

    private static byte[] readableClass(String internalName, int classFileVersion) {
        return classWithMethods(internalName, "java/lang/Object", classFileVersion,
                new String[]{"tick", "getBlockState", "isAir", "onInitialize", "register"});
    }

    private static byte[] obfuscatedClass(String internalName, int classFileVersion) {
        return classWithMethods(internalName, "java/lang/Object", classFileVersion,
                new String[]{"a", "b", "aa", "ab", "c"});
    }

    private static byte[] classWithSuper(String internalName, String superName, int classFileVersion) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(classFileVersion, Opcodes.ACC_PUBLIC, internalName, null, superName, null);
        emitConstructor(cw, superName);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] classWithMethods(String internalName, String superName,
                                          int classFileVersion, String[] methodNames) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(classFileVersion, Opcodes.ACC_PUBLIC, internalName, null, superName, null);
        for (String name : methodNames) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, name, "()V", null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 1);
            mv.visitEnd();
        }
        emitConstructor(cw, superName);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void emitConstructor(ClassWriter cw, String superName) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }

    private static void put(JarOutputStream out, String internalName, byte[] bytes) throws IOException {
        out.putNextEntry(new JarEntry(internalName + ".class"));
        out.write(bytes);
        out.closeEntry();
    }
}
