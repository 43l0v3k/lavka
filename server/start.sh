#!/usr/bin/env bash
# Сервер + Cloudflare quick tunnel. Первый запуск: pip install -r requirements.txt и установить cloudflared.
cd "$(dirname "$0")"
# export LAVKA_TG_TOKEN=123456:ABC...
python3 app.py &
SRV=$!
trap 'kill $SRV 2>/dev/null' EXIT
sleep 2
echo "Ищи строку https://....trycloudflare.com — это адрес для приложения и сайта"
cloudflared tunnel --url http://localhost:8080
