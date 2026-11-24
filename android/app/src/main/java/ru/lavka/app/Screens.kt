@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package ru.lavka.app

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun toast(ctx: android.content.Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

/** Выполняет запрос и показывает ошибку тостом. */
private suspend fun <T> safe(ctx: android.content.Context, block: suspend () -> T): T? =
    try { block() } catch (e: ApiError) { toast(ctx, e.message ?: "Ошибка"); null }

@Composable
fun TopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold) },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

// ================= Главная и списки =================

@Composable
fun HomeScreen(onOpen: (Int) -> Unit, needLogin: () -> Unit) {
    var q by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf("") }
    var free by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = q, onValueChange = { q = it },
            placeholder = { Text("Искать объявления") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            singleLine = true, shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { query = q.trim() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(selected = cat == "", onClick = { cat = "" }, label = { Text("Все") }) }
            items(Session.cats) { c ->
                FilterChip(selected = cat == c.id, onClick = { cat = c.id }, label = { Text("${c.emoji} ${c.name}") })
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(selected = free, onClick = { free = !free }, label = { Text("Бесплатно") }) }
            item { FilterChip(selected = sort == "cheap", onClick = { sort = if (sort == "cheap") "" else "cheap" }, label = { Text("Дешевле") }) }
            item { FilterChip(selected = sort == "expensive", onClick = { sort = if (sort == "expensive") "" else "expensive" }, label = { Text("Дороже") }) }
        }
        ListingGrid(
            key = listOf(query, cat, free, sort),
            fetch = { off -> Api.listings(q = query, category = cat, free = free, sort = sort, offset = off) },
            onOpen = onOpen, needLogin = needLogin, emptyText = "Ничего не найдено",
        )
    }
}

@Composable
fun SimpleListScreen(
    title: String, onBack: (() -> Unit)?, emptyText: String, onOpen: (Int) -> Unit, needLogin: () -> Unit,
    fetch: suspend (Int) -> Pair<List<Listing>, Boolean>,
) {
    Column(Modifier.fillMaxSize()) {
        TopBar(title, onBack)
        ListingGrid(key = listOf(Session.me?.id), fetch = fetch, onOpen = onOpen, needLogin = needLogin, emptyText = emptyText)
    }
}

@Composable
fun ListingGrid(
    key: Any, fetch: suspend (Int) -> Pair<List<Listing>, Boolean>, onOpen: (Int) -> Unit,
    needLogin: () -> Unit, emptyText: String,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember(key) { mutableStateOf<List<Listing>>(emptyList()) }
    var more by remember(key) { mutableStateOf(false) }
    var loading by remember(key) { mutableStateOf(true) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(key, reload) {
        loading = true; error = null
        try {
            val (list, hm) = fetch(0)
            items = list; more = hm
        } catch (e: ApiError) { error = e.message }
        loading = false
    }

    when {
        loading && items.isEmpty() -> Loading()
        error != null -> Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(error!!, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            SecondaryButton("Повторить") { reload++ }
        }
        items.isEmpty() -> Empty("🫙", emptyText)
        else -> LazyVerticalGrid(
            columns = GridCells.Fixed(2), contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items, key = { it.id }) { l ->
                ListingCard(l, onOpen = { onOpen(l.id) }, onFav = {
                    if (Session.me == null) { needLogin(); return@ListingCard }
                    scope.launch {
                        val on = !l.favorite
                        safe(ctx) { Api.setFavorite(l.id, on) } ?: return@launch
                        items = items.map { if (it.id == l.id) it.copy(favorite = on) else it }
                    }
                })
            }
            if (more) item(span = { GridItemSpan(2) }) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    SecondaryButton("Показать ещё") {
                        scope.launch {
                            safe(ctx) { fetch(items.size) }?.let { (list, hm) -> items = items + list; more = hm }
                        }
                    }
                }
            }
        }
    }
}

// ================= Объявление =================

