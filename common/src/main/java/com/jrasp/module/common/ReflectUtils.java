package com.jrasp.module.common;

import java.lang.reflect.Method;

public class ReflectUtils {

    public static Method getMethod(final Class<?> clazz, final String name, final Class<?>... parameterClassArray) {
        try {
            return clazz.getMethod(name, parameterClassArray);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(String.format("class method not found, class: %s, method: %s",
                    clazz.getName(), name), e);
        }
    }

    public static <T> T invokeMethod(final Method method, final Object target, final Object... parameterArray) {
        final boolean isAccessible = method.isAccessible();
        try {
            method.setAccessible(true);
            return (T) method.invoke(target, parameterArray);
        } catch (Throwable e) {
            throw new RuntimeException(String.format("method invoke exception, method: %s", method.getName()), e);
        } finally {
            method.setAccessible(isAccessible);
        }
    }
}