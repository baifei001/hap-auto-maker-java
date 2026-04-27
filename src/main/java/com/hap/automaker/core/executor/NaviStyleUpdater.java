package com.hap.automaker.core.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.hap.automaker.api.HapApiClient;
import com.hap.automaker.util.LoggerFactory;

import org.slf4j.Logger;

import java.util.*;

/**
 * 导航样式更新执行器
 *
 * Python 对应: update_app_navi_style.py
 *
 * 职责:
 * - 根据应用特性（分组数量）决定导航样式
 * - 更新应用导航风格
 *
 * 导航样式策略:
 * - pcNaviStyle=1 (左侧导航): 分组数 <= 3 时使用，适合小型应用
 * - pcNaviStyle=0 (经典顶部导航): 分组数 > 3 时使用，适合大型应用
 *
 * API 调用:
 * - POST /api/HomeApp/EditAppInfo（需要完整应用参数）
 * - 注意：旧端点 /api/AppManagement/UpdateAppNaviStyle 返回 405，
 *   正确端点是 /api/HomeApp/EditAppInfo，参见 Python update_app_navi_style.py
 */
public class NaviStyleUpdater implements Executor<NaviStyleUpdater.Input, NaviStyleUpdater.Output> {

    private static final Logger logger = LoggerFactory.getLogger(NaviStyleUpdater.class);

    // 分组数阈值：超过此数量切换为经典导航
    private static final int NAVI_STYLE_THRESHOLD = 3;

    private final HapApiClient apiClient;

    public NaviStyleUpdater(HapApiClient apiClient) {
        this.apiClient = apiClient;
    }

    @Override
    public String getName() {
        return "NaviStyleUpdater";
    }

    @Override
    public Output execute(Input input) throws ExecutorException {
        String appId = input.getAppId();
        int sectionCount = input.getSectionCount();

        try {
            // 根据分组数决定导航样式
            int pcNaviStyle;
            String styleDescription;

            if (sectionCount > NAVI_STYLE_THRESHOLD) {
                pcNaviStyle = 0; // 经典顶部导航
                styleDescription = "经典顶部导航（分组数=" + sectionCount + " > " + NAVI_STYLE_THRESHOLD + "）";
            } else {
                pcNaviStyle = 1; // 左侧导航
                styleDescription = "左侧导航（分组数=" + sectionCount + " <= " + NAVI_STYLE_THRESHOLD + "）";
            }

            // 如果指定了强制样式，使用强制值
            if (input.getForceNaviStyle() != null) {
                pcNaviStyle = input.getForceNaviStyle();
                styleDescription = input.getForceNaviStyle() == 0 ? "经典顶部导航（强制）" : "左侧导航（强制）";
            }

            if (input.isDryRun()) {
                logger.info("[DryRun] Would update navi style for app {} to {} (pcNaviStyle={})",
                    appId, styleDescription, pcNaviStyle);
                return new Output(true, pcNaviStyle, styleDescription, null);
            }

            // 调用 API 更新导航样式
            // 使用 EditAppInfo 端点（需要完整应用参数），参见 Python update_app_navi_style.py
            JsonNode response = apiClient.updateNavStyle(appId, pcNaviStyle);

            // 检查响应
            boolean success = checkSuccess(response);

            if (!success) {
                String errorMsg = "API returned unsuccessful response: " + response.toString();
                throw new ExecutorException(getName(), errorMsg);
            }

            logger.info("✓ 导航样式更新成功: {}", styleDescription);

            return new Output(true, pcNaviStyle, styleDescription, null);

        } catch (ExecutorException e) {
            throw e;
        } catch (Exception e) {
            String errorMsg = "更新导航样式失败: " + e.getMessage();
            if (input.isFailFast()) {
                throw new ExecutorException(getName(), errorMsg, e);
            }
            logger.error("✗ {}", errorMsg);
            return new Output(false, 0, "", errorMsg);
        }
    }

    private boolean checkSuccess(JsonNode response) {
        if (response == null) return false;

        // 检查各种可能的成功标志
        int errorCode = response.path("error_code").asInt(0);
        if (errorCode == 1) return true;

        int state = response.path("state").asInt(0);
        if (state == 1) return true;

        int resultCode = response.path("resultCode").asInt(0);
        if (resultCode == 1) return true;

        int status = response.path("status").asInt(0);
        if (status == 1) return true;

        boolean success = response.path("success").asBoolean(false);
        return success;
    }

    // ========== 输入类 ==========
    public static class Input {
        private final String appId;
        private final int sectionCount;
        private final Integer forceNaviStyle; // 强制指定的导航样式（null表示自动决定）
        private final boolean dryRun;
        private final boolean failFast;

        public Input(String appId, int sectionCount, Integer forceNaviStyle,
                     boolean dryRun, boolean failFast) {
            this.appId = appId;
            this.sectionCount = sectionCount;
            this.forceNaviStyle = forceNaviStyle;
            this.dryRun = dryRun;
            this.failFast = failFast;
        }

        public String getAppId() { return appId; }
        public int getSectionCount() { return sectionCount; }
        public Integer getForceNaviStyle() { return forceNaviStyle; }
        public boolean isDryRun() { return dryRun; }
        public boolean isFailFast() { return failFast; }
    }

    // ========== 输出类 ==========
    public static class Output {
        private final boolean success;
        private final int pcNaviStyle;
        private final String styleDescription;
        private final String errorMessage;

        public Output(boolean success, int pcNaviStyle, String styleDescription, String errorMessage) {
            this.success = success;
            this.pcNaviStyle = pcNaviStyle;
            this.styleDescription = styleDescription;
            this.errorMessage = errorMessage;
        }

        public boolean isSuccess() { return success; }
        public int getPcNaviStyle() { return pcNaviStyle; }
        public String getStyleDescription() { return styleDescription; }
        public String getErrorMessage() { return errorMessage; }
    }
}
