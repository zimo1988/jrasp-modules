package com.jrasp.module.tomcat;

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

// 支持tomcat6.0～9.0，不支持tomcat10.0、10.1
@MetaInfServices(Module.class)
@Information(id = "tomcat", version = "1.0", author = "jrasp", middlewareVersion = "[6.0,9.0]")
public class TomcatModule implements Module, LoadCompleted {

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
        tomcatRequestPreHook(); // preRequest
        tomcatRequestHook();    // request
        tomcatRequestCharBodyHook(); // request body1
        tomcatRequestByteBodyHook(); // request body2
    }

    // 这个是请求的起点，用来清除 requestInfo 信息
    public void tomcatRequestPreHook() {
        new EventWatchBuilder(moduleEventWatcher)
                .onClass("org.apache.catalina.connector.CoyoteAdapter")
                .includeBootstrap()
                .onBehavior("service")
                .onWatch(new AdviceListener() {
                    @Override
                    public void before(Advice advice) throws Throwable {
                        requestInfoThreadLocal.remove(); // 清除 requestInfo 信息
                        bodyHookTime.remove();           // 清除 http body 耗时计数
                    }

                    @Override
                    public void afterThrowing(Advice advice) throws Throwable {
                        requestInfoThreadLocal.remove();
                    }
                });
    }

    // 绑定 request 参数
    public void tomcatRequestHook() {
        new EventWatchBuilder(moduleEventWatcher)
                .onClass("org.apache.catalina.core.StandardWrapperValve")
                .includeBootstrap()
                .onBehavior("invoke")
                .onWatch(new AdviceListener() {
                    @Override
                    public void before(Advice advice) throws Throwable {
                        long start = System.nanoTime();
                        HashMap<String, Object> stringObjectHashMap = requestInfoThreadLocal.get();
                        HttpServletRequest request = (HttpServletRequest) advice.getParameterArray()[0];
                        storeRequestInfo(stringObjectHashMap, request);
                        long end = System.nanoTime();
                        stringObjectHashMap.put("tomcat.request.parameter.time", (end - start) / 1000000.0);
                    }

                    @Override
                    public void afterThrowing(Advice advice) throws Throwable {
                        requestInfoThreadLocal.remove();
                    }
                });
    }

    // RequestBody1  todo  char[] 类型
    public void tomcatRequestCharBodyHook() {
        new EventWatchBuilder(moduleEventWatcher)
                .onClass("org.apache.catalina.connector.CoyoteReader")
                .includeBootstrap()
                .onBehavior("read")
                .withParameterTypes(char[].class, int.class, int.class)
                .onWatch(new AdviceListener() {
                    @Override
                    public void afterReturning(Advice advice) throws Throwable {
                        long start = System.nanoTime();
                        Double time = bodyHookTime.get();
                        HashMap<String, Object> stringObjectHashMap = requestInfoThreadLocal.get();
                        if (time == null || time < 0.1) {
                            char[] allBytes = (char[]) advice.getParameterArray()[0];
                            int readLength = (Integer) advice.getReturnObj() + 1;
                            String parameters = new String(allBytes,0,Math.min(readLength, allBytes.length));
                            Object parametersBody = stringObjectHashMap.get("parameterBody");
                            if (parametersBody == null) {
                                parametersBody = "";
                            }
                            stringObjectHashMap.put("parameterBody", parametersBody + parameters);
                        }
                        long end = System.nanoTime();
                        double callTime = (end - start) / 1000000.0;
                        time += callTime;
                        stringObjectHashMap.put("tomcat.request.body.time", time);
                        bodyHookTime.set(time);
                    }

                    @Override
                    public void afterThrowing(Advice advice) throws Throwable {
                        requestInfoThreadLocal.remove();
                    }
                });
    }

    public void tomcatRequestByteBodyHook() {
        new EventWatchBuilder(moduleEventWatcher, EventWatchBuilder.PatternType.REGEX)
                .onClass("org.apache.catalina.connector.CoyoteInputStream")
                .includeBootstrap()
                .onBehavior("read")
                .withParameterTypes(byte[].class, int.class, int.class)
                .onWatch(new AdviceListener() {
                    @Override
                    public void afterReturning(Advice advice) {
                        try {
                            long start = System.nanoTime();
                            Double time = bodyHookTime.get();
                            HashMap<String, Object> stringObjectHashMap = requestInfoThreadLocal.get();
                            if (time == null || time < 0.1) {
                                byte[] allBytes = (byte[]) advice.getParameterArray()[0];
                                int readLength = (Integer) advice.getReturnObj() + 1;
                                String parameters = new String(allBytes, 0, Math.min(readLength, allBytes.length), "utf-8");
                                Object parametersBody = stringObjectHashMap.get("parameterBody");
                                if (parametersBody == null) {
                                    parametersBody = "";
                                }
                                stringObjectHashMap.put("parameterBody", parametersBody + parameters);
                            }
                            long end = System.nanoTime();
                            double callTime = (end - start) / 1000000.0;
                            time += callTime;
                            stringObjectHashMap.put("tomcat.request.body.time", time);
                            bodyHookTime.set(time);
                        } catch (Exception e) {
                            //logger.error("build tomcat request body watcher error", e);
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

        // url上的参数
        Map<String, String[]> parameterMap = request.getParameterMap();
        stringObjectHashMap.put("parameterMap", parameterMap);

        // 请求cookie
        Cookie[] cookies = request.getCookies();
        stringObjectHashMap.put("cookies", cookies);
    }

}
