package com.jrasp.module.file;

import  com.jrasp.api.json.JSONObject;
import com.jrasp.api.*;
import com.jrasp.api.Module;
import com.jrasp.api.annotation.Command;
import com.jrasp.api.listener.ext.Advice;
import com.jrasp.api.listener.ext.AdviceListener;
import com.jrasp.api.listener.ext.EventWatchBuilder;
import com.jrasp.api.log.Log;
import com.jrasp.api.model.RestResultUtils;
import com.jrasp.api.resource.ModuleEventWatcher;
import com.jrasp.module.common.StackTrace;
import org.kohsuke.MetaInfServices;

import java.io.File;
import java.io.PrintWriter;
import java.util.*;

import static com.jrasp.module.common.ClassLoaderUtil.earlyLoadClass;

@MetaInfServices(Module.class)
@Information(id = "file", version = "1.0.0", author = "jrasp")
public class FileModule implements Module, LoadCompleted {

    @Resource
    private Log logger;

    @Resource
    private JSONObject jsonObject;

    @Resource
    private ModuleEventWatcher moduleEventWatcher;

    @Resource
    private ThreadLocal<HashMap<String, Object>> requestInfoThreadLocal;

    private volatile boolean enableBlock = true;

    @Override
    public void loadCompleted() {
        earlyLoadClass("java.io.FileInputStream", "java.io.FileOutputStream", "java.io.File", "java.io.RandomAccessFile");
        fileInputStreamHook();
        fileOutputStreamHook();
        fileDeleteAndListHook();
        fileRandomAccessHook();
    }

    @Command("/block")
    public void block(final Map<String, String> parameterMap, final PrintWriter writer) {
        String isBlockParam = parameterMap.get("isBlock");
        enableBlock = Boolean.parseBoolean(isBlockParam);
        logger.info("file block status: {}", enableBlock);
        String result = jsonObject.toJSONString(RestResultUtils.success("更新文件操作阻断状态", enableBlock));
        writer.println(result);
        writer.flush();
        writer.close();
    }

    public void fileInputStreamHook() {
        final String className = "java.io.FileInputStream";
        final String methodName = "<init>";
        new EventWatchBuilder(moduleEventWatcher)
                .onClass(className)
                .includeBootstrap()
                .onBehavior(methodName)
                .withParameterTypes(File.class)
                .onWatch(new AdviceListener() {
                    @Override
                    public void before(Advice advice) throws Throwable {
                        File file = (File) advice.getParameterArray()[0];
                        String fileName = file.getPath();
                        checkFileAndPrintLog(fileName, className, methodName);
                    }

                    @Override
                    public void afterThrowing(Advice advice) throws Throwable {
                        requestInfoThreadLocal.remove();
                    }
                });
    }

    public void fileOutputStreamHook() {
        final String className = "java.io.FileOutputStream";
        final String methodName = "<init>";
        new EventWatchBuilder(moduleEventWatcher)
                .onClass(className)
                .includeBootstrap()
                .onBehavior(methodName)
                .withParameterTypes(File.class, boolean.class)
                .onWatch(new AdviceListener() {
                    @Override
                    public void before(Advice advice) throws Throwable {
                        File file = (File) advice.getParameterArray()[0];
                        String fileName = file.getPath();
                        checkFileAndPrintLog(fileName, className, methodName);
                    }

                    @Override
                    public void afterThrowing(Advice advice) throws Throwable {
                        requestInfoThreadLocal.remove();
                    }
                });
    }

    // 文件删除、遍历
    public void fileDeleteAndListHook() {
        final String className = "java.io.File";
        new EventWatchBuilder(moduleEventWatcher, EventWatchBuilder.PatternType.REGEX)
                .onClass(className)
                .includeBootstrap()
                .onBehavior("delete|listFiles")
                .withEmptyParameterTypes()
                .onWatch(new AdviceListener() {
                    @Override
                    public void before(Advice advice) throws Throwable {
                        if (advice.getTarget() == null) {
                            return;
                        }
                        File file = (File) advice.getTarget();
                        String fileName = file.getPath();
                        checkFileAndPrintLog(fileName, className, advice.getBehavior().getName());
                    }

                    @Override
                    public void afterThrowing(Advice advice) throws Throwable {
                        requestInfoThreadLocal.remove();
                    }
                });
    }

    // fileRandomAccess 读、写
    public void fileRandomAccessHook() {
        final String className = "java.io.RandomAccessFile";
        final String methodName = "<init>";
        new EventWatchBuilder(moduleEventWatcher)
                .onClass(className)
                .includeBootstrap()
                .onBehavior(methodName)
                .withParameterTypes(File.class, String.class)
                .onWatch(new AdviceListener() {
                    @Override
                    public void before(Advice advice) throws Throwable {
                        if (advice.getTarget() == null) {
                            return;
                        }
                        File file = (File) advice.getParameterArray()[0];
                        String fileName = file.getPath();
                        checkFileAndPrintLog(fileName, className, advice.getBehavior().getName());
                    }

                    @Override
                    public void afterThrowing(Advice advice) throws Throwable {
                        requestInfoThreadLocal.remove();
                    }
                });
    }

    // has_traversal
    private boolean checkFile(String filePath) {
        return filePath.contains("../..");
    }

    private void checkFileAndPrintLog(String file, String className, String methodName) throws ProcessControlException {
        if (checkFile(file)) {
            long start = System.nanoTime();
            // 获取上下文参数
            // new HashMap<String, Object> 写法兼容jdk1.6
            HashMap<String, Object> result = new HashMap<String, Object>(requestInfoThreadLocal.get());

            // 获取栈
            ArrayList<String> stackTrace = StackTrace.getStackTrace();
            result.put("stackTrace", stackTrace);

            // 文件
            result.put("file", file);

            // 输出日志
            String s = jsonObject.toJSONString(result);
            logger.warn(s);

            // 耗时统计
            long end = System.nanoTime();
            logger.info("方法: {}, 耗时: {} ms", className + "#" + methodName, (end - start) / 1000000.0);

            // 抛出异常阻断
            if (enableBlock) {
                String info = methodName + " file [" + file + "] block by rasp.";
                ProcessControlException.throwThrowsImmediately(new RuntimeException(info));
            }
        }
    }

}
