@echo off
REM 鉴权功能测试运行脚本

echo ===================================
echo 鉴权功能测试
echo ===================================

REM 检查 Maven Wrapper 是否存在
if not exist "mvnw.cmd" (
    echo ❌ 未找到 mvnw.cmd，请在项目根目录运行此脚本
    exit /b 1
)

REM 1. 运行 AuthServiceTest
echo.
echo [1/2] 运行 AuthServiceTest...
call mvnw test -Dtest=AuthServiceTest -DfailIfNoTests=false

if %ERRORLEVEL% NEQ 0 (
    echo ❌ AuthServiceTest 失败
    exit /b 1
)

REM 2. 运行 CommandAuthTest
echo.
echo [2/2] 运行 CommandAuthTest...
call mvnw test -Dtest=CommandAuthTest -DfailIfNoTests=false

if %ERRORLEVEL% NEQ 0 (
    echo ❌ CommandAuthTest 失败
    exit /b 1
)

echo.
echo ===================================
echo ✅ 所有测试通过！
echo ===================================

REM 显示测试报告
echo.
echo 测试报告已生成：
echo   target/surefire-reports/
echo.
echo 查看详细报告：
echo   target/surefire-reports/TEST-com.example.IntelligentRobot.service.AuthServiceTest.xml
echo   target/surefire-reports/TEST-com.example.IntelligentRobot.service.CommandAuthTest.xml

exit /b 0
