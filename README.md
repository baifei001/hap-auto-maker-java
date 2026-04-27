# HAP Auto Maker Java CLI

HAP Auto Maker 的 Java 实现版本，用于自动化创建明道云应用。

## 功能特性

- **10-Wave 并行执行框架** - 高效的任务流水线执行，支持串行/并行混合模式
- **AI 驱动的工作表规划** - 使用 DeepSeek/Gemini API 自动分析需求并生成工作表结构
- **完整的工作表生命周期管理** - 创建、字段管理、关联关系、视图、页面
- **智能图表生成** - 基于数据特征推荐并创建图表
- **完善的错误处理和重试机制** - 指数退避重试，支持 "数据过时" 错误自动恢复
- **12个AI规划器** - 工作表/视图/图表/页面/角色/图标/布局/筛选/Mock数据/关联/机器人/分组
- **16个执行器** - 覆盖完整的创建流程
- **17个流水线服务** - 端到端自动化
- **14个CLI命令** - 完整操作支持，含增量操作
- **66种注册类型** - 38字段+11视图+17图表

## 快速开始

### 环境要求

- Java 17+
- Maven 3.8+

### 配置

1. **AI 配置** - 创建 `config/credentials/ai_auth.json`：
```json
{
  "provider": "deepseek",
  "api_key": "your-deepseek-api-key",
  "model": "deepseek-chat",
  "base_url": "https://api.deepseek.com/v1"
}
```

2. **组织认证** - 创建 `config/credentials/organization_auth.json`：
```json
{
  "app_key": "your-app-key",
  "secret_key": "your-secret-key",
  "project_id": "your-project-id",
  "owner_id": "your-owner-id",
  "group_ids": ""
}
```

3. **Web 认证**（用于页面创建）- 创建 `config/credentials/web_auth.json`：
```json
{
  "accountid": "your-account-id",
  "md_pss_id": "your-session-cookie"
}
```

### 构建和运行

```bash
# 构建项目
mvn clean package -DskipTests

# 查看帮助
java -jar target/hap-auto-maker-cli.jar --help

# 执行需求规格（完整流程）
java -jar target/hap-auto-maker-cli.jar execute-requirements --spec-json your_spec.json

# 仅设置配置
java -jar target/hap-auto-maker-cli.jar setup

# 创建应用
java -jar target/hap-auto-maker-cli.jar make-app --name "应用名称" --icon sys_0_lego --color "#2196F3"
```

## 命令列表

| 命令 | 说明 |
|------|------|
| `setup` | 初始化配置文件 |
| `make-app` | 创建新应用 |
| `execute-requirements` | 执行完整需求流程（Wave 1-8） |
| `add-worksheet` | 增量添加工作表 |
| `add-field` | 增量添加字段 |
| `add-view` | 增量添加视图 |
| `add-chart` | 增量添加图表 |
| `modify-view` | 修改视图 |
| `delete-view` | 删除视图 |
| `delete-default-views` | 删除默认视图 |
| `update-worksheet-icons` | 更新工作表图标 |
| `page-get` | 获取页面配置 |
| `page-save` | 保存页面配置 |
| `page-delete` | 删除页面 |

## 项目结构

```
src/main/java/com/hap/automaker/
├── Main.java                   # 入口
├── api/                        # API 客户端
│   └── HapApiClient.java       # V3 API + Web API
├── ai/                         # AI 客户端
│   ├── AiTextClient.java       # AI接口
│   ├── HttpAiTextClient.java   # HTTP实现
│   └── AiJsonParser.java       # JSON解析
├── cli/                        # 命令行接口 (14个命令)
├── config/                     # 配置管理
├── core/                       # 核心框架
│   ├── executor/               # 16个执行器
│   ├── planner/                # 12个AI规划器
│   ├── registry/               # 3个注册中心 (38+11+17=66种类型)
│   └── wave/                   # Wave执行框架
├── executors/                  # 兼容执行器 (IconCreator, RoleCreator)
├── model/                      # 数据模型 + 规格定义
├── pipeline/                   # 编排层 (PhaseOne + PhaseTwo)
├── service/                    # 服务层 (17+个服务)
└── util/                       # 工具类
```

## 10-Wave 执行流程

```
Wave 1:    创建应用         → 创建/使用现有应用，获取授权
Wave 2:    规划阶段         → AI规划工作表和角色（并行执行）
Wave 2.5:  分组与导航       → 规划分组和导航风格
Wave 3:    创建工作表       → 创建所有工作表和字段
Wave 3.5:  创建视图         → 为每个工作表创建视图（并行执行）
Wave 3.5b: 数据填充         → 生成测试数据并填写关联字段（并行执行）
Wave 4:    图表规划         → 规划并创建图表
Wave 5:    图标匹配         → 更新工作表图标
Wave 6:    自定义页面       → 创建仪表盘页面和Chatbot
Wave 7:    清理             → 删除默认视图
```

## 测试

```bash
# 运行所有测试 (453个)
mvn test

# 运行特定测试
mvn test -Dtest=FieldTypeRegistryBatch1Test
mvn test -Dtest="*PipelineServiceTest"
```

## 技术栈

- **Java 17** - 主要开发语言
- **Maven** - 构建工具
- **Jackson** - JSON 处理
- **SLF4J + Logback** - 日志框架
- **JUnit 5** - 单元测试
- **picocli** - CLI 框架

## 迁移自 Python 版本

| Python | Java |
|--------|------|
| `make_app.py` | `ExecuteRequirementsCommand` |
| `waves.py` | `WaveExecutor` / `WaveBuilder` |
| `field_types.py` | `FieldTypeRegistry` |
| `view_types.py` | `ViewTypeRegistry` |
| `chart_config_schema.py` | `ChartTypeRegistry` |
| `planners/*.py` | `core/planner/*.java` |
| `executors/*.py` | `core/executor/*.java` |
| `pipeline_*.py` | `service/*PipelineService.java` |

## License

MIT License
