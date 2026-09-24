package com.example.privprint.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.privprint.ui.UserScreen

@Composable
fun PrivPrintNavigationRail(
    currentScreen: UserScreen,
    onNavigate: (UserScreen) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surface,
        header = {
            Spacer(modifier = Modifier.height(16.dp))
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "PrivPrint",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
        },
        modifier = modifier.fillMaxHeight()
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

            NavigationRailItem(
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
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                ),
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
    }
}
