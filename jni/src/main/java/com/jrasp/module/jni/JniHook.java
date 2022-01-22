package com.jrasp.module.jni;

import com.jrasp.api.Information;
import com.jrasp.api.LoadCompleted;
import com.jrasp.api.Module;
import com.jrasp.api.Resource;
import com.jrasp.api.json.JSONObject;
import com.jrasp.api.listener.ext.Advice;
import com.jrasp.api.listener.ext.AdviceListener;
import com.jrasp.api.listener.ext.EventWatchBuilder;
import com.jrasp.api.log.Log;
import com.jrasp.api.resource.ModuleEventWatcher;
import com.jrasp.module.common.StackTrace;
import org.kohsuke.MetaInfServices;

import java.util.ArrayList;
import java.util.HashMap;

// jni注入
@MetaInfServices(Module.class)
@Information(id = "jni", version = "1.0", author = "jrasp")
public class JniHook implements Module, LoadCompleted {

    @Resource
    private Log logger;

    @Resource
    private JSONObject jsonObject;

    @Resource
    private ModuleEventWatcher moduleEventWatcher;

    @Resource
    private ThreadLocal<HashMap<String, Object>> requestInfoThreadLocal;

    @Override
    public void loadCompleted() {
        loadLibraryHook();
    }

    public void loadLibraryHook() {
        new EventWatchBuilder(moduleEventWatcher)
                .onClass("java.lang.System")
                .includeBootstrap()
                .onBehavior("load|loadLibrary")
                .onWatch(new AdviceListener() {
                    @Override
                    public void before(Advice advice) throws Throwable {
                        String libname = (String) advice.getParameterArray()[0];
                        HashMap<String, Object> result = new HashMap<String, Object>(requestInfoThreadLocal.get());
                        ArrayList<String> stackTrace = StackTrace.getStackTrace();
                        result.put("stackTrace", stackTrace);
                        result.put("libName", libname);
                        // 输出日志
                        String s = jsonObject.toJSONString(result);
                        logger.warn(s);
                    }

                    @Override
                    public void afterThrowing(Advice advice) throws Throwable {
                        requestInfoThreadLocal.remove();
                    }
                });
    }

}
