package com.wanwu.nook

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.wanwu.nook.data.Furniture
import com.wanwu.nook.data.HomeStore
import com.wanwu.nook.image.CutoutService
import com.wanwu.nook.net.HomeAssistantManager
import com.wanwu.nook.net.SecretStore
import com.wanwu.nook.net.SyncManager
import com.wanwu.nook.ui.devices.DevicesScreen
import com.wanwu.nook.ui.furniture.FurnitureDetailScreen
import com.wanwu.nook.ui.home.HomeScreen
import com.wanwu.nook.ui.home.RoomScreen
import com.wanwu.nook.ui.items.CompartmentScreen
import com.wanwu.nook.ui.items.ItemDetailScreen
import com.wanwu.nook.ui.items.ItemsScreen
import com.wanwu.nook.ui.items.UnsortedScreen
import com.wanwu.nook.ui.onboarding.OnboardingScreen
import com.wanwu.nook.ui.settings.CategoryManagerScreen
import com.wanwu.nook.ui.settings.CustomIconsScreen
import com.wanwu.nook.ui.settings.HASettingsScreen
import com.wanwu.nook.ui.settings.RoomsManagerScreen
import com.wanwu.nook.ui.settings.SettingsScreen
import com.wanwu.nook.ui.theme.NookTheme
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.io.File

/** Small persisted UI preferences. */
class AppPrefs(private val prefs: android.content.SharedPreferences) {
    var autoCutout by mutableStateOf(prefs.getBoolean("cutout.auto", true))
        private set
    var cutoutEngine by mutableStateOf(prefs.getString("cutout.engine", "auto") ?: "auto")
        private set
    var gridLayout by mutableStateOf(prefs.getBoolean("collection.grid", true))
        private set
    var grouping by mutableStateOf(prefs.getString("items.grouping", "location") ?: "location")
        private set

    fun updateAutoCutout(v: Boolean) { autoCutout = v; prefs.edit().putBoolean("cutout.auto", v).apply() }
    fun updateCutoutEngine(v: String) { cutoutEngine = v; prefs.edit().putString("cutout.engine", v).apply() }
    fun updateGridLayout(v: Boolean) { gridLayout = v; prefs.edit().putBoolean("collection.grid", v).apply() }
    fun updateGrouping(v: String) { grouping = v; prefs.edit().putString("items.grouping", v).apply() }
}

class AppContainer(context: Context) {
    val scope = MainScope()
    private val prefs = context.getSharedPreferences("nook", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)
    val store = HomeStore(File(context.filesDir, "nook"), scope)
    val sync = SyncManager(store, prefs, secrets, scope)
    val ai = com.wanwu.nook.net.AIService(prefs, secrets)
    val ha = HomeAssistantManager(store, scope)
    val cutout = CutoutService(context)
    val appPrefs = AppPrefs(prefs)
    val router = AppRouter()

    /** Instrumented tests start from a blank, signed-out app. */
    suspend fun resetForTests() {
        sync.signOut(wipeLocal = true)
        sync.updateOffline(false)
        ai.updateSource(com.wanwu.nook.net.AIService.Source.server)
        ai.updateKey(null)
        ai.updateBaseUrl("")
        appPrefs.updateGridLayout(true)
        appPrefs.updateAutoCutout(true)
        router.reset()
    }
}

class NookApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                container.sync.startPeriodicSync()
            }

            override fun onStop(owner: LifecycleOwner) {
                container.store.saveNow()
                container.sync.stopPeriodicSync()
                container.ha.stopPolling()
            }
        })
        container.scope.launch {
            container.store.cleanUpFiles()
            container.sync.refreshServerInfo()
        }
    }
}

enum class Tab(val title: String) { Home("家"), Items("物品"), Devices("设备"), Me("我的") }

sealed interface Route {
    data class Room(val id: String) : Route
    data class Furniture(val id: String) : Route
    data class Compartment(val id: String) : Route
    data class Item(val id: String) : Route
    data object Unsorted : Route
    data object RoomsManager : Route
    data object Categories : Route
    data object CustomIcons : Route
    data object HASettings : Route
    data object AISettings : Route
}

