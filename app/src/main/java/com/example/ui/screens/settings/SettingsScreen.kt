package com.example.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AcademySettings
import com.example.data.model.BankAccount
import com.example.data.model.MessageTemplate
import com.example.ui.theme.AmberDarkColorScheme
import com.example.ui.theme.BlueDarkColorScheme
import com.example.ui.theme.ForestDarkColorScheme
import com.example.ui.theme.GreenDarkColorScheme
import com.example.ui.theme.NavyDarkColorScheme
import com.example.ui.theme.OrangeDarkColorScheme
import com.example.ui.theme.PurpleDarkColorScheme
import com.example.ui.theme.RoseDarkColorScheme
import com.example.ui.theme.SlateDarkColorScheme
import com.example.ui.theme.TealDarkColorScheme
import com.example.ui.screens.dialogs.AddEditBankAccountDialog
import com.example.ui.viewmodel.MainViewModel

data class ThemeModelItem(
    val id: String,
    val name: String,
    val subName: String,
    val primaryColor: Color,
    val secondaryColor: Color
)

val availableThemeModels = listOf(
    ThemeModelItem("BLUE", "آبی حرفه‌ای", "Professional Blue", BlueDarkColorScheme.primary, BlueDarkColorScheme.tertiary),
    ThemeModelItem("GREEN", "سبز زمردی", "Emerald Green", GreenDarkColorScheme.primary, GreenDarkColorScheme.tertiary),
    ThemeModelItem("PURPLE", "بنفش سلطنتی", "Royal Purple", PurpleDarkColorScheme.primary, PurpleDarkColorScheme.tertiary),
    ThemeModelItem("ORANGE", "نارنجی پرانرژی", "Sunset Orange", OrangeDarkColorScheme.primary, OrangeDarkColorScheme.tertiary),
    ThemeModelItem("NAVY", "سرمه‌ای مدرن", "Deep Navy", NavyDarkColorScheme.primary, NavyDarkColorScheme.tertiary),
    ThemeModelItem("TEAL", "فیروزه‌ای ایرانی", "Persian Teal", TealDarkColorScheme.primary, TealDarkColorScheme.tertiary),
    ThemeModelItem("ROSE", "زرشکی و رزگلد", "Crimson Rose", RoseDarkColorScheme.primary, RoseDarkColorScheme.tertiary),
    ThemeModelItem("AMBER", "طلایی و کهربایی", "Golden Amber", AmberDarkColorScheme.primary, AmberDarkColorScheme.tertiary),
    ThemeModelItem("SLATE", "نوک‌مدادی گرافیتی", "Slate Graphite", SlateDarkColorScheme.primary, SlateDarkColorScheme.tertiary),
    ThemeModelItem("FOREST", "سبز زیتونی جنگلی", "Forest Olive", ForestDarkColorScheme.primary, ForestDarkColorScheme.tertiary)
)

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class,
    com.google.accompanist.permissions.ExperimentalPermissionsApi::class
)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    user: com.example.data.model.User? = null,
    activeSubScreenHeader: Boolean = true,
    onBack: () -> Unit,
    onNavigateToCourses: () -> Unit = {},
    onNavigateToAuditLogs: () -> Unit = {},
    onNavigateToReminders: () -> Unit = {},
    onOpenChangePassword: () -> Unit = {},
    onLogout: () -> Unit = {},
    onOpenBotTab: () -> Unit = {}
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    val templates by viewModel.messageTemplates.collectAsState()
    val bankAccounts by viewModel.bankAccounts.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val lastSyncSummary by viewModel.lastSyncSummary.collectAsState()
    val isOnline by viewModel.isOnline.collectAsState()

    // Footer hub state: which settings detail tab is open, and which hub dialog is showing.
    var settingsTab by remember { mutableStateOf(0) }
    var messageTab by remember { mutableStateOf(0) }
    var hubDialog by remember { mutableStateOf<String?>(null) }

    // Hub shortcuts that jump straight into the matching section
    fun jumpToMessageTab(tab: Int) {
        messageTab = tab
        settingsTab = 2
    }

    var showRestoreDialog by remember { mutableStateOf(false) }
    var restoreJsonInput by remember { mutableStateOf("") }
    var isRestoring by remember { mutableStateOf(false) }

    var showAddEditBankDialog by remember { mutableStateOf(false) }
    var editingBankAccount by remember { mutableStateOf<BankAccount?>(null) }
    var bankAccountToDelete by remember { mutableStateOf<BankAccount?>(null) }

    var academyName by remember(settings) { mutableStateOf(settings?.academyName ?: "آموزشگاه کامپیوتر") }
    var phone by remember(settings) { mutableStateOf(settings?.phone ?: "") }
    var address by remember(settings) { mutableStateOf(settings?.address ?: "") }
    var currencyUnit by remember(settings) { mutableStateOf(settings?.currencyUnit ?: "تومان") }

    // Theme & Appearance States
    // 0 ظاهر، 1 آموزشگاه و بانک، 2 پیام‌ها، 3 امنیت، 4 داده‌ها
    val showTheme = settingsTab == 0
    val showAcademy = settingsTab == 1
    val showBanks = settingsTab == 1
    val showMessages = settingsTab == 2
    val showSecurity = settingsTab == 3
    val showData = settingsTab == 4

    var selectedThemeName by remember(settings) { mutableStateOf(settings?.themeName ?: "BLUE") }
    var selectedThemeMode by remember(settings) { mutableStateOf(settings?.themeMode ?: "SYSTEM") }

    // Telegram Bot States

    // Automation toggles & Times & Admin Notification Templates (Titles & Bodies)
    var remind7Days by remember(settings) { mutableStateOf(settings?.reminder7DaysEnabled ?: true) }
    var remind7DaysTime by remember(settings) { mutableStateOf(settings?.reminder7DaysTime ?: "10:00") }
    var adminTitle7Days by remember(settings) { mutableStateOf(settings?.reminder7DaysAdminTitle ?: "🔔 یادآوری ۷ روز قبل: {studentName}") }
    var adminTemplate7Days by remember(settings) { mutableStateOf(settings?.reminder7DaysAdminTemplate ?: "یادآوری: ۷ روز تا موعد قسط {installmentNumber} هنرجو {studentName} به مبلغ {amount} ({courseName})") }

    var remind3Days by remember(settings) { mutableStateOf(settings?.reminder3DaysEnabled ?: true) }
    var remind3DaysTime by remember(settings) { mutableStateOf(settings?.reminder3DaysTime ?: "10:00") }
    var adminTitle3Days by remember(settings) { mutableStateOf(settings?.reminder3DaysAdminTitle ?: "🔔 یادآوری ۳ روز قبل: {studentName}") }
    var adminTemplate3Days by remember(settings) { mutableStateOf(settings?.reminder3DaysAdminTemplate ?: "یادآوری: ۳ روز تا موعد قسط {installmentNumber} هنرجو {studentName} به مبلغ {amount} باقی مانده است.") }

    var remind1Day by remember(settings) { mutableStateOf(settings?.reminder1DayEnabled ?: true) }
    var remind1DayTime by remember(settings) { mutableStateOf(settings?.reminder1DayTime ?: "10:00") }
    var adminTitle1Day by remember(settings) { mutableStateOf(settings?.reminder1DayAdminTitle ?: "⏰ یادآوری قسط فردا: {studentName}") }
    var adminTemplate1Day by remember(settings) { mutableStateOf(settings?.reminder1DayAdminTemplate ?: "یادآوری: فردا موعد قسط {installmentNumber} هنرجو {studentName} به مبلغ {amount} ({dueDate}) است.") }

    var remindDueDay by remember(settings) { mutableStateOf(settings?.reminderDueDayEnabled ?: true) }
    var remindDueDayTime by remember(settings) { mutableStateOf(settings?.reminderDueDayTime ?: "09:00") }
    var adminTitleDueDay by remember(settings) { mutableStateOf(settings?.reminderDueDayAdminTitle ?: "🚨 سررسید قسط امروز: {studentName}") }
    var adminTemplateDueDay by remember(settings) { mutableStateOf(settings?.reminderDueDayAdminTemplate ?: "یادآوری فوری: قسط {installmentNumber} هنرجو {studentName} به مبلغ {amount} امروز سررسید می‌شود.") }

    var remind1DayAfter by remember(settings) { mutableStateOf(settings?.reminder1DayAfterEnabled ?: true) }
    var remind1DayAfterTime by remember(settings) { mutableStateOf(settings?.reminder1DayAfterTime ?: "11:00") }
    var adminTitle1DayAfter by remember(settings) { mutableStateOf(settings?.reminder1DayAfterAdminTitle ?: "⚠️ هشدار ۱ روز دیرکرد: {studentName}") }
    var adminTemplate1DayAfter by remember(settings) { mutableStateOf(settings?.reminder1DayAfterAdminTemplate ?: "هشدار دیرکرد: ۱ روز از موعد قسط {installmentNumber} هنرجو {studentName} گذشته است. مانده: {remainingDebt}") }

    var remindOverdue by remember(settings) { mutableStateOf(settings?.reminder3DaysAfterEnabled ?: true) }
    var remind3DaysAfterTime by remember(settings) { mutableStateOf(settings?.reminder3DaysAfterTime ?: "11:00") }
    var adminTitle3DaysAfter by remember(settings) { mutableStateOf(settings?.reminder3DaysAfterAdminTitle ?: "⚠️ اخطار ۳ روز معوقه: {studentName}") }
    var adminTemplate3DaysAfter by remember(settings) { mutableStateOf(settings?.reminder3DaysAfterAdminTemplate ?: "اخطار معوقه: قسط {installmentNumber} هنرجو {studentName} دوره {courseName} به مبلغ {amount} معوق شده است.") }

    var sendPaymentReceiptMsg by remember(settings) { mutableStateOf(true) }

    // Security & App Lock States
    var managerUsername by remember(settings) { mutableStateOf(settings?.managerUsername ?: "admin") }
    var appLockEnabled by remember(settings) { mutableStateOf(settings?.appLockEnabled ?: false) }
    var appLockPin by remember(settings) {
        mutableStateOf(
            if (com.example.data.security.CredentialHasher.isHashed(settings?.appLockPin)) "" else (settings?.appLockPin ?: "")
        )
    }
    var appLockTimeoutMinutes by remember(settings) { mutableIntStateOf(settings?.appLockTimeoutMinutes ?: 5) }
    var showPinText by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (activeSubScreenHeader) {
                        Text(
                            text = "تنظیمات",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                navigationIcon = {
                    if (activeSubScreenHeader) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "بازگشت")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 0. Hub: everything that used to be in the "More" tab, grouped.
            SettingsHubSection(
                user = user,
                settings = settings,
                versionName = com.example.BuildConfig.VERSION_NAME,
                selectedTab = settingsTab,
                onSelectTab = { settingsTab = it },
                onNavigateToCourses = onNavigateToCourses,
                onNavigateToAuditLogs = onNavigateToAuditLogs,
                onNavigateToReminders = onNavigateToReminders,
                onOpenChangePassword = onOpenChangePassword,
                onOpenInstallmentFormula = { hubDialog = "installments" },
                onOpenCalendar = { hubDialog = "calendar" },
                onOpenAbout = { hubDialog = "about" },
                onOpenBackup = { hubDialog = "backup" },
                onOpenIntegrations = { hubDialog = "integrations" },
                onLogout = onLogout,
                onTestNotification = {
                    val helper = com.example.service.NotificationHelper(context)
                    helper.showInstallmentDueNotification(
                        notificationId = 9999,
                        studentName = "علی محمدی (تست)",
                        amountFormatted = "۱,۵۰۰,۰۰۰ تومان",
                        dueDateShamsi = com.example.data.util.PersianDateUtil.currentShamsiString(),
                        dueCategory = "امروز",
                        customTitle = "🔔 تست اعلان سررسید قسط",
                        customMessage = "سامانه یادآوری اقساط آموزشگاه با موفقیت فعال است."
                    )
                    Toast.makeText(context, "اعلان آزمایشی ارسال شد.", Toast.LENGTH_SHORT).show()
                }
            )

            // 1. Appearance & Theme Selection Card (بخش ظاهر برنامه و تم‌ها)
            if (showTheme) Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Palette,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column {
                            Text(
                                text = "ظاهر برنامه و انتخاب تم (Themes)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "انتخاب پالت رنگی و حالت روشنایی برنامه",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                    // Mode Selection: Light / Dark / System
                    Text(
                        text = "حالت نمایش (روشنایی / تاریکی):",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val modeOptions = listOf(
                            Triple("LIGHT", "روشن", Icons.Default.LightMode),
                            Triple("DARK", "تاریک", Icons.Default.DarkMode),
                            Triple("SYSTEM", "سیستم", Icons.Default.BrightnessAuto)
                        )

                        modeOptions.forEach { (modeKey, modeTitle, modeIcon) ->
                            val isSelected = selectedThemeMode == modeKey
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    selectedThemeMode = modeKey
                                    // Save immediately for instant live feedback
                                    val current = (settings ?: AcademySettings()).copy(
                                        themeMode = modeKey,
                                        themeName = selectedThemeName
                                    )
                                    viewModel.updateSettings(current) {}
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = modeIcon,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                label = {
                                    Text(
                                        text = modeTitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Palette Models Grid
                    Text(
                        text = "مدل‌های تم و پالت‌های رنگی (${availableThemeModels.size} تم متنوع):",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        maxItemsInEachRow = 2
                    ) {
                        availableThemeModels.forEach { item ->
                            val isSelected = selectedThemeName == item.id
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable {
                                        selectedThemeName = item.id
                                        val current = (settings ?: AcademySettings()).copy(
                                            themeName = item.id,
                                            themeMode = selectedThemeMode
                                        )
                                        viewModel.updateSettings(current) {}
                                    }
                                    .border(
                                        width = if (isSelected) 2.dp else 1.dp,
                                        color = if (isSelected) item.primaryColor else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                        shape = RoundedCornerShape(14.dp)
                                    ),
                                color = if (isSelected) item.primaryColor.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // Color preview circles
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(item.primaryColor),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }

                                    Box(
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clip(CircleShape)
                                            .background(item.secondaryColor)
                                    )

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.name,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) item.primaryColor else MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = item.subName,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                            fontSize = 9.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 2. Academy Profile Card
            if (showAcademy) Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "مشخصات عمومی آموزشگاه",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    OutlinedTextField(
                        value = academyName,
                        onValueChange = { academyName = it },
                        label = { Text("نام آموزشگاه") },
                        leadingIcon = { Icon(Icons.Default.School, contentDescription = null) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("شماره تماس") },
                        leadingIcon = { Icon(Icons.Default.Call, contentDescription = null) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("آدرس و نشانی") },
                        leadingIcon = { Icon(Icons.Default.LocationOn, contentDescription = null) },
                        minLines = 2,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // 3. Bank Accounts & Payment Cards Card
            if (showBanks) Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.CreditCard,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "حساب‌ها و کارت‌های بانکی آموزشگاه",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "شماره کارت و شبای آموزشگاه جهت دریافت شهریه و درج در قبوض",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        FilledTonalButton(
                            onClick = {
                                editingBankAccount = null
                                showAddEditBankDialog = true
                            },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("کارت جدید", fontSize = 12.sp)
                        }
                    }

                    if (bankAccounts.isEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CreditCard,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(36.dp)
                                )
                                Text(
                                    text = "هنوز حساب یا کارتی ثبت نشده است.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Button(
                                    onClick = {
                                        editingBankAccount = null
                                        showAddEditBankDialog = true
                                    },
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("افزودن حساب / کارت بانکی")
                                }
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            bankAccounts.forEach { acc ->
                                BankAccountItemCard(
                                    account = acc,
                                    onSetDefault = { viewModel.setDefaultBankAccount(acc) },
                                    onEdit = {
                                        editingBankAccount = acc
                                        showAddEditBankDialog = true
                                    },
                                    onDelete = {
                                        bankAccountToDelete = acc
                                    },
                                    onCopy = { text, label ->
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
                                        Toast.makeText(context, "$label در حافظه کپی شد: $text", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // 4. Telegram bot credentials live in the Bot tab only.
            if (showBanks) Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .clickable { onOpenBotTab() },
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.SmartToy, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "تنظیمات ربات تلگرام",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "توکن ربات، سرور Cloudflare، ضد اسپم و ساعات سکوت",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Notification Permission Status (Android 13+)
            if (showMessages && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                val notifPermissionState = com.google.accompanist.permissions.rememberPermissionState(
                    android.Manifest.permission.POST_NOTIFICATIONS
                )
                val isGranted = notifPermissionState.status is com.google.accompanist.permissions.PermissionStatus.Granted

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (isGranted) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = if (isGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = if (isGranted) "مجوز اعلان‌های اندروید فعال است" else "مجوز ارسال اعلان داده نشده است",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (isGranted) "یادآوری‌های اقساط و موعدها در زمان مشخص به عنوان نوتیفیکیشن نمایش داده خواهند شد." else "برای دریافت خودکار یادآوری‌ها در نوتیفیکیشن‌بار، روی فعال‌سازی بزنید.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (!isGranted) {
                            Button(
                                onClick = { notifPermissionState.launchPermissionRequest() },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Text("فعال‌سازی", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            // 4. Dedicated Message & Reminder Center (مرکز تنظیم پیام‌ها و یادآوری‌ها)
            if (showMessages) MessageSettingsSection(
                viewModel = viewModel,
                settings = settings,
                selectedTab = messageTab,
                onSelectedTabChange = { messageTab = it },
                remind7Days = remind7Days,
                remind7DaysTime = remind7DaysTime,
                adminTitle7Days = adminTitle7Days,
                adminTemplate7Days = adminTemplate7Days,
                onAdmin7DaysChange = { checked, time, title, tmpl ->
                    remind7Days = checked
                    remind7DaysTime = time
                    adminTitle7Days = title
                    adminTemplate7Days = tmpl
                },
                remind3Days = remind3Days,
                remind3DaysTime = remind3DaysTime,
                adminTitle3Days = adminTitle3Days,
                adminTemplate3Days = adminTemplate3Days,
                onAdmin3DaysChange = { checked, time, title, tmpl ->
                    remind3Days = checked
                    remind3DaysTime = time
                    adminTitle3Days = title
                    adminTemplate3Days = tmpl
                },
                remind1Day = remind1Day,
                remind1DayTime = remind1DayTime,
                adminTitle1Day = adminTitle1Day,
                adminTemplate1Day = adminTemplate1Day,
                onAdmin1DayChange = { checked, time, title, tmpl ->
                    remind1Day = checked
                    remind1DayTime = time
                    adminTitle1Day = title
                    adminTemplate1Day = tmpl
                },
                remindDueDay = remindDueDay,
                remindDueDayTime = remindDueDayTime,
                adminTitleDueDay = adminTitleDueDay,
                adminTemplateDueDay = adminTemplateDueDay,
                onAdminDueDayChange = { checked, time, title, tmpl ->
                    remindDueDay = checked
                    remindDueDayTime = time
                    adminTitleDueDay = title
                    adminTemplateDueDay = tmpl
                },
                remind1DayAfter = remind1DayAfter,
                remind1DayAfterTime = remind1DayAfterTime,
                adminTitle1DayAfter = adminTitle1DayAfter,
                adminTemplate1DayAfter = adminTemplate1DayAfter,
                onAdmin1DayAfterChange = { checked, time, title, tmpl ->
                    remind1DayAfter = checked
                    remind1DayAfterTime = time
                    adminTitle1DayAfter = title
                    adminTemplate1DayAfter = tmpl
                },
                remindOverdue = remindOverdue,
                remind3DaysAfterTime = remind3DaysAfterTime,
                adminTitle3DaysAfter = adminTitle3DaysAfter,
                adminTemplate3DaysAfter = adminTemplate3DaysAfter,
                onAdmin3DaysAfterChange = { checked, time, title, tmpl ->
                    remindOverdue = checked
                    remind3DaysAfterTime = time
                    adminTitle3DaysAfter = title
                    adminTemplate3DaysAfter = tmpl
                }
            )

            // Testing and Reschedule Notification Action Buttons Card
            if (showMessages) Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            viewModel.triggerTestGeneralNotification(
                                title = "یادآوری سررسید قسط (تست)",
                                body = "سررسید قسط شماره ۲ هنرجو علی رضایی دوره طراحی وب فردا ساعت ۱۰:۰۰ می‌باشد."
                            )
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.NotificationsActive, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("تست نوتیفیکیشن", style = MaterialTheme.typography.bodySmall)
                    }

                    FilledTonalButton(
                        onClick = {
                            viewModel.rescheduleAllReminders { count ->
                                Toast.makeText(context, "زمانبندی $count یادآوری در سیستم با موفقیت بروز شد.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("بروزرسانی آلارم‌ها", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // 5. Cloud Sync & Backup Data Card
            if (showData) Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "همگام‌سازی ابری و پشتیبان‌گیری",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        // Online / Offline Status Badge
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isOnline) Color(0xFFDCFCE7) else Color(0xFFFEE2E2)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    if (isOnline) Icons.Default.Wifi else Icons.Default.WifiOff,
                                    contentDescription = null,
                                    tint = if (isOnline) Color(0xFF15803D) else Color(0xFFB91C1C),
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = if (isOnline) "آنلاین" else "آفلاین (محلی)",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isOnline) Color(0xFF15803D) else Color(0xFFB91C1C)
                                )
                            }
                        }
                    }

                    Text(
                        text = "داده‌های برنامه در پایگاه داده محلی (Room) ذخیره شده و با فایربیس (Firestore) جهت جلوگیری از دست رفتن اطلاعات همگام می‌شوند.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Firebase Project & Runtime Configuration Diagnostic Card
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "اطلاعات پروژه فایربیس در حال اجرا:",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            val fbApp = try { com.google.firebase.FirebaseApp.getInstance() } catch (_: Exception) { null }
                            val opts = fbApp?.options
                            val authUser = try { com.google.firebase.auth.FirebaseAuth.getInstance().currentUser } catch (_: Exception) { null }
                            val key = opts?.apiKey ?: "N/A"
                            val maskedKey = if (key.length > 4) "...${key.takeLast(4)}" else key

                            Text(text = "• Project ID: ${opts?.projectId ?: "N/A"}", fontSize = 11.sp)
                            Text(text = "• Application ID: ${opts?.applicationId ?: "N/A"}", fontSize = 11.sp)
                            Text(text = "• API Key (Last 4): $maskedKey", fontSize = 11.sp)
                            Text(text = "• Auth UID: ${authUser?.uid ?: "کاربر وارد نشده"}", fontSize = 11.sp)
                            Text(text = "• Auth Email: ${authUser?.email ?: "N/A"}", fontSize = 11.sp)
                            Text(text = "• Target Collection: students/{studentId}", fontSize = 11.sp)
                            Text(text = "• Database ID: (default)", fontSize = 11.sp)
                        }
                    }

                    // Cloud Sync Button
                    Button(
                        onClick = {
                            viewModel.syncWithCloud { success, message ->
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isSyncing,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("در حال همگام‌سازی...")
                        } else {
                            Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("همگام‌سازی ابری با فایربیس")
                        }
                    }

                    if (lastSyncSummary != null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Text(
                                text = "آخرین وضعیت: ${lastSyncSummary?.message ?: ""} (${lastSyncSummary?.timestampShamsi ?: ""})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(8.dp),
                                fontSize = 11.sp
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                    Text(
                        text = "پشتیبان‌گیری آفلاین JSON:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                viewModel.exportDatabaseJson(includeSecrets = false) { json ->
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("پشتیبان پایگاه داده", json)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "اطلاعات پشتیبان در کلیپ‌بورد کپی شد.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("کپی JSON", fontSize = 11.sp)
                        }

                        Button(
                            onClick = {
                                viewModel.exportDatabaseJson(includeSecrets = false) { json ->
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/json"
                                        putExtra(Intent.EXTRA_TEXT, json)
                                    }
                                    context.startActivity(Intent.createChooser(intent, "اشتراک‌گذاری فایل پشتیبان JSON"))
                                }
                            },
                            modifier = Modifier.weight(1.2f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("اشتراک فایل", fontSize = 11.sp)
                        }

                        FilledTonalButton(
                            onClick = {
                                restoreJsonInput = ""
                                showRestoreDialog = true
                            },
                            modifier = Modifier.weight(1.1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("بازیابی", fontSize = 11.sp)
                        }
                    }
                }
            }

            // 6. Security & App Lock Card
            if (showSecurity) Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "امنیت و حساب کاربری مدیر",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    // Manager Username Setting
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "نام کاربری مدیر (Manager Username):",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        OutlinedTextField(
                            value = managerUsername,
                            onValueChange = { managerUsername = it },
                            label = { Text("نام کاربری مدیر") },
                            placeholder = { Text("مثلاً admin یا manager") },
                            leadingIcon = { Icon(Icons.Default.AccountCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            singleLine = true,
                            isError = managerUsername.trim().length < 3,
                            supportingText = {
                                if (managerUsername.trim().length < 3) {
                                    Text("نام کاربری باید حداقل ۳ کاراکتر باشد", color = MaterialTheme.colorScheme.error)
                                } else {
                                    Text("از این نام کاربری برای ورود به برنامه استفاده می‌شود.")
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("manager_username_input")
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "قفل برنامه با پین (App Lock)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "درخواست رمز عبور هنگام بازگشت به برنامه",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Switch(
                            checked = appLockEnabled,
                            onCheckedChange = { appLockEnabled = it }
                        )
                    }

                    if (appLockEnabled) {
                        OutlinedTextField(
                            value = appLockPin,
                            onValueChange = { if (it.length <= 8 && it.all { char -> char.isDigit() }) appLockPin = it },
                            label = { Text("رمز ورود (PIN ۴ تا ۸ رقمی)") },
                            placeholder = { Text("مثلاً ۱۲۳۴") },
                            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                            trailingIcon = {
                                IconButton(onClick = { showPinText = !showPinText }) {
                                    Icon(
                                        imageVector = if (showPinText) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (showPinText) "مخفی‌سازی رمز" else "نمایش رمز"
                                    )
                                }
                            },
                            visualTransformation = if (showPinText) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "مدت زمان قفل خودکار پس از رفتن برنامه به پس‌زمینه:",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        val timeoutOptions = listOf(
                            Pair(0, "فوری"),
                            Pair(1, "۱ دقیقه"),
                            Pair(5, "۵ دقیقه"),
                            Pair(15, "۱۵ دقیقه"),
                            Pair(30, "۳۰ دقیقه")
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            timeoutOptions.forEach { (mins, label) ->
                                FilterChip(
                                    selected = appLockTimeoutMinutes == mins,
                                    onClick = { appLockTimeoutMinutes = mins },
                                    label = { Text(label, style = MaterialTheme.typography.bodySmall) }
                                )
                            }
                        }
                    }
                }
            }

            // Save Settings Button
            Button(
                onClick = {
                    if (managerUsername.trim().length < 3) {
                        Toast.makeText(context, "نام کاربری مدیر باید حداقل ۳ کاراکتر باشد.", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    if (appLockEnabled && appLockPin.length < 4) {
                        Toast.makeText(context, "لطفاً رمز عبور حداقل ۴ رقمی وارد کنید.", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    val updated = (settings ?: AcademySettings()).copy(
                        academyName = academyName,
                        phone = phone,
                        address = address,
                        currencyUnit = currencyUnit,
                        themeName = selectedThemeName,
                        themeMode = selectedThemeMode,
                        reminder7DaysEnabled = remind7Days,
                        reminder7DaysTime = remind7DaysTime,
                        reminder7DaysAdminTitle = adminTitle7Days,
                        reminder7DaysAdminTemplate = adminTemplate7Days,
                        reminder3DaysEnabled = remind3Days,
                        reminder3DaysTime = remind3DaysTime,
                        reminder3DaysAdminTitle = adminTitle3Days,
                        reminder3DaysAdminTemplate = adminTemplate3Days,
                        reminder1DayEnabled = remind1Day,
                        reminder1DayTime = remind1DayTime,
                        reminder1DayAdminTitle = adminTitle1Day,
                        reminder1DayAdminTemplate = adminTemplate1Day,
                        reminderDueDayEnabled = remindDueDay,
                        reminderDueDayTime = remindDueDayTime,
                        reminderDueDayAdminTitle = adminTitleDueDay,
                        reminderDueDayAdminTemplate = adminTemplateDueDay,
                        reminder1DayAfterEnabled = remind1DayAfter,
                        reminder1DayAfterTime = remind1DayAfterTime,
                        reminder1DayAfterAdminTitle = adminTitle1DayAfter,
                        reminder1DayAfterAdminTemplate = adminTemplate1DayAfter,
                        reminder3DaysAfterEnabled = remindOverdue,
                        reminder3DaysAfterTime = remind3DaysAfterTime,
                        reminder3DaysAfterAdminTitle = adminTitle3DaysAfter,
                        reminder3DaysAfterAdminTemplate = adminTemplate3DaysAfter,
                        managerUsername = managerUsername.trim(),
                        appLockEnabled = appLockEnabled,
                        appLockPin = when {
                            appLockPin.isBlank() -> (settings?.appLockPin ?: "")
                            com.example.data.security.CredentialHasher.isHashed(appLockPin) -> appLockPin
                            else -> com.example.data.security.CredentialHasher.hash(appLockPin)
                        },
                        appLockTimeoutMinutes = appLockTimeoutMinutes
                    )
                    viewModel.updateSettings(updated) { success ->
                        if (success) {
                            Toast.makeText(context, "تنظیمات، نام کاربری و زمانبندی یادآوری‌ها با موفقیت ذخیره شد.", Toast.LENGTH_SHORT).show()
                            viewModel.rescheduleAllReminders()
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("save_settings_button"),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "ذخیره تغییرات آموزشگاه",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // Dialog for Add / Edit Bank Account
    if (showAddEditBankDialog) {
        AddEditBankAccountDialog(
            initialAccount = editingBankAccount,
            onDismiss = {
                showAddEditBankDialog = false
                editingBankAccount = null
            },
            onSave = { bankName, type, accountTitle, cardNumber, shabaNumber, accountNumber, accountHolder, isDefault ->
                if (editingBankAccount != null) {
                    viewModel.updateBankAccount(
                        editingBankAccount!!.copy(
                            bankName = bankName,
                            type = type,
                            accountTitle = accountTitle,
                            cardNumber = cardNumber,
                            shabaNumber = shabaNumber,
                            accountNumber = accountNumber,
                            accountHolder = accountHolder,
                            isDefault = isDefault
                        )
                    )
                } else {
                    viewModel.addBankAccount(
                        bankName = bankName,
                        type = type,
                        accountTitle = accountTitle,
                        cardNumber = cardNumber,
                        shabaNumber = shabaNumber,
                        accountNumber = accountNumber,
                        accountHolder = accountHolder,
                        isDefault = isDefault
                    )
                }
                showAddEditBankDialog = false
                editingBankAccount = null
            }
        )
    }

    // Dialog for Delete Bank Account confirmation
    if (bankAccountToDelete != null) {
        AlertDialog(
            onDismissRequest = { bankAccountToDelete = null },
            title = {
                Text(
                    text = "حذف حساب بانکی",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            },
            text = {
                Text("آیا از حذف حساب «${bankAccountToDelete?.accountTitle}» متعلق به ${bankAccountToDelete?.bankName} اطمینان دارید؟")
            },
            confirmButton = {
                Button(
                    onClick = {
                        bankAccountToDelete?.let { viewModel.deleteBankAccount(it) }
                        bankAccountToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("حذف حساب")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { bankAccountToDelete = null }) {
                    Text("انصراف")
                }
            }
        )
    }

    SettingsHubDialogs(
        which = hubDialog,
        settings = settings,
        versionName = com.example.BuildConfig.VERSION_NAME,
        onClose = { hubDialog = null },
        onGoToBotSettings = { settingsTab = 1 }
    )

    // Restore Backup Dialog
    if (showRestoreDialog) {
        AlertDialog(
            onDismissRequest = { if (!isRestoring) showRestoreDialog = false },
            title = {
                Text(
                    text = "بازیابی اطلاعات از فایل پشتیبان JSON",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "متن محتوای فایل JSON نسخه پشتیبان را در کادر زیر جای‌گذاری (Paste) کنید:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = restoreJsonInput,
                        onValueChange = { restoreJsonInput = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        placeholder = { Text("{\n  \"exportDateShamsi\": ...\n}", fontSize = 12.sp) },
                        shape = RoundedCornerShape(12.dp),
                        singleLine = false,
                        maxLines = 8
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                                if (clipText.isNotBlank()) {
                                    restoreJsonInput = clipText
                                    Toast.makeText(context, "متن از کلیپ‌بورد درج شد.", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "کلیپ‌بورد خالی است.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("درج از کلیپ‌بورد", fontSize = 11.sp)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (restoreJsonInput.isBlank()) {
                            Toast.makeText(context, "لطفاً ابتدا داده‌های JSON را وارد کنید.", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        isRestoring = true
                        viewModel.restoreBackupJson(restoreJsonInput) { success, message ->
                            isRestoring = false
                            if (success) {
                                showRestoreDialog = false
                                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    enabled = !isRestoring && restoreJsonInput.isNotBlank(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    if (isRestoring) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("در حال بازیابی...")
                    } else {
                        Text("شروع بازیابی")
                    }
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showRestoreDialog = false },
                    enabled = !isRestoring,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("انصراف")
                }
            }
        )
    }
}

@Composable
private fun BankAccountItemCard(
    account: BankAccount,
    onSetDefault: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCopy: (String, String) -> Unit
) {
    val bankColors = remember(account.bankName) {
        when {
            account.bankName.contains("ملت") -> listOf(Color(0xFFB71C1C), Color(0xFFD32F2F))
            account.bankName.contains("ملی") -> listOf(Color(0xFF0D47A1), Color(0xFF1976D2))
            account.bankName.contains("سامان") -> listOf(Color(0xFF006064), Color(0xFF00838F))
            account.bankName.contains("پاسارگاد") -> listOf(Color(0xFFE65100), Color(0xFFFB8C00))
            account.bankName.contains("بلو") -> listOf(Color(0xFF1565C0), Color(0xFF03A9F4))
            account.bankName.contains("رسالت") -> listOf(Color(0xFF1B5E20), Color(0xFF388E3C))
            account.bankName.contains("تجارت") -> listOf(Color(0xFF283593), Color(0xFF3F51B5))
            account.bankName.contains("صادرات") -> listOf(Color(0xFF4A148C), Color(0xFF7B1FA2))
            else -> listOf(Color(0xFF334155), Color(0xFF475569))
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
        border = if (account.isDefault) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Header Row: Bank + Default Badge + Menu Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(Brush.horizontalGradient(bankColors))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = account.bankName.ifBlank { "بانک" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "(${account.accountTitle})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (account.isDefault) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "پیش‌فرض",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // Card Number
            if (account.cardNumber.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "شماره کارت ۱۶ رقمی:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = account.displayCardNumber,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    FilledTonalButton(
                        onClick = { onCopy(account.cardNumber, "شماره کارت") },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("کپی", fontSize = 11.sp)
                    }
                }
            }

            // Shaba Number
            if (account.shabaNumber.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "شماره شبا (IBAN):",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = account.displayShaba,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            val cleanShaba = if (account.shabaNumber.startsWith("IR", ignoreCase = true)) account.shabaNumber.uppercase() else "IR${account.shabaNumber}"
                            onCopy(cleanShaba, "شماره شبا")
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("کپی شبا", fontSize = 11.sp)
                    }
                }
            }

            // Account Number & Account Holder
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (account.accountHolder.isNotBlank()) {
                    Text(
                        text = "صاحب حساب: ${account.accountHolder}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (account.accountNumber.isNotBlank()) {
                    Text(
                        text = "شماره حساب: ${account.accountNumber}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Actions row: Set Default, Edit, Delete
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!account.isDefault) {
                    OutlinedButton(
                        onClick = onSetDefault,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Outlined.StarBorder, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("انتخاب پیش‌فرض", fontSize = 11.sp)
                    }
                }

                FilledTonalButton(
                    onClick = onEdit,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("ویرایش", fontSize = 11.sp)
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "حذف حساب",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

