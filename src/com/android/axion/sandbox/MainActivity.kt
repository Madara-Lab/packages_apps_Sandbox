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

import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.android.axion.sandbox.io.BackupManager
import com.android.axion.sandbox.io.VaultAccessController
import com.android.axion.sandbox.security.LockedAppBehavior
import com.android.axion.sandbox.security.PrivateSectionBehavior
import com.android.axion.sandbox.security.SecurityType
import com.android.axion.sandbox.security.SandboxSecurityManager
import com.android.axion.sandbox.ui.ForgotPasswordScreen
import com.android.axion.sandbox.ui.LockScreen
import com.android.axion.sandbox.ui.PasswordScreen
import com.android.axion.sandbox.ui.PatternScreen
import com.android.axion.sandbox.ui.SandboxApp
import com.android.axion.sandbox.ui.SecurityQuestionSetupScreen
import com.android.axion.sandbox.ui.SecuritySetupScreen
import com.android.axion.sandbox.ui.SettingsScreen
import com.android.axion.sandbox.ui.theme.SandboxTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var securityManager: SandboxSecurityManager
    private lateinit var backupManager: BackupManager

    private val createBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            lifecycleScope.launch {
                val success = backupManager.exportConfig(it)
                if (success) {
                    Toast.makeText(this@MainActivity, R.string.backup_success, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, R.string.backup_error, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val restoreBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            lifecycleScope.launch {
                val success = backupManager.importConfig(it)
                if (success) {
                    Toast.makeText(this@MainActivity, R.string.restore_success, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, R.string.restore_error, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        securityManager = SandboxSecurityManager(this)
        backupManager = BackupManager(this)
        VaultAccessController.lock(this)

        setContent {
            SandboxTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    SandboxNavigation(
                        securityManager = securityManager,
                        onShowBiometricPrompt = { onSuccess ->
                            showBiometricPrompt(onSuccess)
                        },
                        onBackupAppList = {
                            createBackupLauncher.launch("sandbox_config.json")
                        },
                        onRestoreAppList = {
                            restoreBackupLauncher.launch(arrayOf("application/json"))
                        }
                    )
                }
            }
        }
    }

    private fun showBiometricPrompt(onSuccess: () -> Unit) {
        val prompt = BiometricPrompt.Builder(this)
            .setTitle(getString(R.string.biometric_title))
            .setNegativeButton(getString(R.string.action_cancel), mainExecutor) { _, _ -> }
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
                    onSuccess()
                }
            }
        )
    }
}

enum class SandboxScreen {
    UNLOCK_PRIVATE,
    SETUP_TYPE_SELECTOR,
    SETUP_CREDENTIAL,
    SETUP_SECURITY_QUESTION,
    MAIN,
    SETTINGS,
    SETTINGS_RECOVERY,
    VERIFY_CURRENT_CREDENTIAL,
    CHANGE_SECURITY,
    FORGOT_PASSWORD
}

