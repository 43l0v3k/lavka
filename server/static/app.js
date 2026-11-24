"use strict";
// ---------- утилиты ----------
const $ = (s, r = document) => r.querySelector(s);
const view = $("#view");
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const fmtPrice = (p) => (p === 0 ? "Бесплатно" : p.toLocaleString("ru-RU") + " ₽");
const timeAgo = (ts) => {
  const d = Math.floor(Date.now() / 1000) - ts;
  if (d < 60) return "только что";
  if (d < 3600) return Math.floor(d / 60) + " мин назад";
  if (d < 86400) return Math.floor(d / 3600) + " ч назад";
  const dt = new Date(ts * 1000);
  return dt.toLocaleDateString("ru-RU", { day: "numeric", month: "short" });
};
const hhmm = (ts) => new Date(ts * 1000).toLocaleTimeString("ru-RU", { hour: "2-digit", minute: "2-digit" });

let toastT;
function toast(msg) {
  const t = $("#toast");
  t.textContent = msg; t.classList.add("show");
  clearTimeout(toastT); toastT = setTimeout(() => t.classList.remove("show"), 2600);
}

const store = {
  get token() { try { return localStorage.getItem("lavka_token"); } catch { return null; } },
  set token(v) { try { v ? localStorage.setItem("lavka_token", v) : localStorage.removeItem("lavka_token"); } catch {} },
};
let me = null, cats = [];

async function api(path, { method = "GET", json, form } = {}) {
  const headers = {};
  if (store.token) headers.Authorization = "Bearer " + store.token;
  let body;
  if (json !== undefined) { headers["Content-Type"] = "application/json"; body = JSON.stringify(json); }
  if (form) body = form;
  let r;
  try { r = await fetch("/api" + path, { method, headers, body }); }
  catch { throw new Error("Нет связи с сервером"); }
  const data = await r.json().catch(() => ({}));
  if (r.status === 401 && store.token && path !== "/login") { store.token = null; me = null; renderNav(); }
  if (!r.ok) { const e = new Error(data.error || "Ошибка " + r.status); e.status = r.status; throw e; }
  return data;
}

async function loadMe() {
  me = null;
  if (store.token) { try { me = await api("/me"); } catch {} }
  renderNav();
}
function renderNav() {
  $("#navMe").textContent = me ? me.name.split(" ")[0] : "Войти";
}
async function pollUnread() {
  if (!me) { ["#badge", "#badge2"].forEach((s) => ($(s).hidden = true)); return; }
  try {
    const { unread } = await api("/unread");
    ["#badge", "#badge2"].forEach((s) => { $(s).hidden = !unread; $(s).textContent = unread > 99 ? "99+" : unread; });
  } catch {}
}
function requireAuth() {
  if (me) return true;
  sessionStorage.setItem("lavka_back", location.hash);
  location.hash = "#/me";
  toast("Сначала войди");
  return false;
}

// ---------- карточки ----------
const emoji = (id) => (cats.find((c) => c.id === id) || {}).emoji || "📦";
function card(l) {
  const img = l.cover ? `style="background-image:url('${esc(l.cover)}')"` : "";
  return `<a class="lc" href="#/l/${l.id}">
    <div class="lc-img" ${img}>${l.cover ? "" : emoji(l.category)}</div>
    ${l.status === "sold" ? '<span class="badge-sold">Продано</span>' : ""}
    <button class="heart ${l.favorite ? "on" : ""}" data-fav="${l.id}" aria-label="В избранное">${l.favorite ? "♥" : "♡"}</button>
    <div class="lc-body">
      <div class="price ${l.price === 0 ? "free" : ""}">${fmtPrice(l.price)}</div>
      <div class="lc-title">${esc(l.title)}</div>
      <div class="lc-meta">${esc(l.city || "—")} · ${timeAgo(l.created_at)}</div>
    </div></a>`;
}
view.addEventListener("click", async (e) => {
  const h = e.target.closest("[data-fav]");
  if (!h) return;
  e.preventDefault(); e.stopPropagation();
  if (!requireAuth()) return;
  const on = !h.classList.contains("on");
  try {
    await api("/favorites/" + h.dataset.fav, { method: on ? "PUT" : "DELETE" });
    h.classList.toggle("on", on); h.textContent = on ? "♥" : "♡";
  } catch (er) { toast(er.message); }
});

