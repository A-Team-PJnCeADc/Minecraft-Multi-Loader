package dev.multiloader.api.locating;

/**
 * Mod 文件读取失败.
 * <p>这是"文件格式不合法"的信号,不是"读不了这个文件"的信号.
 * 前者应当让启动失败并给出可读诊断,后者(IO 问题)应包装后同样抛出本异常,
 * 由上层决定是致命还是跳过.
*/
public class ModFileException extends Exception {

    private static final long serialVersionUID = 1L;

    public ModFileException(String message) {
        super(message);
    }

    public ModFileException(String message, Throwable cause) {
        super(message, cause);
    }

/** 带上出错的文件路径,避免调用方到处拼上下文. */
    public static ModFileException at(Object path, String message) {
        return new ModFileException(path + ": " + message);
    }

    public static ModFileException at(Object path, String message, Throwable cause) {
        return new ModFileException(path + ": " + message, cause);
    }
}
