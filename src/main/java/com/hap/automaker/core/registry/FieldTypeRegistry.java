package com.hap.automaker.core.registry;

import java.util.*;

/**
 * 字段类型注册中心 - 38种字段类型
 */
public class FieldTypeRegistry implements TypeRegistry<FieldTypeConfig> {

    private static final Map<Integer, FieldTypeConfig> BY_ID = new HashMap<>();
    private static final Map<String, FieldTypeConfig> BY_NAME = new HashMap<>();
    private static final Map<String, List<FieldTypeConfig>> BY_CATEGORY = new HashMap<>();
    private static final List<FieldTypeConfig> ALL = new ArrayList<>();

    static {
        // ========== 批次1: 基础文本 (4种) ==========
        register("Text", 2, "文本", "basic", true, false, null,
            "单行文本。第一个自动设为标题(attribute=1)。enumDefault: 0=自动, 1=多行, 2=单行, 3=Markdown。",
            Map.of("sorttype", "zh", "analysislink", "1"),
            Map.of("sorttype", "排序方式: zh/en",
                   "analysislink", "解析为超链接: 0=否, 1=是",
                   "datamask", "数据掩码: 0=禁用, 1=启用",
                   "filterregex", "正则过滤模式（字符串）",
                   "encryId", "加密标识（字符串，Markdown 模式下清空）"),
            null);

        register("RichText", 41, "富文本", "basic", false, false, null,
            "富文本编辑器，支持格式化文字、图片、表格等内容。",
            Map.of("sorttype", "zh"),
            null, null);

        register("AutoNumber", 33, "自动编号", "basic", false, true,
            "自动编号由系统自动管理，AI 规划禁止生成此字段类型",
            "自动递增编号，创建记录时自动分配。strDefault='increase' 必填。",
            Map.of("sorttype", "zh"),
            Map.of("sorttype", "排序方式",
                   "increase", "JSON 编号规则数组 [{type:1-4, repeatType:0-4, start, length, format}]",
                   "usetimezone", "使用时区"),
            Map.of("strDefault", "increase"));

        register("TextCombine", 32, "文本组合", "basic", false, true,
            "文本组合依赖引用其他字段，AI 无法正确配置公式",
            "文本公式：多字段文本拼接，支持引用其他字段。",
            Map.of("sorttype", "zh", "analysislink", "1"),
            null, null);

        // ========== 批次2: 数值类 (5种) ==========
        register("Number", 6, "数字", "number", false, false, null,
            "数值字段，支持小数精度设置。dot=小数位数(0-6)，precision=显示精度。",
            Map.of("sorttype", "zh"),
            Map.of("sorttype", "排序方式",
                   "dot", "小数位数: 0-6",
                   "precision", "显示精度",
                   "unit", "单位",
                   "unitpos", "单位位置: 0=前缀, 1=后缀",
                   "thousandth", "千分位显示: 0=否, 1=是",
                   "datamask", "数据掩码: 0=禁用, 1=启用"),
            null);

        register("Money", 8, "金额", "number", false, false, null,
            "金额字段，默认2位小数，支持货币符号。",
            Map.of("sorttype", "zh"),
            Map.of("sorttype", "排序方式",
                   "dot", "小数位数: 默认2",
                   "precision", "显示精度",
                   "unit", "货币单位: 默认¥",
                   "unitpos", "单位位置",
                   "thousandth", "千分位显示: 默认1(是)"),
            null);

        register("MoneyCapital", 25, "金额大写", "number", false, true,
            "金额大写需要基于金额字段计算，AI 无需直接生成",
            "金额大写：自动将金额转为中文大写，用于财务票据。",
            Map.of("sorttype", "zh"),
            null, null);

        register("Formula", 31, "公式", "number", false, true,
            "公式字段依赖复杂表达式，AI 无法正确配置计算逻辑",
            "计算公式：基于其他字段的数值计算，支持加减乘除、函数等。",
            Map.of("sorttype", "zh"),
            Map.of("sorttype", "排序方式",
                   "dot", "结果小数位数",
                   "precision", "显示精度"),
            null);

        register("FormulaDate", 38, "公式日期", "date", false, true,
            "公式日期需要复杂日期计算，AI 难以正确配置",
            "公式日期：基于日期字段计算，如到期日、工作日等。",
            Map.of("sorttype", "zh"),
            null, null);

        // ========== 批次3: 选择类 (6种) ==========
        register("SingleSelect", 9, "单选", "select", true, false, null,
            "单选字段，从预定义选项中选择一个。",
            Map.of("sorttype", "zh", "analysislink", "1"),
            Map.of("sorttype", "排序方式",
                   "analysislink", "解析为超链接: 0=否, 1=是",
                   "showtype", "显示方式: 0=下拉, 1=平铺",
                   "checktype", "选择方式: 0=单选, 1=多选",
                   "filterregex", "过滤正则",
                   "datamask", "数据掩码"),
            null);

        register("MultipleSelect", 10, "多选", "select", false, false, null,
            "多选字段，从预定义选项中选择多个。",
            Map.of("sorttype", "zh", "analysislink", "1"),
            Map.of("sorttype", "排序方式",
                   "analysislink", "解析为超链接",
                   "checktype", "选择方式: 1=多选",
                   "maxitem", "最大选择数",
                   "datamask", "数据掩码"),
            null);

        register("Dropdown", 11, "下拉框", "select", false, false, null,
            "下拉选择，从选项列表中选择一个值。",
            Map.of("sorttype", "zh"),
            Map.of("sorttype", "排序方式",
                   "showtype", "显示方式",
                   "checktype", "选择方式"),
            null);

        register("FlatSelect", 36, "平铺选择", "select", false, false, null,
            "平铺显示的单选/多选，选项以按钮形式展示。",
            Map.of("sorttype", "zh", "showtype", "1"),
            Map.of("sorttype", "排序方式",
                   "showtype", "必须设为1表示平铺显示"),
            null);

        register("CascadeSelect", 28, "级联选择", "select", false, true,
            "级联选择需要配置多级选项层次，AI 难以正确规划",
            "级联选择：多级联动选择，如省-市-区。",
            Map.of("sorttype", "zh"),
            null, null);

        register("AssociationCascade", 35, "关联级联", "relation", false, true,
            "关联级联依赖其他表数据结构，AI 无法自动配置",
            "关联级联：基于关联数据的级联选择。",
            Map.of("sorttype", "zh"),
            null, null);

        // ========== 批次4: 日期时间类 (6种) ==========
        register("Date", 15, "日期", "date", false, false, null,
            "日期字段，选择年月日。",
            Map.of("sorttype", "time"),
            Map.of("sorttype", "排序方式: time=按时间",
                   "format", "显示格式",
                   "defaulttype", "默认值类型: 0=空, 1=今天, 2=自定义",
                   "defaultdate", "默认日期字符串"),
            null);

        register("DateTime", 16, "日期时间", "date", false, false, null,
            "日期时间字段，选择年月日时分。",
            Map.of("sorttype", "time"),
            Map.of("sorttype", "排序方式",
                   "format", "显示格式",
                   "defaulttype", "默认值类型",
                   "defaultdate", "默认日期时间"),
            null);

        register("Time", 46, "时间", "date", false, false, null,
            "时间字段，选择时分。",
            Map.of("sorttype", "time"),
            Map.of("sorttype", "排序方式",
                   "format", "时间格式: HH:mm"),
            null);

        register("Week", 21, "星期", "date", false, false, null,
            "星期选择字段，选择一周中的某天。",
            Map.of("sorttype", "time"),
            Map.of("sorttype", "排序方式",
                   "showtype", "显示方式",
                   "format", "格式"),
            null);

        register("CreatedAt", 1001, "创建时间", "date", false, true,
            "创建时间由系统自动记录，AI 无需生成",
            "创建时间：记录创建时自动设置，不可修改。",
            Map.of("sorttype", "time"),
            null, null);

        register("ModifiedAt", 1002, "修改时间", "date", false, true,
            "修改时间由系统自动记录，AI 无需生成",
            "修改时间：记录更新时自动更新，不可修改。",
            Map.of("sorttype", "time"),
            null, null);

        // ========== 批次5: 人员组织类 (5种) ==========
        register("Collaborator", 19, "成员", "people", false, false, null,
            "成员字段，选择组织中的用户。",
            Map.of("sorttype", "zh"),
            Map.of("sorttype", "排序方式",
                   "choicecontroltype", "选择范围控制: 0=所有, 1=范围",
                   "choicecontrolrange", "选择范围ID列表",
                   "defaulttype", "默认值类型",
                   "defaultuserids", "默认用户ID列表"),
            null);

        register("Owner", 1003, "拥有者", "people", false, true,
            "拥有者由系统自动设置为创建人，AI 无需生成",
            "拥有者：记录创建者，不可修改。",
            Map.of("sorttype", "zh"),
            null, null);

        register("Department", 27, "部门", "people", false, false, null,
            "部门字段，选择组织中的部门。",
            Map.of("sorttype", "zh"),
            Map.of("sorttype", "排序方式",
                   "choicecontroltype", "选择范围控制",
                   "choicecontrolrange", "选择范围ID列表"),
            null);

        register("DepartmentMerge", 1004, "部门合并", "people", false, true,
            "部门合并需要特殊配置，AI 无法正确生成",
            "部门合并：多部门字段合并显示。",
            Map.of("sorttype", "zh"),
            null, null);

        register("Role", 1005, "角色", "people", false, true,
            "角色字段需要预定义角色配置，AI 无法正确生成",
            "角色：选择预定义的角色。",
            Map.of("sorttype", "zh"),
            null, null);

        // ========== 批次6: 联系方式+文件+地理位置 (8种) ==========
        // --- 联系方式 (4种) ---
        register("Phone", 5, "电话", "contact", false, false, null,
            "电话号码字段，支持座机和手机号格式。",
            Map.of("sorttype", "zh"),
            Map.of("sorttype", "排序方式",
                   "format", "格式",
                   "linkify", "可拨打: 0=否, 1=是"),
            null);

        register("Email", 7, "邮箱", "contact", false, false, null,
            "邮箱字段，验证邮箱格式。",
            Map.of("sorttype", "zh", "analysislink", "1"),
            Map.of("sorttype", "排序方式",
                   "analysislink", "解析为超链接",
                   "linkify", "可点击发送"),
            null);

        register("Link", 17, "链接", "contact", false, false, null,
            "超链接字段，可点击跳转。",
            Map.of("sorttype", "zh", "analysislink", "1"),
            Map.of("sorttype", "排序方式",
                   "analysislink", "解析为超链接",
                   "linkify", "可点击跳转"),
            null);

        register("SubForm", 34, "子表", "contact", false, true,
            "子表需要预定义子表结构，AI 难以正确配置",
            "子表：在当前记录中嵌入另一个表的数据。",
            Map.of("sorttype", "zh"),
            null, null);

        // --- 文件 (2种) ---
        register("Attachment", 14, "附件", "file", false, false, null,
            "附件字段，上传文件。",
            Map.of("sorttype", "zh"),
            Map.of("sorttype", "排序方式",
                   "maxcount", "最大上传数量",
                   "filetypes", "允许文件类型",
                   "maxsize", "最大文件大小(MB)"),
            null);

        register("RelatedInfo", 1006, "关联资料", "file", false, true,
            "关联资料依赖其他数据源，AI 无法正确配置",
            "关联资料：关联其他表的数据。",
            Map.of("sorttype", "zh"),
            null, null);

        // --- 地理位置 (2种) ---
        register("Location", 40, "定位", "location", false, false, null,
            "定位字段，获取GPS坐标或手动选择位置。",
            Map.of("sorttype", "zh"),
            Map.of("sorttype", "排序方式",
                   "showformat", "显示格式: address/coordinate/both",
                   "defaulttype", "默认值类型"),
            null);

        register("Area", 29, "地区", "location", false, false, null,
            "地区字段，选择省市区/县。",
            Map.of("sorttype", "zh"),
            Map.of("sorttype", "排序方式",
                   "level", "级别: 1=省, 2=省市, 3=省市区"),
            null);

        // ========== 批次7: 高级/特殊+布局类 (4种) ==========
        register("Rating", 30, "评分", "advanced", false, false, null,
            "评分字段，通常以星星形式展示。",
            Map.of("sorttype", "zh"),
            Map.of("sorttype", "排序方式",
                   "max", "最大值: 通常为5",
                   "allowhalf", "允许半星: 0=否, 1=是",
                   "showtext", "显示文字: 0=否, 1=是"),
            null);

        register("Checkbox", 24, "复选框", "advanced", false, false, null,
            "复选框字段，布尔值开关。",
            Map.of("sorttype", "zh"),
            Map.of("sorttype", "排序方式",
                   "defaultvalue", "默认值: true/false"),
            null);

        register("Relation", 20, "关联记录", "relation", false, true,
            "关联记录需要预先创建工作表，AI 规划时需要特殊处理",
            "关联记录：关联其他工作表的数据，支持1-N和N-N关系。",
            Map.of("sorttype", "zh"),
            Map.of("sorttype", "排序方式",
                   "bidirectional", "双向关联: true/false",
                   "subType", "关联类型: 1=单条, 2=多条"),
            null);

        register("Divider", 22, "分割线", "layout", false, true,
            "分割线用于表单布局，不存储数据",
            "分割线：表单内的视觉分隔线。",
            Map.of(),
            null, null);
    }

