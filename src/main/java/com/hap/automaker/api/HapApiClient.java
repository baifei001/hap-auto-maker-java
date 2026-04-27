package com.hap.automaker.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hap.automaker.config.Jacksons;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/**
 * HAP (明道云) API 客户端
 *
 * 支持两种认证方式：
 * 1. 用户 Token 认证（Web API）- 需要 authToken + projectId
 * 2. 组织认证（Open API）- 需要 AppKey + AppSecret + 签名
 */
public class HapApiClient {

    private static final String BASE_URL = "https://api.mingdao.com";
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    // 用户认证
    private String authToken;
    private String projectId;

    // 组织认证
    private String appKey;
    private String appSecret;

    public HapApiClient() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        this.mapper = Jacksons.mapper();
    }

    public void setAuth(String authToken, String projectId) {
        this.authToken = authToken;
        this.projectId = projectId;
    }

    public void setOrgAuth(String appKey, String appSecret) {
        this.appKey = appKey;
        this.appSecret = appSecret;
    }

    // ========== 组织认证 API (Open API /v1/open/) ==========

    /**
     * 创建应用（组织认证 API）- 完整参数版本
     *
     * Python 对应: create_app.py - /v1/open/app/create
     *
     * @param name 应用名称
     * @param description 应用描述
     * @param icon 图标名称
     * @param color 主题颜色
     * @param projectId 项目ID
     * @param ownerId 拥有者ID
     * @param groupIds 分组ID列表（逗号分隔）
     * @return 包含 appId 的响应
     */
    public JsonNode createAppOpen(String name, String description, String icon,
                                   String color, String projectId, String ownerId,
                                   String groupIds) throws Exception {
        if (appKey == null || appSecret == null) {
            throw new IllegalStateException("Organization auth not configured. Call setOrgAuth first.");
        }

        long timestamp = System.currentTimeMillis();
        String sign = generateSign(timestamp);

        ObjectNode body = mapper.createObjectNode();
        body.put("appKey", appKey);
        body.put("sign", sign);
        body.put("timestamp", timestamp);
        body.put("projectId", projectId);
        body.put("name", name);
        body.put("icon", icon);
        body.put("color", color);
        body.put("ownerId", ownerId);

        // 处理分组ID
        if (groupIds != null && !groupIds.isEmpty()) {
            String[] groups = groupIds.split(",");
            var groupArray = mapper.createArrayNode();
            for (String g : groups) {
                if (!g.trim().isEmpty()) {
                    groupArray.add(g.trim());
                }
            }
            if (groupArray.size() > 0) {
                body.set("groupIds", groupArray);
            }
        }

        if (description != null && !description.isEmpty()) {
            body.put("description", description);
        }

        return postOpen("/v1/open/app/create", body);
    }

    /**
     * 生成组织认证签名
     *
     * Python 对应: create_app.py build_sign()
     * raw = f"AppKey={app_key}&SecretKey={secret_key}&Timestamp={timestamp_ms}"
     * sign = base64(sha256(raw).hexdigest())
     *
     * 注意：这与 V3 API 的 HMAC-SHA256 签名不同！
     */
    private String generateSign(long timestamp) throws Exception {
        String raw = "AppKey=" + appKey + "&SecretKey=" + appSecret + "&Timestamp=" + timestamp;
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return Base64.getEncoder().encodeToString(hexString.toString().getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode postOpen(String path, JsonNode body) throws Exception {
        String url = BASE_URL + path;

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(120))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Open API call failed: " + response.statusCode() + " " + response.body());
        }

        JsonNode result = mapper.readTree(response.body());

        // 检查明道云错误码
        int errorCode = result.path("error_code").asInt(0);
        if (errorCode != 1) {
            String msg = result.path("error_msg").asText("Unknown error");
            throw new RuntimeException("HAP Open API error: " + errorCode + " - " + msg);
        }

        return result;
    }

    /**
     * 发送 GET 请求到 Open App API（使用预计算签名）
     * 用于 /v1/open/app/* 端点
     *
     * @param path API 路径（如 /v1/open/app/getRoles）
     * @param appId 应用 ID
     * @param appKey 应用 Key（从 app_authorize 加载）
     * @param sign 预计算签名（从 app_authorize 加载）
     * @return API 响应
     */
    public JsonNode getOpenApp(String path, String appId, String appKey, String sign) throws Exception {
        if (appKey == null || sign == null) {
            throw new IllegalStateException("Organization auth not configured.");
        }

        long timestamp = System.currentTimeMillis();

        // 构建带认证参数的 URL
        StringBuilder urlBuilder = new StringBuilder(BASE_URL + path);
        urlBuilder.append("?appKey=").append(URLEncoder.encode(appKey, StandardCharsets.UTF_8));
        urlBuilder.append("&sign=").append(URLEncoder.encode(sign, StandardCharsets.UTF_8));
        urlBuilder.append("&timestamp=").append(timestamp);
        urlBuilder.append("&appId=").append(URLEncoder.encode(appId, StandardCharsets.UTF_8));

        String url = urlBuilder.toString();

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json, text/plain, */*")
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Open App API call failed: " + response.statusCode() + " " + response.body());
        }

        JsonNode result = mapper.readTree(response.body());

        // 检查明道云错误码 (Open App API 使用 success 字段)
        if (!result.path("success").asBoolean(false)) {
            String msg = result.path("error_msg").asText("Unknown error");
            throw new RuntimeException("HAP Open App API error: " + msg);
        }

        return result;
    }

    /**
     * 发送 POST 请求到 Open App API（使用预计算签名）
     * Python create_roles_from_recommendation.py 使用此方式
     * 认证参数同时在 query string 和 body 中
     *
     * Python 对应:
     *   payload.update(params)  # 合并到 body
     *   requests.request(..., params=params, json=payload)
     *
     * @param path API 路径（如 /v1/open/app/createRole）
     * @param appId 应用 ID
     * @param appKey 应用 Key（从 app_authorize 加载）
     * @param sign 预计算签名（从 app_authorize 加载）
     * @param payload 业务数据（JSON body，认证参数会被合并进去）
     * @return API 响应
     */
    public JsonNode postOpenApp(String path, String appId, String appKey, String sign, JsonNode payload) throws Exception {
        if (appKey == null || appKey.isBlank() || sign == null || sign.isBlank()) {
            throw new IllegalStateException("Organization auth not configured (appKey or sign is empty).");
        }

        long timestamp = System.currentTimeMillis();

        // 构建带认证参数的 URL（query string 方式）
        StringBuilder urlBuilder = new StringBuilder(BASE_URL + path);
        urlBuilder.append("?appKey=").append(URLEncoder.encode(appKey, StandardCharsets.UTF_8));
        urlBuilder.append("&sign=").append(URLEncoder.encode(sign, StandardCharsets.UTF_8));
        urlBuilder.append("&timestamp=").append(timestamp);
        urlBuilder.append("&appId=").append(URLEncoder.encode(appId, StandardCharsets.UTF_8));

        String url = urlBuilder.toString();

        // 将认证参数合并到 body 中（与 Python payload.update(params) 一致）
        ObjectNode bodyWithAuth = (ObjectNode) payload.deepCopy();
        bodyWithAuth.put("appKey", appKey);
        bodyWithAuth.put("sign", sign);
        bodyWithAuth.put("timestamp", timestamp);
        bodyWithAuth.put("appId", appId);

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(120))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/plain, */*")
            .POST(HttpRequest.BodyPublishers.ofString(bodyWithAuth.toString()))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Open App API call failed: " + response.statusCode() + " " + response.body());
        }

        JsonNode result = mapper.readTree(response.body());

        // 检查明道云错误码 (Open App API 使用 success 字段)
        if (!result.path("success").asBoolean(false)) {
            String msg = result.path("error_msg").asText("Unknown error");
            throw new RuntimeException("HAP Open App API error: " + msg);
        }

        return result;
    }

    // ========== V3 API (开放平台 API) ==========

    /**
     * 创建工作表（V3 开放平台 API）
     *
     * Python 对应: create_worksheets_from_plan.py - POST /v3/app/worksheets
     *
     * @param name 工作表名称
     * @param fields 字段列表（符合 V3 API 格式的字段定义）
     * @return 包含 worksheetId 的响应
     */
    public JsonNode createWorksheetV3(String name, JsonNode fields) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("name", name);
        if (fields != null && !fields.isEmpty()) {
            body.set("fields", fields);
        }
        return postV3("/v3/app/worksheets", body);
    }

    /**
     * 编辑工作表 - 添加/更新字段（V3 开放平台 API）
     *
     * Python 对应: create_worksheets_from_plan.py - PUT /v3/app/worksheets/{worksheet_id}
     *
     * @param worksheetId 工作表ID
     * @param fields 要添加/更新的字段列表
     * @return API 响应
     */
    public JsonNode editWorksheetV3(String worksheetId, JsonNode fields) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        if (fields != null && !fields.isEmpty()) {
            body.set("fields", fields);
        }
        return putV3("/v3/app/worksheets/" + worksheetId, body);
    }

    private JsonNode postV3(String path, JsonNode body) throws Exception {
        return v3Request("POST", path, body);
    }

    private JsonNode putV3(String path, JsonNode body) throws Exception {
        return v3Request("PUT", path, body);
    }

    private JsonNode deleteV3(String path) throws Exception {
        return v3Request("DELETE", path, null);
    }

    /**
     * 删除工作表（V3 开放平台 API）
     *
     * Python 对应: delete_worksheet.py - DELETE /v3/app/worksheets/{worksheetId}
     *
     * @param worksheetId 工作表ID
     * @return API 响应
     */
    public JsonNode deleteWorksheetV3(String worksheetId) throws Exception {
        return deleteV3("/v3/app/worksheets/" + worksheetId);
    }

    private JsonNode v3Request(String method, String path, JsonNode body) throws Exception {
        if (appKey == null || appSecret == null) {
            throw new IllegalStateException("Organization auth not configured. Call setOrgAuth first.");
        }

        String url = BASE_URL + path;

        // 生成认证签名
        long timestamp = System.currentTimeMillis();
        String sign = generateSign(timestamp);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(120))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json");

        // 添加组织认证头
        HttpRequest.BodyPublisher bodyPublisher;
        if (body != null) {
            // 在 body 中添加认证参数
            ObjectNode bodyWithAuth = (ObjectNode) body;
            bodyWithAuth.put("appKey", appKey);
            bodyWithAuth.put("sign", sign);
            bodyWithAuth.put("timestamp", timestamp);
            bodyPublisher = HttpRequest.BodyPublishers.ofString(bodyWithAuth.toString());
        } else if ("DELETE".equals(method)) {
            // DELETE 请求添加认证参数到 URL
            String separator = url.contains("?") ? "&" : "?";
            url = url + separator + "appKey=" + URLEncoder.encode(appKey, StandardCharsets.UTF_8)
                + "&sign=" + URLEncoder.encode(sign, StandardCharsets.UTF_8)
                + "&timestamp=" + timestamp;
            bodyPublisher = HttpRequest.BodyPublishers.noBody();
        } else {
            bodyPublisher = HttpRequest.BodyPublishers.noBody();
        }

        HttpRequest request;
        switch (method) {
            case "POST" -> request = requestBuilder.POST(bodyPublisher).build();
            case "PUT" -> request = requestBuilder.PUT(bodyPublisher).build();
            case "DELETE" -> request = requestBuilder.DELETE().build();
            default -> throw new IllegalArgumentException("Unsupported method: " + method);
        }

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("V3 API call failed: " + response.statusCode() + " " + response.body());
        }

        JsonNode result = mapper.readTree(response.body());

        // V3 API 使用 success 字段判断成功
        if (!result.path("success").asBoolean(false)) {
            String msg = result.path("message").asText("Unknown error");
            throw new RuntimeException("HAP V3 API error: " + msg);
        }

        return result;
    }

    // ========== Web API (用户 Token 认证) ==========

    /**
     * 创建应用
     */
    public JsonNode createApp(String name, String groupIds, String iconMode, String colorMode) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("projectId", projectId);
        body.put("name", name);
        if (groupIds != null && !groupIds.isEmpty()) {
            body.put("groupIds", groupIds);
        }

        // 图标和颜色由单独接口设置
        return postWeb("/api/AppManagement/CreateApp", body);
    }

    /**
     * 更新应用导航风格
     *
     * Python 对应: update_app_navi_style.py - EditAppInfo
     * 正确端点: POST /api/HomeApp/EditAppInfo（不是 /api/AppManagement/UpdateAppNaviStyle）
     *
     * 注意：此接口需要完整的应用信息，不能只传 pcNaviStyle，
     * 否则会返回 405 或覆盖其他字段为空值。
     */
    public JsonNode updateNavStyle(String appId, int pcNaviStyle,
                                    String projectId, String appName,
                                    String icon, String iconColor) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("appId", appId);
        body.put("projectId", projectId);
        body.put("name", appName != null ? appName : "");
        body.put("description", "");
        body.put("shortDesc", "");
        body.put("iconColor", iconColor != null ? iconColor : "#00bcd4");
        body.put("navColor", iconColor != null ? iconColor : "#00bcd4");
        body.put("icon", icon != null ? icon : "");
        body.put("pcNaviStyle", pcNaviStyle);
        body.put("displayIcon", "011");
        return postWeb("/api/HomeApp/EditAppInfo", body);
    }

    /**
     * 更新应用导航风格（简化版，仅传 appId 和 pcNaviStyle）
     *
     * 注意：此方法会先获取应用元数据再调用完整参数版本。
     * 如果已有应用信息，推荐使用 {@link #updateNavStyle(String, int, String, String, String, String)}
     */
    public JsonNode updateNavStyle(String appId, int pcNaviStyle) throws Exception {
        // 先获取应用元数据
        JsonNode appInfo = getAppInfo(appId);

        String projectName = appInfo.path("name").asText("");
        String projectIcon = appInfo.path("icon").asText("");
        String projectColor = appInfo.path("color").asText("#00bcd4");

        // 从 iconUrl 提取图标名
        String iconUrl = appInfo.path("iconUrl").asText("");
        String iconName = extractIconName(iconUrl);

        return updateNavStyle(appId, pcNaviStyle, projectId,
                              projectName, iconName, projectColor);
    }

    /**
     * 获取应用信息
     *
     * Python 对应: update_app_navi_style.py - fetch_app_meta()
     * API 端点: GET /v3/app（带 HAP-Appkey 和 HAP-Sign 头）
     */
    public JsonNode getAppInfo(String appId) throws Exception {
        // 使用 Open API 获取应用信息
        if (appKey == null || appSecret == null) {
            throw new IllegalStateException("Organization auth not configured. Call setOrgAuth first.");
        }

        long timestamp = System.currentTimeMillis();
        String sign = generateSign(timestamp);

        StringBuilder url = new StringBuilder(BASE_URL + "/v3/app");
        url.append("?appKey=").append(URLEncoder.encode(appKey, StandardCharsets.UTF_8));
        url.append("&sign=").append(URLEncoder.encode(sign, StandardCharsets.UTF_8));
        url.append("&timestamp=").append(timestamp);
        if (projectId != null) {
            url.append("&projectId=").append(URLEncoder.encode(projectId, StandardCharsets.UTF_8));
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(url.toString()))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Get app info failed: " + response.statusCode());
        }

        JsonNode result = mapper.readTree(response.body());

        if (!result.path("success").asBoolean(false)) {
            String msg = result.path("message").asText("Unknown error");
            throw new RuntimeException("HAP API error getting app info: " + msg);
        }

        return result.path("data");
    }

    /**
     * 从 iconUrl 提取图标名称
     *
     * Python 对应: update_app_navi_style.py - icon_from_icon_url()
     * 例如: https://fp1.mingdaoyun.cn/customIcon/sys_1_11_car.svg -> sys_1_11_car
     */
    private String extractIconName(String iconUrl) {
        if (iconUrl == null || iconUrl.trim().isEmpty()) {
            return "";
        }
        String url = iconUrl.trim();
        String name = url.substring(url.lastIndexOf('/') + 1);
        if (name.endsWith(".svg")) {
            name = name.substring(0, name.length() - 4);
        }
        return name;
    }

    /**
     * 创建工作表
     */
    public JsonNode createWorksheet(String appId, String name, String icon, String iconColor) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("appId", appId);
        body.put("name", name);
        if (icon != null) {
            body.put("icon", icon);
        }
        if (iconColor != null) {
            body.put("iconColor", iconColor);
        }
        return postWeb("/api/Worksheet/CreateWorksheet", body);
    }

    /**
     * 创建自定义页面（AddWorkSheet）
     * Python 对应: executors/create_pages_from_plan.py - create_page()
     * API 端点: POST /api/AppManagement/AddWorkSheet
     *
     * @param appId 应用ID
     * @param appSectionId 分组ID
     * @param projectId 项目ID
     * @param name 页面名称
     * @param icon 图标代码
     * @param iconColor 图标颜色
     * @return API 响应（包含 pageId）
     */
    public JsonNode addWorkSheet(String appId, String appSectionId, String projectId,
                                 String name, String icon, String iconColor) throws Exception {
        String iconUrl = "https://fp1.mingdaoyun.cn/customIcon/" + icon + ".svg";
        ObjectNode body = mapper.createObjectNode();
        body.put("appId", appId);
        body.put("appSectionId", appSectionId);
        body.put("name", name);
        body.put("remark", "");
        body.put("iconColor", iconColor);
        body.put("projectId", projectId);
        body.put("icon", icon);
        body.put("iconUrl", iconUrl);
        body.put("type", 1);       // 1 = 自定义页
        body.put("createType", 0);
        return postWeb("/api/AppManagement/AddWorkSheet", body);
    }

    /**
     * 保存工作表字段
     */
    public JsonNode saveWorksheetControls(String worksheetId, JsonNode controls) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("worksheetId", worksheetId);
        body.set("controls", controls);
        return postWeb("/api/Worksheet/SaveWorksheetControls", body);
    }

    /**
     * 保存工作表视图（SaveWorksheetView）
     *
     * Python 对应: /api/Worksheet/SaveWorksheetView
     * API 端点: POST /api/Worksheet/SaveWorksheetView
     *
     * @param worksheetId 工作表ID
     * @param viewConfig 视图配置JSON
     * @return API 响应
     */
    public JsonNode saveWorksheetView(String worksheetId, JsonNode viewConfig) throws Exception {
        return postWeb("/api/Worksheet/SaveWorksheetView", viewConfig);
    }

    /**
     * 获取工作表视图列表
     *
     * @param worksheetId 工作表ID
     * @return 包含视图列表的响应
     */
    public JsonNode getWorksheetViews(String worksheetId) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("worksheetId", worksheetId);
        return postWeb("/api/Worksheet/GetViewsByWorksheetId", body);
    }

    /**
     * 删除工作表视图
     *
     * @param viewId 视图ID
     * @return API 响应
     */
    public JsonNode deleteWorksheetView(String viewId) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("viewId", viewId);
        return postWeb("/api/Worksheet/DeleteView", body);
    }

    /**
     * 获取应用授权信息（Open API）
     * Python 对应: get_app_authorize.py
     *
     * @param appId 应用 ID
     * @param appKey 组织 appKey
     * @param sign 签名
     * @param timestamp 时间戳
     * @param projectId 项目 ID
     * @return API 响应
     */
    public JsonNode getAppAuthorize(String appId, String appKey, String sign, long timestamp, String projectId) throws Exception {
        StringBuilder urlBuilder = new StringBuilder(BASE_URL + "/v1/open/app/getAppAuthorize");
        urlBuilder.append("?appKey=").append(URLEncoder.encode(appKey, StandardCharsets.UTF_8));
        urlBuilder.append("&sign=").append(URLEncoder.encode(sign, StandardCharsets.UTF_8));
        urlBuilder.append("&timestamp=").append(timestamp);
        urlBuilder.append("&projectId=").append(URLEncoder.encode(projectId, StandardCharsets.UTF_8));
        urlBuilder.append("&appId=").append(URLEncoder.encode(appId, StandardCharsets.UTF_8));

        String url = urlBuilder.toString();

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json, text/plain, */*")
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("GetAppAuthorize API call failed: " + response.statusCode() + " " + response.body());
        }

        JsonNode result = mapper.readTree(response.body());

        if (!result.path("success").asBoolean(false)) {
            String msg = result.path("error_msg").asText("Unknown error");
            throw new RuntimeException("HAP GetAppAuthorize API error: " + msg);
        }

        return result;
    }

    /**
     * 获取应用授权信息（Web API - 旧方法）
     */
    public JsonNode getAppAuthorize(String appId) throws Exception {
        return postWeb("/api/AppManagement/GetAppAuthorize",
            Map.of("appId", appId));
    }

    /**
     * 获取工作表详情
     */
    public JsonNode getWorksheetDetail(String worksheetId) throws Exception {
        return postWeb("/api/Worksheet/GetWorksheetInfo",
            Map.of("worksheetId", worksheetId));
    }

    /**
     * 创建工作表分组
     */
    public JsonNode createSection(String appId, String name, int row) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("appId", appId);
        body.put("name", name);
        body.put("row", row);
        return postWeb("/api/AppManagement/CreateSection", body);
    }

    /**
     * 移动工作表到分组
     */
    public JsonNode moveWorksheetToSection(String appId, String sectionId, String worksheetId, int row) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("appId", appId);
        body.put("sectionId", sectionId);
        body.put("worksheetId", worksheetId);
        body.put("row", row);
        return postWeb("/api/AppManagement/MoveWorksheetToSection", body);
    }

    /**
     * 获取统计图表页面
     */
    public JsonNode getReportPage(String pageId) throws Exception {
        return postWeb("/api/report/custom/getPage",
            Map.of("appId", pageId));
    }

    /**
     * 保存统计图表页面
     */
    public JsonNode saveReportPage(String pageId, JsonNode pageConfig) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("appId", pageId);
        body.setAll((ObjectNode) pageConfig);
        return postWeb("/api/report/custom/savePage", body);
    }

    /**
     * 保存图表配置
     */
    public JsonNode saveReportConfig(JsonNode reportConfig) throws Exception {
        return postWeb("/api/report/reportConfig/saveReportConfig", reportConfig);
    }

    /**
     * 写入记录
     */
    public JsonNode addRow(String worksheetId, JsonNode rowData) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("worksheetId", worksheetId);
        body.set("row", rowData);
        return postWeb("/api/Worksheet/AddRow", body);
    }

    /**
     * 批量创建记录（V3 API）
     *
     * Python 对应: mock_data_common.py - create_rows_batch_v3()
     * API 端点: POST /v3/app/worksheets/{worksheet_id}/rows/batch
     *
     * @param worksheetId 工作表ID
     * @param rows 记录列表（每行包含 fields 数组）
     * @param triggerWorkflow 是否触发工作流
     * @return API 响应
     */
    public JsonNode createRowsBatchV3(String worksheetId, JsonNode rows, boolean triggerWorkflow) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.set("rows", rows);
        body.put("triggerWorkflow", triggerWorkflow);
        return postV3("/v3/app/worksheets/" + worksheetId + "/rows/batch", body);
    }

    /**
     * 更新记录字段（V3 API）
     *
     * Python 对应: mock_data_common.py - update_row_relation()
     * API 端点: PATCH /v3/app/worksheets/{worksheet_id}/rows/{row_id}
     *
     * @param worksheetId 工作表ID
     * @param rowId 记录ID
     * @param fields 字段值列表
     * @param triggerWorkflow 是否触发工作流
     * @return API 响应
     */
    public JsonNode updateRowV3(String worksheetId, String rowId, JsonNode fields, boolean triggerWorkflow) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.set("fields", fields);
        body.put("triggerWorkflow", triggerWorkflow);
        return patchV3("/v3/app/worksheets/" + worksheetId + "/rows/" + rowId, body);
    }

    /**
     * 保存工作表视图筛选条件
     *
     * Python 对应: executors/pipeline_tableview_filters_v2.py
     * API 端点: POST /api/Worksheet/filters/save
     *
     * @param worksheetId 工作表ID
     * @param viewId 视图ID
     * @param filterConfig 筛选配置
     * @return API 响应
     */
    public JsonNode saveWorksheetViewFilter(String worksheetId, String viewId, JsonNode filterConfig) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("worksheetId", worksheetId);
        body.put("viewId", viewId);
        body.setAll((ObjectNode) filterConfig);
        return postWeb("/api/Worksheet/filters/save", body);
    }

    private JsonNode patchV3(String path, JsonNode body) throws Exception {
        if (appKey == null || appSecret == null) {
            throw new IllegalStateException("Organization auth not configured. Call setOrgAuth first.");
        }

        String url = BASE_URL + path;

        long timestamp = System.currentTimeMillis();
        String sign = generateSign(timestamp);

        ObjectNode bodyWithAuth = (ObjectNode) body;
        bodyWithAuth.put("appKey", appKey);
        bodyWithAuth.put("sign", sign);
        bodyWithAuth.put("timestamp", timestamp);

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(120))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(bodyWithAuth.toString()))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("V3 API PATCH failed: " + response.statusCode() + " " + response.body());
        }

        JsonNode result = mapper.readTree(response.body());

        if (!result.path("success").asBoolean(false)) {
            String msg = result.path("message").asText("Unknown error");
            throw new RuntimeException("HAP V3 API error: " + msg);
        }

        return result;
    }

    private JsonNode postWeb(String path, Map<String, Object> params) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        params.forEach((k, v) -> {
            if (v instanceof String) {
                body.put(k, (String) v);
            } else if (v instanceof Integer) {
                body.put(k, (Integer) v);
            } else if (v instanceof Boolean) {
                body.put(k, (Boolean) v);
            }
        });
        return postWeb(path, body);
    }

    /**
     * 发送 Web API POST 请求（JsonNode body）
     */
    public JsonNode postWeb(String path, JsonNode body) throws Exception {
        String url = BASE_URL + path;

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(120))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json");

        if (authToken != null) {
            requestBuilder.header("Authorization", authToken);
        }
        if (projectId != null) {
            requestBuilder.header("projectId", projectId);
        }

        HttpRequest request = requestBuilder
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("API call failed: " + response.statusCode() + " " + response.body());
        }

        JsonNode result = mapper.readTree(response.body());

        // 检查明道云错误码
        int errorCode = result.path("error_code").asInt(0);
        if (errorCode != 1) {
            String msg = result.path("error_msg").asText("Unknown error");
            throw new RuntimeException("HAP API error: " + errorCode + " - " + msg);
        }

        return result;
    }

    // ========== Open App API (带组织认证) ==========

    /**
     * 发送 GET 请求到 Open App API（带组织认证参数）
     */
    public JsonNode get(String path) throws Exception {
        if (appKey == null || appSecret == null) {
            throw new IllegalStateException("Organization auth not configured. Call setOrgAuth first.");
        }

        String url = buildOpenAppUrl(path);

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("API call failed: " + response.statusCode() + " " + response.body());
        }

        JsonNode result = mapper.readTree(response.body());

        if (!result.path("success").asBoolean(false)) {
            String msg = result.path("message").asText("Unknown error");
            throw new RuntimeException("HAP API error: " + msg);
        }

        return result;
    }

    /**
     * 发送 POST 请求到 Open App API（带组织认证参数）
     */
    public JsonNode post(String path, JsonNode body) throws Exception {
        if (appKey == null || appSecret == null) {
            throw new IllegalStateException("Organization auth not configured. Call setOrgAuth first.");
        }

        String url = buildOpenAppUrl(path);

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(120))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("API call failed: " + response.statusCode() + " " + response.body());
        }

        JsonNode result = mapper.readTree(response.body());

        if (!result.path("success").asBoolean(false)) {
            String msg = result.path("message").asText("Unknown error");
            throw new RuntimeException("HAP API error: " + msg);
        }

        return result;
    }

    /**
     * 发送 POST 请求到 Open App API（带组织认证参数）- 带查询参数版本
     */
    public JsonNode postWithParams(String path, Map<String, Object> params) throws Exception {
        if (appKey == null || appSecret == null) {
            throw new IllegalStateException("Organization auth not configured. Call setOrgAuth first.");
        }

        String url = buildOpenAppUrl(path);

        // 将 params 转换为查询参数
        if (params != null && !params.isEmpty()) {
            StringBuilder query = new StringBuilder();
            for (Map.Entry<String, Object> entry : params.entrySet()) {
                if (query.length() > 0) {
                    query.append("&");
                }
                query.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                     .append("=")
                     .append(URLEncoder.encode(String.valueOf(entry.getValue()), StandardCharsets.UTF_8));
            }
            url = url + "&" + query;
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(120))
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("API call failed: " + response.statusCode() + " " + response.body());
        }

        JsonNode result = mapper.readTree(response.body());

        if (!result.path("success").asBoolean(false)) {
            String msg = result.path("message").asText("Unknown error");
            throw new RuntimeException("HAP API error: " + msg);
        }

        return result;
    }

    /**
     * 构建带组织认证参数的 URL
     */
    private String buildOpenAppUrl(String path) throws Exception {
        long timestamp = System.currentTimeMillis();
        String sign = generateSign(timestamp);

        StringBuilder url = new StringBuilder(BASE_URL + path);
        url.append("?appKey=").append(URLEncoder.encode(appKey, StandardCharsets.UTF_8));
        url.append("&sign=").append(URLEncoder.encode(sign, StandardCharsets.UTF_8));
        url.append("&timestamp=").append(timestamp);

        return url.toString();
    }
}
