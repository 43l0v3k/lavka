import hashlib
import hmac
import os
import re
import secrets
import time
import uuid
from functools import wraps

from flask import Flask, jsonify, request, send_from_directory, g

import bot
import db

BASE = os.path.dirname(os.path.abspath(__file__))
UPLOADS = os.path.join(db.DATA, "uploads")
os.makedirs(UPLOADS, exist_ok=True)

app = Flask(__name__, static_folder=os.path.join(BASE, "static"), static_url_path="/static")
app.config["MAX_CONTENT_LENGTH"] = 64 * 1024 * 1024
app.config["JSON_AS_ASCII"] = False
app.json.ensure_ascii = False
app.teardown_appcontext(db.close)

CATEGORIES = [
    ("transport", "Транспорт", "🚗"),
    ("realty", "Недвижимость", "🏠"),
    ("electronics", "Электроника", "📱"),
    ("home", "Дом и сад", "🛋️"),
    ("clothes", "Одежда и обувь", "👕"),
    ("kids", "Детям", "🧸"),
    ("hobby", "Хобби и отдых", "🎸"),
    ("animals", "Животные", "🐾"),
    ("services", "Услуги", "🛠️"),
    ("jobs", "Работа", "💼"),
    ("other", "Другое", "📦"),
]
CAT_IDS = {c[0] for c in CATEGORIES}

ID_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"  # без 0/O/1/I
MAX_IMAGES = 8
MAX_IMAGE_BYTES = 8 * 1024 * 1024


# ---------- утилиты ----------

def err(msg, code=400):
    return jsonify({"error": msg}), code


def now():
    return int(time.time())


def pw_hash(password, salt):
    return hashlib.pbkdf2_hmac("sha256", password.encode(), salt, 200_000)


def new_user_id(c):
    while True:
        a = "".join(secrets.choice(ID_ALPHABET) for _ in range(4))
        b = "".join(secrets.choice(ID_ALPHABET) for _ in range(4))
        uid = f"L-{a}-{b}"
        if not c.execute("SELECT 1 FROM users WHERE id=?", (uid,)).fetchone():
            return uid


def norm_id(s):
    s = re.sub(r"[\s_]+", "", (s or "").upper()).replace("–", "-")
    if re.fullmatch(r"L?[A-Z0-9]{8}", s):  # ввели без дефисов
        s = s[-8:]
        s = f"L-{s[:4]}-{s[4:]}"
    return s


def issue_token(c, uid):
    token = secrets.token_urlsafe(32)
    c.execute("INSERT INTO tokens(token_hash, user_id, created_at) VALUES(?,?,?)",
              (hashlib.sha256(token.encode()).hexdigest(), uid, now()))
    c.commit()
    return token


def current_user():
    if "user" in g:
        return g.user
    h = request.headers.get("Authorization", "")
    user = None
    if h.startswith("Bearer "):
        th = hashlib.sha256(h[7:].encode()).hexdigest()
        user = db.get().execute(
            "SELECT u.* FROM tokens t JOIN users u ON u.id=t.user_id WHERE t.token_hash=?", (th,)
        ).fetchone()
    g.user = user
    return user


def auth(f):
    @wraps(f)
    def wrapper(*a, **kw):
        if not current_user():
            return err("Нужно войти", 401)
        return f(*a, **kw)
    return wrapper


_attempts = {}


def too_many_attempts(key):
    t = now()
    lst = [x for x in _attempts.get(key, []) if t - x < 300]
    _attempts[key] = lst
    return len(lst) >= 8


def sniff_image(b):
    if b[:3] == b"\xff\xd8\xff":
        return "jpg"
    if b[:8] == b"\x89PNG\r\n\x1a\n":
        return "png"
    if b[:6] in (b"GIF87a", b"GIF89a"):
        return "gif"
    if b[:4] == b"RIFF" and b[8:12] == b"WEBP":
        return "webp"
    return None


