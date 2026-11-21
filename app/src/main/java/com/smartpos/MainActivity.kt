package com.smartpos

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smartpos.cloud.Cloud
import com.smartpos.ui.AddProductScreen
import com.smartpos.ui.EditProductScreen
import com.smartpos.ui.HomeScreen
import com.smartpos.ui.PosTheme
import com.smartpos.ui.SellScannerScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PosTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    PosApp()
                }
            }
        }
    }
}

@Composable
fun PosApp(vm: PosViewModel = viewModel()) {
    val screen by vm.screen.collectAsState()
    val context = LocalContext.current
    val cloud by Cloud.state.collectAsState()
    val syncTick by Cloud.syncTick.collectAsState()

    // Every screen reports problems / confirmations through the same toast channel.
    LaunchedEffect(Unit) {
        vm.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    // Optional online mode: load the saved connection once.
    LaunchedEffect(Unit) { Cloud.init(context) }

    // Keep the cloud sync (selling phone) or live view (monitor phone) running on every screen.
    LaunchedEffect(cloud.signedIn, cloud.role, syncTick) {
        if (cloud.signedIn) {
            when (cloud.role) {
                Cloud.ROLE_SELLER -> Cloud.runSeller(context)
                Cloud.ROLE_MONITOR -> Cloud.runMonitor(context)
            }
        }
    }

    BackHandler(enabled = screen !is Screen.Home) { vm.screen.value = Screen.Home }

    when (val s = screen) {
        Screen.Home -> HomeScreen(vm)
        is Screen.AddProduct -> AddProductScreen(vm, s.barcode)
        is Screen.EditProduct -> EditProductScreen(vm, s.id)
        Screen.Scanner -> SellScannerScreen(vm)
    }
}
