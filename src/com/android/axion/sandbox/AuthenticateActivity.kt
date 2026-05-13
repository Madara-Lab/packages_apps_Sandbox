/*
 * Copyright (C) 2025-2026 AxionOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.axion.sandbox

import android.app.Activity
import android.app.AxSandboxManager
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserHandle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.android.axion.sandbox.security.SecurityType
import com.android.axion.sandbox.security.SandboxSecurityManager
import com.android.axion.sandbox.ui.LockScreen
import com.android.axion.sandbox.ui.PasswordScreen
import com.android.axion.sandbox.ui.PatternScreen
import com.android.axion.sandbox.ui.theme.SandboxTheme

class AuthenticateActivity : ComponentActivity() {

    private lateinit var securityManager: SandboxSecurityManager
    private var packageName: String? = null
    private var appLabel: String? = null
    private var userId: Int = 0
    private var isSystemUnlock: Boolean = false

    private enum class AuthState { IDLE, PROMPT_SHOWING, EXITING, FINISHED }

    private var resultIntent: Intent? = null
    private var isAuthSuccess = false
    private var failedTime: Long = 0
    private var authState: AuthState = AuthState.IDLE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setupWindowForOverlay()

        enableEdgeToEdge()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                cancelAndFinish()
            }
        })

        resultIntent = Intent()

        securityManager = SandboxSecurityManager(this)

        packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
            ?: intent.getStringExtra(EXTRA_LOCKED_PACKAGE)

        appLabel = intent.getStringExtra(EXTRA_APP_LABEL) ?: packageName?.let { pkg ->
            try {
                val pm = applicationContext.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) {
                pkg
            }
        } ?: getString(R.string.app_name)

        userId = intent.getIntExtra(EXTRA_USER_ID, 0)
            .takeIf { it != 0 } ?: intent.getIntExtra(EXTRA_LOCKED_UID, 0).let {
            UserHandle.getUserId(it)
        }

        isSystemUnlock = ACTION_SYSTEM_UNLOCK == intent.action

        if (!securityManager.isSetup()) {
            unlockAndFinish()
            return
        }

        val biometricType = if (securityManager.isBiometricEnabled() && securityManager.isBiometricAvailable()) {
             securityManager.getBiometricType()
        } else {
             SandboxSecurityManager.BiometricType.NONE
        }

        setContent {
            SandboxTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    AuthenticateScreen(
                        securityManager = securityManager,
                        appLabel = appLabel ?: getString(R.string.app_name),
                        onSuccess = { unlockAndFinish() },
                        onCancel = { cancelAndFinish() },
                        biometricType = biometricType,
                        isPreferBiometric = securityManager.isPreferBiometric(),
                        onBiometricClick = { showBiometricPrompt() }
                    )
                }
            }
        }
    }

    private fun showBiometricPrompt() {
        val negativeButtonText = when (securityManager.getSecurityType()) {
            SecurityType.PIN -> getString(R.string.biometric_use_pin)
            SecurityType.PASSWORD -> getString(R.string.biometric_use_password)
            SecurityType.PATTERN -> getString(R.string.biometric_use_pattern)
            else -> getString(R.string.action_cancel)
        }

        val prompt = BiometricPrompt.Builder(this)
            .setTitle(getString(R.string.auth_title_unlock, appLabel ?: getString(R.string.app_name)))
            .setNegativeButton(negativeButtonText, mainExecutor) { _, _ -> }
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK
            )
            .build()

        prompt.authenticate(
            CancellationSignal(),
            mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    super.onAuthenticationSucceeded(result)
                    unlockAndFinish()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                     super.onAuthenticationError(errorCode, errString)
                     if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED ||
                         errorCode == BiometricPrompt.BIOMETRIC_ERROR_NEGATIVE_BUTTON) {
                     } else {
                         cancelAndFinish()
                     }
                }
            }
        )
    }

    private fun setupWindowForOverlay() {
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        window?.apply {
            addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )

            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )

            attributes = attributes?.apply {
                privateFlags = privateFlags or
                    WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS
            }
        }

        val km = getSystemService(KeyguardManager::class.java)
        km?.requestDismissKeyguard(this, null)
    }

    private fun unlockAndFinish() {
        if (authState == AuthState.FINISHED || isFinishing) return
        authState = AuthState.FINISHED
        if (packageName != null) {
            (getSystemService(Context.AX_SANDBOX_SERVICE) as? AxSandboxManager)
                ?.unlockApp(packageName!!, userId)
        }
        resultIntent?.apply {
            putExtra(EXTRA_LOCKED_PACKAGE, packageName)
            putExtra(EXTRA_LOCKED_UID, userId)
        }
        setResult(Activity.RESULT_OK, resultIntent)
        isAuthSuccess = true
        finish()
    }

    private fun cancelAndFinish() {
        if (authState == AuthState.FINISHED || isFinishing) return
        authState = AuthState.FINISHED
        resultIntent?.apply {
            putExtra(EXTRA_LOCKED_PACKAGE, packageName)
            putExtra(EXTRA_LOCKED_UID, userId)
        }
        setResult(Activity.RESULT_CANCELED, resultIntent)
        failedTime = SystemClock.elapsedRealtime()
        moveTaskToBack(true)
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        finish()
    }

    override fun onPause() {
        super.onPause()
        if (authState != AuthState.FINISHED) {
            cancelAndFinish()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (authState == AuthState.IDLE) {
            cancelAndFinish()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        if (hasFocus && isAuthSuccess) {
            isAuthSuccess = false
            return
        }

        val timeSinceFailed = SystemClock.elapsedRealtime() - failedTime
        val isMultiWindow = WindowModeUtil.isAppInMultiWindowMode()
        val isFreeform = WindowModeUtil.isAppInFreeformMode()

        if (hasFocus && !isMultiWindow && !isFreeform) {
            if (timeSinceFailed in 1..280 && isScreenOn()) {
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    startActivity(this)
                }
                finish()
            }
        }
    }

    private fun isScreenOn(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        return powerManager?.isInteractive ?: false
    }

    companion object {
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_APP_LABEL = "app_label"
        const val EXTRA_USER_ID = "user_id"

        const val EXTRA_LOCKED_PACKAGE = "LOCKED_PACKAGE"
        const val EXTRA_LOCKED_UID = "LOCKED_UID"

        const val ACTION_AUTHENTICATE = "com.android.axion.sandbox.action.AUTHENTICATE"
        const val ACTION_SYSTEM_UNLOCK = "com.android.axion.sandbox.action.SYSTEM_UNLOCK"
    }
}

@Composable
fun AuthenticateScreen(
    securityManager: SandboxSecurityManager,
    appLabel: String,
    onSuccess: () -> Unit,
    onCancel: () -> Unit,
    biometricType: SandboxSecurityManager.BiometricType = SandboxSecurityManager.BiometricType.NONE,
    isPreferBiometric: Boolean = false,
    onBiometricClick: () -> Unit = {}
) {
    LaunchedEffect(biometricType) {
        if (biometricType != SandboxSecurityManager.BiometricType.NONE && isPreferBiometric) {
            onBiometricClick()
        }
    }

    val securityType = securityManager.getSecurityType()

    when (securityType) {
        SecurityType.PIN -> {
            LockScreen(
                isSetup = false,
                onUnlock = onSuccess,
                onPinEntered = { pin -> securityManager.verifyCredential(pin) },
                onBack = onCancel,
                biometricType = biometricType,
                onBiometricClick = onBiometricClick
            )
        }
        SecurityType.PASSWORD -> {
            PasswordScreen(
                isSetup = false,
                onUnlock = onSuccess,
                onPasswordEntered = { password -> securityManager.verifyCredential(password) },
                onBack = onCancel,
                biometricType = biometricType,
                onBiometricClick = onBiometricClick
            )
        }
        SecurityType.PATTERN -> {
            PatternScreen(
                isSetup = false,
                onUnlock = onSuccess,
                onPatternEntered = { pattern -> securityManager.verifyPattern(pattern) },
                onBack = onCancel,
                biometricType = biometricType,
                onBiometricClick = onBiometricClick
            )
        }
        SecurityType.NONE -> {
            LaunchedEffect(Unit) {
                onSuccess()
            }
        }
    }
}
