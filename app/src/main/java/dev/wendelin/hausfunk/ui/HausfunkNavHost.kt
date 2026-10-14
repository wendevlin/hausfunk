package dev.wendelin.hausfunk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.windowsizeclass.WindowSizeClass
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
import dev.wendelin.hausfunk.ui.baby.BabyScreen
import dev.wendelin.hausfunk.ui.onboarding.OnboardingScreen
import dev.wendelin.hausfunk.ui.parent.ParentHomeScreen
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
fun HausfunkNavHost(app: HausfunkApplication, windowSizeClass: WindowSizeClass) {
    val settings by app.settings.settings.collectAsState(initial = null)
    val trusted by app.trustStore.devices.collectAsState(initial = emptyList())

    val loaded = settings
    if (loaded == null) {
        // DataStore answers within a frame or two; show the plain background meanwhile.
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }

    val navController = rememberNavController()
    val startDestination = remember { if (loaded.onboardingComplete) Routes.ROLE else Routes.ONBOARDING }
    val openSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } }

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
                windowSizeClass = windowSizeClass,
                onRoleChosen = { role ->
                    app.appScope.launch { app.settings.setLastRole(role) }
                    navController.navigate(if (role == Role.BABY) Routes.BABY else Routes.PARENT)
                },
                onDismissIdentityResetNotice = { app.appScope.launch { app.settings.dismissIdentityResetNotice() } },
                onOpenSettings = openSettings,
            )
        }
        composable(Routes.BABY) {
            BabyScreen(
                onStop = { navController.popBackStack(Routes.ROLE, inclusive = false) },
                onOpenSettings = openSettings,
            )
        }
        composable(Routes.PARENT) {
            ParentHomeScreen(
                babies = trusted.filter { it.role == Role.BABY },
                windowSizeClass = windowSizeClass,
                onBack = { navController.popBackStack() },
                onOpenSettings = openSettings,
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                deviceName = loaded.deviceName,
                deviceId = loaded.identityDeviceId,
                pairedDevices = trusted,
                onBack = { navController.popBackStack() },
                onRenameSelf = { name -> app.appScope.launch { app.settings.setDeviceName(name) } },
                onRenamePeer = { id, name -> app.appScope.launch { app.trustStore.rename(id, name) } },
                onRemovePeer = { id -> app.appScope.launch { app.trustStore.remove(id) } },
            )
        }
    }
}