    private static void register(String key, int controlType, String name, String category,
                                  boolean canBeTitle, boolean aiDisabled, String aiDisabledReason,
                                  String doc, Map<String, Object> advancedSetting,
                                  Map<String, String> advancedSettingAllKeys, Map<String, Object> extra) {
        FieldTypeConfig cfg = new FieldTypeConfig();
        cfg.setControlType(controlType);
        cfg.setName(name);
        cfg.setCategory(category);
        cfg.setCanBeTitle(canBeTitle);
        cfg.setAiDisabled(aiDisabled);
        cfg.setAiDisabledReason(aiDisabledReason);
        cfg.setDoc(doc);
        if (advancedSetting != null) cfg.setAdvancedSetting(new HashMap<>(advancedSetting));
        if (advancedSettingAllKeys != null) cfg.setAdvancedSettingAllKeys(new HashMap<>(advancedSettingAllKeys));
        if (extra != null) cfg.setExtra(new HashMap<>(extra));

        BY_ID.put(controlType, cfg);
        BY_NAME.put(key, cfg);
        ALL.add(cfg);
        BY_CATEGORY.computeIfAbsent(category, k -> new ArrayList<>()).add(cfg);
    }

    @Override
    public FieldTypeConfig getById(int id) {
        return BY_ID.get(id);
    }

