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
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Storefront
import com.example.privprint.ui.ShopScreen
import com.example.privprint.ui.UserScreen

enum class ShopNavDestination(
    val screen: ShopScreen,
    val label: String,
    val iconFilled: androidx.compose.ui.graphics.vector.ImageVector,
    val iconOutlined: androidx.compose.ui.graphics.vector.ImageVector
) {
    DASHBOARD(ShopScreen.DASHBOARD, "Overview", Icons.Filled.Storefront, Icons.Outlined.Storefront),
    QUEUE(ShopScreen.QUEUE, "Queue", Icons.Filled.ListAlt, Icons.Outlined.ListAlt),
    PRINTERS(ShopScreen.PRINTERS, "Printers", Icons.Filled.Print, Icons.Outlined.Print),
    WINDOWS(ShopScreen.WINDOWS_STATION, "Windows PC", Icons.Filled.DesktopWindows, Icons.Outlined.DesktopWindows),
    AUDIT(ShopScreen.AUDIT, "Security", Icons.Filled.Security, Icons.Outlined.Security)
}

@Composable
fun PrivPrintShopNavigationRail(
    currentScreen: ShopScreen,
    onNavigate: (ShopScreen) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surface,
        header = {
            Spacer(modifier = Modifier.height(16.dp))
            Icon(
                imageVector = Icons.Default.Print,
                contentDescription = "PrivPrint Shop",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
        },
        modifier = modifier.fillMaxHeight()
    ) {
        ShopNavDestination.entries.forEach { item ->
            val isSelected = currentScreen == item.screen

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
