# 模拟飞书 Webhook 请求测试脚本
# 使用方法：在 PowerShell 中运行 .\test-webhook.ps1

$BASE_URL = "http://localhost:8082"

Write-Host "===== 飞书 Webhook 测试脚本 =====" -ForegroundColor Green
Write-Host ""

# 测试 1: 发送 /help 指令（简单指令）
Write-Host "[测试 1] 发送 /help 指令..." -ForegroundColor Yellow

$body1 = @{
    type = "im.message.receive_v1"
    header = @{
        event_id = "test-help-$(Get-Date -Format 'HHmmss')"
        event_type = "im.message.receive_v1"
    }
    event = @{
        message = @{
            message_id = "msg-help-001"
            chat_id = "oc_test_group_123"
            chat_type = "group"
            content = '{"text": "@_user_1 /help"}'
            mentions = @(
                @{
                    key = "@_user_1"
                    id = @{ open_id = "ou_test_bot" }
                    name = "机器人"
                    mentioned_type = "bot"
                }
            )
        }
        sender = @{
            sender_id = @{ open_id = "ou_test_user" }
            sender_type = "user"
        }
    }
} | ConvertTo-Json -Depth 10

$response1 = Invoke-RestMethod -Uri "$BASE_URL/feishu/webhook" `
    -Method Post `
    -ContentType "application/json" `
    -Body $body1

Write-Host "响应内容：" -ForegroundColor Cyan
$response1 | ConvertTo-Json | Write-Host

$taskId1 = $response1.task_id
Write-Host "提取的 task_id: $taskId1" -ForegroundColor Green
Write-Host ""

# 测试 2: 查询任务状态（/help）
if ($taskId1) {
    Write-Host "[测试 2] 查询任务状态（/help）..." -ForegroundColor Yellow
    $status1 = Invoke-RestMethod -Uri "$BASE_URL/api/task/$taskId1" -Method Get
    $status1 | ConvertTo-Json | Write-Host
    Write-Host ""
}

# 测试 3: 发送 /review 指令（复杂指令）
Write-Host "[测试 3] 发送 /review 指令（代码审查）..." -ForegroundColor Yellow

$body2 = @{
    type = "im.message.receive_v1"
    header = @{
        event_id = "test-review-$(Get-Date -Format 'HHmmss')"
        event_type = "im.message.receive_v1"
    }
    event = @{
        message = @{
            message_id = "msg-review-001"
            chat_id = "oc_test_group_123"
            chat_type = "group"
            content = '{"text": "@_user_1 /review test 1"}'
            mentions = @(
                @{
                    key = "@_user_1"
                    id = @{ open_id = "ou_test_bot" }
                    name = "机器人"
                    mentioned_type = "bot"
                }
            )
        }
        sender = @{
            sender_id = @{ open_id = "ou_test_user" }
            sender_type = "user"
        }
    }
} | ConvertTo-Json -Depth 10

$response2 = Invoke-RestMethod -Uri "$BASE_URL/feishu/webhook" `
    -Method Post `
    -ContentType "application/json" `
    -Body $body2

Write-Host "响应内容：" -ForegroundColor Cyan
$response2 | ConvertTo-Json | Write-Host

$taskId2 = $response2.task_id
Write-Host "提取的 task_id (review): $taskId2" -ForegroundColor Green
Write-Host ""

# 测试 4: 轮询查询任务状态（review）
if ($taskId2) {
    Write-Host "[测试 4] 轮询查询任务状态（review）..." -ForegroundColor Yellow
    
    for ($i = 1; $i -le 10; $i++) {
        Write-Host "第 $i 次查询：" -ForegroundColor Cyan
        $status2 = Invoke-RestMethod -Uri "$BASE_URL/api/task/$taskId2" -Method Get
        $status2 | Select-Object taskId, status, progress, statusMsg | Format-Table
        
        if ($status2.status -eq "COMPLETED" -or $status2.status -eq "FAILED") {
            Write-Host "任务已结束，停止轮询" -ForegroundColor Green
            break
        }
        
        Start-Sleep -Seconds 1
    }
    Write-Host ""
}

# 测试 5: 查询任务数量
Write-Host "[测试 5] 查询当前任务数量..." -ForegroundColor Yellow
$taskCount = Invoke-RestMethod -Uri "$BASE_URL/api/task/count" -Method Get
$taskCount | ConvertTo-Json | Write-Host
Write-Host ""

# 测试 6: 测试幂等性（重复发送相同 event_id）
Write-Host "[测试 6] 测试幂等性（重复发送相同 event_id）..." -ForegroundColor Yellow

$body3 = @{
    type = "im.message.receive_v1"
    header = @{
        event_id = "test-help-001"  # 与测试 1 相同的 event_id
        event_type = "im.message.receive_v1"
    }
    event = @{
        message = @{
            message_id = "msg-help-002"
            chat_id = "oc_test_group_123"
            chat_type = "group"
            content = '{"text": "@_user_1 /help"}'
            mentions = @(
                @{
                    key = "@_user_1"
                    id = @{ open_id = "ou_test_bot" }
                    name = "机器人"
                    mentioned_type = "bot"
                }
            )
        }
        sender = @{
            sender_id = @{ open_id = "ou_test_user" }
            sender_type = "user"
        }
    }
} | ConvertTo-Json -Depth 10

$response3 = Invoke-RestMethod -Uri "$BASE_URL/feishu/webhook" `
    -Method Post `
    -ContentType "application/json" `
    -Body $body3

Write-Host "幂等性测试响应：" -ForegroundColor Cyan
$response3 | ConvertTo-Json | Write-Host
Write-Host "注意：如果幂等性生效，task_id 应该与第一次相同" -ForegroundColor Yellow
Write-Host ""

Write-Host "===== 测试完成 =====" -ForegroundColor Green
Write-Host "提示：" -ForegroundColor Yellow
Write-Host "  1. 查看应用日志，确认任务状态更新正常"
Write-Host "  2. 如果任务状态未更新，检查异步处理是否生效"
Write-Host "  3. 如果 PowerShell 执行策略限制，运行：Set-ExecutionPolicy -Scope CurrentUser RemoteSigned"

# 暂停，方便查看结果
Read-Host -Prompt "按 Enter 退出"
