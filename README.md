# jrasp-module


## 简介

jrasp-agent的安全模块,目前支持的模块有:

rce、xxe、mysql、file、jetty、tomcat、weblogic

## 使用

### 依赖安装
由于 jrasp-module 依赖 jrasp-agent 项目,需要先下载安装 [jrasp-agent](https://github.com/jvm-rasp/jrasp-agent) 项目，在`jrasp-agent`工程根目录下执行  `mvn clean install` 

### 编译输出

编译使用的是 jdk8

工程根目录下执行 `mvn clean package` 

全部插件在目录`deploy`下

### 运行

将`deploy`下插件复制到 jrasp-agent/required-module