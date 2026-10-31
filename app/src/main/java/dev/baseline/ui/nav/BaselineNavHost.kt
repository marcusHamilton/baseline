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
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.baseline.AppContainer
import dev.baseline.R
import dev.baseline.garmin.KeyStatus
import dev.baseline.ui.activities.ActivitiesScreen
import dev.baseline.ui.activities.ActivitiesViewModel
import dev.baseline.ui.activity.ActivityScreen
import dev.baseline.ui.activity.ActivityViewModel
import dev.baseline.ui.activity.sections.RouteMap
import dev.baseline.ui.calendar.CalendarScreen
import dev.baseline.ui.calendar.CalendarViewModel
import dev.baseline.ui.components.OfflineBar
import dev.baseline.ui.onboarding.OnboardingScreen
import dev.baseline.ui.onboarding.OnboardingState
import dev.baseline.ui.onboarding.OnboardingViewModel
import dev.baseline.ui.records.RecordsScreen
import dev.baseline.ui.records.RecordsViewModel
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
    const val ONBOARDING = "onboarding"
}

internal const val SELECTED_DATE = "selectedDate"

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
    // Decided once, when the vault first answers; later key changes must not rebuild the graph.
    val start = remember(status != null) {
        status?.let { if (it is KeyStatus.Missing) Routes.ONBOARDING else Routes.TODAY }
    }
    if (start == null) {
        Box(Modifier.fillMaxSize().background(Bg))
        return
    }
    Scaffold(
        bottomBar = {
            if (destination != null && destination.route != Routes.ONBOARDING) {
                NavigationBar {
                    Tabs.forEach { tab ->
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
                    TodayScreen(
                        vm,
                        onOpenCalendar = { nav.navigate(Routes.CALENDAR) },
                        onOpenSleep = { date -> nav.navigate("sleep/$date") },
                        onOpenActivity = { id -> nav.navigate("activity/$id") },
                        zone = container.dateTicker.zone,
                        updater = container.updater,
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
                    SleepScreen(
                        viewModel {
                            // Read once, when the ViewModel is made: the screen is for one fixed day, so nothing here should recompose.
                            val today = container.today.value
                            val date = entry.arguments?.getString("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today
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
                    ActivityScreen(
                        viewModel { ActivityViewModel(container.repository, container.scheduler, id) },
                        onBack = { nav.popBackStack() },
                        mapContent = { if (settled) RouteMap(it, Modifier.fillMaxSize()) },
                    )
                }
                composable(Routes.RECORDS) {
                    RecordsScreen(
                        viewModel { RecordsViewModel(container.repository, container.today) },
                        onBack = { nav.popBackStack() },
                        onOpenActivity = { id -> nav.navigate("activity/$id") },
                    )
                }
                composable(Routes.TRENDS) {
                    TrendsScreen(viewModel { TrendsViewModel(container.repository, container.today) })
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