// ---------- главная ----------
const feed = { q: "", category: "", sort: "new", free: false, min: "", max: "", city: "", items: [], more: false };

async function pageHome() {
  view.innerHTML = `
    <div class="chips" id="chips"></div>
    <div class="filters">
      <select id="fSort"><option value="new">Сначала новые</option><option value="cheap">Сначала дешёвые</option><option value="expensive">Сначала дорогие</option></select>
      <input id="fMin" type="number" min="0" placeholder="от ₽"><input id="fMax" type="number" min="0" placeholder="до ₽">
      <input id="fCity" placeholder="Город">
      <label><input type="checkbox" id="fFree"> Бесплатно</label>
    </div>
    <div id="list"></div>`;
  const chips = $("#chips");
  chips.innerHTML = `<button class="chip ${feed.category ? "" : "on"}" data-c="">Все</button>` +
    cats.map((c) => `<button class="chip ${feed.category === c.id ? "on" : ""}" data-c="${c.id}">${c.emoji} ${esc(c.name)}</button>`).join("");
  chips.onclick = (e) => { const b = e.target.closest("[data-c]"); if (b) { feed.category = b.dataset.c; pageHome(); } };
  $("#fSort").value = feed.sort; $("#fMin").value = feed.min; $("#fMax").value = feed.max; $("#fCity").value = feed.city; $("#fFree").checked = feed.free;
  const apply = () => { feed.sort = $("#fSort").value; feed.min = $("#fMin").value; feed.max = $("#fMax").value; feed.city = $("#fCity").value.trim(); feed.free = $("#fFree").checked; loadFeed(true); };
  ["#fSort", "#fFree"].forEach((s) => $(s).addEventListener("change", apply));
  ["#fMin", "#fMax", "#fCity"].forEach((s) => $(s).addEventListener("change", apply));
  $("#searchInput").value = feed.q;
  loadFeed(true);
}
async function loadFeed(reset) {
  const box = $("#list");
  if (reset) { feed.items = []; box.innerHTML = '<div class="spinner">Загружаю…</div>'; }
  const p = new URLSearchParams({ limit: 24, offset: feed.items.length });
  if (feed.q) p.set("q", feed.q);
  if (feed.category) p.set("category", feed.category);
  if (feed.sort !== "new") p.set("sort", feed.sort);
  if (feed.free) p.set("free", "1");
  if (feed.min) p.set("min", feed.min);
  if (feed.max) p.set("max", feed.max);
  if (feed.city) p.set("city", feed.city);
  try {
    const r = await api("/listings?" + p);
    feed.items = feed.items.concat(r.items); feed.more = r.has_more;
    if (!$("#list")) return;
    box.innerHTML = feed.items.length
      ? `<div class="grid">${feed.items.map(card).join("")}</div>${feed.more ? '<p style="text-align:center"><button class="btn" id="more">Показать ещё</button></p>' : ""}`
      : '<div class="empty"><big>🔍</big>Ничего не найдено. Попробуй другой запрос.</div>';
    const m = $("#more"); if (m) m.onclick = () => loadFeed(false);
  } catch (e) { box.innerHTML = `<div class="empty">${esc(e.message)}</div>`; }
}
$("#searchForm").addEventListener("submit", (e) => {
  e.preventDefault(); feed.q = $("#searchInput").value.trim();
  if (location.hash === "#/" || location.hash === "") pageHome(); else location.hash = "#/";
});

// ---------- список с готовым запросом (избранное / мои) ----------
async function pageList(title, query, emptyText) {
  view.innerHTML = `<h1>${title}</h1><div id="list"><div class="spinner">Загружаю…</div></div>`;
  try {
    const r = await api("/listings?limit=50&" + query);
    $("#list").innerHTML = r.items.length ? `<div class="grid">${r.items.map(card).join("")}</div>` : `<div class="empty"><big>🫙</big>${emptyText}</div>`;
  } catch (e) { $("#list").innerHTML = `<div class="empty">${esc(e.message)}</div>`; }
}

