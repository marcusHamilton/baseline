package dev.baseline.ui.nav

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.baseline.AppContainer
import dev.baseline.R
import dev.baseline.ai.AiMode
import dev.baseline.ai.ChatSession
import dev.baseline.ai.context.AskScope
import dev.baseline.garmin.KeyStatus
import dev.baseline.ui.activities.ActivitiesScreen
import dev.baseline.ui.activities.ActivitiesViewModel
import dev.baseline.ui.activity.ActivityScreen
import dev.baseline.ui.activity.ActivityViewModel
import dev.baseline.ui.activity.sections.RouteMap
import dev.baseline.ui.ai.AskButton
import dev.baseline.ui.ai.AskHostViewModel
import dev.baseline.ui.ai.AskScopeHolder
import dev.baseline.ui.ai.AskSheet
import dev.baseline.ui.ai.AskViewModel
import dev.baseline.ui.ai.InsightSlot
import dev.baseline.ui.ai.InsightViewModel
import dev.baseline.ui.ai.LocalAskScopeHolder
import dev.baseline.ui.ai.ReportAskScope
import dev.baseline.ui.calendar.CalendarScreen
import dev.baseline.ui.calendar.CalendarViewModel
import dev.baseline.ui.components.OfflineBar
import dev.baseline.ui.onboarding.OnboardingScreen
import dev.baseline.ui.onboarding.OnboardingState
import dev.baseline.ui.onboarding.OnboardingViewModel
import dev.baseline.ui.records.RecordsScreen
import dev.baseline.ui.records.RecordsViewModel
import dev.baseline.ui.settings.AiCard
import dev.baseline.ui.settings.AiKeyScreen
import dev.baseline.ui.settings.AiKeyViewModel
import dev.baseline.ui.settings.AiSettingsViewModel
import dev.baseline.ui.settings.HistoryCoverage
import dev.baseline.ui.settings.SettingsScreen
import dev.baseline.ui.settings.SettingsViewModel
import dev.baseline.ui.sleep.SleepScreen
import dev.baseline.ui.sleep.SleepViewModel
import dev.baseline.ui.theme.Bg
import dev.baseline.ui.today.TodayScreen
import dev.baseline.ui.today.TodayViewModel
import dev.baseline.ui.trends.TrendsScreen
import dev.baseline.ui.trends.TrendsViewModel
import dev.baseline.widget.WidgetTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.LocalDate

object Routes {
    const val TODAY = "today"
    const val ACTIVITIES = "activities"
    const val TRENDS = "trends"
    const val SETTINGS = "settings"
    const val CALENDAR = "calendar"
    const val SLEEP = "sleep/{date}"
    const val ACTIVITY = "activity/{id}"
    const val RECORDS = "records"
    const val AI_KEY = "settings/ai-key?step={step}"
    const val ONBOARDING = "onboarding"
}

private const val AI_STEP_CONNECTION = "connection"
private const val AI_STEP_MODEL = "model"

private fun aiKeyRoute(step: String) = "settings/ai-key?step=$step"

internal const val SELECTED_DATE = "selectedDate"

/** Set on the Trends entry when Today's Recovery card opens it, so Trends scrolls to its Training load card. */
internal const val FOCUS_TRAINING_LOAD = "focusTrainingLoad"

/** Tells the screen below (Today) which day to show, then goes back to it. */
internal fun NavController.returnSelectedDate(date: LocalDate) {
    previousBackStackEntry?.savedStateHandle?.set(SELECTED_DATE, date.toString())
    popBackStack()
}

private data class Tab(val route: String, val label: String, @DrawableRes val icon: Int)

private val Tabs = listOf(
    Tab(Routes.TODAY, "Today", R.drawable.ic_today),
    Tab(Routes.ACTIVITIES, "Activities", R.drawable.ic_activities),
    Tab(Routes.TRENDS, "Trends", R.drawable.ic_trends),
    Tab(Routes.SETTINGS, "Settings", R.drawable.ic_settings),
)

/** The Ask button sits between the first two tabs and the last two: Today, Activities, [Ask], Trends, Settings. */
private const val TABS_BEFORE_ASK = 2

private fun isTab(route: String?) = Tabs.any { it.route == route }

// Navigation's default is a 700 ms crossfade, which makes every page change feel slow. Tabs swap with a quick fade;
// pages opened from a tab slide in a little from the right and back out on Back.
private const val TAB_FADE_MS = 120
private const val PUSH_MS = 220
private val PushEasing = FastOutSlowInEasing