def clean_str(v, limit):
    return (v or "").strip()[:limit]


def parse_price(v):
    try:
        p = int(str(v).strip() or "0")
    except ValueError:
        return None
    return p if 0 <= p <= 1_000_000_000 else None


def listing_dict(row, images=None):
    d = {
        "id": row["id"],
        "title": row["title"],
        "description": row["description"],
        "price": row["price"],
        "category": row["category"],
        "city": row["city"],
        "status": row["status"],
        "views": row["views"],
        "created_at": row["created_at"],
        "seller": {"id": row["user_id"], "name": row["seller_name"]},
        "favorite": bool(row["fav"]),
        "is_mine": bool(row["is_mine"]),
    }
    if images is None:
        d["cover"] = f"/uploads/{row['cover']}" if row["cover"] else None
    else:
        d["images"] = [f"/uploads/{f}" for f in images]
        d["cover"] = d["images"][0] if images else None
    return d


LISTING_SELECT = """
SELECT l.*, u.name AS seller_name,
  (SELECT filename FROM images WHERE listing_id=l.id ORDER BY position LIMIT 1) AS cover,
  EXISTS(SELECT 1 FROM favorites f WHERE f.listing_id=l.id AND f.user_id=:uid) AS fav,
  (l.user_id=:uid) AS is_mine
FROM listings l JOIN users u ON u.id=l.user_id
"""


def uid_or_empty():
    u = current_user()
    return u["id"] if u else ""


# ---------- страницы и файлы ----------

@app.after_request
def headers(resp):
    resp.headers["X-Content-Type-Options"] = "nosniff"
    return resp


@app.get("/")
def index():
    return send_from_directory(app.static_folder, "index.html")


@app.get("/uploads/<path:name>")
def uploaded(name):
    return send_from_directory(UPLOADS, name, max_age=86400 * 30)


@app.get("/api/ping")
def ping():
    return jsonify({"ok": True, "app": "lavka", "time": now()})


@app.get("/api/categories")
def categories():
    return jsonify([{"id": i, "name": n, "emoji": e} for i, n, e in CATEGORIES])


# ---------- аккаунт ----------

@app.post("/api/register")
def register():
    j = request.get_json(silent=True) or {}
    name = clean_str(j.get("name"), 40)
    password = j.get("password") or ""
    city = clean_str(j.get("city"), 60)
    if len(name) < 2:
        return err("Имя — минимум 2 символа")
    if len(password) < 8:
        return err("Пароль — минимум 8 символов")
    if len(password) > 200:
        return err("Пароль слишком длинный")
    c = db.get()
    uid = new_user_id(c)
    salt = secrets.token_bytes(16)
    c.execute("INSERT INTO users(id,name,salt,pass_hash,city,created_at) VALUES(?,?,?,?,?,?)",
              (uid, name, salt, pw_hash(password, salt), city, now()))
    c.commit()
    return jsonify({"id": uid, "name": name, "token": issue_token(c, uid)}), 201


@app.post("/api/login")
def login():
    j = request.get_json(silent=True) or {}
    uid = norm_id(j.get("id"))
    password = j.get("password") or ""
    key = (request.remote_addr, uid)
    if too_many_attempts(key):
        return err("Слишком много попыток, подожди 5 минут", 429)
    c = db.get()
    u = c.execute("SELECT * FROM users WHERE id=?", (uid,)).fetchone()
    # хеш считаем всегда, чтобы время ответа не выдавало, существует ли ID
    salt = u["salt"] if u else b"\0" * 16
    ok = hmac.compare_digest(pw_hash(password, salt), u["pass_hash"] if u else b"\0" * 32)
    if not u or not ok:
        _attempts.setdefault(key, []).append(now())
        return err("Неверный ID или пароль", 401)
    return jsonify({"id": u["id"], "name": u["name"], "token": issue_token(c, uid)})