// ---------- объявление ----------
async function pageListing(id) {
  view.innerHTML = '<div class="spinner">Загружаю…</div>';
  let l;
  try { l = await api("/listings/" + id); } catch (e) { view.innerHTML = `<div class="empty"><big>🕳️</big>${esc(e.message)}</div>`; return; }
  const cat = cats.find((c) => c.id === l.category);
  view.innerHTML = `
    <div class="detail">
      <div>
        <div class="gallery">
          <div class="main" id="mainImg">${l.images.length ? "" : emoji(l.category)}</div>
          ${l.images.length > 1 ? `<div class="thumbs">${l.images.map((u, i) => `<button data-i="${i}" class="${i ? "" : "on"}" style="background-image:url('${esc(u)}')" aria-label="Фото ${i + 1}"></button>`).join("")}</div>` : ""}
        </div>
        <h1 style="margin-top:20px">${esc(l.title)}</h1>
        <div class="muted">${cat ? cat.emoji + " " + esc(cat.name) + " · " : ""}${esc(l.city || "город не указан")} · ${timeAgo(l.created_at)} · 👁 ${l.views}</div>
        <div class="descr">${esc(l.description) || '<span class="muted">Без описания</span>'}</div>
      </div>
      <aside class="side">
        <div class="card">
          <div class="price ${l.price === 0 ? "free" : ""}" style="font-size:30px">${fmtPrice(l.price)}</div>
          ${l.status === "sold" ? '<p class="muted">Объявление снято: продано</p>' : ""}
          <div style="display:grid;gap:8px;margin-top:14px" id="actions"></div>
        </div>
        <a class="card seller" href="#/u/${esc(l.seller.id)}">
          <div class="avatar">${esc(l.seller.name.slice(0, 1).toUpperCase())}</div>
          <div><b>${esc(l.seller.name)}</b><div class="uid">${esc(l.seller.id)}</div></div>
        </a>
      </aside>
    </div>`;
  const main = $("#mainImg");
  const show = (i) => { main.style.backgroundImage = `url('${l.images[i]}')`; document.querySelectorAll(".thumbs button").forEach((b, j) => b.classList.toggle("on", i === j)); };
  if (l.images.length) show(0);
  document.querySelectorAll(".thumbs button").forEach((b) => (b.onclick = () => show(+b.dataset.i)));
  const act = $("#actions");
  if (l.is_mine) {
    act.innerHTML = `<button class="btn" id="bSold">${l.status === "sold" ? "Вернуть в продажу" : "Отметить проданным"}</button><button class="btn btn-danger" id="bDel">Удалить</button>`;
    $("#bSold").onclick = async () => { try { await api("/listings/" + id, { method: "PUT", json: { status: l.status === "sold" ? "active" : "sold" } }); pageListing(id); } catch (e) { toast(e.message); } };
    $("#bDel").onclick = async () => { if (!confirm("Удалить объявление навсегда?")) return; try { await api("/listings/" + id, { method: "DELETE" }); toast("Удалено"); location.hash = "#/me"; } catch (e) { toast(e.message); } };
  } else {
    act.innerHTML = `<button class="btn btn-accent" id="bChat">Написать продавцу</button><button class="btn" id="bFav">${l.favorite ? "♥ В избранном" : "♡ В избранное"}</button>`;
    $("#bChat").onclick = async () => { if (!requireAuth()) return; try { const c = await api(`/listings/${id}/chat`, { method: "POST" }); location.hash = "#/chat/" + c.id; } catch (e) { toast(e.message); } };
    $("#bFav").onclick = async () => { if (!requireAuth()) return; try { await api("/favorites/" + id, { method: l.favorite ? "DELETE" : "PUT" }); l.favorite = !l.favorite; $("#bFav").textContent = l.favorite ? "♥ В избранном" : "♡ В избранное"; } catch (e) { toast(e.message); } };
  }
}

async function pageSeller(uid) {
  view.innerHTML = `<h1 class="uid" style="font-size:20px">Объявления · ${esc(uid)}</h1><div id="list"><div class="spinner">Загружаю…</div></div>`;
  try {
    const r = await api("/listings?limit=50&user=" + encodeURIComponent(uid));
    $("#list").innerHTML = r.items.length ? `<div class="grid">${r.items.map(card).join("")}</div>` : '<div class="empty"><big>🫙</big>Активных объявлений нет</div>';
  } catch (e) { $("#list").innerHTML = `<div class="empty">${esc(e.message)}</div>`; }
}

