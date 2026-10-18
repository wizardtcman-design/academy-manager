package com.example

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Payment
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.model.AcademySettings
import com.example.ui.navigation.Screen
import com.example.ui.screens.audit.AuditLogsScreen
import com.example.ui.screens.auth.AppLockScreen
import com.example.ui.screens.auth.LoginScreen
import com.example.ui.screens.bot.BotScreen
import com.example.ui.screens.courses.CoursesScreen
import com.example.ui.screens.dashboard.DashboardScreen
import com.example.ui.screens.dialogs.AddEditStudentDialog
import com.example.ui.screens.dialogs.ChangePasswordDialog
import com.example.ui.screens.dialogs.QuickThemeDialog
import com.example.ui.screens.more.MoreScreen
import com.example.ui.screens.payments.PaymentsScreen
import com.example.ui.screens.reminders.RemindersScreen
import com.example.ui.screens.reports.ReportsScreen
import com.example.ui.screens.settings.SettingsScreen
import com.example.ui.screens.settings.availableThemeModels
import com.example.ui.screens.students.StudentsScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.MainViewModel
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.NotificationsActive
import kotlinx.coroutines.launch

data class NavigationTabItem(
    val screen: Screen,
    val title: String,
    val icon: ImageVector,
    val testTag: String
)

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val notificationPermissionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) requestExactAlarmPermissionIfNeeded()
        }

    /**
     * Android 13+ needs runtime permission for notifications; without it every
     * installment reminder is silently dropped by the system.
     */
    private fun requestRuntimeNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestExactAlarmPermissionIfNeeded()
        }
    }

    /**
     * Android 12+ can deny exact alarms. Ask once; if denied, ReminderScheduler
     * falls back to inexact alarms (still delivered, just not to the minute).
     */
    private fun requestExactAlarmPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val am = getSystemService(android.app.AlarmManager::class.java)
            if (am != null && !am.canScheduleExactAlarms()) {
                try {
                    startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                            .setData(android.net.Uri.parse("package:$packageName"))
                    )
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestRuntimeNotificationPermission()
        com.example.service.SyncWorker.schedule(this)

        setContent {
            val settings by viewModel.settings.collectAsState()
            val themeName = settings?.themeName ?: "BLUE"
            val themeMode = settings?.themeMode ?: "SYSTEM"

            MyApplicationTheme(
                themeName = themeName,
                themeMode = themeMode
            ) {
                val isAuthenticated by viewModel.isAuthenticated.collectAsState()
                val currentUser by viewModel.currentUser.collectAsState()
                val students by viewModel.students.collectAsState()
                val courses by viewModel.courses.collectAsState()
                val stats by viewModel.dashboardStats.collectAsState()
                val auditLogs by viewModel.auditLogs.collectAsState()
                val userMessage by viewModel.userMessage.collectAsState()

                val snackbarHostState = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()

                // Display snackbars for user feedback
                LaunchedEffect(userMessage) {
                    userMessage?.let { msg ->
                        scope.launch {
                            snackbarHostState.showSnackbar(msg)
                            viewModel.clearUserMessage()
                        }
                    }
                }

                var isAppLocked by remember { mutableStateOf(false) }
                var lastBackgroundTime by remember { mutableLongStateOf(0L) }
                var hasInitializedLock by remember { mutableStateOf(false) }

                // Initial lock state on launch
                LaunchedEffect(settings) {
                    if (!hasInitializedLock && settings != null) {
                        hasInitializedLock = true
                        if (settings!!.appLockEnabled && settings!!.appLockPin.isNotBlank()) {
                            isAppLocked = true
                        }
                    }
                }

                // Manage lifecycle for App Lock background timeout
                val lifecycleOwner = LocalLifecycleOwner.current
                LaunchedEffect(lifecycleOwner, settings) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_STOP -> {
                                lastBackgroundTime = System.currentTimeMillis()
                            }
                            Lifecycle.Event.ON_START -> {
                                val currentSettings = settings
                                if (currentSettings != null && currentSettings.appLockEnabled && currentSettings.appLockPin.isNotBlank()) {
                                    val timeoutMinutes = currentSettings.appLockTimeoutMinutes
                                    val timeoutMillis = timeoutMinutes * 60 * 1000L
                                    if (lastBackgroundTime > 0L && (System.currentTimeMillis() - lastBackgroundTime) >= timeoutMillis) {
                                        isAppLocked = true
                                    }
                                }
                            }
                            else -> {}
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                }

                if (!isAuthenticated) {
                    LoginScreen(
                        onLogin = { email, pass, onResult ->
                            viewModel.login(email, pass, onResult)
                        }
                    )
                } else if (isAppLocked && settings?.appLockEnabled == true && settings?.appLockPin?.isNotBlank() == true) {
                    AppLockScreen(
                        academyName = settings?.academyName ?: "مدیریت آموزشگاه",
                        correctPin = settings?.appLockPin ?: "",
                        onUnlock = {
                            isAppLocked = false
                            lastBackgroundTime = 0L
                        }
                    )
                } else {
                    MainAppScaffold(
                        viewModel = viewModel,
                        snackbarHostState = snackbarHostState,
                        currentUser = currentUser,
                        students = students,
                        courses = courses,
                        settings = settings,
                        stats = stats,
                        auditLogs = auditLogs
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScaffold(
    viewModel: MainViewModel,
    snackbarHostState: SnackbarHostState,
    currentUser: com.example.data.model.User?,
    students: List<com.example.data.model.Student>,
    courses: List<com.example.data.model.Course>,
    settings: com.example.data.model.AcademySettings?,
    stats: com.example.data.repository.DashboardStats,
    auditLogs: List<com.example.data.model.AuditLog>
) {
    var currentTab by remember { mutableStateOf<Screen>(Screen.Dashboard) }
    var activeSubScreen by remember { mutableStateOf<Screen?>(null) }
    var showQuickAddStudentDialog by remember { mutableStateOf(false) }
    var showChangePasswordDialog by remember { mutableStateOf(false) }
    var showQuickThemeDialog by remember { mutableStateOf(false) }

    val activeThemeItem = remember(settings?.themeName) {
        availableThemeModels.find { it.id == (settings?.themeName ?: "BLUE") } ?: availableThemeModels.first()
    }

    val tabs = listOf(
        NavigationTabItem(Screen.Dashboard, "داشبورد", Icons.Default.Dashboard, "tab_dashboard"),
        NavigationTabItem(Screen.Students, "هنرجویان", Icons.Default.People, "tab_students"),
        NavigationTabItem(Screen.Payments, "پرداخت‌ها", Icons.Default.Payment, "tab_payments"),
        NavigationTabItem(Screen.Bot, "ربات", Icons.Default.SmartToy, "tab_bot"),
        NavigationTabItem(Screen.Reports, "گزارش‌ها", Icons.Default.Assessment, "tab_reports"),
        NavigationTabItem(Screen.More, "بیشتر", Icons.Default.MoreHoriz, "tab_more")
    )

    // Handle back button on sub-screens
    if (activeSubScreen != null) {
        BackHandler {
            activeSubScreen = null
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (activeSubScreen == null) {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.School,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            Column {
                                Text(
                                    text = settings?.academyName ?: "آموزشگاه کامپیوتر",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                                val currentTabTitle = tabs.find { it.screen == currentTab }?.title ?: ""
                                Text(
                                    text = currentTabTitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    },
                    actions = {
                        // Quick Light / Dark Mode Toggle
                        IconButton(
                            onClick = {
                                val currentMode = settings?.themeMode ?: "SYSTEM"
                                val newMode = if (currentMode == "DARK") "LIGHT" else "DARK"
                                val updated = (settings ?: AcademySettings()).copy(
                                    themeMode = newMode,
                                    themeName = settings?.themeName ?: "BLUE"
                                )
                                viewModel.updateSettings(updated) {}
                            },
                            modifier = Modifier.testTag("quick_dark_mode_button")
                        ) {
                            Icon(
                                imageVector = if (settings?.themeMode == "DARK") Icons.Default.LightMode else Icons.Default.DarkMode,
                                contentDescription = "تغییر حالت شب و روز",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        // Quick Theme Palette Switcher Pill
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { showQuickThemeDialog = true }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                .testTag("quick_theme_selector_button")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(activeThemeItem.primaryColor)
                                )
                                Icon(
                                    imageVector = Icons.Default.Palette,
                                    contentDescription = "انتخاب تم",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = activeThemeItem.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        // Quick Settings shortcut button
                        IconButton(
                            onClick = { activeSubScreen = Screen.Settings },
                            modifier = Modifier.testTag("top_bar_settings_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "تنظیمات آموزشگاه",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        },
        bottomBar = {
            if (activeSubScreen == null) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp
                ) {
                    tabs.forEach { tab ->
                        val selected = (currentTab == tab.screen)
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                currentTab = tab.screen
                                activeSubScreen = null
                            },
                            icon = {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = tab.title
                                )
                            },
                            label = {
                                Text(
                                    text = tab.title,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer
                            ),
                            modifier = Modifier.testTag(tab.testTag)
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AnimatedContent(
                targetState = activeSubScreen ?: currentTab,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "screen_transition"
            ) { destination ->
                when (destination) {
                    Screen.Dashboard -> {
                        DashboardScreen(
                            viewModel = viewModel,
                            user = currentUser,
                            settings = settings,
                            stats = stats,
                            onNavigateToStudents = { currentTab = Screen.Students },
                            onNavigateToCourses = { activeSubScreen = Screen.Courses },
                            onNavigateToPayments = { currentTab = Screen.Payments },
                            onNavigateToReports = { currentTab = Screen.Reports },
                            onNavigateToSettings = { activeSubScreen = Screen.Settings },
                            onOpenAddStudent = { showQuickAddStudentDialog = true },
                            onLoadSampleData = { viewModel.loadSampleData() },
                            onClearSampleData = { viewModel.clearSampleData() }
                        )
                    }

                    Screen.Students -> {
                        StudentsScreen(
                            viewModel = viewModel
                        )
                    }

                    Screen.Payments -> {
                        PaymentsScreen(
                            viewModel = viewModel
                        )
                    }

                    Screen.Bot -> {
                        BotScreen(
                            viewModel = viewModel
                        )
                    }

                    Screen.Reports -> {
                        ReportsScreen(
                            viewModel = viewModel
                        )
                    }

                    Screen.More -> {
                        MoreScreen(
                            user = currentUser,
                            settings = settings,
                            onNavigateToCourses = { activeSubScreen = Screen.Courses },
                            onNavigateToSettings = { activeSubScreen = Screen.Settings },
                            onNavigateToAuditLogs = { activeSubScreen = Screen.AuditLogs },
                            onNavigateToReminders = { activeSubScreen = Screen.Reminders },
                            onOpenChangePassword = { showChangePasswordDialog = true },
                            onLogout = { viewModel.logout() }
                        )
                    }

                    Screen.Reminders -> {
                        RemindersScreen(
                            viewModel = viewModel,
                            onBack = { activeSubScreen = null }
                        )
                    }

                    Screen.Courses -> {
                        CoursesScreen(
                            courses = courses,
                            currencyUnit = settings?.currencyUnit ?: "تومان",
                            onAddCourse = { title, duration, fee, installments, desc, isActive ->
                                viewModel.addCourse(title, duration, fee, installments, desc, isActive) { _, _ -> }
                            },
                            onUpdateCourse = { course ->
                                viewModel.updateCourse(course) { _, _ -> }
                            },
                            onDeleteCourse = { course ->
                                viewModel.deleteCourse(course)
                            }
                        )
                    }

                    Screen.Settings -> {
                        SettingsScreen(
                            viewModel = viewModel,
                            onBack = { activeSubScreen = null }
                        )
                    }

                    Screen.AuditLogs -> {
                        AuditLogsScreen(
                            logs = auditLogs,
                            onBack = { activeSubScreen = null }
                        )
                    }

                    else -> {
                        DashboardScreen(
                            viewModel = viewModel,
                            user = currentUser,
                            settings = settings,
                            stats = stats,
                            onNavigateToStudents = { currentTab = Screen.Students },
                            onNavigateToCourses = { activeSubScreen = Screen.Courses },
                            onNavigateToPayments = { currentTab = Screen.Payments },
                            onNavigateToReports = { currentTab = Screen.Reports },
                            onNavigateToSettings = { activeSubScreen = Screen.Settings },
                            onOpenAddStudent = { showQuickAddStudentDialog = true },
                            onLoadSampleData = { viewModel.loadSampleData() },
                            onClearSampleData = { viewModel.clearSampleData() }
                        )
                    }
                }
            }
        }
    }

    if (showQuickAddStudentDialog) {
        AddEditStudentDialog(
            studentToEdit = null,
            onDismiss = { showQuickAddStudentDialog = false },
            onConfirm = { fName, lName, phone, natCode, notes ->
                viewModel.addStudent(fName, lName, phone, natCode, address = "", notes = notes) { _, _ -> }
                showQuickAddStudentDialog = false
            }
        )
    }

    if (showChangePasswordDialog) {
        ChangePasswordDialog(
            onDismiss = { showChangePasswordDialog = false },
            onConfirm = { oldPass, newPass ->
                viewModel.changePassword(oldPass, newPass) { success, _ ->
                    if (success) {
                        showChangePasswordDialog = false
                    }
                }
            }
        )
    }

    if (showQuickThemeDialog) {
        QuickThemeDialog(
            settings = settings,
            onDismiss = { showQuickThemeDialog = false },
            onSaveTheme = { themeName, themeMode ->
                val updated = (settings ?: AcademySettings()).copy(
                    themeName = themeName,
                    themeMode = themeMode
                )
                viewModel.updateSettings(updated) {}
            }
        )
    }
}