@app.post("/api/logout")
@auth
def logout():
    h = request.headers["Authorization"][7:]
    c = db.get()
    c.execute("DELETE FROM tokens WHERE token_hash=?", (hashlib.sha256(h.encode()).hexdigest(),))
    c.commit()
    return jsonify({"ok": True})


def me_dict(u):
    return {
        "id": u["id"], "name": u["name"], "city": u["city"], "created_at": u["created_at"],
        "telegram": {"enabled": bot.enabled(), "linked": bool(u["tg_chat_id"]), "bot": bot.username},
    }


@app.get("/api/me")
@auth
def me():
    return jsonify(me_dict(current_user()))


@app.route("/api/me", methods=["PATCH"])
@app.route("/api/me/update", methods=["POST"])  # HttpURLConnection на Android не умеет PATCH
@auth
def me_update():
    j = request.get_json(silent=True) or {}
    u = current_user()
    name = clean_str(j.get("name", u["name"]), 40)
    city = clean_str(j.get("city", u["city"]), 60)
    if len(name) < 2:
        return err("Имя — минимум 2 символа")
    c = db.get()
    c.execute("UPDATE users SET name=?, city=? WHERE id=?", (name, city, u["id"]))
    c.commit()
    g.pop("user", None)
    return jsonify(me_dict(current_user()))


# ---------- объявления ----------

@app.get("/api/listings")
def listings_list():
    a = request.args
    where, p = [], {"uid": uid_or_empty()}
    if a.get("mine") == "1":
        if not current_user():
            return err("Нужно войти", 401)
        where.append("l.user_id=:uid")
    elif a.get("user"):
        where += ["l.user_id=:seller", "l.status='active'"]
        p["seller"] = norm_id(a["user"])
    elif a.get("favorites") == "1":
        if not current_user():
            return err("Нужно войти", 401)
        where.append("EXISTS(SELECT 1 FROM favorites f2 WHERE f2.listing_id=l.id AND f2.user_id=:uid)")
    else:
        where.append("l.status='active'")
    q = clean_str(a.get("q"), 100).lower()
    if q:
        esc = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        where.append("(lc(l.title) LIKE :q ESCAPE '\\' OR lc(l.description) LIKE :q ESCAPE '\\')")
        p["q"] = f"%{esc}%"
    if a.get("category") in CAT_IDS:
        where.append("l.category=:cat")
        p["cat"] = a["category"]
    if a.get("free") == "1":
        where.append("l.price=0")
    for key, op in (("min", ">="), ("max", "<=")):
        v = parse_price(a.get(key)) if a.get(key) else None
        if v is not None:
            where.append(f"l.price {op} :{key}")
            p[key] = v
    city = clean_str(a.get("city"), 60).lower()
    if city:
        where.append("lc(l.city)=:city")
        p["city"] = city
    order = {"cheap": "l.price ASC, l.id DESC", "expensive": "l.price DESC, l.id DESC"}.get(a.get("sort"), "l.id DESC")
    try:
        limit = max(1, min(int(a.get("limit", 24)), 50))
        offset = max(0, int(a.get("offset", 0)))
    except ValueError:
        return err("Некорректные limit/offset")
    sql = LISTING_SELECT + (" WHERE " + " AND ".join(where) if where else "") + f" ORDER BY {order} LIMIT {limit} OFFSET {offset}"
    rows = db.get().execute(sql, p).fetchall()
    return jsonify({"items": [listing_dict(r) for r in rows], "has_more": len(rows) == limit})


def fetch_listing(lid):
    c = db.get()
    row = c.execute(LISTING_SELECT + " WHERE l.id=:id", {"uid": uid_or_empty(), "id": lid}).fetchone()
    if not row:
        return None, None
    imgs = [r["filename"] for r in c.execute("SELECT filename FROM images WHERE listing_id=? ORDER BY position", (lid,))]
    return row, imgs


