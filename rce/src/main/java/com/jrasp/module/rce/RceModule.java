package com.jrasp.module.rce;

import com.jrasp.api.*;
import com.jrasp.api.Module;
import com.jrasp.api.annotation.Command;
import com.jrasp.api.json.JSONObject;
import com.jrasp.api.listener.ext.Advice;
import com.jrasp.api.listener.ext.AdviceListener;
import com.jrasp.api.listener.ext.EventWatchBuilder;
import com.jrasp.api.log.Log;
import com.jrasp.api.model.RestResultUtils;
import com.jrasp.api.resource.ModuleEventWatcher;
import com.jrasp.module.common.ClassLoaderUtil;
import com.jrasp.module.common.StackTrace;
import com.jrasp.module.common.StringUtils;
import org.kohsuke.MetaInfServices;

import java.io.PrintWriter;
import java.util.*;

import static com.jrasp.module.common.JavaVersionUtils.isGreaterThanJava8;

@MetaInfServices(Module.class)
@Information(id = "rce", version = "1.0.0", isActiveOnLoad = true, author = "jrasp", middlewareVersion = "[6,11]")
public class RceModule implements Module, LoadCompleted {

    @Resource
    private Log logger;

    @Resource
    private JSONObject jsonObject;

    @Resource
    private ModuleEventWatcher moduleEventWatcher;

    @Resource
    private ThreadLocal<HashMap<String, Object>> requestInfoThreadLocal;

    private volatile boolean enableBlock = true;

    private volatile HashSet<String> cmdWhiteList = new HashSet<String>(16);

    @Override
    public void loadCompleted() {
        // 尝试提前加载类,无论加载是否成功
        // UNIXProcess比ProcessImpl更加底层，绕过可能性小; 但是jdk9+没有这个类
        ClassLoaderUtil.earlyLoadClass("java.lang.ProcessImpl", "java.lang.UNIXProcess");
        if (isGreaterThanJava8()) {
            processImplHook();
        } else {
            unixProcessHook();
        }
    }

    @Command("/whitelist")
    public void config(Map<String, String[]> parameterMap, final PrintWriter writer) {
        String[] whiteLists = parameterMap.get("item");
        if (whiteLists != null && whiteLists.length > 0) {
            cmdWhiteList.clear();
            cmdWhiteList.addAll(Arrays.asList(whiteLists));
        }
        String result = jsonObject.toJSONString(RestResultUtils.success("更新命令执行白名单", cmdWhiteList));
        writer.println(result);
        writer.flush();
        writer.close();
    }

    @Command("/block")
    public void block(Map<String, String> parameterMap, final PrintWriter writer) {
        String isBlockParam = parameterMap.get("isBlock");
        enableBlock = Boolean.parseBoolean(isBlockParam);
        logger.info("rce block status: {}", enableBlock);
        String result = jsonObject.toJSONString(RestResultUtils.success("更新命令执行阻断状态", enableBlock));
        writer.println(result);
        writer.flush();
        writer.close();
    }

    public void processImplHook() {
        final String className = "java.lang.ProcessImpl";
        final String methodName = "start";
        new EventWatchBuilder(moduleEventWatcher)
                .onClass(className)
                .includeBootstrap()
                .onBehavior(methodName)
                .onWatch(new AdviceListener() {
                    @Override
                    public void before(Advice advice) throws Throwable {
                        // 获取命令
                        String[] cmdArray = (String[]) advice.getParameterArray()[0];
                        String cmdString = StringUtils.join(cmdArray, " ");
                        checkCmdAndPrintLog(cmdString, className, methodName);
                    }

                    @Override
                    public void afterThrowing(Advice advice) throws Throwable {
                        requestInfoThreadLocal.remove();
                    }
                });
    }

    public void unixProcessHook() {
        final String className = "java.lang.UNIXProcess";
        final String methodName = "<init>";
        new EventWatchBuilder(moduleEventWatcher)
                .onClass(className)
                .includeBootstrap()
                .onBehavior(methodName)
                .onWatch(new AdviceListener() {
                    @Override
                    public void before(Advice advice) throws Throwable {
                        byte[] prog = (byte[]) advice.getParameterArray()[0];     // 命令
                        byte[] argBlock = (byte[]) advice.getParameterArray()[1]; // 参数
                        String cmdString = getCommandAndArgs(prog, argBlock);
                        checkCmdAndPrintLog(cmdString, className, methodName);
                    }

                    @Override
                    public void afterThrowing(Advice advice) throws Throwable {
                        requestInfoThreadLocal.remove();
                    }
                });
    }

    public static String getCommandAndArgs(byte[] command, byte[] args) {
        // 命令&参数解析
        List<String> commands = new LinkedList<String>();
        if (command != null && command.length > 0) {
            commands.add(new String(command, 0, command.length - 1));
        }
        if (args != null && args.length > 0) {
            int position = 0;
            for (int i = 0; i < args.length; i++) {
                if (args[i] == 0) {
                    commands.add(new String(Arrays.copyOfRange(args, position, i)));
                    position = i + 1;
                }
            }
        }
        return StringUtils.join(commands, " ");
    }

    private void checkCmdAndPrintLog(String cmdString, String className, String methodName) throws ProcessControlException {
        long start = System.nanoTime();

        // 获取上下文参数
        HashMap<String, Object> result = new HashMap<String, Object>(requestInfoThreadLocal.get());

        // 获取栈
        ArrayList<String> stackTrace = StackTrace.getStackTrace();
        result.put("stackTrace", stackTrace);

        // 获取命令
        result.put("cmdString", cmdString);

        // 判断是否阻断
        boolean blockStatus = enableBlock && !cmdWhiteList.contains(cmdString);
        result.put("isBlocked", blockStatus);

        // 输出日志
        String s = jsonObject.toJSONString(result);
        logger.warn(s);

        // 耗时统计
        long end = System.nanoTime();
        logger.info("方法: {}, 耗时: {} ms", className + "#" + methodName, (end - start) / 1000000.0);

        // 线程变量清除
        requestInfoThreadLocal.remove();

        // 抛出异常阻断
        if (blockStatus) {
            String info = " cmd [" + cmdString + "] block by rasp.";
            ProcessControlException.throwThrowsImmediately(new RuntimeException(info));
        }
    }
}