private val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    if (isTab(targetState.destination.route)) {
        fadeIn(tween(TAB_FADE_MS))
    } else {
        slideInHorizontally(tween(PUSH_MS, easing = PushEasing)) { it / 8 } + fadeIn(tween(PUSH_MS, easing = PushEasing))
    }
}
private val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = { fadeOut(tween(TAB_FADE_MS)) }
private val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = { fadeIn(tween(TAB_FADE_MS)) }
private val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    if (isTab(initialState.destination.route)) {
        fadeOut(tween(TAB_FADE_MS))
    } else {
        slideOutHorizontally(tween(PUSH_MS, easing = PushEasing)) { it / 8 } + fadeOut(tween(PUSH_MS, easing = PushEasing))
    }
}

// The edge-swipe back gesture has its own transitions (Navigation's default shrinks the page to 70%). Leaving a pushed
// page it slides the whole page off with the finger, no fade, so the page beneath stays solid and the two never blend.
private val predictivePopEnter: AnimatedContentTransitionScope<NavBackStackEntry>.(Int) -> EnterTransition = {
    if (isTab(initialState.destination.route)) fadeIn(tween(TAB_FADE_MS)) else EnterTransition.None
}
private val predictivePopExit: AnimatedContentTransitionScope<NavBackStackEntry>.(Int) -> ExitTransition = {
    if (isTab(initialState.destination.route)) {
        fadeOut(tween(TAB_FADE_MS))
    } else {
        slideOutHorizontally(tween(PUSH_MS, easing = PushEasing)) { it }
    }
}

private val NoDeepLink: StateFlow<WidgetTarget?> = MutableStateFlow(null)