/** Tab selection plus one back stack per tab. */
class AppRouter {
    var tab by mutableStateOf(Tab.Home)
    val stacks: Map<Tab, SnapshotStateList<Route>> = Tab.entries.associateWith { mutableStateListOf<Route>() }
    /** Furniture to pulse when its room opens. */
    var highlight by mutableStateOf<String?>(null)

    val current get() = stacks.getValue(tab)

    fun open(route: Route) { current.add(route) }

    fun back(): Boolean {
        val s = current
        if (s.isEmpty()) return false
        s.removeAt(s.lastIndex)
        return true
    }

    fun showOnPlan(f: Furniture) {
        tab = Tab.Home
        highlight = f.id
        stacks.getValue(Tab.Home).apply { clear(); add(Route.Room(f.roomId)) }
    }

    fun reset() {
        tab = Tab.Home
        stacks.values.forEach { it.clear() }
        highlight = null
    }
}

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("no container") }
val LocalStore = staticCompositionLocalOf<HomeStore> { error("no store") }
val LocalSync = staticCompositionLocalOf<SyncManager> { error("no sync") }
val LocalHA = staticCompositionLocalOf<HomeAssistantManager> { error("no ha") }
val LocalAI = staticCompositionLocalOf<com.wanwu.nook.net.AIService> { error("no ai") }
val LocalRouter = staticCompositionLocalOf<AppRouter> { error("no router") }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as NookApplication).container
        setContent {
            NookTheme {
                CompositionLocalProvider(
                    LocalContainer provides container,
                    LocalStore provides container.store,
                    LocalSync provides container.sync,
                    LocalHA provides container.ha,
                    LocalAI provides container.ai,
                    LocalRouter provides container.router,
                ) {
                    RootScreen()
                }
            }
        }
    }
}

@Composable
fun RootScreen() {
    val store = LocalStore.current
    if (store.didOnboard) MainScreen() else OnboardingScreen()
}

@Composable
fun MainScreen() {
    val router = LocalRouter.current
    val sync = LocalSync.current
    val holder = rememberSaveableStateHolder()
    LaunchedEffect(Unit) { sync.syncNow() }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = router.tab == t,
                        onClick = { if (router.tab == t) router.current.clear() else router.tab = t },
                        icon = {
                            Icon(when (t) {
                                Tab.Home -> Icons.Outlined.Home
                                Tab.Items -> Icons.Outlined.Inventory2
                                Tab.Devices -> Icons.Outlined.Lightbulb
                                Tab.Me -> Icons.Outlined.AccountCircle
                            }, null)
                        },
                        label = { Text(t.title) },
                        modifier = Modifier.testTag("tab.${t.title}"),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
            val stack = router.current
            BackHandler(enabled = stack.isNotEmpty()) { router.back() }
            val top = stack.lastOrNull()
            val key = "${router.tab}:${stack.size}:$top"
            holder.SaveableStateProvider(key) {
                if (top == null) {
                    when (router.tab) {
                        Tab.Home -> HomeScreen()
                        Tab.Items -> ItemsScreen()
                        Tab.Devices -> DevicesScreen()
                        Tab.Me -> SettingsScreen()
                    }
                } else {
                    RouteScreen(top)
                }
            }
        }
    }
}

@Composable
fun RouteScreen(route: Route) {
    when (route) {
        is Route.Room -> RoomScreen(route.id)
        is Route.Furniture -> FurnitureDetailScreen(route.id)
        is Route.Compartment -> CompartmentScreen(route.id)
        is Route.Item -> ItemDetailScreen(route.id)
        Route.Unsorted -> UnsortedScreen()
        Route.RoomsManager -> RoomsManagerScreen()
        Route.Categories -> CategoryManagerScreen()
        Route.CustomIcons -> CustomIconsScreen()
        Route.HASettings -> HASettingsScreen()
        Route.AISettings -> com.wanwu.nook.ui.settings.AISettingsScreen()
    }
}
