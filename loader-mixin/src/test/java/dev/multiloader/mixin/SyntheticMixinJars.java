package dev.multiloader.mixin;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipFile;

/**
 * 测试用 mixin fixture 生成器.
 * <p>用 ASM 直接产出 mixin 类字节码,从而精确控制 {@code @Mixin} 的目标声明形态
 * ({@code value=} 与 {@code targets=} 两种)与成员注解.
 * 这样测的是"我们的解析器读得对不对",而不是"某个真实 mod 恰好长什么样".
*/
final class SyntheticMixinJars {

    static final String MIXIN_ANNOTATION = "Lorg/spongepowered/asm/mixin/Mixin;";
    static final String OVERWRITE = "Lorg/spongepowered/asm/mixin/Overwrite;";
    static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";

    private static final String CONFIG_SUFFIX = ".mixins.json";

    private SyntheticMixinJars() {
    }

/** 单 mixin 类的 mod jar.modId 同时用作 jar 文件名前缀. */
    static Path modJar(Path dir, String modId, String configName,
                       Map<String, byte[]> mixinClasses) throws IOException {
        Map.Entry<String, byte[]> first = mixinClasses.entrySet().iterator().next();
        String internalName = first.getKey().replace(".class", "");
        String packageName = internalName.substring(0, internalName.lastIndexOf('/')).replace('/', '.');
        String simpleName = internalName.substring(internalName.lastIndexOf('/') + 1);

        return modJar(dir, modId, configName, packageName, List.of(simpleName), mixinClasses);
    }

/** 多 mixin 类的 mod jar,可显式指定 package 与声明顺序. */
    static Path modJar(Path dir, String modId, String configName, String packageName,
                       List<String> mixinNames, Map<String, byte[]> mixinClasses) throws IOException {
        Files.createDirectories(dir);
        Path jar = dir.resolve(modId + ".jar");

        StringBuilder declared = new StringBuilder();
        for (int i = 0; i < mixinNames.size(); i++) {
            declared.append(i == 0 ? "" : ",").append('"').append(mixinNames.get(i)).append('"');
        }

        String config = """
                {
                  "required": true,
                  "minVersion": "0.8",
                  "package": "%s",
                  "compatibilityLevel": "JAVA_25",
                  "mixins": [%s],
                  "injectors": { "defaultRequire": 1 }
                }
                """.formatted(packageName, declared);

        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            putText(out, configName, config);
            for (Map.Entry<String, byte[]> entry : mixinClasses.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return jar;
    }

/** 任意文本条目的 jar,用于"配置文件缺失"这类用例. */
    static Path jarWith(Path dir, String fileName, Map<String, String> entries) throws IOException {
        Files.createDirectories(dir);
        Path jar = dir.resolve(fileName);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                putText(out, entry.getKey(), entry.getValue());
            }
        }
        return jar;
    }

/** 列出 jar 里声明的 mixin 配置名(以 .mixins.json 结尾的条目). */
    static Set<String> configsOf(Path jar) {
        List<String> configs = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            zip.stream()
                    .map(entry -> entry.getName())
                    .filter(name -> name.endsWith(CONFIG_SUFFIX))
                    .sorted()
                    .forEach(configs::add);
        } catch (IOException e) {
            throw new IllegalStateException("cannot list " + jar, e);
        }
        return new java.util.LinkedHashSet<>(configs);
    }

/**
     * 生成 mixin 类,目标声明形如 {@code @Mixin(Target.class)}.
*/
    static byte[] mixinClass(String internalName, String targetInternalName,
                             String methodName, String descriptor, String memberAnnotation) {
        return build(internalName, methodName, descriptor, memberAnnotation, visitor -> {
            AnnotationVisitor array = visitor.visitArray("value");
            array.visit(null, Type.getObjectType(targetInternalName));
            array.visitEnd();
        });
    }

/**
     * 生成 mixin 类,目标声明形如 {@code @Mixin(targets = "net.minecraft...")}.
*/
    static byte[] mixinClassWithTargets(String internalName, String targetInternalName,
                                        String methodName, String descriptor, String memberAnnotation) {
        return build(internalName, methodName, descriptor, memberAnnotation, visitor -> {
            AnnotationVisitor array = visitor.visitArray("targets");
            array.visit(null, targetInternalName.replace('/', '.'));
            array.visitEnd();
        });
    }

    private interface TargetWriter {
        void write(AnnotationVisitor mixinAnnotationVisitor);
    }

    private static byte[] build(String internalName, String methodName, String descriptor,
                                String memberAnnotation, TargetWriter targetWriter) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);

        // visible=false 是**必须的**,不是随手写的:真实 Mixin 的 @Mixin
        // 标注了 @Retention(RetentionPolicy.CLASS),所以它落在
        // RuntimeInvisibleAnnotations,运行时不可见.
        // 夹具这里如果写成 true(可见),就会让"我们的解析器只扫 visibleAnnotations"
        // 这类缺陷**永远测不出来** 测试全绿,线上静默失效.
        // 这个坑真实发生过:MixinConfigManager 曾因此读不到任何目标,
        // 导致 mixin 冲突检测完全失明且无任何报错.
        // 对照:下面成员级注解(@Inject/@Overwrite)用的是 true
        // Mixin 把它们定义成 RUNTIME 保留,确实可见.两者的差别要如实反映.
        AnnotationVisitor mixin = cw.visitAnnotation(MIXIN_ANNOTATION, false);
        targetWriter.write(mixin);
        mixin.visitEnd();

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, methodName, descriptor, null, null);
        AnnotationVisitor member = mv.visitAnnotation(memberAnnotation, true);
        member.visitEnd();
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 1);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void putText(JarOutputStream out, String name, String content) throws IOException {
        out.putNextEntry(new JarEntry(name));
        out.write(content.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }

/** 便于构造"单类"的 map. */
    static Map<String, byte[]> singleClass(String internalNameWithClass, byte[] bytes) {
        Map<String, byte[]> map = new LinkedHashMap<>();
        map.put(internalNameWithClass, bytes);
        return map;
    }
}
