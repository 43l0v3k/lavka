package ru.lavka.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Глобальное состояние: кто вошёл, категории, непрочитанные. */
object Session {
    var me by mutableStateOf<Me?>(null)
    var cats by mutableStateOf<List<Category>>(emptyList())
    var unread by mutableIntStateOf(0)

    fun emoji(id: String) = cats.firstOrNull { it.id == id }?.emoji ?: "📦"
}

fun fmtPrice(p: Int): String =
    if (p == 0) "Бесплатно" else NumberFormat.getIntegerInstance(Locale("ru")).format(p) + " ₽"

fun timeAgo(ts: Long): String {
    val d = System.currentTimeMillis() / 1000 - ts
    return when {
        d < 60 -> "только что"
        d < 3600 -> "${d / 60} мин назад"
        d < 86400 -> "${d / 3600} ч назад"
        else -> SimpleDateFormat("d MMM", Locale("ru")).format(Date(ts * 1000))
    }
}

fun hhmm(ts: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts * 1000))

@Composable
fun Cover(url: String?, category: String, modifier: Modifier = Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(Session.emoji(category), fontSize = 38.sp)
        }
    }
}

@Composable
fun ListingCard(l: Listing, onOpen: () -> Unit, onFav: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.clickable(onClick = onOpen),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Box {
            Cover(l.cover, l.category, Modifier.fillMaxWidth().aspectRatio(4f / 3f))
            IconButton(
                onClick = onFav,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(34.dp)
                    .clip(CircleShape).background(Color.White.copy(alpha = 0.92f)),
            ) {
                Icon(
                    if (l.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = "В избранное",
                    tint = if (l.favorite) Accent else Color(0xFF333333),
                    modifier = Modifier.size(20.dp),
                )
            }
            if (l.status == "sold") {
                Text(
                    "Продано", color = Color.White, fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                        .background(Color(0xAA000000), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                fmtPrice(l.price), fontWeight = FontWeight.ExtraBold, fontSize = 18.sp,
                color = if (l.price == 0) Ok else MaterialTheme.colorScheme.onSurface,
            )
            Text(l.title, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 2.dp))
            Text(
                "${l.city.ifEmpty { "—" }} · ${timeAgo(l.createdAt)}",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            )
        }
    }
}

@Composable
fun Avatar(name: String, size: Int = 46) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.take(1).uppercase(), color = Accent, fontWeight = FontWeight.ExtraBold, fontSize = (size / 2.5).sp)
    }
}

@Composable
fun Empty(emoji: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(emoji, fontSize = 48.sp)
        Spacer(Modifier.height(8.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun Loading() {
    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }
}

@Composable
fun PrimaryButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, danger: Boolean = false, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, modifier = modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (danger) Color(0xFFC0392B) else MaterialTheme.colorScheme.onSurface),
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}
