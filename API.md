# Fan-ME-FRP Launcher API 文档

## 基础信息

| 项目 | 内容 |
|------|------|
| 基础 URL | `http://127.0.0.1:1023` |
| 绑定地址 | `127.0.0.1`（仅本地访问） |
| 端口 | `1023` |
| 响应格式 | `application/json; charset=utf-8` |
| CORS | 允许所有来源 |

---

## 接口列表

### 1. 登录 / 启动隧道

通过 accesstoken 验证身份并启动 frpc 隧道。

#### 请求

```
POST /api/login
```

#### 请求头

| 字段 | 值 |
|------|-----|
| Content-Type | application/json |

#### 请求体

```json
{
  "accesstoken": "CB92FABF19DE772C267F3531ABFE40BA1C3A865A_138425"
}
```

#### 参数说明

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| accesstoken | string | 是 | 格式: `runId_proxyId`，下划线拼接 |

**accesstoken 格式说明：**

- `runId` — 运行 ID，用于 Bearer Token 鉴权
- `proxyId` — 隧道代理 ID，数字
- 示例: `CB92FABF19DE772C267F3531ABFE40BA1C3A865A_138425`

#### 响应

##### 成功 (200)

```json
{
  "code": 200,
  "message": "登录成功，frpc 已启动"
}
```

##### 参数错误 (400)

```json
{
  "code": 400,
  "message": "缺少 accesstoken 参数"
}
```

```json
{
  "code": 400,
  "message": "accesstoken 格式错误，应为 runId_proxyId"
}
```

```json
{
  "code": 400,
  "message": "accesstoken 格式错误，末尾需为数字 proxyId"
}
```

##### 服务器错误 (500)

```json
{
  "code": 500,
  "message": "FRPC 初始化失败"
}
```

```json
{
  "code": 500,
  "message": "获取隧道配置失败"
}
```

```json
{
  "code": 500,
  "message": "frpc 启动失败"
}
```

#### 调用示例

```bash
curl -X POST http://127.0.0.1:1023/api/login \
  -H "Content-Type: application/json" \
  -d '{"accesstoken": "CB92FABF19DE772C267F3531ABFE40BA1C3A865A_138425"}'
```

#### 工作流程

```
POST /api/login
  │
  ├─ 解析 accesstoken → runId + proxyId
  │
  ├─ 初始化 FrpcManager（下载依赖）
  │
  ├─ EasyStartup.execute(runId, proxyId)
  │   ├─ POST https://api.mefrp.com/api/auth/easyStartup
  │   │   (Bearer: runId, Body: {proxyId})
  │   └─ 生成 tmp/tmp_{proxyId}.toml
  │
  └─ FrpcManager.start(config)
      └─ JNA 加载 frpc_jna.dll 启动隧道
```

---

## 错误码说明

| HTTP 状态码 | code | 说明 |
|-------------|------|------|
| 200 | 200 | 成功 |
| 400 | 400 | 请求参数错误 |
| 404 | 404 | 接口不存在 |
| 500 | 500 | 服务器内部错误 |

---

## 启动方式

### GUI 模式（自动启动 API）

```bash
java -jar Fan-ME-FRP-Launcher-1.0.jar
```

GUI 启动后自动在 `127.0.0.1:1023` 开启 API 服务。

### 测试 API

```bash
# 启动 GUI
java -jar Fan-ME-FRP-Launcher-1.0.jar &

# 调用 API
curl -X POST http://127.0.0.1:1023/api/login \
  -H "Content-Type: application/json" \
  -d '{"accesstoken": "CB92FABF19DE772C267F3531ABFE40BA1C3A865A_138425"}'
```
