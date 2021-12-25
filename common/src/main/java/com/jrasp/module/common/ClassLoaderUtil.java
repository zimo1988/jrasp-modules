package com.jrasp.module.common;

public class ClassLoaderUtil {
    // 尝试提前加载这个类
    public static void earlyLoadClass(String... className) {
        for (int i = 0; i < className.length; i++) {
            try {
                Class.forName(className[i]);
            } catch (ClassNotFoundException e) {
                // ignore
            }
        }
    }
}
