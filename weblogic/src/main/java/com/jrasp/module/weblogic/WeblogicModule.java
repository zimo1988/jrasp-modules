package com.jrasp.module.weblogic;

import com.jrasp.api.Information;
import com.jrasp.api.LoadCompleted;
import com.jrasp.api.Module;
import com.jrasp.api.Resource;
import com.jrasp.api.listener.ext.Advice;
import com.jrasp.api.listener.ext.AdviceListener;
import com.jrasp.api.listener.ext.EventWatchBuilder;
import com.jrasp.api.log.Log;
import com.jrasp.api.resource.ModuleEventWatcher;
import org.kohsuke.MetaInfServices;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

@MetaInfServices(Module.class)
@Information(id = "weblogic", version = "1.0", author = "jrasp")
public class WeblogicModule implements Module, LoadCompleted {

    @Resource
    private Log logger;

    @Resource
    private ModuleEventWatcher moduleEventWatcher;

    @Resource
    private ThreadLocal<HashMap<String, Object>> requestInfoThreadLocal;

    // 统计 body hook 点耗时
    private ThreadLocal<Double> bodyHookTime = new ThreadLocal<Double>() {
        @Override
        public Double initialValue() {
            return 0.0d;
        }
    };

    @Override
    public void loadCompleted() {
        buildWeblogicRequestWatcher();    // request paramters
        buildWeblogicRequestBodyWatcher(); // request body
    }


    // 绑定 request paramters 参数
    public void buildWeblogicRequestWatcher() {
        final String className="weblogic.servlet.internal.WebAppServletContext";
        final String methodName="securedExecute";
        new EventWatchBuilder(moduleEventWatcher, EventWatchBuilder.PatternType.REGEX)
                .onClass(className)
                .includeBootstrap()
                .onBehavior(methodName)
                .onWatch(new AdviceListener() {
                    @Override
                    public void before(Advice advice) {
                        HttpServletRequest request = (HttpServletRequest)advice.getParameterArray()[0];
                        HashMap<String, Object> stringObjectHashMap = requestInfoThreadLocal.get();
                        storeRequestInfo(stringObjectHashMap,request);
                    }

                    @Override
                    public void afterThrowing(Advice advice) throws Throwable {
                        requestInfoThreadLocal.remove();
                    }
                });
    }

    // 绑定 request body 参数
    public void buildWeblogicRequestBodyWatcher() {
        final String className="weblogic.servlet.internal.ServletInputStreamImpl";
        final String methodName="read";
        new EventWatchBuilder(moduleEventWatcher)
                .onClass(className)
                .includeBootstrap()
                .onBehavior(methodName)
                .withParameterTypes(byte[].class, int.class, int.class)
                .onWatch(new AdviceListener() {
                    @Override
                    public void afterReturning(Advice advice) throws Throwable {
                        try {
                            long start = System.nanoTime();
                            Double time = bodyHookTime.get();
                            if (time == null || time <= 0.1) { // 截取 body的耗时统计
                                byte[] allBytes = (byte[]) advice.getParameterArray()[0];
                                int readLength = (Integer) advice.getReturnObj() + 1;
                                String parameters = new String(allBytes, 0, Math.min(readLength, allBytes.length), "utf-8");
                                HashMap<String, Object> stringObjectHashMap = requestInfoThreadLocal.get();
                                Object parametersBody = stringObjectHashMap.get("parameterBody");
                                if (parametersBody == null) {
                                    parametersBody = "";
                                }
                                stringObjectHashMap.put("parameterBody", parametersBody + parameters);
                            }
                            long end = System.nanoTime();
                            double callTime = (end - start) / 1000000.0;
                            time += callTime;
                            bodyHookTime.set(time);
                            requestInfoThreadLocal.get().put("weblogic.request.body.time", bodyHookTime.get());
                        } catch (Exception e) {
                            logger.error("buildWeblogicRequestBodyWatcher error", e);
                        }
                    }

                    @Override
                    public void afterThrowing(Advice advice) throws Throwable {
                        requestInfoThreadLocal.remove();
                    }
                });
    }

    public static void storeRequestInfo(HashMap<String, Object> stringObjectHashMap, HttpServletRequest request) {
        // 本机地址
        String localAddr = request.getLocalAddr();
        stringObjectHashMap.put("localAddr", localAddr);

        // http请求类型：get、post
        String method = request.getMethod();
        stringObjectHashMap.put("method", method);

        // http请求协议: HTTP/1.1
        String protocol = request.getProtocol();
        stringObjectHashMap.put("protocol", protocol);

        // 调用主机地址
        String remoteHost = request.getRemoteHost();
        stringObjectHashMap.put("remoteHost", remoteHost);

        // http请求路径
        String requestURI = request.getRequestURI();
        stringObjectHashMap.put("requestURI", requestURI);

        Map<String, String[]> parameterMap = request.getParameterMap();
        stringObjectHashMap.put("parameterMap", parameterMap);

        // 请求cookie
        Cookie[] cookies = request.getCookies();
        stringObjectHashMap.put("cookies", cookies);
    }
}