// ---------- новое объявление ----------
function pageNew() {
  if (!requireAuth()) return;
  view.innerHTML = `
    <form class="card narrow" id="nf" style="max-width:640px">
      <h2>Новое объявление</h2>
      <label class="field"><span>Название</span><input name="title" maxlength="100" required placeholder="Например, велосипед Stels"></label>
      <div class="row">
        <label class="field"><span>Категория</span><select name="category" required>${cats.map((c) => `<option value="${c.id}">${c.emoji} ${esc(c.name)}</option>`).join("")}</select></label>
        <label class="field"><span>Цена, ₽ (0 — бесплатно)</span><input name="price" type="number" min="0" value="0" required></label>
      </div>
      <label class="field"><span>Город</span><input name="city" maxlength="60" value="${esc(me.city)}" placeholder="Краснодар"></label>
      <label class="field"><span>Описание</span><textarea name="description" maxlength="5000" placeholder="Состояние, комплект, где забрать…"></textarea></label>
      <label class="field"><span>Фото (до 8 штук)</span><input type="file" name="images" accept="image/*" multiple id="files"></label>
      <div class="previews" id="prev"></div>
      <div class="err" id="er"></div>
      <button class="btn btn-accent btn-block" id="go">Опубликовать</button>
    </form>`;
  $("#files").onchange = (e) => {
    const box = $("#prev"); box.innerHTML = "";
    [...e.target.files].slice(0, 8).forEach((f) => { const d = document.createElement("div"); d.style.backgroundImage = `url('${URL.createObjectURL(f)}')`; box.append(d); });
  };
  $("#nf").onsubmit = async (e) => {
    e.preventDefault();
    const fd = new FormData(e.target);
    const files = $("#files").files;
    fd.delete("images");
    [...files].slice(0, 8).forEach((f) => fd.append("images", f));
    $("#go").disabled = true; $("#er").textContent = "";
    try { const l = await api("/listings", { method: "POST", form: fd }); toast("Опубликовано"); location.hash = "#/l/" + l.id; }
    catch (er) { $("#er").textContent = er.message; $("#go").disabled = false; }
  };
}

// ---------- чаты ----------
async function pageChats() {
  if (!requireAuth()) return;
  view.innerHTML = '<h1>Сообщения</h1><div id="cl" class="chatlist"><div class="spinner">Загружаю…</div></div>';
  try {
    const list = await api("/chats");
    $("#cl").innerHTML = list.length ? list.map((c) => `
      <a class="ci" href="#/chat/${c.id}">
        <div class="ph" ${c.cover ? `style="background-image:url('${esc(c.cover)}')"` : ""}>${c.cover ? "" : "📦"}</div>
        <div class="tx"><b>${esc(c.other.name)}</b><div class="muted">${esc(c.title)}</div><div>${esc(c.last_text)}</div></div>
        ${c.unread ? `<span class="un">${c.unread}</span>` : ""}
      </a>`).join("") : '<div class="empty"><big>💬</big>Пока нет переписок</div>';
  } catch (e) { $("#cl").innerHTML = `<div class="empty">${esc(e.message)}</div>`; }
}

