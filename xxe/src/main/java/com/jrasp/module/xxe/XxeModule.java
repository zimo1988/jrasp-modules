package com.jrasp.module.xxe;

import com.jrasp.api.json.JSONObject;
import com.jrasp.api.Information;
import com.jrasp.api.LoadCompleted;
import com.jrasp.api.Module;
import com.jrasp.api.Resource;
import com.jrasp.api.annotation.Command;
import com.jrasp.api.listener.ext.Advice;
import com.jrasp.api.listener.ext.AdviceListener;
import com.jrasp.api.listener.ext.EventWatchBuilder;
import com.jrasp.api.log.Log;
import com.jrasp.api.model.RestResultUtils;
import com.jrasp.api.resource.ModuleEventWatcher;
import com.jrasp.module.common.ClassLoaderUtil;
import org.kohsuke.MetaInfServices;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.stream.XMLInputFactory;
import java.io.PrintWriter;
import java.util.Map;

@MetaInfServices(Module.class)
@Information(id = "xxe", version = "1.0.0", author = "jrasp")
public class XxeModule implements Module, LoadCompleted {

    @Resource
    private Log logger;

    @Resource
    private JSONObject jsonObject;

    @Resource
    private ModuleEventWatcher moduleEventWatcher;

    private volatile boolean enableBlock = true;

    @Override
    public void loadCompleted() {
        ClassLoaderUtil.earlyLoadClass(
                "javax.xml.parsers.DocumentBuilderFactory",
                "javax.xml.stream.XMLInputFactory");
        closeDocumentBuilderFactoryConfigXXE();
        closeXMLInputFactoryConfigXXE();
    }

    @Command("/block")
    public void block(Map<String, String> parameterMap, final PrintWriter writer) {
        String isBlockParam = parameterMap.get("isBlock");
        enableBlock = Boolean.parseBoolean(isBlockParam);
        logger.info("xxe block status: {}", enableBlock);
        String result = jsonObject.toJSONString(RestResultUtils.success("更新xxe阻断状态", enableBlock));
        writer.println(result);
        writer.flush();
        writer.close();
    }

    private static final String FEATURE_DEFAULTS_1 = "http://apache.org/xml/features/disallow-doctype-decl";
    private static final String FEATURE_DEFAULTS_2 = "http://xml.org/sax/features/external-general-entities";
    private static final String FEATURE_DEFAULTS_3 = "http://xml.org/sax/features/external-parameter-entities";
    private static final String FEATURE_DEFAULTS_4 = "http://apache.org/xml/features/nonvalidating/load-external-dtd";

    public void closeDocumentBuilderFactoryConfigXXE() {
        final String className = "javax.xml.parsers.DocumentBuilderFactory";
        final String methdName = "newInstance";
        new EventWatchBuilder(moduleEventWatcher)
                .onClass(className)
                .includeBootstrap()
                .onBehavior(methdName)
                .withEmptyParameterTypes()
                .onWatch(new AdviceListener() {
                    @Override
                    public void afterReturning(Advice advice) throws Throwable {
                        if (!enableBlock) {
                            return;
                        }
                        long start = System.nanoTime();
                        DocumentBuilderFactory instance = (DocumentBuilderFactory) advice.getReturnObj();
                        // 这个是基本的防御方式。 如果DTDs被禁用, 能够防止绝大部分的XXE;如果这里设置为true会影响mybatis-xml的加载
                        instance.setFeature(FEATURE_DEFAULTS_1, false);
                        // 如果不能完全禁用DTDs，至少下面的几个需要禁用:
                        instance.setFeature(FEATURE_DEFAULTS_2, false);
                        instance.setFeature(FEATURE_DEFAULTS_3, false);
                        instance.setFeature(FEATURE_DEFAULTS_4, false);
                        instance.setXIncludeAware(false);
                        instance.setExpandEntityReferences(false);
                        long end = System.nanoTime();
                        logger.info("方法: {}, 耗时: {} ms", className + "#" + methdName, (end - start) / 1000000.0);
                    }
                });
    }

    public void closeXMLInputFactoryConfigXXE() {
        final String className = "javax.xml.stream.XMLInputFactory";
        final String methdName = "newInstance";
        new EventWatchBuilder(moduleEventWatcher)
                .onClass(className)
                .includeBootstrap()
                .onBehavior(methdName)
                .withEmptyParameterTypes()
                .onWatch(new AdviceListener() {
                    @Override
                    public void afterReturning(Advice advice) throws Throwable {
                        if (!enableBlock) {
                            return;
                        }
                        long start = System.nanoTime();
                        XMLInputFactory factory = (XMLInputFactory) advice.getReturnObj();
                        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
                        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
                        long end = System.nanoTime();
                        logger.info("方法: {}, 耗时: {} ms", className + "#" + methdName, (end - start) / 1000000.0);
                    }
                });
    }
}
