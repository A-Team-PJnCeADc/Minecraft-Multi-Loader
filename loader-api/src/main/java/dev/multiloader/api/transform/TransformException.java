package dev.multiloader.api.transform;

/**
 * 字节码转换失败.checked,因为转换失败必须是致命的:
 * 静默回退到未转换的字节码会让问题推迟到运行时以更难诊断的形式爆发.
*/
public class TransformException extends Exception {

    private static final long serialVersionUID = 1L;

    public TransformException(String message) {
        super(message);
    }

    public TransformException(String message, Throwable cause) {
        super(message, cause);
    }

    public static TransformException forClass(String processorName, String className, Throwable cause) {
        return new TransformException(
                "Processor '" + processorName + "' failed on class " + className, cause);
    }
}
