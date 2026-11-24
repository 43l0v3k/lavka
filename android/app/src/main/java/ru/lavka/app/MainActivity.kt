package ru.lavka.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Api.init(applicationContext)
        setContent { LavkaTheme { Root() } }
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "Главная", Icons.Filled.Home),
    Tab("favs", "Избранное", Icons.Filled.Favorite),
    Tab("new", "Подать", Icons.Filled.Add),
    Tab("chats", "Чаты", Icons.Filled.Email),
    Tab("profile", "Профиль", Icons.Filled.Person),
)

@Composable
fun Root() {
    val nav = rememberNavController()

    // стартовая загрузка: категории и текущий пользователь
    LaunchedEffect(Unit) {
        runCatching { Session.cats = Api.categories() }
        if (Api.token != null) runCatching { Session.me = Api.me() }
    }
    // значок непрочитанных
    LaunchedEffect(Session.me?.id) {
        while (Session.me != null) {
            runCatching { Session.unread = Api.unread() }
            delay(15_000)
        }
    }
    // если категории не успели загрузиться (сервер был недоступен) — подтянуть позже
    LaunchedEffect(Session.cats.isEmpty()) {
        while (Session.cats.isEmpty()) {
            delay(4000)
            runCatching { Session.cats = Api.categories() }
        }
    }

    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val tabRoutes = tabs.map { it.route }
    val needLogin: () -> Unit = { nav.navigate("profile") { launchSingleTop = true } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (route in tabRoutes) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = route == t.route,
                        onClick = { goTab(nav, t.route) },
                        icon = {
                            if (t.route == "chats" && Session.unread > 0) {
                                BadgedBox(badge = { Badge { Text(if (Session.unread > 99) "99+" else "${Session.unread}") } }) { Icon(t.icon, t.label) }
                            } else Icon(t.icon, t.label)
                        },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Accent, selectedTextColor = Accent, indicatorColor = MaterialTheme.colorScheme.primaryContainer),
                    )
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(pad).consumeWindowInsets(pad)) {
            composable("home") { HomeScreen(onOpen = { nav.navigate("listing/$it") }, needLogin = needLogin) }
            composable("favs") {
                if (Session.me == null) AuthScreen(onDone = {}) else
                    SimpleListScreen("Избранное", null, "Тут появится то, что ты отметишь ♡", { nav.navigate("listing/$it") }, needLogin) { off -> Api.listings(favorites = true, offset = off) }
            }
            composable("new") {
                if (Session.me == null) AuthScreen(onDone = {}) else
                    NewListingScreen(onBack = { nav.popBackStack() }, onCreated = { id ->
                        nav.navigate("listing/$id") { popUpTo("home") }
                    })
            }
            composable("chats") { if (Session.me == null) AuthScreen(onDone = {}) else ChatsScreen(onOpen = { nav.navigate("chat/$it") }) }
            composable("profile") { ProfileScreen(onMine = { nav.navigate("mine") }, onAuthDone = {}) }
            composable("mine") {
                SimpleListScreen("Мои объявления", { nav.popBackStack() }, "Ты ещё ничего не подавал(а)", { nav.navigate("listing/$it") }, needLogin) { off -> Api.listings(mine = true, offset = off) }
            }
            composable("listing/{id}", listOf(navArgument("id") { type = NavType.IntType })) { be ->
                ListingScreen(
                    id = be.arguments!!.getInt("id"), onBack = { nav.popBackStack() },
                    onSeller = { nav.navigate("seller/$it") }, onChat = { nav.navigate("chat/$it") }, needLogin = needLogin,
                )
            }
            composable("seller/{uid}", listOf(navArgument("uid") { type = NavType.StringType })) { be ->
                SellerScreen(be.arguments!!.getString("uid")!!, { nav.popBackStack() }, { nav.navigate("listing/$it") }, needLogin)
            }
            composable("chat/{id}", listOf(navArgument("id") { type = NavType.IntType })) { be ->
                ChatScreen(be.arguments!!.getInt("id"), onBack = { nav.popBackStack() }, onListing = { nav.navigate("listing/$it") })
            }
        }
    }
}

private fun goTab(nav: NavHostController, route: String) {
    nav.navigate(route) {
        popUpTo("home") { saveState = false }
        launchSingleTop = true
    }
}
