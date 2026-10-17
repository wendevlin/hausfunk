package dev.wendelin.hausfunk.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.ui.platform.LocalContext
import dev.wendelin.hausfunk.CrashLog
import dev.wendelin.hausfunk.R
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.wendelin.hausfunk.HausfunkApplication
import dev.wendelin.hausfunk.data.Role
import dev.wendelin.hausfunk.ui.baby.BabyRoute
import dev.wendelin.hausfunk.ui.onboarding.OnboardingScreen
import dev.wendelin.hausfunk.ui.parent.ParentRoute
import dev.wendelin.hausfunk.ui.role.RolePickerScreen
import dev.wendelin.hausfunk.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

private object Routes {
    const val ONBOARDING = "onboarding"
    const val ROLE = "role"
    const val BABY = "baby"
    const val PARENT = "parent"
    const val SETTINGS = "settings"
}

@Composable
fun HausfunkNavHost(app: HausfunkApplication) {
    val settings by app.settings.settings.collectAsState(initial = null)
    val trusted by app.trustStore.devices.collectAsState(initial = emptyList())

    val loaded = settings
    if (loaded == null) {
        // DataStore answers within a frame or two; show the plain background meanwhile.
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }

    val navController = rememberNavController()
    // Monitoring that is still running (screen off, app swiped away) brings its screen back.
    val startDestination = remember {
        when {
            !loaded.onboardingComplete -> Routes.ONBOARDING
            app.babySession.value != null -> Routes.BABY
            app.parentSession.value != null -> Routes.PARENT
            else -> Routes.ROLE
        }
    }
    val openSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } }

    // Debug builds: the last crash, to copy and send.
    val context = LocalContext.current
    var crash by remember { mutableStateOf(CrashLog.last(context)) }
    crash?.let { trace ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.crash_title)) },
            text = {
                Text(
                    trace.take(CRASH_PREVIEW_CHARS),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Hausfunk crash", trace))
                    CrashLog.clear(context)
                    crash = null
                }) { Text(stringResource(R.string.action_copy)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    CrashLog.clear(context)
                    crash = null
                }) { Text(stringResource(R.string.action_ok)) }
            },
        )
    }

    // The other device removed the pairing (it told us): say so once, on whatever screen is open.
    val unpaired by app.unpairedNotice.collectAsState()
    unpaired?.let { name ->
        AlertDialog(
            onDismissRequest = { app.unpairedNotice.value = null },
            title = { Text(stringResource(R.string.unpaired_title)) },
            text = { Text(stringResource(R.string.unpaired_body, name)) },
            confirmButton = {
                TextButton(onClick = { app.unpairedNotice.value = null }) { Text(stringResource(R.string.action_ok)) }
            },
        )
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                savedName = loaded.deviceName,
                onNameChosen = { name -> app.appScope.launch { app.settings.setDeviceName(name) } },
                onFinished = {
                    app.appScope.launch { app.settings.completeOnboarding() }
                    navController.navigate(Routes.ROLE) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.ROLE) {
            RolePickerScreen(
                lastRole = loaded.lastRole,
                showIdentityResetNotice = loaded.identityResetNotice,
                onRoleChosen = { role ->
                    app.appScope.launch { app.settings.setLastRole(role) }
                    navController.navigate(if (role == Role.BABY) Routes.BABY else Routes.PARENT)
                },
                onDismissIdentityResetNotice = { app.appScope.launch { app.settings.dismissIdentityResetNotice() } },
                onOpenSettings = openSettings,
            )
        }
        composable(Routes.BABY) {
            BabyRoute(
                app = app,
                onStop = {
                    if (!navController.popBackStack(Routes.ROLE, inclusive = false)) {
                        navController.navigate(Routes.ROLE) { popUpTo(Routes.BABY) { inclusive = true } }
                    }
                },
                onOpenSettings = openSettings,
            )
        }
        composable(Routes.PARENT) {
            ParentRoute(
                app = app,
                settings = loaded,
                trusted = trusted,
                onBack = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Routes.ROLE) { popUpTo(Routes.PARENT) { inclusive = true } }
                    }
                },
                onOpenSettings = openSettings,
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                deviceName = loaded.deviceName,
                deviceId = loaded.identityDeviceId,
                pairedDevices = trusted,
                alarmGraceSeconds = loaded.alarmGraceSeconds,
                onBack = { navController.popBackStack() },
                onAlarmGrace = { seconds -> app.appScope.launch { app.settings.setAlarmGrace(seconds) } },
                onRenameSelf = { name -> app.appScope.launch { app.settings.setDeviceName(name) } },
                onRenamePeer = { id, name -> app.appScope.launch { app.trustStore.rename(id, name) } },
                onRemovePeer = { id ->
                    app.appScope.launch {
                        app.trustStore.remove(id)
                        app.settings.forgetSound(id)
                    }
                },
            )
        }
    }
}

private const val CRASH_PREVIEW_CHARS = 1500
