package dev.multiloader.agent;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 极简 class 文件常量池读取器.
 * <p>为什么不用 ASM:本类只服务于 premain 阶段的版本探测.探测要在
 * "什么都还没加载"的时刻跑,此时依赖越少越稳(少一个可能解析失败的 jar,
 * 少一层模块可见性问题).所需信息仅限常量池层面的名字,不需要指令遍历.
 * <p>只读不写,遇到不认识的常量池 tag 直接报错而不是猜.
*/
final class ClassFileStrings {

    private static final int MAGIC = 0xCAFEBABE;

    private static final int CP_UTF8 = 1;
    private static final int CP_INTEGER = 3;
    private static final int CP_FLOAT = 4;
    private static final int CP_LONG = 5;
    private static final int CP_DOUBLE = 6;
    private static final int CP_CLASS = 7;
    private static final int CP_STRING = 8;
    private static final int CP_FIELD_REF = 9;
    private static final int CP_METHOD_REF = 10;
    private static final int CP_INTERFACE_METHOD_REF = 11;
    private static final int CP_NAME_AND_TYPE = 12;
    private static final int CP_METHOD_HANDLE = 15;
    private static final int CP_METHOD_TYPE = 16;
    private static final int CP_DYNAMIC = 17;
    private static final int CP_INVOKE_DYNAMIC = 18;
    private static final int CP_MODULE = 19;
    private static final int CP_PACKAGE = 20;

    private ClassFileStrings() {
    }

/**
     * @param majorVersion       class file 主版本(Java 25 = 69)
     * @param thisClass          本类内部名
     * @param superClass         父类内部名(Object 为 {@code java/lang/Object})
     * @param referencedClasses  常量池中引用到的全部类内部名
     * @param methodNames        声明的方法名(不含继承)
     * @param fieldNames         声明的字段名
*/
    record Info(int majorVersion,
                String thisClass,
                String superClass,
                Set<String> referencedClasses,
                List<String> methodNames,
                List<String> fieldNames) {
    }

    static Info read(byte[] data) {
        ByteBuffer b = ByteBuffer.wrap(data);
        if (b.remaining() < 10 || b.getInt() != MAGIC) {
            throw new IllegalArgumentException("not a class file (bad magic)");
        }
        b.getShort();                       // minor_version
        int major = b.getShort() & 0xFFFF;  // major_version

        int cpCount = b.getShort() & 0xFFFF;
        String[] utf8 = new String[cpCount];
        int[] classRef = new int[cpCount];       // CP_Class        -> utf8 index
        int[] natName = new int[cpCount];        // CP_NameAndType  -> name utf8 index

        for (int i = 1; i < cpCount; i++) {
            int tag = b.get() & 0xFF;
            switch (tag) {
                case CP_UTF8 -> utf8[i] = readModifiedUtf8(b);
                case CP_INTEGER, CP_FLOAT -> b.position(b.position() + 4);
                case CP_LONG, CP_DOUBLE -> {
                    b.position(b.position() + 8);
                    i++;    // 8 字节常量占两个常量池槽位
                }
                case CP_CLASS -> classRef[i] = b.getShort() & 0xFFFF;
                case CP_STRING, CP_METHOD_TYPE, CP_MODULE, CP_PACKAGE -> b.getShort();
                case CP_FIELD_REF, CP_METHOD_REF, CP_INTERFACE_METHOD_REF,
                     CP_NAME_AND_TYPE, CP_DYNAMIC, CP_INVOKE_DYNAMIC -> {
                    int first = b.getShort() & 0xFFFF;
                    int second = b.getShort() & 0xFFFF;
                    if (tag == CP_NAME_AND_TYPE) {
                        natName[i] = first;
                    }
                    if (tag == CP_DYNAMIC || tag == CP_INVOKE_DYNAMIC) {
                        // 与上面两类同形,只是首字段语义不同,一并跳过即可
                        @SuppressWarnings("unused") int ignored = second;
                    }
                }
                case CP_METHOD_HANDLE -> {
                    b.get();                        // reference_kind
                    b.getShort();                   // reference_index
                }
                default -> throw new IllegalArgumentException("unknown constant pool tag " + tag);
            }
        }

        b.getShort();                                   // access_flags
        String thisClass = className(classRef, utf8, b.getShort() & 0xFFFF);
        String superClass = className(classRef, utf8, b.getShort() & 0xFFFF);

        int interfaceCount = b.getShort() & 0xFFFF;
        b.position(b.position() + interfaceCount * 2);

        List<String> fieldNames = new ArrayList<>();
        readMembers(b, natName, utf8, fieldNames);

        List<String> methodNames = new ArrayList<>();
        readMembers(b, natName, utf8, methodNames);

        Set<String> referenced = new LinkedHashSet<>();
        for (int i = 1; i < cpCount; i++) {
            if (classRef[i] != 0 && classRef[i] < utf8.length && utf8[classRef[i]] != null) {
                referenced.add(utf8[classRef[i]]);
            }
        }

        return new Info(major, thisClass, superClass,
                Collections.unmodifiableSet(referenced),
                List.copyOf(methodNames),
                List.copyOf(fieldNames));
    }

    private static void readMembers(ByteBuffer b, int[] natName, String[] utf8, List<String> namesOut) {
        int count = b.getShort() & 0xFFFF;
        for (int i = 0; i < count; i++) {
            b.getShort();                                   // access_flags
            int nameIndex = b.getShort() & 0xFFFF;
            b.getShort();                                   // descriptor_index

            if (nameIndex < natName.length && nameIndex < utf8.length && utf8[nameIndex] != null) {
                namesOut.add(utf8[nameIndex]);
            }

            int attrCount = b.getShort() & 0xFFFF;
            for (int a = 0; a < attrCount; a++) {
                b.getShort();                               // attribute_name_index
                int len = b.getInt();
                b.position(b.position() + len);
            }
        }
    }

    private static String className(int[] classRef, String[] utf8, int classIndex) {
        if (classIndex <= 0 || classIndex >= classRef.length) {
            return null;
        }
        int utf8Index = classRef[classIndex];
        return utf8Index > 0 && utf8Index < utf8.length ? utf8[utf8Index] : null;
    }

/**
     * CONSTANT_Utf8 用的是 modified UTF-8.对我们的用途(类名/方法名)
     * 逐字节 ASCII 解码已经足够,非 ASCII 字节按替代字符处理,
     * 不影响任何判据.
*/
    private static String readModifiedUtf8(ByteBuffer b) {
        int length = b.getShort() & 0xFFFF;
        byte[] bytes = new byte[length];
        b.get(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }
}