@Composable
fun BaselineNavHost(container: AppContainer, deepLink: StateFlow<WidgetTarget?> = NoDeepLink, onDeepLinkConsumed: () -> Unit = {}) {
    val nav = rememberNavController()
    val target by deepLink.collectAsStateWithLifecycle()
    val destination = nav.currentBackStackEntryAsState().value?.destination
    // A page opened from a tab (Records, an activity, the calendar) keeps that tab highlighted.
    var currentTab by rememberSaveable { mutableStateOf(Routes.TODAY) }
    destination?.route?.takeIf(::isTab)?.let { if (it != currentTab) currentTab = it }
    val status by container.apiKeyVault.status.collectAsStateWithLifecycle(initialValue = null)
    val aiMode by container.aiMode.collectAsStateWithLifecycle()
    val scopeHolder = remember { AskScopeHolder() }
    // The Activity's ViewModel, so an open sheet and its chat survive configuration changes.
    val askHost: AskHostViewModel = viewModel()
    val askFor by askHost.askFor.collectAsStateWithLifecycle()
    val currentEntryId = nav.currentBackStackEntryAsState().value?.id
    // Decided once, when the vault first answers; later key changes must not rebuild the graph.
    val start = remember(status != null) {
        status?.let { if (it is KeyStatus.Missing) Routes.ONBOARDING else Routes.TODAY }
    }
    if (start == null) {
        Box(Modifier.fillMaxSize().background(Bg))
        return
    }
    CompositionLocalProvider(LocalAskScopeHolder provides scopeHolder) {
        Scaffold(
            bottomBar = {
                if (destination != null && destination.route != Routes.ONBOARDING) {
                    NavigationBar {
                        Tabs.take(TABS_BEFORE_ASK).forEach { TabItem(it, currentTab, nav, container) }
                        // An action, not a tab: it never navigates or takes the selection, and it is not there in mode Off.
                        if (aiMode != null && aiMode != AiMode.OFF) {
                            AskButton { askHost.open(scopeHolder.current(currentEntryId)) }
                        }
                        Tabs.drop(TABS_BEFORE_ASK).forEach { TabItem(it, currentTab, nav, container) }
                    }
                }
            },
        ) { padding ->
            // consumeWindowInsets: the padding already covers the system bars, so a screen's imePadding only adds the keyboard's extra height.
            Column(Modifier.padding(padding).consumeWindowInsets(padding)) {
                if (destination?.route != Routes.ONBOARDING) ConnectivityLine(container)
                NavHost(
                    nav,
                    startDestination = start,
                    modifier = Modifier.weight(1f),
                    enterTransition = enter,
                    exitTransition = exit,
                    popEnterTransition = popEnter,
                    popExitTransition = popExit,
                    predictivePopEnterTransition = predictivePopEnter,
                    predictivePopExitTransition = predictivePopExit,
                ) {
                    composable(Routes.ONBOARDING) {
                        val vm = viewModel { OnboardingViewModel(container.apiKeyVault, container.tokenProvider::clear, container.testKey) }
                        val state by vm.state.collectAsStateWithLifecycle()
                        LaunchedEffect(state) {
                            if (state is OnboardingState.Done) {
                                // The scheduler picks the saved key up by itself; Today asks for the first sync and the notification permission.
                                nav.navigate(Routes.TODAY) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                            }
                        }
                        OnboardingScreen(vm)
                    }
                    composable(Routes.TODAY) { entry ->
                        val vm = viewModel {
                            TodayViewModel(
                                container.repository,
                                container.scheduler,
                                container.userPrefs,
                                container.db.taskDao().counts(),
                                container.db.taskDao().backfillPendingFlow(),
                                container.apiKeyVault.status,
                                container.online,
                                container.today,
                            )
                        }
                        // The calendar's "Open day" hands its date back through this entry, which keeps the same ViewModel.
                        val picked by entry.savedStateHandle.getStateFlow<String?>(SELECTED_DATE, null).collectAsStateWithLifecycle()
                        LaunchedEffect(picked) {
                            picked?.let { runCatching { LocalDate.parse(it) }.getOrNull()?.let(vm::setDate) }
                            // null, not remove(): removing would drop the flow this screen is collecting.
                            if (picked != null) entry.savedStateHandle[SELECTED_DATE] = null as String?
                        }
                        val insightVm = viewModel { InsightViewModel(container.insightService) }
                        // Only the shown day matters here, so the rest of Today's state doesn't recompose this block.
                        val shownDate by vm.state.sliceAsState { it.date }
                        ReportAskScope(AskScope.Today(shownDate))
                        TodayScreen(
                            vm,
                            onOpenCalendar = { nav.navigate(Routes.CALENDAR) },
                            onOpenSleep = { date -> nav.navigate("sleep/$date") },
                            onOpenActivity = { id -> nav.navigate("activity/$id") },
                            zone = container.dateTicker.zone,
                            updater = container.updater,
                            onOpenTrends = {
                                nav.navigate(Routes.TRENDS) {
                                    popUpTo(Routes.TODAY)
                                    launchSingleTop = true
                                }
                                runCatching { nav.getBackStackEntry(Routes.TRENDS) }.getOrNull()?.savedStateHandle?.set(FOCUS_TRAINING_LOAD, true)
                            },
                            insight = {
                                // The day Today's header shows, so the card follows the ‹ › buttons.
                                val date = vm.state.collectAsStateWithLifecycle().value.date
                                val scope = AskScope.Today(date)
                                InsightSlot(insightVm, scope, container.today, onAsk = {
                                    askHost.open(scope)
                                }, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp))
                            },
                        )
                    }
                    composable(Routes.CALENDAR) {
                        CalendarScreen(
                            viewModel { CalendarViewModel(container.repository, container.today) },
                            onBack = { nav.popBackStack() },
                            onOpenDay = nav::returnSelectedDate,
                        )
                    }
                    composable(Routes.SLEEP) { entry ->
                        // Read once: the screen is for one fixed day, so nothing here should recompose.
                        val date = remember(entry) {
                            entry.arguments?.getString("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: container.today.value
                        }
                        val insightVm = viewModel { InsightViewModel(container.insightService) }
                        ReportAskScope(AskScope.Sleep(date))
                        SleepScreen(
                            viewModel {
                                val today = container.today.value
                                SleepViewModel(
                                    container.repository,
                                    container.db.taskDao(),
                                    container.scheduler,
                                    date,
                                    today,
                                    container.dateTicker.zone,
                                )
                            },
                            onBack = { nav.popBackStack() },
                            insight = {
                                val scope = AskScope.Sleep(date)
                                InsightSlot(insightVm, scope, container.today, onAsk = { askHost.open(scope) })
                            },
                        )
                    }
                    composable(Routes.ACTIVITIES) {
                        ActivitiesScreen(
                            viewModel { ActivitiesViewModel(container.repository, container.today) },
                            onOpenActivity = { id -> nav.navigate("activity/$id") },
                            onOpenRecords = { nav.navigate(Routes.RECORDS) },
                        )
                    }
                    composable(Routes.ACTIVITY) { entry ->
                        // An id that is not a number shows "Activity not found" rather than crashing.
                        val id = entry.arguments?.getString("id")?.toLongOrNull() ?: -1L
                        // MapLibre's MapView is slow to create; make it once the page has slid in, so the transition doesn't stutter.
                        // Until then the map's card shows empty.
                        var settled by remember { mutableStateOf(false) }
                        if (transition.currentState == EnterExitState.Visible) settled = true
                        val vm = viewModel { ActivityViewModel(container.repository, container.scheduler, id) }
                        val insightVm = viewModel { InsightViewModel(container.insightService) }
                        // The sport is known once the activity has loaded; until then the sheet says "this activity".
                        val sport by vm.state.sliceAsState { it.header?.family }
                        ReportAskScope(AskScope.Activity(id, sport))
                        ActivityScreen(
                            vm,
                            onBack = { nav.popBackStack() },
                            mapContent = { if (settled) RouteMap(it, Modifier.fillMaxSize()) },
                            insight = {
                                // The screen only draws this once the activity has loaded, so the sport is known.
                                val scope = AskScope.Activity(id, sport)
                                InsightSlot(insightVm, scope, container.today, onAsk = { askHost.open(scope) })
                            },
                        )
                    }
                    composable(Routes.RECORDS) {
                        RecordsScreen(
                            viewModel { RecordsViewModel(container.repository, container.today) },
                            onBack = { nav.popBackStack() },
                            onOpenActivity = { id -> nav.navigate("activity/$id") },
                        )
                    }
                    composable(Routes.TRENDS) { entry ->
                        val focus by entry.savedStateHandle.getStateFlow(FOCUS_TRAINING_LOAD, false).collectAsStateWithLifecycle()
                        val vm = viewModel { TrendsViewModel(container.repository, container.today) }
                        val days by vm.state.sliceAsState { it.range.days }
                        ReportAskScope(AskScope.Trends(days))
                        TrendsScreen(
                            vm,
                            focusTrainingLoad = focus,
                            onFocused = { entry.savedStateHandle[FOCUS_TRAINING_LOAD] = false },
                        )
                    }
                    composable(Routes.SETTINGS) {
                        SettingsScreen(
                            viewModel {
                                SettingsViewModel(
                                    container.apiKeyVault, container.userPrefs, container.scheduler,
                                    testKey = container.testKey,
                                    clearToken = container.tokenProvider::clear,
                                    recalculator = container.historyRecalculator,
                                    coverage = container.repository.historyStart.map { (health, activities) -> HistoryCoverage(health, activities) },
                                    imperial = container.repository.imperial,
                                    failedCount = container.db.taskDao().failedCount(),
                                    requeueFailed = container.db.taskDao()::requeueAllFailed,
                                )
                            },
                            container.dateTicker.zone,
                            container.updater,
                            container.clock,
                            aiCard = {
                                val ai = viewModel {
                                    AiSettingsViewModel(
                                        container.aiPrefs,
                                        container.aiKeyVault,
                                        container.nanoEngine,
                                        container.aiKeyEntry,
                                        container.insightService,
                                    )
                                }
                                val aiState by ai.state.collectAsStateWithLifecycle()
                                AiCard(
                                    aiState,
                                    onMode = ai::setMode,
                                    onDownload = ai::downloadNano,
                                    onAddKey = { nav.navigate(aiKeyRoute(AI_STEP_CONNECTION)) },
                                    onChangeModel = { nav.navigate(aiKeyRoute(AI_STEP_MODEL)) },
                                    onReplaceKey = { nav.navigate(aiKeyRoute(AI_STEP_CONNECTION)) },
                                    onRemove = ai::removeKey,
                                    onClearInsights = ai::clearInsights,
                                )
                            },
                        )
                    }
                    composable(
                        Routes.AI_KEY,
                        arguments = listOf(
                            navArgument("step") {
                                type = NavType.StringType
                                defaultValue = AI_STEP_CONNECTION
                            },
                        ),
                    ) { entry ->
                        val startAtModel = entry.arguments?.getString("step") == AI_STEP_MODEL
                        AiKeyScreen(
                            viewModel { AiKeyViewModel(container.aiKeyEntry, container.aiKeyVault, startAtModel) },
                            onBack = { nav.popBackStack() },
                        )
                    }
                }
                LaunchedEffect(target, start) {
                    val pending = target ?: return@LaunchedEffect
                    if (status is KeyStatus.Missing || nav.currentDestination?.route == Routes.ONBOARDING) {
                        onDeepLinkConsumed()
                        return@LaunchedEffect
                    }
                    when (pending) {
                        WidgetTarget.Today -> {
                            currentTab = Routes.TODAY
                            nav.navigate(Routes.TODAY) {
                                popUpTo(Routes.TODAY)
                                launchSingleTop = true
                            }
                            runCatching { nav.getBackStackEntry(Routes.TODAY) }.getOrNull()
                                ?.savedStateHandle?.set(SELECTED_DATE, container.today.value.toString())
                        }

                        WidgetTarget.SleepToday -> {
                            val today = container.today.value
                            currentTab = Routes.TODAY
                            nav.navigate(Routes.TODAY) {
                                popUpTo(Routes.TODAY)
                                launchSingleTop = true
                            }
                            runCatching { nav.getBackStackEntry(Routes.TODAY) }.getOrNull()
                                ?.savedStateHandle?.set(SELECTED_DATE, today.toString())
                            nav.navigate("sleep/$today") {
                                launchSingleTop = true
                            }
                        }

                        WidgetTarget.Activities -> {
                            currentTab = Routes.ACTIVITIES
                            nav.navigate(Routes.ACTIVITIES) {
                                popUpTo(Routes.TODAY)
                                launchSingleTop = true
                            }
                        }

                        is WidgetTarget.Activity -> {
                            currentTab = Routes.ACTIVITIES
                            nav.navigate(Routes.ACTIVITIES) {
                                popUpTo(Routes.TODAY)
                                launchSingleTop = true
                            }
                            nav.navigate("activity/${pending.id}") {
                                launchSingleTop = true
                            }
                        }

                        WidgetTarget.Settings -> {
                            currentTab = Routes.SETTINGS
                            nav.navigate(Routes.SETTINGS) {
                                popUpTo(Routes.TODAY)
                                launchSingleTop = true
                            }
                        }
                    }
                    onDeepLinkConsumed()
                }
            }
        }
        val store = askHost.store
        val openScope = askFor
        if (openScope != null && store != null) {
            // The store is new for every open, so closing and reopening never meets the old chat.
            key(store) { AskSheetHost(container, openScope, store, onDismiss = askHost::close) }
        }
    }
}