@app.get("/api/listings/<int:lid>")
def listing_get(lid):
    row, imgs = fetch_listing(lid)
    if not row:
        return err("Объявление не найдено", 404)
    if row["status"] != "active" and not row["is_mine"]:
        return err("Объявление не найдено", 404)
    if not row["is_mine"]:
        c = db.get()
        c.execute("UPDATE listings SET views=views+1 WHERE id=?", (lid,))
        c.commit()
    return jsonify(listing_dict(row, imgs))


@app.post("/api/listings")
@auth
def listing_create():
    f = request.form
    title = clean_str(f.get("title"), 100)
    desc = clean_str(f.get("description"), 5000)
    price = parse_price(f.get("price"))
    category = f.get("category", "")
    city = clean_str(f.get("city"), 60) or current_user()["city"]
    if len(title) < 3:
        return err("Название — минимум 3 символа")
    if price is None:
        return err("Некорректная цена (0 = бесплатно)")
    if category not in CAT_IDS:
        return err("Выбери категорию")
    files = [x for x in request.files.getlist("images") if x and x.filename][:MAX_IMAGES]
    saved = []
    for x in files:
        data = x.read(MAX_IMAGE_BYTES + 1)
        if len(data) > MAX_IMAGE_BYTES:
            return err("Фото больше 8 МБ")
        ext = sniff_image(data)
        if not ext:
            return err("Поддерживаются JPG, PNG, WEBP, GIF")
        saved.append((data, ext))
    c = db.get()
    cur = c.execute(
        "INSERT INTO listings(user_id,title,description,price,category,city,created_at) VALUES(?,?,?,?,?,?,?)",
        (current_user()["id"], title, desc, price, category, city, now()))
    lid = cur.lastrowid
    for i, (data, ext) in enumerate(saved):
        name = f"{uuid.uuid4().hex}.{ext}"
        with open(os.path.join(UPLOADS, name), "wb") as fh:
            fh.write(data)
        c.execute("INSERT INTO images(listing_id,filename,position) VALUES(?,?,?)", (lid, name, i))
    c.commit()
    row, imgs = fetch_listing(lid)
    return jsonify(listing_dict(row, imgs)), 201


def own_listing(lid):
    row = db.get().execute("SELECT * FROM listings WHERE id=?", (lid,)).fetchone()
    if not row:
        return None, err("Объявление не найдено", 404)
    if row["user_id"] != current_user()["id"]:
        return None, err("Это не твоё объявление", 403)
    return row, None


@app.put("/api/listings/<int:lid>")
@auth
def listing_update(lid):
    row, e = own_listing(lid)
    if e:
        return e
    j = request.get_json(silent=True) or {}
    title = clean_str(j.get("title", row["title"]), 100)
    desc = clean_str(j.get("description", row["description"]), 5000)
    price = parse_price(j.get("price", row["price"]))
    category = j.get("category", row["category"])
    city = clean_str(j.get("city", row["city"]), 60)
    status = j.get("status", row["status"])
    if len(title) < 3:
        return err("Название — минимум 3 символа")
    if price is None or category not in CAT_IDS or status not in ("active", "sold"):
        return err("Некорректные данные")
    c = db.get()
    c.execute("UPDATE listings SET title=?,description=?,price=?,category=?,city=?,status=? WHERE id=?",
              (title, desc, price, category, city, status, lid))
    c.commit()
    row, imgs = fetch_listing(lid)
    return jsonify(listing_dict(row, imgs))


@app.delete("/api/listings/<int:lid>")
@auth
def listing_delete(lid):
    _, e = own_listing(lid)
    if e:
        return e
    c = db.get()
    files = [r["filename"] for r in c.execute("SELECT filename FROM images WHERE listing_id=?", (lid,))]
    c.execute("DELETE FROM listings WHERE id=?", (lid,))
    c.commit()
    for fn in files:
        try:
            os.remove(os.path.join(UPLOADS, fn))
        except OSError:
            pass
    return jsonify({"ok": True})


# ---------- избранное ----------