    @Override
    public FieldTypeConfig getByName(String name) {
        return BY_NAME.get(name);
    }

    @Override
    public List<FieldTypeConfig> getByCategory(String category) {
        return BY_CATEGORY.getOrDefault(category, Collections.emptyList());
    }

    @Override
    public List<FieldTypeConfig> getAll() {
        return Collections.unmodifiableList(ALL);
    }

    @Override
    public boolean validateConfig(String typeId, Map<String, Object> config) {
        if (typeId == null || typeId.isEmpty()) {
            return false;
        }

        FieldTypeConfig typeConfig = BY_NAME.get(typeId);
        if (typeConfig == null) {
            return false; // 未知字段类型
        }

        // 检查 AI 禁用字段
        if (typeConfig.isAiDisabled()) {
            return false;
        }

        // 成员字段不能设为必填
        if (typeConfig.getControlType() == 26) { // 26 = 成员
            Object required = config.get("required");
            if (Boolean.TRUE.equals(required) || "1".equals(String.valueOf(required))) {
                return false; // 成员字段禁止设为必填
            }
        }

        // 选择类字段必须提供 options
        Set<Integer> optionRequiredTypes = Set.of(9, 10, 11); // 单选、多选、下拉
        if (optionRequiredTypes.contains(typeConfig.getControlType())) {
            Object options = config.get("options");
            if (options == null || (options instanceof List && ((List<?>) options).isEmpty())) {
                return false; // 缺少必填选项
            }
        }

        // 关联字段需要 dataSource
        if (typeConfig.getControlType() == 29) { // 29 = 关联记录
            Object dataSource = config.get("dataSource");
            if (dataSource == null || String.valueOf(dataSource).isEmpty()) {
                return false; // 关联字段缺少目标工作表
            }
        }

        // 数值字段的 dot 范围检查
        if (typeConfig.getControlType() == 6 || typeConfig.getControlType() == 8) { // 数值、金额
            Object dot = config.get("dot");
            if (dot != null) {
                try {
                    int dotValue = Integer.parseInt(String.valueOf(dot));
                    if (dotValue < 0 || dotValue > 6) {
                        return false; // 小数位数超出范围
                    }
                } catch (NumberFormatException e) {
                    return false; // 无效的 dot 值
                }
            }
        }

        return true;
    }

