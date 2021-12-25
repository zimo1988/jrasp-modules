package com.jrasp.module.mysql;

import com.jrasp.api.*;
import com.jrasp.api.Module;
import com.jrasp.api.json.JSONObject;
import com.jrasp.api.listener.ext.Advice;
import com.jrasp.api.listener.ext.AdviceListener;
import com.jrasp.api.listener.ext.EventWatchBuilder;
import com.jrasp.api.log.Log;
import com.jrasp.api.resource.ModuleEventWatcher;
import com.jrasp.module.common.ReflectUtils;
import com.jrasp.module.common.StackTrace;
import org.kohsuke.MetaInfServices;

import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;

import static com.jrasp.module.common.ClassLoaderUtil.earlyLoadClass;

@MetaInfServices(Module.class)
@Information(id = "mysql", isActiveOnLoad = true, version = "1.0.0", author = "jrasp")
public class MySQLModule implements Module, LoadCompleted {

    @Resource
    private Log logger;

    @Resource
    private JSONObject jsonObject;

    @Resource
    private ModuleEventWatcher moduleEventWatcher;

    @Resource
    private ThreadLocal<HashMap<String, Object>> requestInfoThreadLocal;

    private volatile boolean enableBlock = false;

    @Override
    public void loadCompleted() {
        earlyLoadClass("com.mysql.jdbc.StatementImpl", "com.mysql.jdbc.Statement", "com.mysql.jdbc.PreparedStatement");
        earlyLoadClass("com.mysql.cj.jdbc.StatementImpl", "com.mysql.cj.jdbc.ClientPreparedStatement", "com.mysql.cj.jdbc.PreparedStatement");
        statementSqlHook();
        preparedStatementSqlHook();
    }

    // sql 拼接
    public void statementSqlHook() {
        new EventWatchBuilder(moduleEventWatcher, EventWatchBuilder.PatternType.REGEX)
                .onClass("com.mysql.jdbc.StatementImpl|com.mysql.jdbc.Statement|com.mysql.cj.jdbc.StatementImpl")
                .includeBootstrap()
                .onBehavior("executeInternal|executeUpdateInternal|addBatch|executeQuery")
                .hasExceptionTypes(SQLException.class)
                .onWatch(new AdviceListener() {
                    @Override
                    public void before(Advice advice) throws Throwable {
                        Object[] parameterArray = advice.getParameterArray();
                        if (parameterArray == null) {
                            return;
                        }
                        String sql = (String) parameterArray[0];
                        checkSqlAndPrintLog(sql, advice.getTarget().getClass().getName(), advice.getBehavior().getName());
                    }

                    @Override
                    public void afterThrowing(Advice advice) throws Throwable {
                        requestInfoThreadLocal.remove();
                    }
                });
    }

    // sql 预编译
    public void preparedStatementSqlHook() {
        new EventWatchBuilder(moduleEventWatcher, EventWatchBuilder.PatternType.REGEX)
                .onClass("com.mysql.jdbc.PreparedStatement|com.mysql.cj.jdbc.ClientPreparedStatement|com.mysql.cj.jdbc.PreparedStatement")
                .includeBootstrap()
                .onBehavior("execute|executeQuery|executeUpdate|executeBatchInternal|executeBatch")
                .withEmptyParameterTypes()
                .onWatch(new AdviceListener() {
                    @Override
                    public void before(Advice advice) throws Throwable {
                        // todo 参考 openrasp, 使用反射获取SQL,可以优化为直接调用
                        // mysql5.x
                        //PreparedStatement preparedStatement=(PreparedStatement)advice.getTarget();
                        //String sql=preparedStatement.getPreparedSql();
                        // mysql8.x
                        //JdbcPreparedStatement jdbcPreparedStatement = (JdbcPreparedStatement) advice.getTarget();
                        //String sql = jdbcPreparedStatement.getPreparedSql();
                        Method getPreparedSql = ReflectUtils.getMethod(advice.getTarget().getClass(), "getPreparedSql");
                        String sql = ReflectUtils.invokeMethod(getPreparedSql, advice.getTarget());
                        checkSqlAndPrintLog(sql, advice.getTarget().getClass().getName(), advice.getBehavior().getName());
                    }

                    @Override
                    public void afterThrowing(Advice advice) throws Throwable {
                        requestInfoThreadLocal.remove();
                    }
                });
    }


    private void checkSqlAndPrintLog(String sql, String className, String method) throws ProcessControlException {
        long start = System.nanoTime();

        // 获取上下文参数
        HashMap<String, Object> stringObjectHashMap = requestInfoThreadLocal.get();
        HashMap<String, Object> result = new HashMap<String, Object>(stringObjectHashMap);

        // sql
        result.put("sql", sql);

        // 获取栈
        ArrayList<String> stackTrace = StackTrace.getStackTrace();
        result.put("stackTrace", stackTrace);

        // 输出日志
        String s = jsonObject.toJSONString(result);
        logger.warn(s);

        // 耗时统计
        long end = System.nanoTime();
        logger.info("方法: {}, 耗时: {} ms", className + "#" + method, (end - start) / 1000000.0);

        // 抛出异常阻断
        if (enableBlock) {
            String info = " sql [" + sql + "] block by rasp.";
            ProcessControlException.throwThrowsImmediately(new RuntimeException(info));
        }
    }


}