@app.put("/api/favorites/<int:lid>")
@auth
def fav_add(lid):
    c = db.get()
    if not c.execute("SELECT 1 FROM listings WHERE id=?", (lid,)).fetchone():
        return err("Объявление не найдено", 404)
    c.execute("INSERT OR IGNORE INTO favorites(user_id,listing_id,created_at) VALUES(?,?,?)",
              (current_user()["id"], lid, now()))
    c.commit()
    return jsonify({"favorite": True})


@app.delete("/api/favorites/<int:lid>")
@auth
def fav_remove(lid):
    c = db.get()
    c.execute("DELETE FROM favorites WHERE user_id=? AND listing_id=?", (current_user()["id"], lid))
    c.commit()
    return jsonify({"favorite": False})


# ---------- чаты ----------

def chat_for_user(cid):
    u = current_user()
    row = db.get().execute(
        "SELECT * FROM chats WHERE id=? AND (buyer_id=? OR seller_id=?)", (cid, u["id"], u["id"])).fetchone()
    return row


@app.post("/api/listings/<int:lid>/chat")
@auth
def chat_open(lid):
    c = db.get()
    l = c.execute("SELECT id,user_id,status FROM listings WHERE id=?", (lid,)).fetchone()
    if not l or l["status"] != "active":
        return err("Объявление не найдено", 404)
    u = current_user()
    if l["user_id"] == u["id"]:
        return err("Нельзя писать самому себе")
    c.execute("INSERT OR IGNORE INTO chats(listing_id,buyer_id,seller_id,created_at) VALUES(?,?,?,?)",
              (lid, u["id"], l["user_id"], now()))
    c.commit()
    cid = c.execute("SELECT id FROM chats WHERE listing_id=? AND buyer_id=?", (lid, u["id"])).fetchone()["id"]
    return jsonify({"id": cid})


@app.get("/api/chats")
@auth
def chats_list():
    u = current_user()
    rows = db.get().execute("""
        SELECT ch.id, ch.listing_id, l.title,
          (SELECT filename FROM images WHERE listing_id=l.id ORDER BY position LIMIT 1) AS cover,
          o.id AS other_id, o.name AS other_name,
          (SELECT text FROM messages WHERE chat_id=ch.id ORDER BY id DESC LIMIT 1) AS last_text,
          (SELECT created_at FROM messages WHERE chat_id=ch.id ORDER BY id DESC LIMIT 1) AS last_at,
          (SELECT COUNT(*) FROM messages WHERE chat_id=ch.id AND sender_id!=:uid AND is_read=0) AS unread
        FROM chats ch
        JOIN listings l ON l.id=ch.listing_id
        JOIN users o ON o.id = CASE WHEN ch.buyer_id=:uid THEN ch.seller_id ELSE ch.buyer_id END
        WHERE (ch.buyer_id=:uid OR ch.seller_id=:uid)
          AND EXISTS(SELECT 1 FROM messages WHERE chat_id=ch.id)
        ORDER BY last_at DESC
    """, {"uid": u["id"]}).fetchall()
    return jsonify([{
        "id": r["id"], "listing_id": r["listing_id"], "title": r["title"],
        "cover": f"/uploads/{r['cover']}" if r["cover"] else None,
        "other": {"id": r["other_id"], "name": r["other_name"]},
        "last_text": r["last_text"], "last_at": r["last_at"], "unread": r["unread"],
    } for r in rows])


@app.get("/api/chats/<int:cid>")
@auth
def chat_get(cid):
    ch = chat_for_user(cid)
    if not ch:
        return err("Чат не найден", 404)
    u = current_user()
    other_id = ch["seller_id"] if ch["buyer_id"] == u["id"] else ch["buyer_id"]
    c = db.get()
    o = c.execute("SELECT id,name FROM users WHERE id=?", (other_id,)).fetchone()
    l = c.execute("SELECT id,title,price,status FROM listings WHERE id=?", (ch["listing_id"],)).fetchone()
    return jsonify({"id": cid, "other": {"id": o["id"], "name": o["name"]},
                    "listing": {"id": l["id"], "title": l["title"], "price": l["price"], "status": l["status"]}})


