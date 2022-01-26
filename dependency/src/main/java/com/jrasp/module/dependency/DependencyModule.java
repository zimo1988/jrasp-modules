package com.jrasp.module.dependency;

import com.jrasp.api.*;
import com.jrasp.api.annotation.Command;
import com.jrasp.api.json.JSONObject;
import com.jrasp.api.log.Log;
import com.jrasp.module.dependency.util.BasicThreadFactory;
import org.kohsuke.MetaInfServices;

import java.io.*;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

// 部分代码来源于open-rasp,并在其基础上进行了优化
@MetaInfServices(Module.class)
@Information(id = "dependency", version = "1.0.0", author = "jrasp")
public class DependencyModule extends ModuleLifecycleAdapter implements Module {

    @Resource
    private Log logger;

    @Resource
    private JSONObject jsonObject;

    @Resource
    private Instrumentation instrumentation;

    @Resource
    private ConfigInfo configInfo;

    private static final int MAX_DEPENDENCES_CACHE = 1000;
    private static final String DEPENDENCY_SOURCE_MANEFEST_IMPL = "manifest_implementation";
    private static final String DEPENDENCY_SOURCE_MANEFEST_SPEC = "manifest_specification";
    private static final String DEPENDENCY_SOURCE_MANEFEST_BUNDLE = "manifest_bundle";
    private static final String DEPENDENCY_SOURCE_POM = "pom";

    public static ConcurrentSkipListSet<String> loadedJarPaths = new ConcurrentSkipListSet<String>();

    // 定时检测的线程
    private static ScheduledExecutorService executorService = new ScheduledThreadPoolExecutor(1,
            new BasicThreadFactory.Builder().namingPattern("jrasp-sca-pool-%d").daemon(true).build());

    @Override
    public void loadCompleted() {
        start(); // 模块加载完成之后
    }

    @Override
    public void onUnload() throws Throwable {
        stop();  // 模块卸载时
    }

    // 12小时上报一次
    private static final long FREQUENCY = 12*60*60;

    private AtomicBoolean initialize = new AtomicBoolean(false);

    private void stop() {
        if (initialize.compareAndSet(true, false)) {
            executorService.shutdown();
        }
    }

    // 定时触发
    private synchronized void start() {
        if (initialize.compareAndSet(false, true)) {
            executorService.scheduleAtFixedRate(new Runnable() {
                @Override
                public void run() {
                    try {
                        work();
                    } catch (Exception e) {
                        logger.error("error occurred when report sca", e);
                    }
                }
            }, 1, FREQUENCY, TimeUnit.SECONDS); // to avoid dead lock, init time could not be 0
        }
    }

    // jrasp-daemon 请求你触发
    @Command("/get")
    public void get(final Map<String, String> parameterMap, final PrintWriter writer) {
        String result = work();
        writer.println(result);
        writer.flush();
        writer.close();
    }

    private String work() {
        Class[] allLoadedClasses = instrumentation.getAllLoadedClasses();
        // 遍历所有已经加载的类
        for (int i = 0; i < allLoadedClasses.length; i++) {
            Class clazz = allLoadedClasses[i];
            ProtectionDomain domain = clazz.getProtectionDomain();
            if (domain != null &&
                    domain.getCodeSource() != null &&
                    domain.getCodeSource().getLocation() != null) {
                String path = domain.getCodeSource().getLocation().getPath();
                if (path != null && path.length() > 0) {
                    if ((path.endsWith(".jar")
                            || path.endsWith(".jar!")
                            || path.endsWith(".jar!/")
                            || path.endsWith(".jar/")
                            || path.endsWith(".jar!" + File.separator))
                            && !(loadedJarPaths.size() >= MAX_DEPENDENCES_CACHE)) {
                        if (!path.endsWith(".jar")) {
                            int start = path.contains("/") ? path.indexOf("/") : path.indexOf("\\");
                            path = path.substring(start, path.lastIndexOf(".jar") + 4);
                        }
                        loadedJarPaths.add(path);
                    }
                }
            }
        }
        HashSet<Dependency> dependencySet = getDependencySet();
        String result = jsonObject.toJSONString(dependencySet);
        logger.info(result);
        return result;
    }

