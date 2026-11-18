package com.smartpos.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import com.smartpos.PosViewModel
import com.smartpos.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: PosViewModel) {
    val tab by vm.homeTab.collectAsState()
    val shop by vm.settings.shopName.collectAsState()
    val currency by vm.settings.currency.collectAsState()
    var showSettings by remember { mutableStateOf(false) }

    val tabs: List<Pair<String, ImageVector>> = listOf(
        "Sell" to Icons.Default.ShoppingCart,
        "Products" to Icons.AutoMirrored.Filled.List,
        "History" to Icons.Default.DateRange
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(shop, fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { vm.homeTab.value = i },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) }
                    )
                }
            }
        },
        floatingActionButton = {
            if (tab == 1) {
                ExtendedFloatingActionButton(
                    onClick = { vm.screen.value = Screen.AddProduct() },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Add product") }
                )
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> SellTab(vm)
                1 -> ProductsTab(vm)
                else -> HistoryTab(vm)
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            shop = shop,
            currency = currency,
            onSave = { s, c ->
                vm.settings.save(s, c)
                showSettings = false
            },
            onDismiss = { showSettings = false }
        )
    }
}
