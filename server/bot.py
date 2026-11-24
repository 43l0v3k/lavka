"""Telegram-бот уведомлений. Только стандартная библиотека, long polling.

Переменные окружения:
  LAVKA_TG_TOKEN  токен от @BotFather (без него бот выключен, сервер работает как обычно)
  LAVKA_TG_PROXY  необязательно, http-прокси вида http://127.0.0.1:8080 (если Telegram у тебя заблокирован)
  LAVKA_PUBLIC_URL  необязательно, адрес сайта для ссылки в уведомлении
"""
import json
import os
import queue
import threading
import time
import urllib.error
import urllib.request

import db

TOKEN = os.environ.get("LAVKA_TG_TOKEN", "").strip()
PROXY = os.environ.get("LAVKA_TG_PROXY", "").strip()
PUBLIC_URL = os.environ.get("LAVKA_PUBLIC_URL", "").strip().rstrip("/")

username = None  # заполняется после getMe
_out = queue.Queue()
_started = False

if PROXY:
    _opener = urllib.request.build_opener(urllib.request.ProxyHandler({"https": PROXY, "http": PROXY}))
else:
    _opener = urllib.request.build_opener()


def enabled():
    return bool(TOKEN) and _started


def _api(method, payload=None, timeout=40):
    req = urllib.request.Request(
        f"https://api.telegram.org/bot{TOKEN}/{method}",
        data=json.dumps(payload or {}).encode(),
        headers={"Content-Type": "application/json"},
    )
    with _opener.open(req, timeout=timeout) as r:
        return json.load(r)


def _send(chat_id, text):
    try:
        _api("sendMessage", {"chat_id": chat_id, "text": text, "disable_web_page_preview": True}, timeout=15)
    except urllib.error.HTTPError as e:
        # 403: пользователь заблокировал бота — отвязываем, чтобы не долбить впустую
        if e.code == 403:
            c = db.connect()
            c.execute("UPDATE users SET tg_chat_id=NULL WHERE tg_chat_id=?", (chat_id,))
            c.commit()
            c.close()
        else:
            print("[bot] sendMessage:", e)
    except Exception as e:  # сеть упала — не роняем поток
        print("[bot] sendMessage:", e)


def notify(user_id, sender_name, listing_title, text, chat_id):
    """Положить уведомление в очередь. Ничего не делает, если бот выключен или аккаунт не привязан."""
    if not enabled():
        return
    c = db.connect()
    row = c.execute("SELECT tg_chat_id FROM users WHERE id=?", (user_id,)).fetchone()
    c.close()
    if not row or not row["tg_chat_id"]:
        return
    msg = f"💬 {sender_name} · «{listing_title}»\n\n{text[:400]}"
    if PUBLIC_URL:
        msg += f"\n\n{PUBLIC_URL}/#/chat/{chat_id}"
    _out.put((row["tg_chat_id"], msg))


def _sender_loop():
    while True:
        chat_id, text = _out.get()
        _send(chat_id, text)
        time.sleep(0.05)  # лимит Telegram ~30 сообщений/сек


def _handle_update(u):
    m = u.get("message")
    if not m or "text" not in m:
        return
    chat_id = m["chat"]["id"]
    text = m["text"].strip()
    if text.startswith("/start"):
        parts = text.split(maxsplit=1)
        code = parts[1].strip().upper() if len(parts) > 1 else ""
        if not code:
            _send(chat_id, "Привет! Это бот Лавки. Чтобы получать уведомления о сообщениях, "
                           "открой в Лавке Профиль → Telegram и нажми «Привязать».")
            return
        c = db.connect()
        row = c.execute("SELECT user_id FROM tg_codes WHERE code=? AND expires>?", (code, int(time.time()))).fetchone()
        if not row:
            c.close()
            _send(chat_id, "Код не подошёл или просрочен. Сгенерируй новый в Профиле.")
            return
        c.execute("UPDATE users SET tg_chat_id=NULL WHERE tg_chat_id=?", (chat_id,))
        c.execute("UPDATE users SET tg_chat_id=? WHERE id=?", (chat_id, row["user_id"]))
        c.execute("DELETE FROM tg_codes WHERE code=?", (code,))
        c.commit()
        name = c.execute("SELECT name FROM users WHERE id=?", (row["user_id"],)).fetchone()["name"]
        c.close()
        _send(chat_id, f"✅ Готово, {name}! Теперь сюда будут приходить новые сообщения. Отвязать: /stop")
    elif text.startswith("/stop"):
        c = db.connect()
        c.execute("UPDATE users SET tg_chat_id=NULL WHERE tg_chat_id=?", (chat_id,))
        c.commit()
        c.close()
        _send(chat_id, "Уведомления отключены. Привязать снова можно в Профиле.")
    else:
        _send(chat_id, "Я умею только присылать уведомления. /stop — отключить.")


def _poll_loop():
    global username
    offset = 0
    while True:
        try:
            if username is None:
                username = _api("getMe")["result"]["username"]
                print(f"[bot] запущен: @{username}")
            data = _api("getUpdates", {"offset": offset, "timeout": 30, "allowed_updates": ["message"]}, timeout=45)
            for u in data.get("result", []):
                offset = u["update_id"] + 1
                try:
                    _handle_update(u)
                except Exception as e:
                    print("[bot] update error:", e)
        except Exception as e:
            print("[bot] polling:", e)
            time.sleep(5)


def start():
    global _started
    if not TOKEN or _started:
        if not TOKEN:
            print("[bot] LAVKA_TG_TOKEN не задан — Telegram-бот выключен")
        return
    _started = True
    threading.Thread(target=_poll_loop, daemon=True).start()
    threading.Thread(target=_sender_loop, daemon=True).start()
