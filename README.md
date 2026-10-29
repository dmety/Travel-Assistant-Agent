# Travel-Assistant-Agent · 旅行助手

本项目是基于 Java 面向对象开发的控制台旅行规划工具，支持多套行程编排、预算统计与本地文件持久化存储。

Java 21 / Maven 多模块控制台项目。当前交付为可连接的工程骨架，功能规划见[架构设计](架构设计.md)和[需求文档](需求文档.md)。业务功能尚未实现。

## 文档目录

- [架构设计](架构设计.md)
- [需求文档](需求文档.md)
- [业务功能技术实现方案](业务功能技术实现方案.md)
- [各版本功能模块](旅行助手-各版本功能模块.md)
- [三个递进版本](旅行助手-三个递进版本.md)
- [架构图片](assets/)

以下命令均在项目根目录执行。

## 环境与构建

安装 JDK 21 和 Maven 3.9，确认 `java -version` 和 `mvn -version` 指向正确环境。在项目根目录运行：

```powershell
mvn clean verify
```

## 启动

在第一个 PowerShell 窗口运行后端：

```powershell
java -jar travel-server/target/travel-server-0.1.0-SNAPSHOT.jar
```

在另一个窗口运行客户端：

```powershell
java -jar travel-client/target/travel-client-0.1.0-SNAPSHOT.jar http://localhost:8080
```

其他电脑将 localhost 替换为服务端可达地址；部署时配置 HTTPS，避免明文传输凭据。设置 `SESSION_COOKIE_SECURE=true` 启用安全会话 Cookie。

健康检查为 `GET /api/health`。当前其他接口默认需要认证；登录接口、业务菜单、Agent 和存储尚待实现。配置文件中的 `TRAVEL_DATA_DIR` 是预留数据目录参数，当前尚无数据写入。

`DEEPSEEK_API_KEY` 和 `AMAP_API_KEY` 在后续适配器中通过环境变量读取，不提交密钥。Spring AI 与 Spring Boot 的版本在父 pom 中统一管理。

本次工程验证：Java 21 下 Maven verify 通过，两个服务端接口测试通过；实际启动后端并运行打包客户端，成功获得 UP 响应。该结果仅证明工程构建、安全入口和客户端连接，不代表业务功能已完成。
