@echo off
chcp 65001 >nul
REM ============================================================
REM 智能机器人 - Windows 一键启动脚本
REM 使用方法：
REM   1. 将此文件放在 jar 同级目录
REM   2. 设置系统环境变量（参见下方列表）
REM   3. 双击运行即可启动服务
REM ============================================================

REM ==================== 环境变量设置说明 ====================
REM 请在运行此脚本前设置以下环境变量：
REM
REM 【必填】飞书配置
REM   FEISHU_APP_ID          - 飞书应用 ID
REM   FEISHU_APP_SECRET      - 飞书应用密钥
REM   FEISHU_ENCRYPT_KEY     - 飞书加密密钥
REM
REM 【必填】AI 配置
REM   QIANWEN_API_KEY        - 通义千问 API 密钥
REM
REM 【可选】Redis 配置（默认 localhost:6379）
REM   REDIS_HOST             - Redis 主机地址
REM   REDIS_PORT             - Redis 端口
REM   REDIS_PASSWORD         - Redis 密码
REM
REM 【可选】高德天气 API
REM   AMAP_KEY               - 高德地图 API 密钥
REM
REM 【可选】GitHub 配置
REM   GITHUB_TOKEN           - GitHub 访问令牌
REM   GITHUB_WEBHOOK_SECRET  - GitHub Webhook 密钥
REM   GITHUB_ADMIN_OPEN_IDS  - 管理员 Open ID（逗号分隔）
REM   GITHUB_DEVELOPER_OPEN_IDS - 开发者 Open ID（逗号分隔）
REM   GITHUB_REPO_ALIASES    - 仓库别名映射（如 test=owner/repo）
REM
REM 【可选】JIRA 配置
REM   JIRA_URL               - JIRA 地址
REM   JIRA_USERNAME          - JIRA 用户名
REM   JIRA_API_TOKEN         - JIRA API 令牌
REM   JIRA_ENABLED           - 是否启用 JIRA（true/false）
REM
REM 【可选】通知配置
REM   NOTIFICATION_DB_PATH   - 通知数据库路径
REM   NOTIFICATION_DEFAULT_CHAT_IDS - 默认通知聊天 ID（逗号分隔）
REM
REM 【可选】搜索引擎配置
REM   SEARCH_INDEX_PATH      - 搜索索引数据库路径
REM   SEARCH_API_CALL_DELAY_MS - API 调用延迟（毫秒）
REM   SEARCH_STARTUP_DELAY_MS  - 启动延迟（毫秒）
REM
REM 【可选】其他配置
REM   CONTEXT_GLOBAL_PARAM_TTL_MINUTES - 上下文参数 TTL（分钟）
REM   WELCOME_BATCH_WINDOW_MS          - 欢迎消息批处理窗口（毫秒）
REM   TASK_STATUS_MAX_LOGS_LENGTH      - 任务日志最大长度
REM   TASK_MONITOR_CHAT_ID             - 任务监控聊天 ID
REM
REM 设置环境变量的方法：
REM   1. 永久设置：系统属性 → 高级 → 环境变量
REM   2. 临时设置：在命令提示符中执行 set FEISHU_APP_ID=your_value
REM ============================================================

REM 设置日志文件
set LOG_FILE=./logs/startup.log
if not exist "./logs" mkdir logs

echo ============================================================ > %LOG_FILE%
echo 智能机器人启动脚本 >> %LOG_FILE%
echo 启动时间: %date% %time% >> %LOG_FILE%
echo ============================================================ >> %LOG_FILE%
echo. >> %LOG_FILE%

echo ============================================================
echo 智能机器人启动脚本
echo 日志文件: %LOG_FILE%
echo ============================================================
echo.

REM ==================== 必填环境变量检查 ====================

echo 检查必需的环境变量...

if not defined FEISHU_APP_ID (
    echo [错误] 未设置环境变量: FEISHU_APP_ID
    echo [错误] 未设置环境变量: FEISHU_APP_ID >> %LOG_FILE%
    goto :env_error
)

if not defined FEISHU_APP_SECRET (
    echo [错误] 未设置环境变量: FEISHU_APP_SECRET
    echo [错误] 未设置环境变量: FEISHU_APP_SECRET >> %LOG_FILE%
    goto :env_error
)

if not defined FEISHU_ENCRYPT_KEY (
    echo [错误] 未设置环境变量: FEISHU_ENCRYPT_KEY
    echo [错误] 未设置环境变量: FEISHU_ENCRYPT_KEY >> %LOG_FILE%
    goto :env_error
)

if not defined QIANWEN_API_KEY (
    echo [错误] 未设置环境变量: QIANWEN_API_KEY
    echo [错误] 未设置环境变量: QIANWEN_API_KEY >> %LOG_FILE%
    goto :env_error
)

echo 必需环境变量检查通过
echo 必需环境变量检查通过 >> %LOG_FILE%
echo.

REM ==================== 可选环境变量默认值 ====================

REM Redis 配置（默认 localhost:6379）
if not defined REDIS_HOST set REDIS_HOST=localhost
if not defined REDIS_PORT set REDIS_PORT=6379
if not defined REDIS_PASSWORD set REDIS_PASSWORD=

