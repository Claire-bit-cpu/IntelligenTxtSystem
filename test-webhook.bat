@echo off
REM 模拟飞书 Webhook 请求测试脚本
REM 使用方法：双击运行，或修改参数后运行

set BASE_URL=http://localhost:8082
set TEST_CHAT_ID=oc_test_group_123
set TEST_USER_ID=ou_test_user_456
set TEST_BOT_ID=ou_test_bot_789

echo ===== 飞书 Webhook 测试脚本 =====
echo.

REM 测试 1: 发送 /help 指令（简单指令）
echo [测试 1] 发送 /help 指令...
curl -X POST "%BASE_URL%/feishu/webhook" ^
  -H "Content-Type: application/json" ^
  -d "{\"type\":\"im.message.receive_v1\",\"header\":{\"event_id\":\"test-help-%RANDOM%\",\"event_type\":\"im.message.receive_v1\"},\"event\":{\"message\":{\"message_id\":\"msg-help-001\",\"chat_id\":\"%TEST_CHAT_ID%\",\"chat_type\":\"group\",\"content\":\"{\\\"text\\\":\\\"@_user_1 /help\\\"}\",\"mentions\":[{\"key\":\"@_user_1\",\"id\":{\"open_id\":\"%TEST_BOT_ID%\"},\"name\":\"机器人\",\"mentioned_type\":\"bot\"}]},\"sender\":{\"sender_id\":{\"open_id\":\"%TEST_USER_ID%\"},\"sender_type\":\"user\"}}}" ^
  -s > response_help.json

echo 响应内容：
type response_help.json
echo.

REM 提取 task_id
for /f "tokens=2 delims=:," %%a in ('findstr /C "task_id" response_help.json') do (
  set TASK_ID=%%a
  set TASK_ID=!TASK_ID:"=!
  set TASK_ID=!TASK_ID: =!
)

echo 提取的 task_id: %TASK_ID%
echo.

REM 测试 2: 查询任务状态
if not "%TASK_ID%"=="" (
  echo [测试 2] 查询任务状态...
  curl -X GET "%BASE_URL%/api/task/%TASK_ID%" -s | jq .
  echo.
)

REM 测试 3: 发送 /review 指令（复杂指令，需要 GitHub 配置）
echo [测试 3] 发送 /review 指令（代码审查）...
curl -X POST "%BASE_URL%/feishu/webhook" ^
  -H "Content-Type: application/json" ^
  -d "{\"type\":\"im.message.receive_v1\",\"header\":{\"event_id\":\"test-review-001\",\"event_type\":\"im.message.receive_v1\"},\"event\":{\"message\":{\"message_id\":\"msg-review-001\",\"chat_id\":\"%TEST_CHAT_ID%\",\"chat_type\":\"group\",\"content\":\"{\\\"text\\\":\\\"@_user_1 /review test 1\\\"}\",\"mentions\":[{\"key\":\"@_user_1\",\"id\":{\"open_id\":\"%TEST_BOT_ID%\"},\"name\":\"机器人\",\"mentioned_type\":\"bot\"}]},\"sender\":{\"sender_id\":{\"open_id\":\"%TEST_USER_ID%\"},\"sender_type\":\"user\"}}}" ^
  -s > response_review.json

echo 响应内容：
type response_review.json
echo.

REM 提取 task_id（review）
for /f "tokens=2 delims=:," %%a in ('findstr /C "task_id" response_review.json') do (
  set TASK_ID_REVIEW=%%a
  set TASK_ID_REVIEW=!TASK_ID_REVIEW:"=!
  set TASK_ID_REVIEW=!TASK_ID_REVIEW: =!
)

echo 提取的 task_id (review): %TASK_ID_REVIEW%
echo.

REM 测试 4: 轮询查询任务状态（review）
if not "%TASK_ID_REVIEW%"=="" (
  echo [测试 4] 轮询查询任务状态（review）...
  for /L %%i in (1,1,10) do (
    echo 第 %%i 次查询：
    curl -X GET "%BASE_URL%/api/task/%TASK_ID_REVIEW%" -s | jq ".progress, .statusMsg"
    timeout /t 1 /nobreak >nul
  )
  echo.
)

REM 测试 5: 查询任务数量
echo [测试 5] 查询当前任务数量...
curl -X GET "%BASE_URL%/api/task/count" -s | jq .
echo.

echo ===== 测试完成 =====
pause