/**
 * The Ask sheet, with its ViewModels in [store], which [AskHostViewModel] owns: it survives a configuration change and is cleared on
 * close, which cancels any request in flight and drops the chat (spec §3.6).
 */
@Composable
private fun AskSheetHost(container: AppContainer, scope: AskScope, store: ViewModelStore, onDismiss: () -> Unit) {
    val owner = remember(store) {
        object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = store
        }
    }
    val vm = viewModel(viewModelStoreOwner = owner, key = "ask-$scope") {
        val session = ChatSession(
            scope,
            container.contextPacks,
            container.nanoEngine,
            container.cloudEngine,
            mode = { container.aiMode.value ?: AiMode.OFF },
            keySet = { container.aiKeySet.value },
            online = container.aiOnline::value,
        )
        AskViewModel(scope, session, container.contextPacks, container.aiPrefs.mode, container.online, container.today, container.nanoEngine::warmup)
    }
    AskSheet(vm, onDismiss)
}

/** One field of this state as Compose state, so a block that only needs it doesn't recompose on every other change. */
@Composable
private fun <S, T> StateFlow<S>.sliceAsState(select: (S) -> T): State<T> {
    val slice = remember(this) { map(select).distinctUntilChanged() }
    val initial = remember(this) { select(value) }
    return slice.collectAsStateWithLifecycle(initialValue = initial)
}

