# Wave 执行框架文档

Wave 执行框架是 HAP Auto Maker Java 的核心执行引擎，负责编排和管理 10-Wave 流水线的执行。

## 架构概述

```
┌─────────────────────────────────────────────────────────────┐
│                    WaveExecutor                             │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐       ┌─────────┐   │
│  │  Wave 1 │ │  Wave 2 │ │ Wave 2.5│  ...  │  Wave 7 │   │
│  │ (串行)  │ │ (并行)  │ │ (串行)  │       │ (串行)  │   │
│  └────┬────┘ └────┬────┘ └────┬────┘       └────┬────┘   │
│       │           │           │                │        │
│       ▼           ▼           ▼                ▼        │
│  ┌─────────────────────────────────────────────────────┐  │
│  │              StepExecutor（步骤执行器）               │  │
│  │         - 重试逻辑（指数退避）                        │  │
│  │         - Gemini 并发控制（信号量）                   │  │
│  └─────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

## 核心组件

### 1. WaveExecutor

Wave 执行引擎，负责协调所有 Wave 的执行。

```java
// 创建标准执行器
WaveExecutor executor = WaveExecutor.standard(5);  // 最大5个并发

// 执行完整流水线
WaveResult result = executor.executeAll(spec, specPath, dryRun, language);

// 关闭执行器
executor.shutdown();
```

**配置选项：**
- `maxGeminiConcurrency`: Gemini API 最大并发数（信号量控制）
- `failFast`: 失败时是否立即终止
- `rollbackOnFailure`: 失败时是否回滚
- `forceReplan`: 是否强制重新规划

### 2. WaveBuilder

用于构建 Wave 流水线的构建器模式实现。

```java
List<Wave> waves = WaveBuilder.create()
    .wave1CreateApp()      // Wave 1: 创建应用
    .wave2Planning()       // Wave 2: 规划（并行）
    .wave25Planning()      // Wave 2.5: 分组规划
    .wave3CreateWorksheets()  // Wave 3: 创建工作表
    .wave35Views()         // Wave 3.5: 创建视图（并行）
    .wave35bData()         // Wave 3.5b: 数据填充
    .wave4Charts()         // Wave 4: 图表
    .wave5Icons()          // Wave 5: 图标
    .wave6Pages()          // Wave 6: 页面
    .wave7Cleanup()        // Wave 7: 清理
    .build();
```

### 3. StepExecutor

步骤执行器，处理单个 Step 的执行和重试逻辑。

**重试机制：**
- 默认最大重试次数：3 次
- 默认重试延迟：1000ms（指数退避）
- 支持 "数据过时" 错误的特殊处理

```java
StepExecutor executor = new StepExecutor(DEFAULT_MAX_RETRIES, DEFAULT_RETRY_DELAY_MS);

StepResult result = executor.execute(step, waveContext, stepResults);
```

### 4. WaveContext

Wave 执行上下文，保存执行过程中的状态和结果。

```java
public class WaveContext {
    private final Path specPath;           // 规格文件路径
    private final boolean dryRun;          // 是否模拟执行
    private final boolean failFast;          // 是否快速失败
    private final RequirementSpec spec;    // 需求规格
    private String appId;                   // 应用ID
    private final List<StepResult> stepResults;  // 步骤结果列表
}
```

## 10-Wave 详细说明

### Wave 1: 创建应用
- **类型**: 串行
- **说明**: 创建新应用并获取授权
- **输出**: `appId`, `appAuthJson`

### Wave 2: 规划阶段
- **类型**: 并行
- **说明**: AI 规划工作表结构和角色
- **输出**: `worksheetPlanJson`
- **并发数**: 2（工作表规划 + 角色规划）

### Wave 2.5: 分组与导航
- **类型**: 串行
- **说明**: 规划分组和导航风格
- **输出**: 分组配置

### Wave 3: 创建工作表
- **类型**: 串行
- **说明**: 根据规划创建所有工作表和字段
- **输出**: `worksheetCreateResultJson`

### Wave 3.5: 创建视图
- **类型**: 并行
- **说明**: 为每个工作表创建视图
- **输出**: `viewResultJson`
- **并发数**: 工作表数量

### Wave 3.5b: 数据填充
- **类型**: 并行
- **说明**: 生成测试数据并填写关联字段
- **输出**: 数据填充结果

### Wave 4: 图表规划
- **类型**: 串行
- **说明**: 规划并创建图表
- **输出**: 图表配置

### Wave 5: 图标匹配
- **类型**: 串行
- **说明**: 为工作表匹配合适的图标
- **输出**: 图标更新结果

### Wave 6: 自定义页面
- **类型**: 串行
- **说明**: 创建仪表盘页面和 Chatbot
- **输出**: `pageResultJson`

### Wave 7: 清理
- **类型**: 串行
- **说明**: 删除默认视图等清理工作
- **输出**: 清理结果

## 并行执行控制

### Gemini 信号量

所有使用 AI 的步骤共享一个信号量，控制并发数：

```java
private final Semaphore geminiSemaphore;

