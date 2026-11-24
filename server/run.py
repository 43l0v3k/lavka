"""Запускает сервер + Cloudflare quick tunnel, следит за ними и шлёт адрес туннеля владельцу в Telegram.

Настройки читаются из server/lavka.env (строки KEY=VALUE, см. lavka.env.example):
  LAVKA_TG_TOKEN   токен бота
  LAVKA_ADMIN_TG   твой числовой Telegram ID (куда слать адрес)
  LAVKA_TG_PROXY   необязательно
cloudflared ищется рядом с этим файлом (cloudflared.exe) или в PATH.
"""
import os
import re
import shutil
import subprocess
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
os.chdir(HERE)


def load_env():
    path = os.path.join(HERE, "lavka.env")
    if not os.path.exists(path):
        return
    with open(path, encoding="utf-8-sig") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                os.environ.setdefault(k.strip(), v.strip().strip('"'))


load_env()
os.environ.setdefault("LAVKA_PORT", "8080")
PORT = os.environ["LAVKA_PORT"]

import db  # noqa: E402  (после load_env: берёт LAVKA_DATA)
import bot  # noqa: E402

LOG = open(os.path.join(db.DATA, "run.log"), "a", encoding="utf-8", buffering=1)
URL_FILE = os.path.join(db.DATA, "public_url.txt")
URL_RE = re.compile(r"https://[a-z0-9-]+\.trycloudflare\.com")
NOWIN = getattr(subprocess, "CREATE_NO_WINDOW", 0)


def log(msg):
    LOG.write(time.strftime("%Y-%m-%d %H:%M:%S ") + msg + "\n")


def find_cloudflared():
    local = os.path.join(HERE, "cloudflared.exe" if os.name == "nt" else "cloudflared")
    return local if os.path.exists(local) else shutil.which("cloudflared")


def notify(url):
    if not (bot.TOKEN and bot.ADMIN_TG):
        return
    for attempt in range(6):  # сеть после загрузки Windows поднимается не сразу
        try:
            bot._api("sendMessage", {"chat_id": int(bot.ADMIN_TG), "text": f"🟢 Лавка онлайн\n{url}"}, timeout=15)
            log("адрес отправлен в Telegram")
            return
        except Exception as e:
            log(f"telegram: {e}")
            time.sleep(10)


def run_server():
    while True:
        log("запуск сервера")
        p = subprocess.Popen([sys.executable, os.path.join(HERE, "app.py")], stdout=LOG, stderr=subprocess.STDOUT,
                             creationflags=NOWIN, cwd=HERE)
        p.wait()
        log(f"сервер упал (код {p.returncode}), перезапуск через 5 с")
        time.sleep(5)


def run_tunnel(cf):
    while True:
        log("запуск туннеля")
        try:
            os.remove(URL_FILE)
        except OSError:
            pass
        p = subprocess.Popen([cf, "tunnel", "--no-autoupdate", "--url", f"http://localhost:{PORT}"],
                             stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8",
                             errors="replace", creationflags=NOWIN)
        announced = False
        for line in p.stdout:
            m = URL_RE.search(line)
            if m and not announced:
                announced = True
                url = m.group(0)
                with open(URL_FILE, "w", encoding="utf-8") as f:
                    f.write(url)
                log("адрес: " + url)
                print("Адрес:", url, flush=True)
                threading.Thread(target=notify, args=(url,), daemon=True).start()
        p.wait()
        log(f"туннель завершился (код {p.returncode}), перезапуск через 10 с")
        time.sleep(10)


def main():
    cf = find_cloudflared()
    threading.Thread(target=run_server, daemon=True).start()
    if not cf:
        log("cloudflared не найден — работает только локальная сеть")
        print("cloudflared не найден: положи cloudflared.exe рядом с run.py. Сервер работает только в локалке.")
        while True:
            time.sleep(3600)
    run_tunnel(cf)


if __name__ == "__main__":
    main()