let chatTimer;
async function pageChat(cid) {
  if (!requireAuth()) return;
  let info;
  try { info = await api("/chats/" + cid); } catch (e) { view.innerHTML = `<div class="empty"><big>🕳️</big>${esc(e.message)}</div>`; return; }
  view.innerHTML = `
    <div class="chat">
      <div class="chat-head"><a href="#/chats" class="muted">← Все чаты</a><div><b>${esc(info.other.name)}</b></div>
        <a href="#/l/${info.listing.id}" class="muted">${esc(info.listing.title)} · ${fmtPrice(info.listing.price)}</a></div>
      <div class="msgs" id="msgs"></div>
      <form class="composer" id="cf"><input id="ct" maxlength="2000" autocomplete="off" placeholder="Сообщение" required><button class="btn btn-accent">➤</button></form>
    </div>`;
  const box = $("#msgs"); let last = 0;
  const add = (m) => {
    const d = document.createElement("div"); d.className = "m" + (m.mine ? " mine" : "");
    d.append(document.createTextNode(m.text));
    const t = document.createElement("time"); t.textContent = hhmm(m.created_at); d.append(t);
    box.append(d); last = Math.max(last, m.id || 0);
  };
  const pull = async () => {
    if (!$("#msgs")) return;
    try {
      const ms = await api(`/chats/${cid}/messages?after=${last}`);
      const stick = box.scrollHeight - box.scrollTop - box.clientHeight < 80 || !last;
      ms.forEach(add);
      if (ms.length && stick) box.scrollTop = box.scrollHeight;
      if (ms.length) pollUnread();
    } catch {}
  };
  await pull(); box.scrollTop = box.scrollHeight;
  clearInterval(chatTimer); chatTimer = setInterval(pull, 3000);
  $("#cf").onsubmit = async (e) => {
    e.preventDefault();
    const inp = $("#ct"), text = inp.value.trim(); if (!text) return;
    inp.value = "";
    try { const m = await api(`/chats/${cid}/messages`, { method: "POST", json: { text } }); add(m); box.scrollTop = box.scrollHeight; }
    catch (er) { toast(er.message); inp.value = text; }
  };
}

// ---------- вход / профиль ----------
function pageAuth() {
  let mode = "login";
  const draw = () => {
    view.innerHTML = `
      <form class="card narrow" id="af">
        <div class="tabs"><button type="button" data-m="login" class="${mode === "login" ? "on" : ""}">Вход</button><button type="button" data-m="reg" class="${mode === "reg" ? "on" : ""}">Регистрация</button></div>
        ${mode === "login"
          ? `<label class="field"><span>Твой ID</span><input name="id" placeholder="L-XXXX-XXXX" autocomplete="username" autocapitalize="characters" required></label>`
          : `<label class="field"><span>Имя</span><input name="name" maxlength="40" autocomplete="name" required></label>
             <label class="field"><span>Город</span><input name="city" maxlength="60" placeholder="Краснодар"></label>`}
        <label class="field"><span>Пароль${mode === "reg" ? " (от 8 символов)" : ""}</span><input name="password" type="password" minlength="${mode === "reg" ? 8 : 1}" autocomplete="${mode === "reg" ? "new-password" : "current-password"}" required></label>
        <div class="err" id="er"></div>
        <button class="btn btn-accent btn-block">${mode === "login" ? "Войти" : "Получить ID"}</button>
        ${mode === "reg" ? '<p class="muted" style="font-size:13px">Почту и телефон не спрашиваем. Ты получишь личный ID — по нему и входишь. Восстановить пароль без ID нельзя, сохрани его.</p>' : ""}
      </form>`;
    view.querySelectorAll(".tabs button").forEach((b) => (b.onclick = () => { mode = b.dataset.m; draw(); }));
    $("#af").onsubmit = async (e) => {
      e.preventDefault();
      const j = Object.fromEntries(new FormData(e.target));
      try {
        const r = await api(mode === "login" ? "/login" : "/register", { method: "POST", json: j });
        store.token = r.token; await loadMe(); pollUnread();
        if (mode === "reg") return showNewId(r.id);
        goBack();
      } catch (er) { $("#er").textContent = er.message; }
    };
  };
  draw();
}
function goBack() { const b = sessionStorage.getItem("lavka_back"); sessionStorage.removeItem("lavka_back"); location.hash = b && b !== "#/me" ? b : "#/"; }
function showNewId(id) {
  view.innerHTML = `
    <div class="card narrow">
      <div class="idcard"><div>Твой личный ID</div><div class="big">${esc(id)}</div>
        <button class="btn" id="cp">Скопировать</button></div>
      <p>Запиши его: <b>вход идёт по ID и паролю</b>. Если потеряешь — аккаунт не вернуть.</p>
      <button class="btn btn-accent btn-block" id="ok">Понятно, поехали</button>
    </div>`;
  $("#cp").onclick = () => navigator.clipboard?.writeText(id).then(() => toast("Скопировано"), () => toast("Выдели и скопируй вручную"));
  $("#ok").onclick = goBack;
}

