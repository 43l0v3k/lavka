"""Быстрая проверка API: python test_api.py  (использует временную базу)"""
import io
import os
import sys
import tempfile

os.environ["LAVKA_DATA"] = tempfile.mkdtemp(prefix="lavka-test-")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import app as A  # noqa: E402

PNG = b"\x89PNG\r\n\x1a\n" + b"\0" * 32
cl = A.app.test_client()


def H(tok):
    return {"Authorization": f"Bearer {tok}"}


def check(cond, msg):
    print(("OK   " if cond else "FAIL ") + msg)
    if not cond:
        sys.exit(1)


# регистрация / вход
r = cl.post("/api/register", json={"name": "Аня", "password": "12345678", "city": "Краснодар"})
check(r.status_code == 201 and r.json["id"].startswith("L-"), f"регистрация, ID={r.json['id']}")
anya, anya_tok = r.json["id"], r.json["token"]
r = cl.post("/api/register", json={"name": "Боря", "password": "12345678"})
bob, bob_tok = r.json["id"], r.json["token"]
check(cl.post("/api/register", json={"name": "X", "password": "12345678"}).status_code == 400, "короткое имя отклонено")
check(cl.post("/api/register", json={"name": "Ок", "password": "123"}).status_code == 400, "короткий пароль отклонён")
check(cl.post("/api/login", json={"id": anya, "password": "wrong"}).status_code == 401, "неверный пароль → 401")
r = cl.post("/api/login", json={"id": anya.lower().replace("-", " "), "password": "12345678"})
check(r.status_code == 200, "вход по ID в другом написании")
check(cl.get("/api/me").status_code == 401, "без токена /me → 401")
check(cl.get("/api/me", headers=H(anya_tok)).json["name"] == "Аня", "/me")

# объявления
r = cl.post("/api/listings", headers=H(anya_tok), data={
    "title": "Велосипед Stels", "description": "Почти новый", "price": "7500", "category": "hobby",
    "images": [(io.BytesIO(PNG), "a.png"), (io.BytesIO(PNG), "b.png")]}, content_type="multipart/form-data")
check(r.status_code == 201 and len(r.json["images"]) == 2, "объявление с 2 фото")
lid = r.json["id"]
r = cl.post("/api/listings", headers=H(anya_tok), data={
    "title": "Отдам диван", "price": "0", "category": "home"}, content_type="multipart/form-data")
check(r.status_code == 201 and r.json["price"] == 0, "бесплатное объявление")
free_id = r.json["id"]
r = cl.post("/api/listings", headers=H(anya_tok), data={
    "title": "Фейк", "price": "1", "category": "home", "images": [(io.BytesIO(b"<script>alert(1)</script>"), "x.png")]},
    content_type="multipart/form-data")
check(r.status_code == 400, "не-картинка отклонена по содержимому")

# поиск (кириллица, регистр)
r = cl.get("/api/listings?q=ВЕЛОСИПЕД")
check(len(r.json["items"]) == 1 and r.json["items"][0]["id"] == lid, "поиск без учёта регистра кириллицы")
check(len(cl.get("/api/listings?free=1").json["items"]) == 1, "фильтр «бесплатно»")
check(len(cl.get("/api/listings?category=home").json["items"]) == 1, "фильтр категории")
check(len(cl.get("/api/listings?q=%25").json["items"]) == 0, "спецсимвол % экранируется")
check(cl.get("/api/listings?sort=cheap").json["items"][0]["price"] == 0, "сортировка по цене")

# права
check(cl.put(f"/api/listings/{lid}", headers=H(bob_tok), json={"title": "взлом"}).status_code == 403, "чужое объявление не править")
check(cl.delete(f"/api/listings/{lid}", headers=H(bob_tok)).status_code == 403, "чужое не удалить")

# избранное
check(cl.put(f"/api/favorites/{lid}", headers=H(bob_tok)).status_code == 200, "в избранное")
r = cl.get("/api/listings?favorites=1", headers=H(bob_tok))
check(len(r.json["items"]) == 1 and r.json["items"][0]["favorite"], "список избранного")

# чат
check(cl.post(f"/api/listings/{lid}/chat", headers=H(anya_tok)).status_code == 400, "самому себе писать нельзя")
cid = cl.post(f"/api/listings/{lid}/chat", headers=H(bob_tok)).json["id"]
check(cl.post(f"/api/listings/{lid}/chat", headers=H(bob_tok)).json["id"] == cid, "чат создаётся один раз")
check(len(cl.get("/api/chats", headers=H(bob_tok)).json) == 0, "пустой чат не в списке")
cl.post(f"/api/chats/{cid}/messages", headers=H(bob_tok), json={"text": "Привет, ещё продаёшь?"})
check(cl.get("/api/unread", headers=H(anya_tok)).json["unread"] == 1, "у продавца 1 непрочитанное")
check(cl.get("/api/unread", headers=H(bob_tok)).json["unread"] == 0, "у автора 0 непрочитанных")
m = cl.get(f"/api/chats/{cid}/messages", headers=H(anya_tok)).json
check(len(m) == 1 and not m[0]["mine"], "продавец видит сообщение")
check(cl.get("/api/unread", headers=H(anya_tok)).json["unread"] == 0, "прочитано")
cl.post(f"/api/chats/{cid}/messages", headers=H(anya_tok), json={"text": "Да!"})
after = m[-1]["id"]
check(len(cl.get(f"/api/chats/{cid}/messages?after={after}", headers=H(bob_tok)).json) == 1, "polling after=")
check(len(cl.get("/api/chats", headers=H(anya_tok)).json) == 1, "чат в списке продавца")

# третий лишний
r = cl.post("/api/register", json={"name": "Витя", "password": "12345678"})
check(cl.get(f"/api/chats/{cid}/messages", headers=H(r.json["token"])).status_code == 404, "чужой чат недоступен")

# telegram выключен
check(cl.post("/api/telegram/link", headers=H(anya_tok)).status_code == 503, "TG выключен → 503 (токена нет)")

# продано и удаление
check(cl.put(f"/api/listings/{lid}", headers=H(anya_tok), json={"status": "sold"}).json["status"] == "sold", "пометить проданным")
check(cl.get(f"/api/listings/{lid}", headers=H(bob_tok)).status_code == 404, "проданное скрыто от других")
check(len(cl.get("/api/listings?mine=1", headers=H(anya_tok)).json["items"]) == 2, "мои объявления включают проданные")
files_before = len(os.listdir(A.UPLOADS))
check(cl.delete(f"/api/listings/{lid}", headers=H(anya_tok)).status_code == 200, "удаление")
check(len(os.listdir(A.UPLOADS)) == files_before - 2, "файлы фото удалены с диска")

# выход
check(cl.post("/api/logout", headers=H(anya_tok)).status_code == 200, "logout")
check(cl.get("/api/me", headers=H(anya_tok)).status_code == 401, "токен после logout не работает")
print("\nВсе проверки пройдены")