REM JIRA 配置（默认禁用）
if not defined JIRA_ENABLED set JIRA_ENABLED=false

REM 通知配置
if not defined NOTIFICATION_DB_PATH set NOTIFICATION_DB_PATH=./notification.db

REM 搜索引擎配置
if not defined SEARCH_INDEX_PATH set SEARCH_INDEX_PATH=./search-index.db
if not defined SEARCH_API_CALL_DELAY_MS set SEARCH_API_CALL_DELAY_MS=300
if not defined SEARCH_STARTUP_DELAY_MS set SEARCH_STARTUP_DELAY_MS=30000

REM 上下文管理器配置
if not defined CONTEXT_GLOBAL_PARAM_TTL_MINUTES set CONTEXT_GLOBAL_PARAM_TTL_MINUTES=30

REM 欢迎消息配置
if not defined WELCOME_BATCH_WINDOW_MS set WELCOME_BATCH_WINDOW_MS=5000

REM 任务状态配置
if not defined TASK_STATUS_MAX_LOGS_LENGTH set TASK_STATUS_MAX_LOGS_LENGTH=10000

echo 可选环境变量已设置为默认值
echo 可选环境变量已设置为默认值 >> %LOG_FILE%
echo.

goto :start_checks

:env_error
echo.
echo ============================================================
echo 环境变量检查失败！
echo ============================================================
echo.
echo 请设置缺失的环境变量后重新运行。
echo.
echo 临时设置方法（命令提示符中执行）：
echo   set FEISHU_APP_ID=your_app_id
echo   set FEISHU_APP_SECRET=your_app_secret
echo   set FEISHU_ENCRYPT_KEY=your_encrypt_key
echo   set QIANWEN_API_KEY=your_api_key
echo.
echo 永久设置方法：
echo   1. 打开"系统属性" → "高级" → "环境变量"
echo   2. 在"用户变量"或"系统变量"中添加环境变量
echo   3. 重新启动命令提示符使设置生效
echo.
pause
exit /b 1

REM ==================== 服务配置 ====================
:start_checks

REM 服务端口（默认 8082）
set SERVER_PORT=8082

REM Jar 文件名（根据实际打包名称修改）
set JAR_NAME=IntelligentRobot-0.0.1-SNAPSHOT.jar

REM ==================== 启动检查 ====================

echo [1/3] 检查 Java 环境... >> %LOG_FILE%
java -version >> %LOG_FILE% 2>&1
if %errorlevel% neq 0 (
    echo [错误] 未检测到 Java，请安装 JDK 17 或更高版本
    echo [错误] 未检测到 Java，请安装 JDK 17 或更高版本 >> %LOG_FILE%
    echo.
    pause
    exit /b 1
)
echo Java 环境检查通过
echo Java 环境检查通过 >> %LOG_FILE%
echo.

echo [2/3] 检查 Jar 文件... >> %LOG_FILE%
if not exist "%JAR_NAME%" (
    echo [错误] 未找到 %JAR_NAME%
    echo [错误] 未找到 %JAR_NAME% >> %LOG_FILE%
    echo 请先执行打包命令：mvn clean package -DskipTests
    echo 请先执行打包命令：mvn clean package -DskipTests >> %LOG_FILE%
    echo.
    pause
    exit /b 1
)
echo 找到 %JAR_NAME%
echo 找到 %JAR_NAME% >> %LOG_FILE%
echo.

echo [3/3] 检查 Redis 连接（跳过，启动时会自动连接）... >> %LOG_FILE%
echo [3/3] 检查 Redis 连接（跳过，启动时会自动连接）...
echo 提示：如果 Redis 未启动，服务启动后会显示连接错误 >> %LOG_FILE%
echo 提示：如果 Redis 未启动，服务启动后会显示连接错误
echo.

REM ==================== 启动服务 ====================

echo ============================================================ >> %LOG_FILE%
echo 启动智能机器人服务... >> %LOG_FILE%
echo 服务端口: %SERVER_PORT% >> %LOG_FILE%
echo 命令行: java -jar %JAR_NAME% --server.port=%SERVER_PORT% --spring.config.location=optional:file:./application-prod.yaml >> %LOG_FILE%
echo ============================================================ >> %LOG_FILE%

echo ============================================================
echo 启动智能机器人服务...
echo 服务端口: %SERVER_PORT%
echo 日志文件: ./logs/intelligent-robot.log
echo 启动日志: %LOG_FILE%
echo 健康检查: http://localhost:%SERVER_PORT%/actuator/health
echo ============================================================
echo.
echo 按 Ctrl+C 停止服务
echo 启动中，请稍候...
echo.

REM 启动 Java 应用并将输出保存到日志
java -jar %JAR_NAME% ^
  --server.port=%SERVER_PORT% ^
  --spring.config.location=optional:file:./application-prod.yaml ^
  >> %LOG_FILE% 2>&1

echo.
echo 服务已停止，按任意键退出...
pause >nul
