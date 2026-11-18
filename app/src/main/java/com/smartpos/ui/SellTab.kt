package com.smartpos.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.smartpos.PosViewModel
import com.smartpos.Screen
import com.smartpos.util.money
import kotlinx.coroutines.launch

@Composable
fun SellTab(vm: PosViewModel) {
    val products by vm.products.collectAsState()
    val labels by vm.labels.collectAsState()
    val cart by vm.cart.collectAsState()
    val currency by vm.settings.currency.collectAsState()
    val shop by vm.settings.shopName.collectAsState()
    val receipt by vm.lastReceipt.collectAsState()
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var showCart by remember { mutableStateOf(false) }
    var showPay by remember { mutableStateOf(false) }

    val filtered = remember(products, query) {
        val q = query.trim()
        if (q.isEmpty()) products
        else products.filter { it.name.contains(q, ignoreCase = true) || it.barcode.contains(q) }
    }
    val groups = remember(filtered, labels) { groupProducts(filtered, labels) }
    val qtyMap = remember(cart) { cart.associate { it.product.id to it.qty } }
    val total = cart.sumOf { it.total }
    val itemCount = cart.sumOf { it.qty }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Search, or scan with a barcode gun") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    // A USB / Bluetooth barcode scanner "types" the code and presses Enter.
                    val code = query.trim()
                    if (code.isNotEmpty()) {
                        scope.launch {
                            val p = vm.findByBarcode(code)
                            if (p != null) {
                                vm.addToCart(p)
                                query = ""
                            } else if (filtered.isEmpty()) {
                                vm.toast("No product with barcode $code")
                            }
                        }
                    }
                })
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { vm.screen.value = Screen.Scanner }) { Text("📷 Scan") }
        }

        LabelBoard(
            groups = groups,
            qtyInCart = qtyMap,
            currency = currency,
            emptyText = if (products.isEmpty())
                "No products yet.\nOpen the Products tab and tap \"Add product\" to photograph and scan your first item."
            else "No products match \"$query\".",
            onProductClick = { vm.addToCart(it) },
            modifier = Modifier.weight(1f)
        )

        if (cart.isNotEmpty()) {
            Surface(tonalElevation = 6.dp, shadowElevation = 8.dp) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { showCart = true }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "$itemCount item${if (itemCount == 1) "" else "s"} · tap to review",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            money(total, currency),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Button(onClick = { showPay = true }) { Text("Charge") }
                }
            }
        }
    }

    if (showCart) {
        CartSheet(
            cart = cart,
            currency = currency,
            onQty = { id, q -> vm.setQty(id, q) },
            onRemove = { vm.removeLine(it) },
            onClear = { vm.clearCart() },
            onCharge = { showPay = true },
            onDismiss = { showCart = false }
        )
    }

    if (showPay && cart.isNotEmpty()) {
        PayDialog(
            total = total,
            currency = currency,
            onDismiss = { showPay = false },
            onConfirm = { paid ->
                vm.checkout(paid) { result ->
                    if (result is com.smartpos.CheckoutResult.Success) {
                        showPay = false
                        showCart = false
                    }
                }
            }
        )
    }

    receipt?.let { r ->
        ReceiptDialog(
            result = r,
            shop = shop,
            currency = currency,
            onDone = { vm.lastReceipt.value = null }
        )
    }
}
