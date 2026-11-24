@echo off
chcp 65001 >nul
cd /d "%~dp0"
rem Сервер + туннель + отправка адреса в Telegram. Настройки: lavka.env (см. lavka.env.example)
python run.py
pause
