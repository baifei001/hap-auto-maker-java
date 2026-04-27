package com.hap.automaker.core.planner;

import com.hap.automaker.util.LoggerFactory;

import org.slf4j.Logger;

import java.util.*;

/**
 * 布局规划器
 *
 * Python 对应: planners/view_configurator.py
 *
 * 职责:
 * - 规划工作表字段的显示布局
 * - 配置表单字段排列顺序
 * - 设置字段分组（分割线）
 */
public class LayoutPlanner implements Planner<LayoutPlanner.Input, LayoutPlanner.Output> {

    private static final Logger logger = LoggerFactory.getLogger(LayoutPlanner.class);

    @Override
    public String getName() {
        return "LayoutPlanner";
    }

    @Override
    public Output plan(Input input) throws PlanningException {
        try {
            List<WorksheetLayout> layouts = new ArrayList<>();

            for (WorksheetInfo ws : input.getWorksheets()) {
                FieldLayout layout = planFieldLayout(ws.getFields(), ws.getPurpose());
                layouts.add(new WorksheetLayout(
                    ws.getWorksheetId(),
                    ws.getWorksheetName(),
                    layout
                ));
            }

            logger.info("✓ 布局规划完成: {} 个工作表", layouts.size());

            return new Output(layouts);

        } catch (Exception e) {
            throw new PlanningException(getName(), "Failed to plan layouts", e);
        }
    }

    /**
     * 规划字段布局
     */
    private FieldLayout planFieldLayout(List<FieldInfo> fields, String purpose) {
        List<FieldGroup> groups = new ArrayList<>();

        // 默认分组：基础信息
        List<FieldInfo> basicFields = new ArrayList<>();
        List<FieldInfo> detailFields = new ArrayList<>();
        List<FieldInfo> otherFields = new ArrayList<>();

        // 分类字段
        for (FieldInfo field : fields) {
            String type = field.getType();
            String name = field.getControlName();

            // 标题/名称字段放第一组
            if ("Text".equals(type) && (name.contains("名称") || name.contains("标题"))) {
                basicFields.add(field);
            }
            // 关键业务字段放第一组
            else if ("SingleSelect".equals(type) || "Date".equals(type) || "DateTime".equals(type)) {
                basicFields.add(field);
            }
            // 人员字段
            else if ("Collaborator".equals(type) || "Department".equals(type)) {
                detailFields.add(field);
            }
            // 金额数值字段
            else if ("Money".equals(type) || "Number".equals(type)) {
                detailFields.add(field);
            }
            // 其他字段
            else {
                otherFields.add(field);
            }
        }

        // 创建分组
        if (!basicFields.isEmpty()) {
            groups.add(new FieldGroup("基础信息", basicFields));
        }
        if (!detailFields.isEmpty()) {
            groups.add(new FieldGroup("详细信息", detailFields));
        }
        if (!otherFields.isEmpty()) {
            groups.add(new FieldGroup("其他", otherFields));
        }

        // 如果只有一个分组且字段少，不显示分组标题
        if (groups.size() == 1 && fields.size() <= 5) {
            groups.get(0).setTitle(null);
        }

        return new FieldLayout(groups);
    }

    // ========== 输入类 ==========
    public static class Input {
        private final List<WorksheetInfo> worksheets;

        public Input(List<WorksheetInfo> worksheets) {
            this.worksheets = worksheets;
        }

        public List<WorksheetInfo> getWorksheets() { return worksheets; }
    }

    public static class WorksheetInfo {
        private final String worksheetId;
        private final String worksheetName;
        private final String purpose;
        private final List<FieldInfo> fields;

        public WorksheetInfo(String worksheetId, String worksheetName, String purpose, List<FieldInfo> fields) {
            this.worksheetId = worksheetId;
            this.worksheetName = worksheetName;
            this.purpose = purpose;
            this.fields = fields;
        }

        public String getWorksheetId() { return worksheetId; }
        public String getWorksheetName() { return worksheetName; }
        public String getPurpose() { return purpose; }
        public List<FieldInfo> getFields() { return fields; }
    }

    public static class FieldInfo {
        private final String controlId;
        private final String controlName;
        private final String type;
        private final boolean isTitle;

        public FieldInfo(String controlId, String controlName, String type, boolean isTitle) {
            this.controlId = controlId;
            this.controlName = controlName;
            this.type = type;
            this.isTitle = isTitle;
        }

        public String getControlId() { return controlId; }
        public String getControlName() { return controlName; }
        public String getType() { return type; }
        public boolean isTitle() { return isTitle; }
    }

    // ========== 输出类 ==========
    public static class Output {
        private final List<WorksheetLayout> layouts;

        public Output(List<WorksheetLayout> layouts) {
            this.layouts = layouts;
        }

        public List<WorksheetLayout> getLayouts() { return layouts; }
    }

    public static class WorksheetLayout {
        private final String worksheetId;
        private final String worksheetName;
        private final FieldLayout layout;

        public WorksheetLayout(String worksheetId, String worksheetName, FieldLayout layout) {
            this.worksheetId = worksheetId;
            this.worksheetName = worksheetName;
            this.layout = layout;
        }

        public String getWorksheetId() { return worksheetId; }
        public String getWorksheetName() { return worksheetName; }
        public FieldLayout getLayout() { return layout; }
    }

    public static class FieldLayout {
        private final List<FieldGroup> groups;

        public FieldLayout(List<FieldGroup> groups) {
            this.groups = groups;
        }

        public List<FieldGroup> getGroups() { return groups; }
    }

    public static class FieldGroup {
        private String title;
        private final List<FieldInfo> fields;

        public FieldGroup(String title, List<FieldInfo> fields) {
            this.title = title;
            this.fields = fields;
        }

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public List<FieldInfo> getFields() { return fields; }
    }
}
