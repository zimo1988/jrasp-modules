package com.jrasp.module.jetty;

import com.jrasp.api.Information;
import com.jrasp.api.LoadCompleted;
import com.jrasp.api.Module;
import com.jrasp.api.Resource;
import com.jrasp.api.listener.ext.Advice;
import com.jrasp.api.listener.ext.AdviceListener;
import com.jrasp.api.listener.ext.EventWatchBuilder;
import com.jrasp.api.log.Log;
import com.jrasp.api.resource.ModuleEventWatcher;
import org.eclipse.jetty.server.HttpChannel;
import org.kohsuke.MetaInfServices;

import org.eclipse.jetty.server.Request;

import javax.servlet.http.Cookie;
import java.util.HashMap;
import java.util.Map;

@MetaInfServices(Module.class)
@Information(id = "jetty", isActiveOnLoad = true, version = "1.0.0", author = "jrasp", middlewareVersion = "[8,9]")
public class JettyModule implements Module, LoadCompleted {

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
        jettyRequestPreHook();
        jettyRequestBodyHook();
    }

    // 绑定 request 参数
    public void jettyRequestPreHook() {
        new EventWatchBuilder(moduleEventWatcher)
                .onClass("org.eclipse.jetty.server.Server")
                .includeBootstrap()
                .onBehavior("handle")
                .withParameterTypes("org.eclipse.jetty.server.HttpChannel")
                .onWatch(new AdviceListener() {
                    @Override
                    public void before(Advice advice) throws Throwable {
                        // 清除 requestInfo 信息
                        requestInfoThreadLocal.remove();
                        bodyHookTime.remove();
                        HttpChannel httpChannel = (HttpChannel) advice.getParameterArray()[0];
                        storeRequestInfo(requestInfoThreadLocal.get(), httpChannel.getRequest()); // 按照实际类型强制转换
                    }

                    @Override
                    public void afterThrowing(Advice advice) throws Throwable {
                        requestInfoThreadLocal.remove(); // 清除 requestInfo 信息
                    }
                });
    }

    // 绑定 request body 参数
    public void jettyRequestBodyHook() {
        new EventWatchBuilder(moduleEventWatcher)
                .onClass("org.eclipse.jetty.server.HttpInput")
                .includeBootstrap()
                .onBehavior("read")
                .withParameterTypes(byte[].class, int.class, int.class)
                .onWatch(new AdviceListener() {
                    @Override
                    public void afterReturning(Advice advice) throws Throwable {
                        try {
                            long start = System.nanoTime();
                            Double time = bodyHookTime.get();
                            HashMap<String, Object> stringObjectHashMap = requestInfoThreadLocal.get();
                            if (time == null || time <= 0.1) { // 截取 body的耗时统计
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
                            stringObjectHashMap.put("jetty.request.body.time", bodyHookTime.get());
                            bodyHookTime.set(time);
                        } catch (Exception e) {
                            e.printStackTrace();
                            logger.error("jetty server httpinput read error", e);
                        }
                    }

                    @Override
                    public void afterThrowing(Advice advice) throws Throwable {
                        requestInfoThreadLocal.remove();
                    }
                });
    }

    public static void storeRequestInfo(HashMap<String, Object> stringObjectHashMap, Request request) {
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

        // 参数
        Map<String, String[]> parameterMap = request.getParameterMap();
        stringObjectHashMap.put("parameterMap", parameterMap);

        // 请求cookie
        Cookie[] cookies = request.getCookies();
        stringObjectHashMap.put("cookies", cookies);
    }
}
