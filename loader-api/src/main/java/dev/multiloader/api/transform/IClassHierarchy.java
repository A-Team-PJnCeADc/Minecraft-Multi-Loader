package dev.multiloader.api.transform;

import java.util.List;
import java.util.Optional;

/**
 * 只读类层级查询.
 * <p>存在的核心目的:让处理器在不触发类加载的前提下看到目标类的
 * 父类链与原始字节码.Mixin 在 bootstrap 阶段需要读目标类字节码来构造
 * MixinInfo,如果那次读取走了完整转换管道就会无限递归,
 * 所以必须有一条绕开管道的只读通路{@link #peekBytes(String)}.
*/
public interface IClassHierarchy {

/**
     * 目标类字节码,**不触发转换管道**.
     * @return 找不到该类时空 Optional
*/
    Optional<byte[]> peekBytes(String internalName);

/** 是否存在该类(同样不触发转换). */
    default boolean exists(String internalName) {
        return peekBytes(internalName).isPresent();
    }

/** 直接父类内部名,无父类(Object / 接口)时空. */
    Optional<String> superName(String internalName);

/**
     * 从该类到根的父类链(不含自身),按由近到远排列.
     * 找不到的类直接跳过,不抛异常.
*/
    List<String> superChain(String internalName);

/** {@code child} 是否是 {@code ancestor} 的子类型(含自身). */
    boolean isAssignable(String childInternalName, String ancestorInternalName);

/** 游戏类的 class file 版本(MC 26.3 = Java 25 = 69).未知时返回 0. */
    int classFileVersion();
}
