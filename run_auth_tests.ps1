# 鉴权功能测试运行脚本（PowerShell 版本）
# 使用方法：右键 → 使用 PowerShell 运行，或在 PowerShell 中执行 ./run_auth_tests.ps1

Write-Host "==================================="
Write-Host "鉴权功能测试"
Write-Host "==================================="

# 检查 Maven Wrapper 是否存在
if (-not (Test-Path "mvnw.cmd")) {
    Write-Host "❌ 未找到 mvnw.cmd，请在项目根目录运行此脚本" -ForegroundColor Red
    exit 1
}

# 1. 运行 AuthServiceTest
Write-Host ""
Write-Host "[1/2] 运行 AuthServiceTest..." -ForegroundColor Cyan
& .\mvnw.cmd test -Dtest=AuthServiceTest -DfailIfNoTests=false

if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ AuthServiceTest 失败" -ForegroundColor Red
    exit 1
}

# 2. 运行 CommandAuthTest
Write-Host ""
Write-Host "[2/2] 运行 CommandAuthTest..." -ForegroundColor Cyan
& .\mvnw.cmd test -Dtest=CommandAuthTest -DfailIfNoTests=false

if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ CommandAuthTest 失败" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "==================================="
Write-Host "✅ 所有测试通过！" -ForegroundColor Green
Write-Host "==================================="

# 显示测试报告
Write-Host ""
Write-Host "测试报告已生成：" -ForegroundColor Yellow
Write-Host "  target\surefire-reports\"
Write-Host ""
Write-Host "查看详细报告：" -ForegroundColor Yellow
Write-Host "  target\surefire-reports\TEST-com.example.IntelligentRobot.service.AuthServiceTest.xml"
Write-Host "  target\surefire-reports\TEST-com.example.IntelligentRobot.service.CommandAuthTest.xml"

Write-Host ""
Write-Host "提示：使用浏览器打开 target\surefire-reports\index.html 查看可视化报告" -ForegroundColor Cyan

exit 0
