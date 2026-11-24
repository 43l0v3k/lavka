package ru.lavka.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

class ApiError(message: String, val code: Int) : Exception(message)

data class Category(val id: String, val name: String, val emoji: String)

data class Listing(
    val id: Int,
    val title: String,
    val description: String,
    val price: Int,
    val category: String,
    val city: String,
    val status: String,
    val views: Int,
    val createdAt: Long,
    val sellerId: String,
    val sellerName: String,
    val favorite: Boolean,
    val isMine: Boolean,
    val cover: String?,
    val images: List<String>,
)

data class ChatItem(
    val id: Int, val title: String, val cover: String?, val otherName: String,
    val lastText: String, val unread: Int,
)

data class ChatInfo(val id: Int, val otherName: String, val listingId: Int, val listingTitle: String, val listingPrice: Int)

data class Message(val id: Int, val mine: Boolean, val text: String, val createdAt: Long)

data class Me(val id: String, val name: String, val city: String, val tgEnabled: Boolean, val tgLinked: Boolean)

data class TgLink(val code: String, val bot: String?, val link: String?)

class Picked(val bytes: ByteArray, val name: String)

object Api {
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("lavka", Context.MODE_PRIVATE)
    }

    var baseUrl: String
        get() = prefs.getString("url", "http://192.168.1.100:8080")!!
        set(v) = prefs.edit().putString("url", v.trim().trimEnd('/')).apply()

    var token: String?
        get() = prefs.getString("token", null)
        set(v) = prefs.edit().apply { if (v == null) remove("token") else putString("token", v) }.apply()

    fun abs(path: String?): String? = path?.let { if (it.startsWith("http")) it else baseUrl + it }

    private suspend fun call(
        method: String,
        path: String,
        json: JSONObject? = null,
        multipart: Pair<Map<String, String>, List<Picked>>? = null,
    ): String = withContext(Dispatchers.IO) {
        val conn = try {
            URL("$baseUrl/api$path").openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw ApiError("Неверный адрес сервера", 0)
        }
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            when {
                json != null -> {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.outputStream.use { it.write(json.toString().toByteArray()) }
                }
                multipart != null -> {
                    val boundary = "----lavka" + UUID.randomUUID().toString().replace("-", "")
                    conn.doOutput = true
                    conn.setChunkedStreamingMode(64 * 1024)
                    conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                    conn.outputStream.use { out ->
                        for ((k, v) in multipart.first) {
                            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n".toByteArray())
                            out.write(v.toByteArray()); out.write("\r\n".toByteArray())
                        }
                        for (f in multipart.second) {
                            out.write(("--$boundary\r\nContent-Disposition: form-data; name=\"images\"; filename=\"${f.name}\"\r\n" +
                                "Content-Type: image/jpeg\r\n\r\n").toByteArray())
                            out.write(f.bytes); out.write("\r\n".toByteArray())
                        }
                        out.write("--$boundary--\r\n".toByteArray())
                    }
                }
            }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code >= 400) {
                val msg = try { JSONObject(text).optString("error") } catch (_: Exception) { "" }
                if (code == 401 && path != "/login") token = null
                throw ApiError(msg.ifEmpty { "Ошибка $code" }, code)
            }
            text
        } catch (e: ApiError) {
            throw e
        } catch (e: Exception) {
            throw ApiError("Нет связи с сервером. Проверь адрес и что сервер запущен", 0)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseListing(o: JSONObject): Listing {
        val imgs = o.optJSONArray("images")
        val images = if (imgs == null) emptyList() else List(imgs.length()) { abs(imgs.getString(it))!! }
        val seller = o.getJSONObject("seller")
        return Listing(
            id = o.getInt("id"), title = o.getString("title"), description = o.optString("description"),
            price = o.getInt("price"), category = o.getString("category"), city = o.optString("city"),
            status = o.getString("status"), views = o.optInt("views"), createdAt = o.getLong("created_at"),
            sellerId = seller.getString("id"), sellerName = seller.getString("name"),
            favorite = o.optBoolean("favorite"), isMine = o.optBoolean("is_mine"),
            cover = if (o.isNull("cover")) null else abs(o.getString("cover")), images = images,
        )
    }

    private fun parseMe(o: JSONObject): Me {
        val tg = o.getJSONObject("telegram")
        return Me(o.getString("id"), o.getString("name"), o.optString("city"), tg.getBoolean("enabled"), tg.getBoolean("linked"))
    }

    suspend fun ping() { call("GET", "/ping") }

    suspend fun categories(): List<Category> {
        val a = JSONArray(call("GET", "/categories"))
        return List(a.length()) { val o = a.getJSONObject(it); Category(o.getString("id"), o.getString("name"), o.getString("emoji")) }
    }

    suspend fun register(name: String, city: String, password: String): Pair<String, Me> {
        val r = JSONObject(call("POST", "/register", JSONObject().put("name", name).put("city", city).put("password", password)))
        token = r.getString("token")
        return r.getString("id") to me()
    }

    suspend fun login(id: String, password: String): Me {
        val r = JSONObject(call("POST", "/login", JSONObject().put("id", id).put("password", password)))
        token = r.getString("token")
        return me()
    }

    suspend fun logout() {
        try { call("POST", "/logout") } catch (_: Exception) {}
        token = null
    }

    suspend fun me(): Me = parseMe(JSONObject(call("GET", "/me")))

    suspend fun updateMe(name: String, city: String): Me =
        parseMe(JSONObject(call("POST", "/me/update", JSONObject().put("name", name).put("city", city))))

    suspend fun listings(
        q: String = "", category: String = "", free: Boolean = false, sort: String = "",
        mine: Boolean = false, favorites: Boolean = false, user: String = "", offset: Int = 0,
    ): Pair<List<Listing>, Boolean> {
        val p = StringBuilder("?limit=24&offset=$offset")
        fun add(k: String, v: String) { if (v.isNotEmpty()) p.append("&$k=").append(URLEncoder.encode(v, "UTF-8")) }
        add("q", q); add("category", category); add("sort", sort); add("user", user)
        if (free) p.append("&free=1")
        if (mine) p.append("&mine=1")
        if (favorites) p.append("&favorites=1")
        val r = JSONObject(call("GET", "/listings$p"))
        val a = r.getJSONArray("items")
        return List(a.length()) { parseListing(a.getJSONObject(it)) } to r.getBoolean("has_more")
    }

    suspend fun listing(id: Int): Listing = parseListing(JSONObject(call("GET", "/listings/$id")))

    suspend fun createListing(title: String, description: String, price: Int, category: String, city: String, images: List<Picked>): Listing =
        parseListing(JSONObject(call("POST", "/listings", multipart = mapOf(
            "title" to title, "description" to description, "price" to price.toString(), "category" to category, "city" to city,
        ) to images)))

    suspend fun setSold(id: Int, sold: Boolean) {
        call("PUT", "/listings/$id", JSONObject().put("status", if (sold) "sold" else "active"))
    }

    suspend fun deleteListing(id: Int) { call("DELETE", "/listings/$id") }

    suspend fun setFavorite(id: Int, on: Boolean) { call(if (on) "PUT" else "DELETE", "/favorites/$id") }

    suspend fun openChat(listingId: Int): Int = JSONObject(call("POST", "/listings/$listingId/chat")).getInt("id")

    suspend fun chats(): List<ChatItem> {
        val a = JSONArray(call("GET", "/chats"))
        return List(a.length()) {
            val o = a.getJSONObject(it)
            ChatItem(o.getInt("id"), o.getString("title"), if (o.isNull("cover")) null else abs(o.getString("cover")),
                o.getJSONObject("other").getString("name"), o.optString("last_text"), o.optInt("unread"))
        }
    }

    suspend fun chatInfo(id: Int): ChatInfo {
        val o = JSONObject(call("GET", "/chats/$id"))
        val l = o.getJSONObject("listing")
        return ChatInfo(id, o.getJSONObject("other").getString("name"), l.getInt("id"), l.getString("title"), l.getInt("price"))
    }

    private fun parseMessage(o: JSONObject) = Message(o.getInt("id"), o.getBoolean("mine"), o.getString("text"), o.getLong("created_at"))

    suspend fun messages(chatId: Int, after: Int): List<Message> {
        val a = JSONArray(call("GET", "/chats/$chatId/messages?after=$after"))
        return List(a.length()) { parseMessage(a.getJSONObject(it)) }
    }

    suspend fun sendMessage(chatId: Int, text: String): Message =
        parseMessage(JSONObject(call("POST", "/chats/$chatId/messages", JSONObject().put("text", text))))

    suspend fun unread(): Int = JSONObject(call("GET", "/unread")).getInt("unread")

    suspend fun tgLink(): TgLink {
        val o = JSONObject(call("POST", "/telegram/link"))
        return TgLink(o.getString("code"), o.optString("bot").ifEmpty { null }, o.optString("link").ifEmpty { null })
    }

    suspend fun tgUnlink() { call("DELETE", "/telegram/link") }

    /** Читает картинку, уменьшает до 1600px, учитывает поворот из EXIF, сжимает в JPEG (сервер принимает до 8 МБ). */
    suspend fun pick(ctx: Context, uri: Uri, index: Int): Picked? = withContext(Dispatchers.IO) {
        try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            var sample = 1
            while (opts.outWidth / sample > 3200 || opts.outHeight / sample > 3200) sample *= 2
            var bmp = ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return@withContext null
            val rotation = ctx.contentResolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
            val scale = minOf(1f, 1600f / maxOf(bmp.width, bmp.height))
            if (scale < 1f || rotation != 0f) {
                val m = Matrix().apply { postScale(scale, scale); postRotate(rotation) }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            Picked(out.toByteArray(), "photo$index.jpg")
        } catch (e: Exception) {
            null
        }
    }
}
