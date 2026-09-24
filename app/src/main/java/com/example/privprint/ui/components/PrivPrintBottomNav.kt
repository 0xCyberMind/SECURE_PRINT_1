package com.example.privprint.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.privprint.ui.UserScreen

enum class NavDestination(
    val screen: UserScreen,
    val label: String,
    val iconFilled: androidx.compose.ui.graphics.vector.ImageVector,
    val iconOutlined: androidx.compose.ui.graphics.vector.ImageVector
) {
    HOME(UserScreen.HOME, "Home", Icons.Filled.Home, Icons.Outlined.Home),
    HISTORY(UserScreen.HISTORY, "History", Icons.Filled.History, Icons.Outlined.History),
    PRIVACY(UserScreen.PRIVACY_CENTER, "Privacy", Icons.Filled.Shield, Icons.Outlined.Shield),
    SETTINGS(UserScreen.SETTINGS, "Settings", Icons.Filled.Settings, Icons.Outlined.Settings)
}

@Composable
fun PrivPrintBottomNav(
    currentScreen: UserScreen,
    onNavigate: (UserScreen) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            modifier = Modifier.height(68.dp)
        ) {
            NavDestination.entries.forEach { item ->
                val isSelected = when (item) {
                    NavDestination.HOME -> currentScreen == UserScreen.HOME ||
                            currentScreen == UserScreen.QR_SCANNER ||
                            currentScreen == UserScreen.SHOP_CONNECTED ||
                            currentScreen == UserScreen.DOCUMENT_PICKER ||
                            currentScreen == UserScreen.PRINT_SETTINGS ||
                            currentScreen == UserScreen.CONFIRMATION ||
                            currentScreen == UserScreen.ACTIVE_TRACKING
                    NavDestination.HISTORY -> currentScreen == UserScreen.HISTORY
                    NavDestination.PRIVACY -> currentScreen == UserScreen.PRIVACY_CENTER
                    NavDestination.SETTINGS -> currentScreen == UserScreen.SETTINGS
                }

                NavigationBarItem(
                    selected = isSelected,
                    onClick = { onNavigate(item.screen) },
                    icon = {
                        Icon(
                            imageVector = if (isSelected) item.iconFilled else item.iconOutlined,
                            contentDescription = item.label
                        )
                    },
                    label = {
                        Text(
                            text = item.label,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                    )
                )
            }
        }
    }
}