    public HashSet<Dependency> getDependencySet() {
        HashSet<Dependency> dependencySet = new HashSet<Dependency>();
        for (String path : loadedJarPaths) {
            String realPath = path;
            String subPath = null;
            int step = 6;
            int i = path.indexOf(".jar!");
            if (i < 0) {
                step = 5;
                i = path.indexOf(".jar/");
            }
            if (i > 0) {
                realPath = path.substring(0, i + 4);
                subPath = path.substring(i + step);
            }
            JarFile jarFile;
            try {
                jarFile = new JarFile(realPath);
            } catch (IOException e) {
                if (e instanceof FileNotFoundException) {
                    loadedJarPaths.remove(path);
                } else {
                    logger.warn("failed to create jar file from " + path + ": " + e.getMessage(), e);
                }
                continue;
            }
            try {
                Dependency dependency = loadDependencyFromJarFile(jarFile, path);
                if (dependency != null) {
                    dependencySet.add(dependency);
                }
                if (subPath != null) {
                    dependency = loadDependencyFromJar(jarFile, path, subPath);
                    if (dependency != null) {
                        dependencySet.add(dependency);
                    }
                }
            } catch (Exception e) {
                logger.warn("failed to parse dependency from jar file " + path + ": " + e.getMessage(), e);
            }
            try {
                jarFile.close();
            } catch (IOException e) {
                logger.warn("failed to close jar file " + path + ": " + e.getMessage(), e);
            }
        }
        return dependencySet;
    }

    private Dependency loadDependencyFromJarFile(JarFile jarFile, String path) throws Exception {
        Dependency dependency = loadDependencyFromPOM(jarFile, path);
        if (dependency != null) {
            return dependency;
        } else {
            dependency = loadDependencyFromManifest(jarFile, path);
            if (dependency != null) {
                return dependency;
            }
        }
        return null;
    }

    private Dependency loadDependencyFromJar(JarFile jarFile, String path, String subPath) throws Exception {
        InputStream in = jarFile.getInputStream(jarFile.getEntry(subPath));
        File outFile = new File(configInfo.getRaspHome() + File.separator + "tmp");
        OutputStream out = new FileOutputStream(outFile);
        byte[] buffer = new byte[1024];
        int i;
        while ((i = in.read(buffer)) != -1) {
            out.write(buffer, 0, i);
        }
        out.flush();
        try {
            out.close();
            in.close();
        } catch (Throwable t) {
            // ignore
        }
        JarFile file = new JarFile(outFile);
        Dependency dependency = loadDependencyFromJarFile(file, path);
        file.close();
        return dependency;
    }

    private Dependency loadDependencyFromPOM(JarFile jarFile, String path) throws Exception {
        InputStream in = readPomFromJarFile(jarFile);
        try {
            if (in != null) {
                Properties properties = new Properties();
                properties.load(in);
                String product = properties.getProperty("artifactId");
                String version = properties.getProperty("version");
                String vendor = properties.getProperty("groupId");
                if (product != null && version != null) {
                    return new Dependency(product, version, vendor, path, DEPENDENCY_SOURCE_POM);
                }
            }
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException io) {
                    logger.warn("Error closing pom inputStream: ", io);
                }
            }
        }
        return null;
    }

    private InputStream readPomFromJarFile(JarFile file) throws Exception {
        Enumeration<? extends ZipEntry> entries = file.entries();
        if (entries != null) {
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().startsWith("META-INF")
                        && entry.getName().endsWith("pom.properties")
                        && !entry.isDirectory()) {
                    return file.getInputStream(entry);
                }
            }
        }
        return null;
    }

    private Dependency loadDependencyFromManifest(JarFile jarFile, String path) throws IOException {
        Manifest manifest = jarFile.getManifest();
        if (manifest != null) {
            Attributes attributes = manifest.getMainAttributes();
            String name = attributes.getValue("implementation-title");
            String version = attributes.getValue("implementation-version");
            String vendor = attributes.getValue("implementation-vendor-id");
            if (vendor == null) {
                vendor = attributes.getValue("implementation-vendor");
            }
            if (name != null && version != null) {
                return new Dependency(name, version, vendor, path, DEPENDENCY_SOURCE_MANEFEST_IMPL);
            } else {
                name = attributes.getValue("specification-title");
                version = attributes.getValue("specification-version");
                vendor = attributes.getValue("specification-vendor");
                if (name != null && version != null) {
                    return new Dependency(name, version, vendor, path, DEPENDENCY_SOURCE_MANEFEST_SPEC);
                } else {
                    name = attributes.getValue("bundle-symbolicname");
                    version = attributes.getValue("bundle-version");
                    vendor = attributes.getValue("bundle-vendor");
                    if (name != null && version != null) {
                        return new Dependency(name, version, vendor, path, DEPENDENCY_SOURCE_MANEFEST_BUNDLE);
                    }
                }
            }
        }
        return null;
    }

}