@app.get("/api/chats/<int:cid>/messages")
@auth
def messages_get(cid):
    ch = chat_for_user(cid)
    if not ch:
        return err("Чат не найден", 404)
    u = current_user()
    try:
        after = int(request.args.get("after", 0))
    except ValueError:
        after = 0
    c = db.get()
    rows = c.execute("SELECT * FROM messages WHERE chat_id=? AND id>? ORDER BY id LIMIT 200", (cid, after)).fetchall()
    c.execute("UPDATE messages SET is_read=1 WHERE chat_id=? AND sender_id!=? AND is_read=0", (cid, u["id"]))
    c.commit()
    return jsonify([{"id": m["id"], "mine": m["sender_id"] == u["id"], "text": m["text"], "created_at": m["created_at"]}
                    for m in rows])


@app.post("/api/chats/<int:cid>/messages")
@auth
def message_send(cid):
    ch = chat_for_user(cid)
    if not ch:
        return err("Чат не найден", 404)
    u = current_user()
    text = clean_str((request.get_json(silent=True) or {}).get("text"), 2000)
    if not text:
        return err("Пустое сообщение")
    c = db.get()
    cur = c.execute("INSERT INTO messages(chat_id,sender_id,text,created_at) VALUES(?,?,?,?)", (cid, u["id"], text, now()))
    c.commit()
    other_id = ch["seller_id"] if ch["buyer_id"] == u["id"] else ch["buyer_id"]
    title = c.execute("SELECT title FROM listings WHERE id=?", (ch["listing_id"],)).fetchone()["title"]
    bot.notify(other_id, u["name"], title, text, cid)
    return jsonify({"id": cur.lastrowid, "mine": True, "text": text, "created_at": now()}), 201


@app.get("/api/unread")
@auth
def unread():
    n = db.get().execute("""
        SELECT COUNT(*) FROM messages m JOIN chats ch ON ch.id=m.chat_id
        WHERE (ch.buyer_id=:u OR ch.seller_id=:u) AND m.sender_id!=:u AND m.is_read=0
    """, {"u": current_user()["id"]}).fetchone()[0]
    return jsonify({"unread": n})


# ---------- Telegram ----------

@app.post("/api/telegram/link")
@auth
def tg_link():
    if not bot.enabled():
        return err("Бот не настроен на сервере (LAVKA_TG_TOKEN)", 503)
    c = db.get()
    uid = current_user()["id"]
    c.execute("DELETE FROM tg_codes WHERE user_id=? OR expires<?", (uid, now()))
    code = "".join(secrets.choice(ID_ALPHABET) for _ in range(8))
    c.execute("INSERT INTO tg_codes(code,user_id,expires) VALUES(?,?,?)", (code, uid, now() + 600))
    c.commit()
    link = f"https://t.me/{bot.username}?start={code}" if bot.username else None
    return jsonify({"code": code, "bot": bot.username, "link": link, "expires_in": 600})


@app.delete("/api/telegram/link")
@auth
def tg_unlink():
    c = db.get()
    c.execute("UPDATE users SET tg_chat_id=NULL WHERE id=?", (current_user()["id"],))
    c.commit()
    return jsonify({"linked": False})


# ---------- запуск ----------

db.init()

if __name__ == "__main__":
    bot.start()
    host = os.environ.get("LAVKA_HOST", "0.0.0.0")
    port = int(os.environ.get("LAVKA_PORT", "8080"))
    print(f"Лавка: http://{host}:{port}  (данные: {db.DATA})")
    try:
        from waitress import serve  # нормальный сервер для выхода в интернет
        serve(app, host=host, port=port, threads=8)
    except ImportError:
        print("waitress не установлен, запускаю встроенный сервер Flask (pip install waitress)")
        app.run(host=host, port=port, threaded=True)
