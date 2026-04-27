# API 客户端文档

HAP Auto Maker Java 提供统一的 API 客户端架构，支持 V3 API（OpenAPI）和 Web API 两种调用方式。

## 架构概览

```
┌───────────────────────────────────────────────────────────────┐
│                      HapApiClient                             │
├───────────────────────────────────────────────────────────────┤
│  ┌───────────────┐  ┌───────────────┐  ┌─────────────────┐  │
│  │  V3 API Methods│  │  Web API Methods│  │  Utility Methods │  │
│  │  (OpenAPI)     │  │  (Cookie Auth) │  │                  │  │
│  └───────┬───────┘  └───────┬───────┘  └─────────────────┘  │
│          │                  │                                │
│          ▼                  ▼                                │
│  ┌────────────────────────────────────────────────────────┐  │
│  │              OpenApiClient (V3 API)                     │  │
│  │  - HMAC-SHA1 签名认证                                  │  │
│  │  - 应用管理、工作表操作                                 │  │
│  └────────────────────────────────────────────────────────┘  │
│          │                                                   │
│          ▼                                                   │
│  ┌────────────────────────────────────────────────────────┐  │
│  │         organization_auth.json (凭证存储)               │  │
│  │  { app_key, secret_key, project_id, owner_id }          │  │
│  └────────────────────────────────────────────────────────┘  │
│                                                               │
│  ┌────────────────────────────────────────────────────────┐  │
│  │              Web Cookie (Web API)                       │  │
│  │  - Cookie 认证 (accountid, md_pss_id)                  │  │
│  │  - 页面操作、高级功能                                   │  │
│  └────────────────────────────────────────────────────────┘  │
│          │                                                   │
│          ▼                                                   │
│  ┌────────────────────────────────────────────────────────┐  │
│  │              web_auth.json (凭证存储)                   │  │
│  │  { accountid, md_pss_id }                              │  │
│  └────────────────────────────────────────────────────────┘  │
└───────────────────────────────────────────────────────────────┘
```

## V3 API (OpenAPI)

### 认证方式

使用 HMAC-SHA1 签名认证，由 `OpenApiClient` 自动处理。

**认证流程：**
1. 从 `organization_auth.json` 加载 `app_key` 和 `secret_key`
2. 对每个请求生成时间戳和签名
3. 在请求头或 URL 参数中添加认证信息

### 主要方法

#### 应用管理

```java
// 创建应用
public JsonNode createApp(String name, String icon, String color) throws Exception

// 删除应用
public JsonNode deleteApp(String appId, String projectId) throws Exception

// 获取应用授权
public JsonNode getAppAuthorize(String appId) throws Exception

// 删除工作表
public JsonNode deleteWorkSheet(String appId, String worksheetId) throws Exception
```

#### 工作表管理

```java
// 添加工作表
public JsonNode addWorkSheet(String appId, String appSectionId, String projectId,
                              String name, String icon, String iconColor) throws Exception

// 获取工作表字段
public JsonNode getWorksheetInfo(String worksheetId) throws Exception

// 更新工作表字段
public JsonNode editWorksheetName(String worksheetId, String name) throws Exception

// 获取关联表信息
public JsonNode getRelationTables(String worksheetId) throws Exception
```

#### 数据操作

```java
// 添加行数据
public JsonNode addRow(String worksheetId, String appId, JsonNode data) throws Exception

// 获取行详情
public JsonNode getRowDetail(String worksheetId, String rowId) throws Exception

// 保存行数据
public JsonNode saveRow(String worksheetId, String appId, JsonNode data) throws Exception

// 获取筛选行列表
public JsonNode getFilterRows(String worksheetId, JsonNode filter) throws Exception
```

#### 图表操作

```java
// 获取图表列表
public JsonNode getChartList(String worksheetId) throws Exception

// 获取图表详情
public JsonNode getChartDetails(String reportId) throws Exception

// 创建图表
public JsonNode createChart(String worksheetId, String appId,
                             String name, String type, JsonNode config) throws Exception

// 更新图表
public JsonNode updateChart(String reportId, String worksheetId, String name,
                             String type, JsonNode config) throws Exception

// 删除图表
public JsonNode deleteChart(String reportId) throws Exception
```

### V3 API 响应格式

```json
{
  "data": { ... },
  "success": true,
  "error_code": 1
}
```

**成功标志:** `error_code == 1`

## Web API

### 认证方式

使用 Cookie 认证，从 `web_auth.json` 加载 `accountid` 和 `md_pss_id`。

### 主要方法

#### 页面操作

```java
// 获取页面详情
public JsonNode getPageDetail(String pageId) throws Exception

// 更新页面
public JsonNode updatePage(String pageId, JsonNode pageData) throws Exception

// 添加工作表（Web API 版本）
public JsonNode addWorkSheetWeb(String name, String appId, String appSectionId,
                                 String icon, String iconColor, String projectId) throws Exception

// 获取视图筛选列表
public JsonNode getViewFilterRows(String viewId, JsonNode options) throws Exception
```

### Web API 响应格式

```json
{
  "state": 1,
  "data": { ... }
}
```

或

```json
{
  "resultCode": 1,
  "data": { ... }
}
```

**成功标志:** `state == 1` 或 `resultCode == 1`

## 使用示例

### 初始化客户端

```java
// 从配置文件初始化
ConfigManager config = new ConfigManager("config/credentials");
AiConfig aiConfig = config.loadAiConfig();
OrganizationAuth orgAuth = config.loadOrganizationAuth();
WebAuth webAuth = config.loadWebAuth();

// 创建 API 客户端
HapApiClient client = new HapApiClient(orgAuth, aiConfig, webAuth);
```

