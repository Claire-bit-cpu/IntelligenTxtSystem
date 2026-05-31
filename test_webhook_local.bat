@echo off
echo 测试本地Webhook端点...
echo.

REM 测试GET请求（应该返回405错误）
echo [1] 测试GET请求（预期：405错误）
curl -X GET http://localhost:8082/feishu/webhook
echo.
echo.

REM 测试POST请求（模拟飞书URL验证）
echo [2] 测试POST请求（URL验证）
curl -X POST http://localhost:8082/feishu/webhook ^
  -H "Content-Type: application/json" ^
  -d "{\"type\":\"url_verification\",\"challenge\":\"test_challenge_123\"}"
echo.
echo.

echo 测试完成！
pause
