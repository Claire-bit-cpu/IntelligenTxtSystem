@echo off
echo 清理消息合并相关的Redis key...
echo 注意：这将删除所有 notify:batch:* 的key

REM 使用 redis-cli 删除 key
redis-cli KEYS "notify:batch:*" | findstr /r /c:"^notify:batch:" > temp_keys.txt
for /f "delims=" %%i in (temp_keys.txt) do (
    echo 删除 key: %%i
    redis-cli DEL "%%i"
)

del temp_keys.txt
echo 清理完成
pause