@Composable
private fun RowScope.TabItem(tab: Tab, currentTab: String, nav: NavController, container: AppContainer) {
    NavigationBarItem(
        selected = tab.route == currentTab,
        onClick = {
            // Today is always the bottom of the stack; tapping its tab also brings it back to the current day.
            if (tab.route == Routes.TODAY) {
                runCatching { nav.getBackStackEntry(Routes.TODAY) }.getOrNull()
                    ?.savedStateHandle?.set(SELECTED_DATE, container.today.value.toString())
            }
            // No saveState/restoreState: a tab always opens on its own page, never on a page left open
            // under it (Activities -> Records, then Activities, shows Activities).
            nav.navigate(tab.route) {
                popUpTo(Routes.TODAY)
                launchSingleTop = true
            }
        },
        icon = { Icon(painterResource(tab.icon), contentDescription = null) },
        label = { Text(tab.label) },
    )
}

/** "Last synced …" under the status bar while offline, on every screen. */
@Composable
private fun ConnectivityLine(container: AppContainer) {
    val online by container.online.collectAsStateWithLifecycle(initialValue = true)
    val lastSynced by container.userPrefs.lastSyncedAt.collectAsStateWithLifecycle(initialValue = null)
    // Only ticks while the line is showing, so the relative time stays current without waking an online screen.
    val now by produceState(container.clock.instant(), online) {
        while (!online) {
            value = container.clock.instant()
            delay(60_000)
        }
    }
    OfflineBar(online, lastSynced, now)
}