### 创建应用和工作表

```java
// 1. 创建应用
JsonNode appResult = client.createApp("测试应用", "sys_0_lego", "#2196F3");
String appId = appResult.get("data").get("appId").asText();

// 2. 获取应用授权
JsonNode authResult = client.getAppAuthorize(appId);
String token = authResult.get("data").get("token").asText();

// 3. 添加工作表
JsonNode sheetResult = client.addWorkSheet(
    appId, sectionId, projectId,
    "客户表", "sys_0_account", "#2196F3"
);
String worksheetId = sheetResult.get("data").get("worksheetId").asText();
```

### 创建图表

```java
// 准备图表配置
ObjectNode config = mapper.createObjectNode();
config.put("xAxis", "客户名称");
config.put("yAxis", "订单金额");
config.put("chartType", "bar");

// 创建图表
JsonNode chartResult = client.createChart(
    worksheetId, appId,
    "订单金额统计", "bar",
    config
);
```

### 页面操作（需要 Web 认证）

```java
// 获取页面详情
JsonNode pageDetail = client.getPageDetail(pageId);

// 更新页面
ObjectNode pageData = mapper.createObjectNode();
pageData.put("name", "新页面名称");
// ... 更多页面配置

JsonNode updateResult = client.updatePage(pageId, pageData);
```

## 错误处理

### 异常类型

```java
try {
    JsonNode result = client.createApp(name, icon, color);
} catch (IOException e) {
    // 网络错误
    logger.error("网络错误: {}", e.getMessage());
} catch (IllegalStateException e) {
    // API 返回错误
    logger.error("API 错误: {}", e.getMessage());
}
```

### 检查响应状态

```java
// V3 API
if (result.has("error_code") && result.get("error_code").asInt() != 1) {
    String errorMsg = result.has("error_msg") ?
        result.get("error_msg").asText() : "Unknown error";
    throw new IllegalStateException("API error: " + errorMsg);
}

// Web API
if (result.has("state") && result.get("state").asInt() != 1) {
    String errorMsg = result.has("msg") ?
        result.get("msg").asText() : "Unknown error";
    throw new IllegalStateException("Web API error: " + errorMsg);
}
```

## 配置说明

### organization_auth.json

```json
{
  "app_key": "32c4602b924122e4",
  "secret_key": "29c6ab1fcc12d91ebbb4bbd6d528d0de",
  "project_id": "015f0f09-41e2-499c-a66e-beabd87a3b31",
  "owner_id": "1bdf4a16-73e0-4b4c-bc39-8038af13d325",
  "group_ids": ""
}
```

**字段说明：**
- `app_key`: 明道云开放平台应用 Key
- `secret_key`: 明道云开放平台应用密钥
- `project_id`: 项目/网络 ID
- `owner_id`: 应用所有者用户 ID
- `group_ids`: 分组 ID（可选）

### web_auth.json

```json
{
  "accountid": "your-account-id",
  "md_pss_id": "your-session-cookie"
}
```

**获取方式：**
1. 登录明道云 Web 端
2. 打开浏览器开发者工具（F12）
3. 查看 Application/Storage/Cookies
4. 复制 `accountid` 和 `md_pss_id` 的值

**注意：** Cookie 有有效期，过期后需要重新获取。

## 高级用法

### 自定义 HTTP 客户端

```java
// 配置连接池和超时
HttpClient customClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(30))
    .build();

HapApiClient client = new HapApiClient(orgAuth, aiConfig, webAuth, customClient);
```

### 批量操作

```java
// 批量创建工作表
for (WorksheetPlan plan : plans) {
    try {
        JsonNode result = client.addWorkSheet(appId, sectionId, projectId,
            plan.getName(), plan.getIcon(), plan.getIconColor());
        // 处理结果...
    } catch (Exception e) {
        // 记录错误，继续下一个
        logger.error("创建工作表失败: {}", plan.getName(), e);
    }
}
```

### 重试机制

API 客户端不内置重试逻辑，建议在调用方实现：

```java
@Retryable(value = {IOException.class}, maxAttempts = 3, backoff = @Backoff(delay = 1000))
public JsonNode callWithRetry(String appId) throws Exception {
    return client.getAppAuthorize(appId);
}
```

## 调试技巧

### 启用请求/响应日志

在 `logback.xml` 中添加：

```xml
<logger name="com.hap.automaker.api" level="DEBUG" />
```

### 查看原始请求

```java
// 在调用前打印请求参数
logger.debug("Request: POST {} with body {}", endpoint, body.toString());

// 在调用后打印响应
logger.debug("Response: {}", result.toString());
```

### 使用 WireMock 测试

```java
@WireMockTest(httpPort = 8080)
class ApiTest {
    @Test
    void testCreateApp() {
        stubFor(post("/openapi/create/app")
            .willReturn(okJson("{\"data\":{\"appId\":\"test-id\"},\"error_code\":1}")));

        OrganizationAuth testAuth = new OrganizationAuth("key", "secret", "project", "owner");
        HapApiClient client = new HapApiClient(testAuth, null, null);

        JsonNode result = client.createApp("Test", "icon", "#000");
        assertEquals("test-id", result.get("data").get("appId").asText());
    }
}
```

## 迁移指南

从 Python `hap_wrapper.py` 迁移到 Java：

| Python | Java |
|--------|------|
| `HapClient.call_v3()` | `client.postV3()` |
| `HapClient.call_web()` | `client.postWeb()` |
| `HapClient.get_v3()` | `client.getV3()` |
| `auth_config['app_key']` | `OrganizationAuth.getAppKey()` |
| `auth_config['secret_key']` | `OrganizationAuth.getSecretKey()` |
