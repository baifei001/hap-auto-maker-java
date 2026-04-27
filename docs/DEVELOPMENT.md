# 开发指南

本指南介绍如何扩展和定制 HAP Auto Maker Java。

## 目录

1. [添加新的工作表字段类型](#添加新的工作表字段类型)
2. [添加新的图表类型](#添加新的图表类型)
3. [添加新的 Wave 步骤](#添加新的-wave-步骤)
4. [自定义 AI 提示词](#自定义-ai-提示词)
5. [添加新的 CLI 命令](#添加新的-cli-命令)

## 添加新的工作表字段类型

### 1. 更新 `FieldTypeRegistry`

```java
// src/main/java/com/hap/automaker/core/registry/FieldTypeRegistry.java

public class FieldTypeRegistry {
    private static final Map<String, FieldTypeConfig> BY_NAME = new HashMap<>();

    static {
        // ... 现有类型

        // 添加新字段类型
        register(new FieldTypeConfig(
            "MyNewType",      // 类型名称
            "我的新类型",      // 显示名称
            "自定义描述",      // 描述
            Map.of(           // 属性配置
                "prop1", "value1",
                "prop2", "value2"
            ),
            false             // 是否禁用 AI
        ));
    }
}
```

### 2. 更新 AI 提示词

在 `WorksheetPlanner` 中添加新类型的说明：

```java
private String buildFieldTypesDescription() {
    return String.join("\n",
        // ... 现有类型
        "MyNewType - 我的新类型: 用于...",
        "  属性:",
        "  - prop1: 属性1说明",
        "  - prop2: 属性2说明"
    );
}
```

### 3. 更新字段创建逻辑

在 `WorksheetCreator` 中处理新类型的字段创建：

```java
private JsonNode buildFieldConfig(FieldDefinition field) {
    ObjectNode config = mapper.createObjectNode();

    switch (field.getType()) {
        // ... 现有类型

        case "MyNewType":
            config.put("prop1", field.getProperties().get("prop1"));
            config.put("prop2", field.getProperties().get("prop2"));
            break;
    }

    return config;
}
```

### 4. 添加测试

```java
@Test
void testMyNewTypeField() {
    WorksheetCreator creator = new WorksheetCreator(apiClient);

    FieldDefinition field = new FieldDefinition(
        "测试字段", "MyNewType", true,
        Map.of("prop1", "value1", "prop2", "value2"),
        "", List.of()
    );

    JsonNode result = creator.createField(worksheetId, field);
    assertTrue(result.has("data"));
}
```

## 添加新的图表类型

### 1. 更新 `ChartTypeRegistry`

```java
// src/main/java/com/hap/automaker/core/registry/ChartTypeRegistry.java

public class ChartTypeRegistry {
    private static final Map<String, ChartTypeConfig> BY_NAME = new HashMap<>();

    static {
        // ... 现有图表类型

        // 添加新图表类型
        register(new ChartTypeConfig(
            "myNewChart",           // 类型ID
            "我的新图表",            // 显示名称
            "Chart",                // 分类
            "描述新图表的用途",       // 描述
            true,                   // 需要 X 轴
            true,                   // 需要 Y 轴
            List.of("line", "bar"), // 兼容的视图类型
            true,                   // 是否启用
            false                   // 是否禁用 AI
        ));
    }
}
```

### 2. 实现图表配置验证

```java
@Override
public boolean validateConfig(String typeId, Map<String, Object> config) {
    if ("myNewChart".equals(typeId)) {
        // 验证特定配置
        Object customProp = config.get("customProp");
        if (customProp == null) {
            return false;
        }
    }
    return true;
}
```

### 3. 更新图表创建逻辑

在 `ChartPipelineService` 中处理新图表类型：

```java
private JsonNode buildChartConfig(String chartType, Map<String, Object> params) {
    ObjectNode config = mapper.createObjectNode();

    switch (chartType) {
        // ... 现有类型

        case "myNewChart":
            config.put("customProp", params.get("customProp").toString());
            config.put("displayMode", "default");
            break;
    }

    return config;
}
```

## 添加新的 Wave 步骤

### 1. 定义步骤

在 `WaveBuilder` 中定义新步骤：

```java
private StepDefinition createMyNewStep() {
    return new StepDefinition(
        100,                    // 步骤ID（唯一）
        "my_new_step",          // 步骤Key
        "我的新步骤",            // 显示名称
        () -> {
            // 步骤执行逻辑
            logger.info("执行我的新步骤");

            // 1. 获取上下文数据
            WaveContext ctx = getContext();
            String appId = ctx.getAppId();

            // 2. 执行业务逻辑
            MyService service = new MyService();
            Result result = service.doSomething(appId);

            // 3. 返回执行结果
            if (result.isSuccess()) {
                return StepExecutable.ExecutableResult.success(
                    result.getOutput(),
                    result.getOutputPath()
                );
            } else {
                return StepExecutable.ExecutableResult.failure(
                    result.getErrorMessage()
                );
            }
        },
        false                    // 是否使用 Gemini
    );
}
```

### 2. 添加到 Wave

```java
public WaveBuilder waveXMyNewFeature() {
    List<StepDefinition> steps = new ArrayList<>();

    steps.add(createMyNewStep());

    waves.add(new Wave(
        8,                       // Wave 编号
        "Wave 8",               // Wave 名称
        "我的新功能",            // Wave 描述
        steps,
        false,                   // 是否并行
        1                        // 最大并发数
    ));

    return this;
}
```

### 3. 添加到标准流水线

```java
public List<Wave> buildStandard() {
    return wave1CreateApp()
        .wave2Planning()
        .wave25Planning()
        .wave3CreateWorksheets()
        .wave35Views()
        .wave35bData()
        .wave4Charts()
        .wave5Icons()
        .wave6Pages()
        .waveXMyNewFeature()  // 添加新 Wave
        .wave7Cleanup()
        .build();
}
```

## 自定义 AI 提示词

### 1. 修改工作表规划提示词

在 `WorksheetPlanner` 中：

```java
private String buildPrompt(String businessContext, String requirements) {
    return String.format(
        """
        作为明道云应用设计专家，请根据以下需求设计工作表结构：

        ## 业务背景
        %s

        ## 需求描述
        %s

        ## 设计要求
        1. 字段命名要简洁明了
        2. 选择合适的字段类型
        3. 合理设置必填字段
        4. 单选字段需要提供选项值
        5. 关联字段需要明确关联目标

        ## 输出格式
        请按以下 JSON 格式输出：
        {
          "worksheets": [
            {
              "name": "工作表名称",
              "purpose": "工作表用途",
              "fields": [
                {
                  "name": "字段名称",
                  "type": "字段类型",
                  "required": true/false,
                  "description": "字段描述",
                  "relation_target": "关联目标工作表（如果是Relation类型）",
                  "option_values": ["选项1", "选项2"]
                }
              ],
              "depends_on": ["依赖的工作表名称"]
            }
          ],
          "relationships": [
            {
              "from": "源工作表",
              "to": "目标工作表",
              "field": "关联字段名",
              "cardinality": "1-N"
            }
          ]
        }
        """,
        businessContext,
        requirements
    );
}
```

### 2. 添加提示词模板文件

创建 `src/main/resources/prompts/worksheet_planning.txt`：

```
# 工作表规划提示词模板

## 角色设定
你是一位经验丰富的数据库设计师和明道云应用专家。

## 任务
根据用户提供的业务需求，设计合理的工作表结构。

## 设计原则
1. 每个工作表应该只负责一个明确的数据实体
2. 字段命名遵循业务术语，避免技术术语
3. 合理使用关联字段建立表间关系
4. 必填字段数量不宜过多
5. 单选字段的选项要全面且互斥

{{business_context}}
{{requirements}}

## 输出要求
- 使用标准的 JSON 格式
- 所有字段名称使用中文
- 包含完整的字段定义
```

加载模板：

```java
private String loadPromptTemplate(String templateName) {
    try (InputStream is = getClass()
            .getResourceAsStream("/prompts/" + templateName + ".txt")) {
        return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }
}
```

## 添加新的 CLI 命令

### 1. 创建命令类

```java
// src/main/java/com/hap/automaker/cli/MyNewCommand.java

package com.hap.automaker.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

@Command(
    name = "my-new-command",
    mixinStandardHelpOptions = true,
    description = "我的新命令描述"
)
public class MyNewCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "参数描述")
    private String param;

    @Option(names = {"-o", "--option"}, description = "选项描述")
    private String option;

    @Option(names = {"--dry-run"}, description = "模拟执行")
    private boolean dryRun;

    @Override
    public Integer call() throws Exception {
        // 初始化
        ConfigManager config = new ConfigManager("config/credentials");
        OrganizationAuth auth = config.loadOrganizationAuth();

        // 执行业务逻辑
        MyService service = new MyService(auth);
        Result result = service.execute(param, option, dryRun);

        // 输出结果
        if (result.isSuccess()) {
            System.out.println("✅ 执行成功: " + result.getMessage());
            return 0;
        } else {
            System.err.println("❌ 执行失败: " + result.getError());
            return 1;
        }
    }
}
```

### 2. 注册到主命令

```java
// src/main/java/com/hap/automaker/cli/HapAutoMakerCli.java

@Command(
    name = "hap-auto-maker",
    mixinStandardHelpOptions = true,
    version = "0.1.0",
    description = "HAP Auto Maker CLI",
    subcommands = {
        SetupCommand.class,
        MakeAppCommand.class,
        ExecuteRequirementsCommand.class,
        PageGetCommand.class,
        PageSaveCommand.class,
        PageDeleteCommand.class,
        MyNewCommand.class,  // 添加新命令
        CommandLine.HelpCommand.class
    }
)
public class HapAutoMakerCli {
    // ...
}
```

### 3. 添加命令文档

在 `docs/COMMANDS.md` 中添加：

```markdown
### my-new-command

描述：执行自定义操作

用法：
```bash
hap-auto-maker my-new-command <param> [OPTIONS]
```

参数：
- `param`: 必需参数

选项：
- `-o, --option <value>`: 可选配置
- `--dry-run`: 模拟执行

示例：
```bash
hap-auto-maker my-new-command test-data --option value --dry-run
```
```

## 调试和测试

### 本地调试

```bash
# 使用 IDE 调试
# 1. 设置断点
# 2. 运行主类 HapAutoMakerCli
# 3. 在 Program Arguments 中输入命令参数

# 命令行调试
mvn compile exec:java \
    -Dexec.mainClass="com.hap.automaker.cli.HapAutoMakerCli" \
    -Dexec.args="execute-requirements --spec-json test.json"
```

### 单元测试

```java
@Test
void testMyNewFeature() {
    // 准备测试数据
    TestData testData = new TestData();

    // 执行
    Result result = myService.execute(testData);

    // 验证
    assertTrue(result.isSuccess());
    assertEquals(expected, result.getData());
}
```

### 集成测试

使用 WireMock 模拟 API：

```java
@WireMockTest(httpPort = 8080)
class MyIntegrationTest {

    @Test
    void testWithMockApi() {
        // 配置 Mock
        stubFor(post("/openapi/xxx")
            .willReturn(okJson("{\"data\":{},\"error_code\":1}")));

        // 执行
        Result result = service.execute();

        // 验证
        assertTrue(result.isSuccess());
        verify(postRequestedFor(urlEqualTo("/openapi/xxx")));
    }
}
```

## 性能优化

### 1. 减少 API 调用

```java
// 不好的做法：多次独立调用
for (Worksheet sheet : worksheets) {
    apiClient.getWorksheetInfo(sheet.getId());  // N 次 API 调用
}

// 好的做法：批量获取
List<String> ids = worksheets.stream()
    .map(Worksheet::getId)
    .collect(Collectors.toList());
JsonNode batchResult = apiClient.batchGetWorksheetInfo(ids);  // 1 次 API 调用
```

### 2. 缓存

```java
public class CachedWorksheetService {
    private final Map<String, JsonNode> cache = new ConcurrentHashMap<>();

    public JsonNode getWorksheetInfo(String worksheetId) {
        return cache.computeIfAbsent(worksheetId, id -> {
            try {
                return apiClient.getWorksheetInfo(id);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
}
```

### 3. 并发控制

```java
// 使用 CompletableFuture 并行处理
List<CompletableFuture<Result>> futures = worksheets.stream()
    .map(sheet -> CompletableFuture.supplyAsync(
        () -> processWorksheet(sheet),
        executorService
    ))
    .collect(Collectors.toList());

// 等待所有完成
List<Result> results = futures.stream()
    .map(CompletableFuture::join)
    .collect(Collectors.toList());
```

## 代码规范

1. **命名规范**: 类名使用 PascalCase，方法名使用 camelCase，常量使用 UPPER_SNAKE_CASE
2. **日志**: 使用 SLF4J，使用占位符而不是字符串拼接
3. **异常**: 使用业务异常类，避免裸抛 RuntimeException
4. **注释**: 公共 API 需要 Javadoc 注释
5. **测试**: 核心逻辑必须有单元测试，API 交互必须有集成测试