    @Override
    public String generatePrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("# 可用字段类型（共").append(ALL.size()).append("种）\\n\\n");

        String[] categories = {"basic", "number", "select", "date", "contact", "people", "relation", "file", "location", "advanced", "layout"};
        for (String cat : categories) {
            List<FieldTypeConfig> types = BY_CATEGORY.get(cat);
            if (types == null || types.isEmpty()) continue;
            sb.append("## ").append(getCategoryName(cat)).append("\\n");
            for (FieldTypeConfig t : types) {
                if (t.isAiDisabled()) continue;
                sb.append("- ").append(t.getName())
                  .append(" (type=").append(t.getControlType()).append(")");
                if (t.getDoc() != null && !t.getDoc().isEmpty()) {
                    sb.append(" - ").append(t.getDoc().split("。")[0]);
                }
                sb.append("\\n");
            }
            sb.append("\\n");
        }
        return sb.toString();
    }

    private String getCategoryName(String category) {
        return switch (category) {
            case "basic" -> "基础文本";
            case "number" -> "数值";
            case "select" -> "选择";
            case "date" -> "日期时间";
            case "contact" -> "联系方式";
            case "people" -> "人员组织";
            case "relation" -> "关联关系";
            case "file" -> "文件";
            case "location" -> "地理位置";
            case "advanced" -> "高级/特殊";
            case "layout" -> "布局（不存储数据）";
            default -> category;
        };
    }

    @Override
    public int size() {
        return ALL.size();
    }

    // Python 兼容性查询
    public int getControlTypeByName(String name) {
        FieldTypeConfig cfg = BY_NAME.get(name);
        return cfg != null ? cfg.getControlType() : -1;
    }

    public Set<String> getPlannableTypes() {
        Set<String> result = new HashSet<>();
        for (Map.Entry<String, FieldTypeConfig> e : BY_NAME.entrySet()) {
            FieldTypeConfig cfg = e.getValue();
            // 排除layout类别、advanced类别以及AI禁用的字段
            if (!cfg.getCategory().equals("layout") &&
                !cfg.getCategory().equals("advanced") &&
                !cfg.isAiDisabled()) {
                result.add(e.getKey());
            }
        }
        return result;
    }
}
