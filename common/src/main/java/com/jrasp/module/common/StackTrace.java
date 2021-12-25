package com.jrasp.module.common;

import java.util.ArrayList;

public class StackTrace {

    // RASP自身的栈开始位置
    private final static String JRASP_STACK_BEGIN = "java.com.jrasp.spy.Spy";

    // 输出调用栈
    public static ArrayList<String> getStackTrace() {
        StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
        ArrayList<String> effectiveStacks = new ArrayList<String>(50);
        for (int i = stackTraceElements.length - 1; i > 0; i--) {
            String info = stackTraceElements[i].toString();
            if (info.startsWith(JRASP_STACK_BEGIN)) {
                break;
            }
            effectiveStacks.add(info);
        }
        return effectiveStacks;
    }
}
