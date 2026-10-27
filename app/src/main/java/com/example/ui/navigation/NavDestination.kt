package com.example.ui.navigation

sealed class Screen(val route: String, val title: String) {
    object Login : Screen("login", "ورود مدیر")
    object Dashboard : Screen("dashboard", "داشبورد")
    object Students : Screen("students", "هنرجویان")
    object Payments : Screen("payments", "پرداخت‌ها")
    object Bot : Screen("bot", "ربات")
    object Reports : Screen("reports", "گزارش‌ها")
    object More : Screen("more", "بیشتر")
    
    // Sub-screens under More
    object Courses : Screen("courses", "دوره‌ها")
    object CourseDetails : Screen("course_details", "جزئیات دوره")
    object Archive : Screen("archive", "بایگانی دوره‌ها")
    object Phonebook : Screen("phonebook", "دفترچه تلفن")
    object Settings : Screen("settings", "تنظیمات آموزشگاه")
    object AuditLogs : Screen("audit_logs", "تاریخچه تغییرات")
    object Reminders : Screen("reminders", "یادآوری‌ها و پیام‌ها")
    object Notifications : Screen("notifications", "اعلان‌ها")
    object Calendar : Screen("calendar", "تقویم شمسی")
    object InstallmentsList : Screen("installments_list", "مدیریت اقساط")
}