@Composable
fun SandboxNavigation(
    securityManager: SandboxSecurityManager,
    onShowBiometricPrompt: (() -> Unit) -> Unit,
    onBackupAppList: () -> Unit,
    onRestoreAppList: () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    var currentScreen by rememberSaveable { 
        mutableStateOf(if (securityManager.isSetup()) SandboxScreen.MAIN else SandboxScreen.SETUP_TYPE_SELECTOR) 
    }
    var selectedSecurityType by rememberSaveable { mutableStateOf(SecurityType.PIN) }
    var pendingSecurityType by rememberSaveable { mutableStateOf<SecurityType?>(null) }
    var firstCredential by remember { mutableStateOf<Any?>(null) }
    var isConfirmStep by rememberSaveable { mutableStateOf(false) }
    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }

    var isPrivateUnlocked by rememberSaveable { mutableStateOf(false) }
    var isPrivateAreaExpanded by rememberSaveable { mutableStateOf(false) }
    var selectedTabIndex by rememberSaveable { mutableStateOf(0) }
    var isPickingFiles by rememberSaveable { mutableStateOf(false) }
    var lastPauseTime by rememberSaveable { mutableStateOf(0L) }

    var currentLockedAppBehavior by remember { mutableStateOf(securityManager.getLockedAppBehavior()) }
    var currentLockedAppTimeout by remember { mutableStateOf(securityManager.getLockedAppTimeout()) }
    var currentPrivateBehavior by remember { mutableStateOf(securityManager.getPrivateSectionBehavior()) }
    var currentPrivateTimeout by remember { mutableStateOf(securityManager.getPrivateSectionTimeout()) }
    var currentSecurityType by remember { mutableStateOf(securityManager.getSecurityType()) }
    var currentBiometricEnabled by remember { mutableStateOf(securityManager.isBiometricEnabled()) }
    var currentPreferBiometric by remember { mutableStateOf(securityManager.isPreferBiometric()) }
    var hasSecurityQuestion by remember { mutableStateOf(securityManager.hasSecurityQuestion()) }
    var forgotPasswordReturnScreen by rememberSaveable { mutableStateOf(SandboxScreen.UNLOCK_PRIVATE) }

    val currentScreenState = rememberUpdatedState(currentScreen)
    val currentPrivateBehaviorState = rememberUpdatedState(currentPrivateBehavior)

    LaunchedEffect(isPrivateUnlocked) {
        if (isPrivateUnlocked) {
            VaultAccessController.unlock()
        } else {
            VaultAccessController.lock(context)
        }
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE && securityManager.isSetup() && !isPickingFiles) {
                lastPauseTime = System.currentTimeMillis()
            } else if (event == Lifecycle.Event.ON_RESUME && securityManager.isSetup()) {
                val currentTime = System.currentTimeMillis()
                val isGracePeriodExpired = (currentTime - lastPauseTime) > 30000 // 30 second grace period

                if (isGracePeriodExpired && isPrivateUnlocked) {
                    val privateBehavior = currentPrivateBehaviorState.value
                    if (privateBehavior == PrivateSectionBehavior.ON_LEAVE && !isPickingFiles) {
                        isPrivateUnlocked = false
                        isPrivateAreaExpanded = false
                    }
                }
                
                // Reset state after check
                isPickingFiles = false

                if (currentPrivateBehaviorState.value == PrivateSectionBehavior.TIMEOUT
                    && securityManager.isTimeoutExpired()) {
                    isPrivateUnlocked = false
                    isPrivateAreaExpanded = false
                } else if (isPrivateUnlocked) {
                    securityManager.setLastUnlockTime()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            VaultAccessController.lock(context)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(currentScreen) {
        if (currentScreen == SandboxScreen.SETUP_CREDENTIAL || currentScreen == SandboxScreen.CHANGE_SECURITY) {
            if (!isConfirmStep) {
                firstCredential = null
            }
        }
    }

    when (currentScreen) {
        SandboxScreen.UNLOCK_PRIVATE -> {
            val securityType = securityManager.getSecurityType()

            if (securityType == SecurityType.NONE || !securityManager.isSetup()) {
                isPrivateUnlocked = true
                isPrivateAreaExpanded = true
                currentScreen = SandboxScreen.MAIN
                return@SandboxNavigation
            }

            val onUnlockSuccess = {
                if (securityManager.isSetup() && !securityManager.hasSecurityQuestion()) {
                    currentScreen = SandboxScreen.SETUP_SECURITY_QUESTION
                } else {
                    isPrivateUnlocked = true
                    isPrivateAreaExpanded = true
                    securityManager.setLastUnlockTime()
                    currentScreen = SandboxScreen.MAIN
                }
            }

            BackHandler {
                currentScreen = SandboxScreen.MAIN
            }

            LaunchedEffect(Unit) {
                if (currentBiometricEnabled && securityManager.isBiometricAvailable() && currentPreferBiometric) {
                    onShowBiometricPrompt(onUnlockSuccess)
                }
            }

            when (securityType) {
                SecurityType.PIN -> {
                    LockScreen(
                        isSetup = false,
                        onUnlock = onUnlockSuccess,
                        onPinEntered = { pin -> securityManager.verifyCredential(pin) },
                        biometricType = if (currentBiometricEnabled && securityManager.isBiometricAvailable())
                            securityManager.getBiometricType() else SandboxSecurityManager.BiometricType.NONE,
                        onBiometricClick = { onShowBiometricPrompt(onUnlockSuccess) },
                        onBack = { currentScreen = SandboxScreen.MAIN },
                        onForgotPassword = if (securityManager.hasSecurityQuestion()) {
                            { currentScreen = SandboxScreen.FORGOT_PASSWORD }
                        } else null
                    )
                }
                SecurityType.PASSWORD -> {
                    PasswordScreen(
                        isSetup = false,
                        onUnlock = onUnlockSuccess,
                        onPasswordEntered = { password -> securityManager.verifyCredential(password) },
                        biometricType = if (currentBiometricEnabled && securityManager.isBiometricAvailable())
                            securityManager.getBiometricType() else SandboxSecurityManager.BiometricType.NONE,
                        onBiometricClick = { onShowBiometricPrompt(onUnlockSuccess) },
                        onBack = { currentScreen = SandboxScreen.MAIN },
                        onForgotPassword = if (securityManager.hasSecurityQuestion()) {
                            { currentScreen = SandboxScreen.FORGOT_PASSWORD }
                        } else null
                    )
                }
                SecurityType.PATTERN -> {
                    PatternScreen(
                        isSetup = false,
                        onUnlock = onUnlockSuccess,
                        onPatternEntered = { pattern -> securityManager.verifyPattern(pattern) },
                        biometricType = if (currentBiometricEnabled && securityManager.isBiometricAvailable())
                            securityManager.getBiometricType() else SandboxSecurityManager.BiometricType.NONE,
                        onBiometricClick = { onShowBiometricPrompt(onUnlockSuccess) },
                        onBack = { currentScreen = SandboxScreen.MAIN },
                        onForgotPassword = if (securityManager.hasSecurityQuestion()) {
                            { currentScreen = SandboxScreen.FORGOT_PASSWORD }
                        } else null
                    )
                }
                SecurityType.NONE -> {
                    isPrivateUnlocked = true
                    isPrivateAreaExpanded = true
                    currentScreen = SandboxScreen.MAIN
                }
            }
        }

        SandboxScreen.SETUP_TYPE_SELECTOR -> {
            SecuritySetupScreen(
                onSecurityTypeSelected = { type ->
                    selectedSecurityType = type
                    firstCredential = null
                    isConfirmStep = false
                    currentScreen = SandboxScreen.SETUP_CREDENTIAL
                },
                onSkip = {
                    currentScreen = SandboxScreen.MAIN
                },
                showSkip = securityManager.isSetup()
            )
        }

        SandboxScreen.SETUP_CREDENTIAL -> {
            val handleBack = {
                if (isConfirmStep) {
                    isConfirmStep = false
                    firstCredential = null
                } else {
                    currentScreen = SandboxScreen.SETUP_TYPE_SELECTOR
                }
            }

            BackHandler { handleBack() }

            when (selectedSecurityType) {
                SecurityType.PIN -> {
                    LockScreen(
                        isSetup = true,
                        onUnlock = {
                            if (!isConfirmStep) {
                                isConfirmStep = true
                            } else {
                                securityManager.setPin(firstCredential as String)
                                currentSecurityType = SecurityType.PIN
                                currentScreen = SandboxScreen.SETUP_SECURITY_QUESTION
                            }
                        },
                        onPinEntered = { pin ->
                            if (!isConfirmStep) {
                                firstCredential = pin
                                true
                            } else {
                                pin == (firstCredential as? String)
                            }
                        },
                        confirmPin = if (isConfirmStep) firstCredential as? String else null,
                        onBack = handleBack
                    )
                }
                SecurityType.PASSWORD -> {
                    PasswordScreen(
                        isSetup = true,
                        onUnlock = {
                            if (!isConfirmStep) {
                                isConfirmStep = true
                            } else {
                                securityManager.setPassword(firstCredential as String)
                                currentSecurityType = SecurityType.PASSWORD
                                currentScreen = SandboxScreen.SETUP_SECURITY_QUESTION
                            }
                        },
                        onPasswordEntered = { password ->
                            if (!isConfirmStep) {
                                firstCredential = password
                                true
                            } else {
                                password == (firstCredential as? String)
                            }
                        },
                        confirmPassword = if (isConfirmStep) firstCredential as? String else null,
                        onBack = handleBack
                    )
                }
                SecurityType.PATTERN -> {
                    PatternScreen(
                        isSetup = true,
                        onUnlock = {
                            if (!isConfirmStep) {
                                isConfirmStep = true
                            } else {
                                @Suppress("UNCHECKED_CAST")
                                securityManager.setPattern(firstCredential as List<Int>)
                                currentSecurityType = SecurityType.PATTERN
                                currentScreen = SandboxScreen.SETUP_SECURITY_QUESTION
                            }
                        },
                        onPatternEntered = { pattern ->
                            if (!isConfirmStep) {
                                firstCredential = pattern
                                true
                            } else {
                                @Suppress("UNCHECKED_CAST")
                                pattern == (firstCredential as? List<Int>)
                            }
                        },
                        confirmPattern = if (isConfirmStep) {
                            @Suppress("UNCHECKED_CAST")
                            firstCredential as? List<Int>
                        } else null,
                        onBack = handleBack
                    )
                }
                SecurityType.NONE -> {
                    currentScreen = SandboxScreen.MAIN
                }
            }
        }

        SandboxScreen.MAIN -> {
            BackHandler(enabled = isSearchExpanded) {
                isSearchExpanded = false
            }

            SandboxApp(
                onSettingsClick = { currentScreen = SandboxScreen.SETTINGS },
                isSearchExpanded = isSearchExpanded,
                onSearchExpandedChange = { expanded -> isSearchExpanded = expanded },
                isPrivateUnlocked = isPrivateUnlocked,
                onUnlockRequest = {
                    currentScreen = SandboxScreen.UNLOCK_PRIVATE
                },
                isPrivateAreaExpanded = isPrivateAreaExpanded,
                onPrivateAreaExpandChange = { expanded ->
                    isPrivateAreaExpanded = expanded
                },
                isSecuritySetup = securityManager.isSetup(),
                onSetupSecurity = {
                    currentScreen = SandboxScreen.SETUP_TYPE_SELECTOR
                },
                selectedTabIndex = selectedTabIndex,
                onTabIndexChange = { index -> selectedTabIndex = index },
                isPickingFiles = isPickingFiles,
                onPickingFilesChange = { picking -> isPickingFiles = picking }
            )
        }

        SandboxScreen.SETTINGS -> {
            BackHandler {
                currentScreen = SandboxScreen.MAIN
            }

            SettingsScreen(
                currentSecurityType = currentSecurityType,
                currentLockedAppBehavior = currentLockedAppBehavior,
                currentLockedAppTimeout = currentLockedAppTimeout,
                currentPrivateBehavior = currentPrivateBehavior,
                currentPrivateTimeout = currentPrivateTimeout,
                isBiometricAvailable = securityManager.isBiometricAvailable(),
                isBiometricEnabled = currentBiometricEnabled,
                isPreferBiometric = currentPreferBiometric,
                onBackClick = { currentScreen = SandboxScreen.MAIN },
                onChangeSecurityType = { type ->
                    if (securityManager.isSetup()) {
                        pendingSecurityType = type
                        currentScreen = SandboxScreen.VERIFY_CURRENT_CREDENTIAL
                    } else {
                        selectedSecurityType = type
                        firstCredential = null
                        isConfirmStep = false
                        currentScreen = SandboxScreen.CHANGE_SECURITY
                    }
                },
                onChangeLockedAppBehavior = { behavior ->
                    securityManager.setLockedAppBehavior(behavior)
                    currentLockedAppBehavior = behavior
                },
                onChangeLockedAppTimeout = { timeout ->
                    securityManager.setLockedAppTimeout(timeout)
                    currentLockedAppTimeout = timeout
                },
                onChangePrivateBehavior = { behavior ->
                    securityManager.setPrivateSectionBehavior(behavior)
                    currentPrivateBehavior = behavior
                    if (behavior == PrivateSectionBehavior.TIMEOUT) {
                        securityManager.setLastUnlockTime()
                    }
                },
                onChangePrivateTimeout = { timeout ->
                    securityManager.setPrivateSectionTimeout(timeout)
                    currentPrivateTimeout = timeout
                },
                onChangeBiometricEnabled = { enabled ->
                    securityManager.setBiometricEnabled(enabled)
                    currentBiometricEnabled = enabled
                    if (!enabled) {
                        currentPreferBiometric = false
                    }
                },
                onChangePreferBiometric = { preferred ->
                    securityManager.setPreferBiometric(preferred)
                    currentPreferBiometric = preferred
                },
                hasSecurityQuestion = hasSecurityQuestion,
                onSetupRecovery = {
                    currentScreen = SandboxScreen.SETTINGS_RECOVERY
                },
                onForgotPassword = {
                    forgotPasswordReturnScreen = SandboxScreen.SETTINGS
                    currentScreen = SandboxScreen.FORGOT_PASSWORD
                },
                onBackupAppList = onBackupAppList,
                onRestoreAppList = onRestoreAppList
            )
        }

        SandboxScreen.VERIFY_CURRENT_CREDENTIAL -> {
            val securityType = securityManager.getSecurityType()

            val onVerified = {
                selectedSecurityType = pendingSecurityType ?: SecurityType.PIN
                pendingSecurityType = null
                firstCredential = null
                isConfirmStep = false
                currentScreen = SandboxScreen.CHANGE_SECURITY
            }

            BackHandler {
                pendingSecurityType = null
                currentScreen = SandboxScreen.SETTINGS
            }

            when (securityType) {
                SecurityType.PIN -> {
                    LockScreen(
                        isSetup = false,
                        promptText = null,
                        onUnlock = onVerified,
                        onPinEntered = { pin -> securityManager.verifyCredential(pin) },
                        biometricType = if (currentBiometricEnabled && securityManager.isBiometricAvailable())
                            securityManager.getBiometricType() else SandboxSecurityManager.BiometricType.NONE,
                        onBiometricClick = { onShowBiometricPrompt(onVerified) },
                        onBack = {
                            pendingSecurityType = null
                            currentScreen = SandboxScreen.SETTINGS
                        }
                    )
                }
                SecurityType.PASSWORD -> {
                    PasswordScreen(
                        isSetup = false,
                        promptText = null,
                        onUnlock = onVerified,
                        onPasswordEntered = { password -> securityManager.verifyCredential(password) },
                        biometricType = if (currentBiometricEnabled && securityManager.isBiometricAvailable())
                            securityManager.getBiometricType() else SandboxSecurityManager.BiometricType.NONE,
                        onBiometricClick = { onShowBiometricPrompt(onVerified) },
                        onBack = {
                            pendingSecurityType = null
                            currentScreen = SandboxScreen.SETTINGS
                        }
                    )
                }
                SecurityType.PATTERN -> {
                    PatternScreen(
                        isSetup = false,
                        promptText = null,
                        onUnlock = onVerified,
                        onPatternEntered = { pattern -> securityManager.verifyPattern(pattern) },
                        biometricType = if (currentBiometricEnabled && securityManager.isBiometricAvailable())
                            securityManager.getBiometricType() else SandboxSecurityManager.BiometricType.NONE,
                        onBiometricClick = { onShowBiometricPrompt(onVerified) },
                        onBack = {
                            pendingSecurityType = null
                            currentScreen = SandboxScreen.SETTINGS
                        }
                    )
                }
                SecurityType.NONE -> {
                    selectedSecurityType = pendingSecurityType ?: SecurityType.PIN
                    pendingSecurityType = null
                    firstCredential = null
                    isConfirmStep = false
                    currentScreen = SandboxScreen.CHANGE_SECURITY
                }
            }
        }

        SandboxScreen.CHANGE_SECURITY -> {
            val handleBack = {
                if (isConfirmStep) {
                    isConfirmStep = false
                    firstCredential = null
                } else {
                    currentScreen = SandboxScreen.SETTINGS
                }
            }

            BackHandler { handleBack() }

            when (selectedSecurityType) {
                SecurityType.PIN -> {
                    LockScreen(
                        isSetup = true,
                        onUnlock = {
                            if (!isConfirmStep) {
                                isConfirmStep = true
                            } else {
                                securityManager.setPin(firstCredential as String)
                                currentSecurityType = SecurityType.PIN
                                currentScreen = SandboxScreen.SETTINGS
                            }
                        },
                        onPinEntered = { pin ->
                            if (!isConfirmStep) {
                                firstCredential = pin
                                true
                            } else {
                                pin == (firstCredential as? String)
                            }
                        },
                        confirmPin = if (isConfirmStep) firstCredential as? String else null,
                        onBack = handleBack
                    )
                }
                SecurityType.PASSWORD -> {
                    PasswordScreen(
                        isSetup = true,
                        onUnlock = {
                            if (!isConfirmStep) {
                                isConfirmStep = true
                            } else {
                                securityManager.setPassword(firstCredential as String)
                                currentSecurityType = SecurityType.PASSWORD
                                currentScreen = SandboxScreen.SETTINGS
                            }
                        },
                        onPasswordEntered = { password ->
                            if (!isConfirmStep) {
                                firstCredential = password
                                true
                            } else {
                                password == (firstCredential as? String)
                            }
                        },
                        confirmPassword = if (isConfirmStep) firstCredential as? String else null,
                        onBack = handleBack
                    )
                }
                SecurityType.PATTERN -> {
                    PatternScreen(
                        isSetup = true,
                        onUnlock = {
                            if (!isConfirmStep) {
                                isConfirmStep = true
                            } else {
                                @Suppress("UNCHECKED_CAST")
                                securityManager.setPattern(firstCredential as List<Int>)
                                currentSecurityType = SecurityType.PATTERN
                                currentScreen = SandboxScreen.SETTINGS
                            }
                        },
                        onPatternEntered = { pattern ->
                            if (!isConfirmStep) {
                                firstCredential = pattern
                                true
                            } else {
                                @Suppress("UNCHECKED_CAST")
                                pattern == (firstCredential as? List<Int>)
                            }
                        },
                        confirmPattern = if (isConfirmStep) {
                            @Suppress("UNCHECKED_CAST")
                            firstCredential as? List<Int>
                        } else null,
                        onBack = handleBack
                    )
                }
                SecurityType.NONE -> {
                    currentScreen = SandboxScreen.SETTINGS
                }
            }
        }

        SandboxScreen.SETUP_SECURITY_QUESTION -> {
            SecurityQuestionSetupScreen(
                onComplete = { question, answer ->
                    securityManager.setSecurityQuestion(question, answer)
                    hasSecurityQuestion = true
                    isPrivateUnlocked = true
                    isPrivateAreaExpanded = true
                    securityManager.setLastUnlockTime()
                    currentScreen = SandboxScreen.MAIN
                },
                onBack = {
                    isPrivateUnlocked = true
                    isPrivateAreaExpanded = true
                    securityManager.setLastUnlockTime()
                    currentScreen = SandboxScreen.MAIN
                }
            )
        }

        SandboxScreen.SETTINGS_RECOVERY -> {
            BackHandler {
                currentScreen = SandboxScreen.SETTINGS
            }

            SecurityQuestionSetupScreen(
                onComplete = { question, answer ->
                    securityManager.setSecurityQuestion(question, answer)
                    hasSecurityQuestion = true
                    currentScreen = SandboxScreen.SETTINGS
                },
                onBack = {
                    currentScreen = SandboxScreen.SETTINGS
                }
            )
        }

        SandboxScreen.FORGOT_PASSWORD -> {
            val securityQuestion = securityManager.getSecurityQuestion()

            if (securityQuestion == null) {
                currentScreen = SandboxScreen.UNLOCK_PRIVATE
                return@SandboxNavigation
            }

            BackHandler {
                currentScreen = forgotPasswordReturnScreen
            }

            ForgotPasswordScreen(
                securityQuestion = securityQuestion,
                onAnswerSubmit = { answer ->
                    securityManager.verifySecurityAnswer(answer)
                },
                onSuccess = {
                    securityManager.clearCredentials()
                    currentSecurityType = SecurityType.NONE
                    currentBiometricEnabled = false
                    hasSecurityQuestion = false
                    isPrivateUnlocked = true
                    isPrivateAreaExpanded = true
                    currentScreen = SandboxScreen.SETUP_TYPE_SELECTOR
                },
                onBack = {
                    currentScreen = forgotPasswordReturnScreen
                }
            )
        }
    }
}
