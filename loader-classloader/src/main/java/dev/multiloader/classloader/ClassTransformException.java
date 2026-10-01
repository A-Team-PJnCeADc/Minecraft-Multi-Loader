package dev.multiloader.classloader;

/**
 * 转换失败的非受检包装.
 * <p>为什么需要它:{@code ClassLoader.findClass} 不允许抛受检异常,
 * 而转换失败必须是致命的(见 {@code TransformException} 的设计意图).
 * 于是把受检的 {@link dev.multiloader.api.transform.TransformException}
 * 包一层带出类加载边界,调用方仍可用 {@code getCause()} 取回原始异常.
*/
public class ClassTransformException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ClassTransformException(String message, Throwable cause) {
        super(message, cause);
    }
}
