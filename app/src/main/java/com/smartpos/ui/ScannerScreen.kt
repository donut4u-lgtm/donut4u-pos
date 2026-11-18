package com.smartpos.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.smartpos.PosViewModel
import com.smartpos.Screen
import com.smartpos.util.money
import com.smartpos.vision.BarcodeAnalyzer
import kotlinx.coroutines.launch

private class LastScan {
    var code: String = ""
    var time: Long = 0L
}

/** Full-screen live scanner for selling: every scanned barcode is added to the cart. */
@Composable
fun SellScannerScreen(vm: PosViewModel) {
    val cart by vm.cart.collectAsState()
    val currency by vm.settings.currency.collectAsState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val beeper = remember { Beeper() }
    DisposableEffect(Unit) { onDispose { beeper.release() } }

    var torch by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Point the camera at a barcode") }
    var statusOk by remember { mutableStateOf(true) }
    var unknownCode by remember { mutableStateOf<String?>(null) }
    val last = remember { LastScan() }

    val handle: (String) -> Unit = { code ->
        val now = SystemClock.elapsedRealtime()
        // The camera sees the same barcode many times per second: ignore repeats for 2 s.
        if (!(code == last.code && now - last.time < 2000)) {
            last.code = code
            last.time = now
            scope.launch {
                val p = vm.findByBarcode(code)
                if (p == null) {
                    unknownCode = code
                    status = "Unknown barcode: $code"
                    statusOk = false
                    beeper.error()
                } else if (vm.addToCart(p)) {
                    unknownCode = null
                    status = "Added: ${p.name}"
                    statusOk = true
                    beeper.ok()
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                } else {
                    unknownCode = null
                    status = "Not enough stock: ${p.name}"
                    statusOk = false
                    beeper.error()
                }
            }
        }
    }
    val currentHandle by rememberUpdatedState(handle)
    val analyzer = remember { BarcodeAnalyzer { code -> currentHandle(code) } }

    RequireCamera(altLabel = "Back", onAlt = { vm.screen.value = Screen.Home }) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            CameraView(Modifier.fillMaxSize(), analyzer = analyzer, torch = torch)

            // Aiming guide
            Box(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.8f)
                    .height(180.dp)
                    .border(
                        3.dp,
                        if (statusOk) Color.White else Color(0xFFFF5252),
                        RoundedCornerShape(16.dp)
                    )
            )

            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .safeDrawingPadding()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(onClick = { vm.screen.value = Screen.Home }) { Text("✕ Close") }
                Spacer(Modifier.weight(1f))
                FilledTonalButton(onClick = { torch = !torch }) {
                    Text(if (torch) "🔦 Light on" else "🔦 Light off")
                }
            }

            Card(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .safeDrawingPadding()
                    .padding(12.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        status,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (statusOk) MaterialTheme.colorScheme.onSurface else Color(0xFFD32F2F)
                    )
                    unknownCode?.let { code ->
                        Button(
                            onClick = { vm.screen.value = Screen.AddProduct(code) },
                            modifier = Modifier.padding(top = 8.dp)
                        ) { Text("Add this product") }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val count = cart.sumOf { it.qty }
                        Column(Modifier.weight(1f)) {
                            Text("$count item${if (count == 1) "" else "s"} in cart")
                            Text(
                                money(cart.sumOf { it.total }, currency),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Button(onClick = { vm.screen.value = Screen.Home }) { Text("Done") }
                    }
                }
            }
        }
    }
}