async function pageProfile() {
  if (!me) return pageAuth();
  view.innerHTML = '<div class="spinner">Загружаю…</div>';
  await loadMe(); if (!me) return pageAuth();
  const tg = me.telegram;
  view.innerHTML = `
    <div class="narrow" style="max-width:640px;display:grid;gap:16px">
      <div class="card seller"><div class="avatar">${esc(me.name.slice(0, 1).toUpperCase())}</div>
        <div style="flex:1"><b>${esc(me.name)}</b><div class="uid">${esc(me.id)}</div><div class="muted">${esc(me.city || "город не указан")}</div></div>
        <button class="btn" id="bEdit">Изменить</button></div>
      <div class="card"><h2>Telegram-уведомления</h2>
        ${!tg.enabled ? '<p class="muted">Бот не включён на сервере. Задай LAVKA_TG_TOKEN и перезапусти сервер.</p>'
          : tg.linked ? '<p>✅ Привязан. Новые сообщения придут в Telegram.</p><button class="btn" id="bUnlink">Отвязать</button>'
          : '<p class="muted">Получай новые сообщения прямо в Telegram.</p><button class="btn btn-accent" id="bLink">Привязать</button><div id="linkBox"></div>'}
      </div>
      <div class="row"><a class="btn" href="#/mine">Мои объявления</a><button class="btn btn-danger" id="bOut">Выйти</button></div>
    </div>`;
  $("#bEdit").onclick = async () => {
    const name = prompt("Имя", me.name); if (name === null) return;
    const city = prompt("Город", me.city); if (city === null) return;
    try { await api("/me", { method: "PATCH", json: { name, city } }); await loadMe(); pageProfile(); } catch (e) { toast(e.message); }
  };
  $("#bOut").onclick = async () => { try { await api("/logout", { method: "POST" }); } catch {} store.token = null; me = null; renderNav(); pollUnread(); location.hash = "#/"; };
  const un = $("#bUnlink"); if (un) un.onclick = async () => { await api("/telegram/link", { method: "DELETE" }); pageProfile(); };
  const ln = $("#bLink"); if (ln) ln.onclick = async () => {
    try {
      const r = await api("/telegram/link", { method: "POST" });
      $("#linkBox").innerHTML = `<p>Открой бота и нажми Start: <a href="${esc(r.link)}" target="_blank" rel="noopener"><b>@${esc(r.bot)}</b></a><br><span class="muted">Или отправь боту: <code>/start ${esc(r.code)}</code> (код живёт 10 минут)</span></p>`;
      const t = setInterval(async () => { if (!$("#linkBox")) return clearInterval(t); await loadMe(); if (me && me.telegram.linked) { clearInterval(t); toast("Telegram привязан"); pageProfile(); } }, 3000);
    } catch (e) { toast(e.message); }
  };
}

// ---------- роутер ----------
async function route() {
  clearInterval(chatTimer);
  const h = location.hash.replace(/^#/, "") || "/";
  const [, a, b] = h.split("/");
  document.querySelectorAll(".tabbar a").forEach((x) => x.classList.toggle("on", x.dataset.t === ({ "": "home", l: "home", favs: "favs", chats: "chats", chat: "chats", me: "me", mine: "me", new: "new" }[a || ""] ?? "home")));
  window.scrollTo(0, 0);
  if (a === "l" && b) return pageListing(+b);
  if (a === "u" && b) return pageSeller(b);
  if (a === "chat" && b) return pageChat(+b);
  switch (a || "") {
    case "": return pageHome();
    case "new": return pageNew();
    case "favs": return requireAuth() && pageList("Избранное", "favorites=1", "Тут появится то, что ты отметишь ♡");
    case "mine": return requireAuth() && pageList("Мои объявления", "mine=1", "Ты ещё ничего не подавал(а)");
    case "chats": return pageChats();
    case "me": return pageProfile();
    default: return pageHome();
  }
}
window.addEventListener("hashchange", route);

(async () => {
  try { cats = await api("/categories"); } catch {}
  await loadMe();
  route();
  pollUnread(); setInterval(pollUnread, 15000);
})();
