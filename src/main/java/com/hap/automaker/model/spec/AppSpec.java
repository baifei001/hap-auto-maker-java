package com.hap.automaker.model.spec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 应用配置
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AppSpec {
    @JsonProperty("target_mode")
    private String targetMode = "create_new";

    @JsonProperty("app_id")
    private String appId;

    @JsonProperty("name")
    private String name;

    @JsonProperty("group_ids")
    private String groupIds;

    @JsonProperty("icon_mode")
    private String iconMode = "ai_match";

    @JsonProperty("color_mode")
    private String colorMode = "random";

    @JsonProperty("navi_style")
    private NaviStyle naviStyle = new NaviStyle();

    public String getTargetMode() {
        return targetMode;
    }

    public void setTargetMode(String targetMode) {
        this.targetMode = targetMode;
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getGroupIds() {
        return groupIds;
    }

    public void setGroupIds(String groupIds) {
        this.groupIds = groupIds;
    }

    public String getIconMode() {
        return iconMode;
    }

    public void setIconMode(String iconMode) {
        this.iconMode = iconMode;
    }

    public String getColorMode() {
        return colorMode;
    }

    public void setColorMode(String colorMode) {
        this.colorMode = colorMode;
    }

    public NaviStyle getNaviStyle() {
        return naviStyle;
    }

    public void setNaviStyle(NaviStyle naviStyle) {
        this.naviStyle = naviStyle;
    }

    @Override
    public String toString() {
        return "AppSpec{" +
            "targetMode='" + targetMode + '\'' +
            ", name='" + name + '\'' +
            ", groupIds='" + groupIds + '\'' +
            ", iconMode='" + iconMode + '\'' +
            ", colorMode='" + colorMode + '\'' +
            ", naviStyle=" + naviStyle.isEnabled() +
            '}';
    }
}
