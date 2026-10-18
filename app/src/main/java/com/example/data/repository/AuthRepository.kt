package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.data.model.AuditLog
import com.example.data.model.User
import com.example.data.security.CredentialHasher
import com.example.data.remote.FirebaseManager
import com.example.data.util.PersianDateUtil
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class AuthRepository(
    private val context: Context,
    private val firebaseManager: FirebaseManager
) {
    private val tag = "AuthRepository"
    private val prefs: SharedPreferences = context.getSharedPreferences("academy_auth_prefs", Context.MODE_PRIVATE)

    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> = _currentUser.asStateFlow()

    private val _isAuthenticated = MutableStateFlow(false)
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    init {
        migrateLegacyPlainPassword()
        checkSavedSession()
    }

    /** Old builds stored the password in clear; drop it and keep only a verifier when possible. */
    private fun migrateLegacyPlainPassword() {
        val legacy = prefs.getString("admin_pass", null) ?: return
        val editor = prefs.edit()
        if (CredentialHasher.needsUpgrade(legacy) || !CredentialHasher.isHashed(legacy)) {
            editor.putString(KEY_PASSWORD_HASH, CredentialHasher.hash(legacy))
        }
        editor.remove("admin_pass")
        editor.apply()
    }

    private fun checkSavedSession() {
        val auth = firebaseManager.auth
        if (auth?.currentUser != null) {
            val fbUser = auth.currentUser!!
            val user = User(
                id = fbUser.uid,
                email = fbUser.email ?: "admin@academy.ir",
                fullName = fbUser.displayName ?: "مدیر آموزشگاه",
                role = "ADMIN"
            )
            _currentUser.value = user
            _isAuthenticated.value = true
        } else {
            // Check local session
            val isLoggedIn = prefs.getBoolean("is_logged_in", false)
            if (isLoggedIn) {
                val email = prefs.getString("user_email", "admin@academy.ir") ?: "admin@academy.ir"
                val name = prefs.getString("user_name", "مدیر آموزشگاه") ?: "مدیر آموزشگاه"
                val user = User(
                    id = prefs.getString("user_id", "admin_local") ?: "admin_local",
                    email = email,
                    fullName = name,
                    role = "ADMIN"
                )
                _currentUser.value = user
                _isAuthenticated.value = true

                // Session restore does not need the password; Firestore auth is restored by Firebase itself.
            } else {
                _currentUser.value = null
                _isAuthenticated.value = false
            }
        }
    }

    fun setManagerUsername(newUsername: String) {
        val trimmed = newUsername.trim()
        if (trimmed.isNotEmpty()) {
            prefs.edit().putString("manager_username", trimmed).apply()
            val current = _currentUser.value
            if (current != null) {
                _currentUser.value = current.copy(
                    email = if (current.email.contains("@")) current.email else "$trimmed@academy.ir"
                )
            }
        }
    }

    suspend fun login(email: String, password: String): Result<User> {
        val trimmedEmail = email.trim()
        val trimmedPassword = password.trim()

        if (trimmedEmail.isEmpty() || trimmedPassword.isEmpty()) {
            return Result.failure(Exception("لطفاً ایمیل / نام کاربری و رمز عبور را وارد نمایید."))
        }

        val savedUsername = prefs.getString("manager_username", "admin") ?: "admin"
        val storedHash = prefs.getString(KEY_PASSWORD_HASH, "") ?: ""
        val isUsernameMatch = trimmedEmail.equals("admin", ignoreCase = true) ||
                trimmedEmail.equals(savedUsername, ignoreCase = true) ||
                trimmedEmail.equals(prefs.getString("user_email", ""), ignoreCase = true) ||
                trimmedEmail.equals("admin@academy.ir", ignoreCase = true)

        // 1. Local verifier (works offline, and is the only path when Firebase is not configured)
        if (storedHash.isNotBlank()) {
            if (!isUsernameMatch) {
                return Result.failure(Exception("نام کاربری یا رمز عبور نادرست است."))
            }
            if (!CredentialHasher.verify(trimmedPassword, storedHash)) {
                return Result.failure(Exception("نام کاربری یا رمز عبور نادرست است."))
            }
            // Upgrade a legacy plain-text value to a proper hash
            if (CredentialHasher.needsUpgrade(storedHash)) {
                prefs.edit().putString(KEY_PASSWORD_HASH, CredentialHasher.hash(trimmedPassword)).apply()
            }
            val user = buildUser(
                id = prefs.getString("user_id", "admin_local") ?: "admin_local",
                email = prefs.getString("user_email", "")
                    ?: if (trimmedEmail.contains("@")) trimmedEmail else "$trimmedEmail@academy.ir",
                name = prefs.getString("user_name", "مدیر آموزشگاه") ?: "مدیر آموزشگاه"
            )
            saveSession(user)
            trySignInToFirebaseIfPossible(user.email, trimmedPassword)
            return Result.success(user)
        }

        // 2. First login on a fresh install: the first successful Firebase login (or the
        // very first local login) becomes the stored credential. No password of any length
        // is accepted without either Firebase or a previously stored verifier.
        val auth = firebaseManager.auth
        if (auth != null) {
            val authEmail = if (trimmedEmail.contains("@")) trimmedEmail else "$trimmedEmail@academy.ir"
            try {
                val authResult = auth.signInWithEmailAndPassword(authEmail, trimmedPassword).await()
                val fbUser = authResult.user
                val user = buildUser(
                    id = fbUser?.uid ?: "admin_uid",
                    email = fbUser?.email ?: authEmail,
                    name = fbUser?.displayName ?: "مدیر آموزشگاه"
                )
                saveSession(user, trimmedPassword)
                return Result.success(user)
            } catch (e: Exception) {
                Log.w(tag, "FirebaseAuth signIn failed: ${e.message}")
                return Result.failure(
                    Exception("ورود ناموفق بود. در اولین بار باید اتصال اینترنت و حساب Firebase برقرار باشد، یا از رمزی که قبلاً روی این دستگاه تعریف شده استفاده کنید.")
                )
            }
        }

        // No Firebase at all: only allow creating the very first local credential.
        if (prefs.getBoolean(KEY_FIRST_RUN_DONE, false)) {
            return Result.failure(Exception("اطلاعات ورود نادرست است."))
        }
        if (trimmedPassword.length < 6) {
            return Result.failure(Exception("رمز عبور باید حداقل ۶ کاراکتر باشد."))
        }
        val user = buildUser(
            id = "admin_local",
            email = if (trimmedEmail.contains("@")) trimmedEmail else "$trimmedEmail@academy.ir",
            name = "مدیر آموزشگاه"
        )
        saveSession(user, trimmedPassword)
        return Result.success(user)
    }

    private fun buildUser(id: String, email: String, name: String) = User(
        id = id,
        email = email,
        fullName = name,
        role = "ADMIN"
    )

    /** Best-effort background Firebase sign-in so Firestore rules see an authenticated user. */
    private fun trySignInToFirebaseIfPossible(email: String, password: String) {
        val auth = firebaseManager.auth ?: return
        if (email.isBlank() || password.isBlank()) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                auth.signInWithEmailAndPassword(email, password).await()
            } catch (e: Exception) {
                Log.d(tag, "Background Firebase auth not available: ${e.message}")
            }
        }
    }

    suspend fun logout(): Result<Unit> {
        try {
            firebaseManager.auth?.signOut()
            prefs.edit().clear().apply()
            _currentUser.value = null
            _isAuthenticated.value = false
            return Result.success(Unit)
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    suspend fun changePassword(oldPass: String, newPass: String): Result<Unit> {
        if (newPass.length < 6) {
            return Result.failure(Exception("رمز عبور جدید باید حداقل ۶ کاراکتر باشد."))
        }

        val auth = firebaseManager.auth
        val user = auth?.currentUser
        if (user != null) {
            try {
                user.updatePassword(newPass).await()
                return Result.success(Unit)
            } catch (e: Exception) {
                Log.w(tag, "FirebaseAuth updatePassword failed: ${e.message}")
            }
        }
        // Local update (hashed, never plain text)
        prefs.edit().putString(KEY_PASSWORD_HASH, CredentialHasher.hash(newPass)).apply()
        return Result.success(Unit)
    }

    private fun saveSession(user: User, password: String = "") {
        val editor = prefs.edit()
            .putBoolean("is_logged_in", true)
            .putBoolean(KEY_FIRST_RUN_DONE, true)
            .putString("user_id", user.id)
            .putString("user_email", user.email)
            .putString("user_name", user.fullName)
        if (password.isNotBlank()) {
            editor.putString(KEY_PASSWORD_HASH, CredentialHasher.hash(password))
        }
        // Remove the legacy plain-text password if an old install left one behind
        editor.remove("admin_pass")
        editor.apply()
        _currentUser.value = user
        _isAuthenticated.value = true
    }

    companion object {
        const val KEY_PASSWORD_HASH = "admin_pass_hash"
        const val KEY_FIRST_RUN_DONE = "first_run_done"
    }
}
