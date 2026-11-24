@echo off
chcp 65001 >nul
cd /d "%~dp0"
set PYW=
for /f "delims=" %%i in ('where pythonw 2^>nul') do if not defined PYW set PYW=%%i
if not defined PYW (
  echo Не нашёл pythonw. Установи Python с галочкой "Add to PATH".
  pause
  exit /b 1
)
schtasks /create /tn "Lavka" /tr "\"%PYW%\" \"%~dp0run.py\"" /sc onlogon /rl limited /f
if errorlevel 1 (echo Не получилось создать задачу & pause & exit /b 1)
echo.
echo Готово: Лавка будет запускаться сама при входе в Windows, без окна.
echo Лог: %~dp0data\run.log
echo Удалить автозапуск: schtasks /delete /tn Lavka /f
pause