private StepResult executeStepWithGeminiControl(StepDefinition step, ...) {
    if (step.isUsesGemini()) {
        geminiSemaphore.acquire();
        try {
            return stepExecutor.execute(step, ctx, null);
        } finally {
            geminiSemaphore.release();
        }
    }
}
```

### Wave 级别并行

```java
// 并行 Wave 执行
private boolean executeWaveParallel(Wave wave, WaveContext ctx, ...) {
    List<Future<StepResult>> futures = new ArrayList<>();

    for (StepDefinition step : steps) {
        Future<StepResult> future = executor.submit(() -> {
            return executeStepWithGeminiControl(step, ctx, ...);
        });
        futures.add(future);
    }

    // 收集所有结果
    for (Future<StepResult> future : futures) {
        StepResult result = future.get();
        // 处理结果...
    }
}
```

## 错误处理

### Fail Fast 模式

```java
private boolean shouldAbort(WaveContext ctx) {
    return failFast && ctx.hasFailure();
}
```

### 步骤重试

```java
private StepResult executeWithRetry(StepDefinition step, WaveContext ctx,
                                    int attempt, long startTime) {
    try {
        return executeInternal(step, ctx);
    } catch (Exception e) {
        if (isRetriable(e) && attempt < maxRetries) {
            long delay = retryDelayMs * (1L << (attempt - 1));  // 指数退避
            Thread.sleep(delay);
            return executeWithRetry(step, ctx, attempt + 1, startTime);
        }
        throw e;
    }
}
```

### 特殊错误处理

"数据过时" 错误自动重试：

```java
private static final Pattern STALE_DATA_PATTERN =
    Pattern.compile("数据过时|stale data|version conflict", Pattern.CASE_INSENSITIVE);

private boolean isRetriable(Exception e) {
    return STALE_DATA_PATTERN.matcher(e.getMessage()).find();
}
```

## 执行报告

执行完成后生成 JSON 格式报告：

```json
{
  "schema_version": "hap_requirement_v1_execution_report",
  "created_at": "2026-04-26T23:43:49.361002+08:00",
  "spec_json": "path/to/spec.json",
  "dry_run": false,
  "fail_fast": true,
  "app_id": "98f56a90-...",
  "steps": [
    {
      "stepId": 1,
      "stepKey": "create_app",
      "title": "Create app and fetch auth",
      "ok": true,
      "skipped": false,
      "startedAt": "...",
      "endedAt": "...",
      "artifacts": { "appId": "...", "appAuthJson": "..." }
    }
  ]
}
```

## 扩展指南

### 添加新的 Wave

1. 在 `WaveBuilder` 中添加方法：

```java
public WaveBuilder waveXNewFeature() {
    List<StepDefinition> steps = new ArrayList<>();

    steps.add(new StepDefinition(
        1, "new_feature", "新功能",
        () -> {
            // 执行逻辑
            return StepExecutable.ExecutableResult.success("output");
        },
        false  // 是否使用 Gemini
    ));

    waves.add(new Wave(8, "Wave 8", "新功能", steps, false, 1));
    return this;
}
```

2. 在 `buildStandard()` 中添加调用：

```java
public List<Wave> buildStandard() {
    wave1CreateApp()
        .wave2Planning()
        // ...
        .waveXNewFeature()  // 添加新 Wave
        .wave7Cleanup();
    return waves;
}
```

### 自定义 Step

```java
StepDefinition customStep = new StepDefinition(
    stepId, "step_key", "步骤标题",
    () -> {
        // 1. 获取依赖数据
        // 2. 执行操作
        // 3. 返回结果

        if (success) {
            return StepExecutable.ExecutableResult.success(output, outputPath);
        } else {
            return StepExecutable.ExecutableResult.failure(errorMessage);
        }
    },
    usesGemini
);
```

## 性能优化建议

1. **并发数调整**: 根据 API 限流调整 `maxGeminiConcurrency`
2. **批处理**: Wave 3.5b 的数据填充使用批处理减少 API 调用
3. **缓存**: 工作表元数据在执行期间缓存
4. **异步日志**: 使用 Logback 的异步 Appender 减少 I/O 阻塞

## 调试技巧

1. 查看执行报告：`data/outputs/execution_runs/execution_run_*.json`
2. 查看技术日志：`data/outputs/app_runs/{run_id}/tech_log.json`
3. 启用 DEBUG 日志：修改 `logback.xml` 中 `com.hap.automaker` 级别为 DEBUG
