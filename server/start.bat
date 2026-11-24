@echo off
chcp 65001 >nul
cd /d "%~dp0"
rem Токен бота (необязательно): set LAVKA_TG_TOKEN=123456:ABC...
rem Первый запуск: pip install -r requirements.txt  и скачать cloudflared.exe в эту папку или в PATH

start "Лавка: сервер" cmd /k python app.py
timeout /t 3 >nul
echo.
echo Ищи в этом окне строку https://....trycloudflare.com - это адрес для приложения и сайта
echo.
cloudflared tunnel --url http://localhost:8080
pause