@Composable
fun ListingScreen(id: Int, onBack: () -> Unit, onSeller: (String) -> Unit, onChat: (Int) -> Unit, needLogin: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var l by remember { mutableStateOf<Listing?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(id) {
        try { l = Api.listing(id) } catch (e: ApiError) { error = e.message }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar("Объявление", onBack)
        val cur = l
        when {
            error != null -> Empty("🕳️", error!!)
            cur == null -> Loading()
            else -> Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                if (cur.images.isEmpty()) {
                    Cover(null, cur.category, Modifier.fillMaxWidth().aspectRatio(4f / 3f))
                } else {
                    val pager = rememberPagerState(pageCount = { cur.images.size })
                    Box {
                        HorizontalPager(pager, Modifier.fillMaxWidth().aspectRatio(4f / 3f)) { p ->
                            AsyncImage(cur.images[p], null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.04f)))
                        }
                        if (cur.images.size > 1) Text(
                            "${pager.currentPage + 1}/${cur.images.size}", color = Color.White, fontSize = 12.sp,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)
                                .background(Color(0xAA000000), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(fmtPrice(cur.price), fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = if (cur.price == 0) Ok else MaterialTheme.colorScheme.onBackground)
                    Text(cur.title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    val cat = Session.cats.firstOrNull { it.id == cur.category }
                    Text(
                        "${cat?.let { it.emoji + " " + it.name + " · " } ?: ""}${cur.city.ifEmpty { "город не указан" }} · ${timeAgo(cur.createdAt)} · 👁 ${cur.views}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                    )
                    if (cur.status == "sold") Text("Объявление снято: продано", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(cur.description.ifEmpty { "Без описания" }, modifier = Modifier.padding(vertical = 8.dp))

                    Card(Modifier.fillMaxWidth().clickable { onSeller(cur.sellerId) }, shape = RoundedCornerShape(16.dp)) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Avatar(cur.sellerName)
                            Column {
                                Text(cur.sellerName, fontWeight = FontWeight.Bold)
                                Text(cur.sellerId, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (cur.isMine) {
                        SecondaryButton(if (cur.status == "sold") "Вернуть в продажу" else "Отметить проданным", Modifier.fillMaxWidth()) {
                            scope.launch { safe(ctx) { Api.setSold(id, cur.status != "sold"); l = Api.listing(id) } }
                        }
                        SecondaryButton("Удалить", Modifier.fillMaxWidth(), danger = true) { confirmDelete = true }
                    } else {
                        PrimaryButton("Написать продавцу", modifier = Modifier.fillMaxWidth()) {
                            if (Session.me == null) { needLogin(); return@PrimaryButton }
                            scope.launch { safe(ctx) { Api.openChat(id) }?.let(onChat) }
                        }
                        SecondaryButton(if (cur.favorite) "♥ В избранном" else "♡ В избранное", Modifier.fillMaxWidth()) {
                            if (Session.me == null) { needLogin(); return@SecondaryButton }
                            scope.launch {
                                val on = !cur.favorite
                                safe(ctx) { Api.setFavorite(id, on) } ?: return@launch
                                l = cur.copy(favorite = on)
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Удалить объявление?") }, text = { Text("Это нельзя отменить.") },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                scope.launch { safe(ctx) { Api.deleteListing(id) }?.let { toast(ctx, "Удалено"); onBack() } }
            }) { Text("Удалить", color = Color(0xFFC0392B)) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
    )
}

// ================= Новое объявление =================

@Composable
fun NewListingScreen(onBack: () -> Unit, onCreated: (Int) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("0") }
    var city by remember { mutableStateOf(Session.me?.city ?: "") }
    var cat by remember { mutableStateOf("") }
    var uris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris = it.take(8) }

    Column(Modifier.fillMaxSize()) {
        TopBar("Новое объявление", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(title, { title = it.take(100) }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Категория", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Session.cats) { c ->
                    FilterChip(selected = cat == c.id, onClick = { cat = c.id }, label = { Text("${c.emoji} ${c.name}") })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    price, { price = it.filter(Char::isDigit).take(9) }, label = { Text("Цена, ₽ (0 — бесплатно)") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                )
                OutlinedTextField(city, { city = it.take(60) }, label = { Text("Город") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(desc, { desc = it.take(5000) }, label = { Text("Описание") }, minLines = 4, modifier = Modifier.fillMaxWidth())
            SecondaryButton(if (uris.isEmpty()) "Добавить фото (до 8)" else "Фото: ${uris.size} · изменить", Modifier.fillMaxWidth()) { picker.launch("image/*") }
            if (uris.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(uris) { u -> AsyncImage(u, null, contentScale = ContentScale.Crop, modifier = Modifier.size(76.dp).clip(RoundedCornerShape(10.dp))) }
            }
            PrimaryButton(if (busy) "Публикую…" else "Опубликовать", enabled = !busy && title.trim().length >= 3 && cat.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                busy = true
                scope.launch {
                    val picked = uris.mapIndexedNotNull { i, u -> Api.pick(ctx, u, i) }
                    if (picked.size < uris.size) toast(ctx, "Часть фото не удалось прочитать")
                    val r = safe(ctx) { Api.createListing(title.trim(), desc.trim(), price.toIntOrNull() ?: 0, cat, city.trim(), picked) }
                    busy = false
                    if (r != null) { toast(ctx, "Опубликовано"); onCreated(r.id) }
                }
            }
            if (cat.isEmpty()) Text("Выбери категорию", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ================= Чаты =================

@Composable
fun ChatsScreen(onOpen: (Int) -> Unit) {
    var list by remember { mutableStateOf<List<ChatItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            try { list = Api.chats(); error = null } catch (e: ApiError) { error = e.message }
            delay(5000)
        }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar("Сообщения")
        val l = list
        when {
            error != null && l == null -> Empty("📡", error!!)
            l == null -> Loading()
            l.isEmpty() -> Empty("💬", "Пока нет переписок")
            else -> LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(l, key = { it.id }) { c ->
                    Card(Modifier.fillMaxWidth().clickable { onOpen(c.id) }, shape = RoundedCornerShape(16.dp)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Cover(c.cover, "", Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)))
                            Column(Modifier.weight(1f)) {
                                Text(c.otherName, fontWeight = FontWeight.Bold, maxLines = 1)
                                Text(c.title, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(c.lastText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (c.unread > 0) Box(
                                Modifier.clip(RoundedCornerShape(11.dp)).background(Accent).padding(horizontal = 8.dp, vertical = 2.dp),
                            ) { Text("${c.unread}", color = Color.White, fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatScreen(id: Int, onBack: () -> Unit, onListing: (Int) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<ChatInfo?>(null) }
    var msgs by remember { mutableStateOf<List<Message>>(emptyList()) }
    var text by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(id) {
        try { info = Api.chatInfo(id) } catch (e: ApiError) { toast(ctx, e.message ?: ""); onBack(); return@LaunchedEffect }
        while (true) {
            try {
                val fresh = Api.messages(id, msgs.lastOrNull()?.id ?: 0)
                if (fresh.isNotEmpty()) { msgs = msgs + fresh; Session.unread = runCatching { Api.unread() }.getOrDefault(Session.unread) }
            } catch (_: ApiError) {}
            delay(3000)
        }
    }
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size - 1) }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar(info?.otherName ?: "Чат", onBack)
        info?.let {
            Text(
                "${it.listingTitle} · ${fmtPrice(it.listingPrice)}",
                color = Accent, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clickable { onListing(it.listingId) }.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(msgs, key = { it.id }) { m ->
                Box(Modifier.fillMaxWidth(), contentAlignment = if (m.mine) Alignment.CenterEnd else Alignment.CenterStart) {
                    Column(
                        Modifier.widthIn(max = 290.dp).clip(RoundedCornerShape(16.dp))
                            .background(if (m.mine) Accent else MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(m.text, color = if (m.mine) Color.White else MaterialTheme.colorScheme.onSurface)
                        Text(hhmm(m.createdAt), fontSize = 11.sp, color = (if (m.mine) Color.White else MaterialTheme.colorScheme.onSurface).copy(alpha = 0.65f))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(text, { text = it.take(2000) }, placeholder = { Text("Сообщение") }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), maxLines = 4)
            IconButton(
                onClick = {
                    val t = text.trim(); if (t.isEmpty()) return@IconButton
                    text = ""
                    scope.launch {
                        val m = safe(ctx) { Api.sendMessage(id, t) }
                        if (m == null) text = t else msgs = msgs + m
                    }
                },
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Accent),
            ) { Icon(Icons.AutoMirrored.Filled.Send, "Отправить", tint = Color.White) }
        }
    }
}

// ================= Профиль и вход =================

@Composable
fun ProfileScreen(onMine: () -> Unit, onAuthDone: () -> Unit) {
    val me = Session.me
    if (me == null) AuthScreen(onAuthDone) else ProfileContent(me, onMine)
}

@Composable
private fun ServerField() {
    var url by remember { mutableStateOf(Api.baseUrl) }
    OutlinedTextField(
        url, { url = it; Api.baseUrl = it }, label = { Text("Адрес сервера") }, singleLine = true,
        supportingText = { Text("Например http://192.168.1.100:8080") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun AuthScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var reg by remember { mutableStateOf(false) }
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var newId by remember { mutableStateOf<String?>(null) }
    val clip = LocalClipboardManager.current

    if (newId != null) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Accent).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Твой личный ID", color = Color.White)
                Text(newId!!, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(vertical = 10.dp))
                OutlinedButton(onClick = { clip.setText(AnnotatedString(newId!!)); toast(ctx, "Скопировано") }) { Text("Скопировать", color = Color.White) }
            }
            Text("Запиши его: вход идёт по ID и паролю. Если потеряешь — аккаунт не вернуть.", modifier = Modifier.padding(vertical = 16.dp))
            PrimaryButton("Понятно, поехали", modifier = Modifier.fillMaxWidth()) { onDone() }
        }
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Лавка", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = Accent)
        TabRow(selectedTabIndex = if (reg) 1 else 0, containerColor = Color.Transparent) {
            Tab(selected = !reg, onClick = { reg = false }, text = { Text("Вход") })
            Tab(selected = reg, onClick = { reg = true }, text = { Text("Регистрация") })
        }
        if (reg) {
            OutlinedTextField(name, { name = it.take(40) }, label = { Text("Имя") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(city, { city = it.take(60) }, label = { Text("Город") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        } else {
            OutlinedTextField(id, { id = it.uppercase() }, label = { Text("Твой ID") }, placeholder = { Text("L-XXXX-XXXX") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        OutlinedTextField(
            pass, { pass = it }, label = { Text(if (reg) "Пароль (от 8 символов)" else "Пароль") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
        )
        PrimaryButton(if (busy) "…" else if (reg) "Получить ID" else "Войти", enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            busy = true
            scope.launch {
                if (reg) {
                    safe(ctx) { Api.register(name.trim(), city.trim(), pass) }?.let { (nid, m) -> Session.me = m; newId = nid }
                } else {
                    safe(ctx) { Api.login(id.trim(), pass) }?.let { Session.me = it; onDone() }
                }
                busy = false
            }
        }
        if (reg) Text("Почту и телефон не спрашиваем. Получишь личный ID — по нему и входишь.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        ServerField()
    }
}

@Composable
private fun ProfileContent(me: Me, onMine: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var edit by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf<TgLink?>(null) }

    // пока ждём привязку Telegram — опрашиваем сервер
    LaunchedEffect(link) {
        if (link == null) return@LaunchedEffect
        while (Session.me?.tgLinked == false) {
            delay(3000)
            runCatching { Api.me() }.getOrNull()?.let { Session.me = it }
        }
        link = null
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Avatar(me.name, 52)
                Column(Modifier.weight(1f)) {
                    Text(me.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(me.id, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(me.city.ifEmpty { "город не указан" }, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { edit = true }) { Text("Изменить") }
            }
        }
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Telegram-уведомления", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                when {
                    !me.tgEnabled -> Text("Бот не включён на сервере. Задай LAVKA_TG_TOKEN и перезапусти сервер.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    me.tgLinked -> {
                        Text("✅ Привязан. Новые сообщения придут в Telegram.")
                        SecondaryButton("Отвязать") { scope.launch { safe(ctx) { Api.tgUnlink(); Session.me = Api.me() } } }
                    }
                    else -> {
                        Text("Получай новые сообщения прямо в Telegram.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PrimaryButton("Привязать") {
                            scope.launch {
                                safe(ctx) { Api.tgLink() }?.let {
                                    link = it
                                    it.link?.let { u -> ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }
                                }
                            }
                        }
                        link?.let { Text("Нажми Start в боте. Или отправь ему: /start ${it.code} (код живёт 10 минут)", fontSize = 13.sp) }
                    }
                }
            }
        }
        SecondaryButton("Мои объявления", Modifier.fillMaxWidth(), onClick = onMine)
        SecondaryButton("Выйти", Modifier.fillMaxWidth(), danger = true) {
            scope.launch { Api.logout(); Session.me = null; Session.unread = 0 }
        }
        ServerField()
    }

    if (edit) {
        var name by remember { mutableStateOf(me.name) }
        var city by remember { mutableStateOf(me.city) }
        AlertDialog(
            onDismissRequest = { edit = false },
            title = { Text("Профиль") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it.take(40) }, label = { Text("Имя") }, singleLine = true)
                    OutlinedTextField(city, { city = it.take(60) }, label = { Text("Город") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { safe(ctx) { Api.updateMe(name.trim(), city.trim()) }?.let { Session.me = it; edit = false } }
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { edit = false }) { Text("Отмена") } },
        )
    }
}

@Composable
fun SellerScreen(uid: String, onBack: () -> Unit, onOpen: (Int) -> Unit, needLogin: () -> Unit) {
    SimpleListScreen("Продавец $uid", onBack, "Активных объявлений нет", onOpen, needLogin) { off -> Api.listings(user = uid, offset = off) }
}
