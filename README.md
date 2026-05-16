# Fan-ME-FRP Launcher

> 幻缘映射 - FRP 客户端启动器

基于 Java 的 FRP 客户端启动器，支持 GUI 图形界面和命令行模式，自动下载依赖、管理 frpc 生命周期。

---

## 功能特性

- **双模式启动** — GUI 图形界面（内置浏览器）+ 命令行模式
- **自动依赖管理** — 自动检测系统架构，下载对应平台的 frpc 动态库/二进制文件
- **多节点支持** — 支持官方 CF 穿透节点、xiaoli 捐赠节点、CF R2 OSS 节点
- **快捷启动** — 通过 API 获取隧道配置，一键启动
- **GUI API 服务** — 提供 RESTful API 供外部调用
- **跨平台** — 支持 Windows、Linux、Android (Termux)

## 快速开始

### 前置要求

- Java 17+
- Gradle（或使用项目自带的 `gradlew`）

### 构建

```bash
git clone https://github.com/xiaofanforfabric/Fan-ME-FRP-Launcher.git
cd Fan-ME-FRP-Launcher
gradlew jar
```

构建产物：`build/libs/Fan-ME-FRP-Launcher-1.0.jar`

### 使用方式

#### 1. GUI 模式

```bash
java -jar build/libs/Fan-ME-FRP-Launcher-1.0.jar
```

启动后自动开启：
- 图形界面（内置浏览器）
- GUI API 服务 (`http://127.0.0.1:1023`)

#### 2. 命令行模式（指定配置文件）

```bash
java -jar build/libs/Fan-ME-FRP-Launcher-1.0.jar -c frpc.ini
```

支持配置文件格式：`.ini` `.toml` `.yaml` `.yml` `.json`

#### 3. 快捷启动模式

```bash
java -jar build/libs/Fan-ME-FRP-Launcher-1.0.jar -t <runId> -p <proxyId>
```

通过 MEFrp API 自动获取隧道配置并启动。

## 项目结构

```
Fan-ME-FRP-Launcher/
├── src/main/java/com/xiaofan/launcher/
│   ├── Main.java              # 主入口
│   ├── GuiMain.java           # GUI 入口（JavaFX）
│   ├── LauncherUI.java        # 主界面
│   ├── api/
│   │   └── GuiApiServer.java  # GUI API 服务
│   ├── browser/
│   │   ├── BrowserEngine.java # 浏览器引擎
│   │   ├── BrowserTab.java    # 浏览器标签页
│   │   └── HtmlParser.java    # HTML 解析器
│   └── frpc/
│       ├── FrpcManager.java   # FRPC 进程管理器
│       ├── FrpcJnaBridge.java # JNA 桥接层
│       ├── EasyStartup.java   # 快捷启动
│       ├── DependencyManager.java # 依赖管理器
│       ├── VersionChecker.java    # 版本检查
│       └── ConfigParser.java      # 配置解析
├── implementation/frp/        # FRP Go 源码（子模块）
├── res/                       # 运行时资源文件
├── API.md                     # API 文档
├── build.gradle               # Gradle 构建配置
└── frpc.ini                   # FRPC 配置示例
```

## 技术栈

- **Java 17** — 主开发语言
- **JavaFX** — GUI 图形界面
- **JNA (Java Native Access)** — 调用 frpc 动态库
- **Gradle** — 构建工具
- **Go** — frpc 核心（`implementation/frp/`）

## 相关链接

- [MEFrp 官网](https://www.mefrp.com)
- [MEFrp API 文档](API.md)
- [FRP 项目](https://github.com/fatedier/frp)

## 贡献者

- **xiaofan** — 后端开发者，项目所有者
- **浪白3jE** — 前端开发者，节点贡献者

## 许可证

本项目基于 [Apache License 2.0](LICENSE) 开源。
