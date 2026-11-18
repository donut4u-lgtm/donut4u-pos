package com.smartpos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartpos.CartLine
import com.smartpos.CheckoutResult
import com.smartpos.util.money
import com.smartpos.util.plainAmount
import com.smartpos.util.receiptText
import com.smartpos.util.shareText

/** Tap a swatch to recolor a label column. */
@Composable
fun ColorPickerDialog(
    label: String,
    currentIndex: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Color for \"$label\"") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LabelPalette.chunked(4).forEachIndexed { rowIdx, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEachIndexed { colIdx, color ->
                            val idx = rowIdx * 4 + colIdx
                            Swatch(
                                selected = idx == currentIndex,
                                color = color,
                                onClick = { onPick(idx) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun Swatch(selected: Boolean, color: Color, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        Modifier
            .size(48.dp)
            .background(color, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (selected) Text("✓", color = onLabelColor(color), fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SettingsDialog(
    shop: String,
    currency: String,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var shopText by remember { mutableStateOf(shop) }
    var currencyText by remember { mutableStateOf(currency) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = shopText,
                    onValueChange = { shopText = it },
                    label = { Text("Shop name (printed on receipts)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = currencyText,
                    onValueChange = { if (it.length <= 4) currencyText = it },
                    label = { Text("Currency symbol") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(shopText, currencyText) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Cash payment: quick amount buttons, live change calculation. */
@Composable
fun PayDialog(
    total: Double,
    currency: String,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit
) {
    var text by remember { mutableStateOf("") }
    val paid = text.toDoubleOrNull() ?: 0.0
    val ok = paid + 0.001 >= total
    val change = paid - total
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Charge ${money(total, currency)}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { raw ->
                        text = raw.replace(',', '.').filter { c -> c.isDigit() || c == '.' }
                    },
                    label = { Text("Cash received") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AssistChip(onClick = { text = plainAmount(total) }, label = { Text("Exact") })
                    listOf(50, 100, 200, 500, 1000).forEach { v ->
                        AssistChip(onClick = { text = v.toString() }, label = { Text("$v") })
                    }
                }
                if (text.isNotEmpty()) {
                    Text(
                        if (ok) "Change: ${money(change, currency)}" else "Short by ${money(-change, currency)}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (ok) Color(0xFF2E7D32) else Color(0xFFD32F2F)
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(paid) }, enabled = ok && text.isNotEmpty()) {
                Text("Complete sale")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun ReceiptDialog(
    result: CheckoutResult.Success,
    shop: String,
    currency: String,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Sale complete ✓") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Total: ${money(result.sale.total, currency)}")
                Text("Cash: ${money(result.sale.paid, currency)}")
                Text(
                    "Change: ${money(result.sale.change, currency)}",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2E7D32)
                )
            }
        },
        confirmButton = { Button(onClick = onDone) { Text("New sale") } },
        dismissButton = {
            TextButton(onClick = {
                shareText(
                    context,
                    "Receipt #${result.sale.id}",
                    receiptText(shop, currency, result.sale, result.items)
                )
            }) { Text("Share receipt") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CartSheet(
    cart: List<CartLine>,
    currency: String,
    onQty: (Long, Int) -> Unit,
    onRemove: (Long) -> Unit,
    onClear: () -> Unit,
    onCharge: () -> Unit,
    onDismiss: () -> Unit
) {
    // Close the sheet automatically when the last item is removed.
    LaunchedEffect(cart.isEmpty()) { if (cart.isEmpty()) onDismiss() }
    val total = cart.sumOf { it.total }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Cart",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onClear) { Text("Clear all") }
            }
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(cart, key = { it.product.id }) { line ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                line.product.name,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                money(line.product.price, currency),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { onQty(line.product.id, line.qty - 1) }) {
                            Text("−", style = MaterialTheme.typography.headlineSmall)
                        }
                        Text("${line.qty}", fontWeight = FontWeight.Bold)
                        IconButton(onClick = { onQty(line.product.id, line.qty + 1) }) {
                            Text("+", style = MaterialTheme.typography.headlineSmall)
                        }
                        Text(
                            money(line.total, currency),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(84.dp)
                        )
                        IconButton(onClick = { onRemove(line.product.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove")
                        }
                    }
                    HorizontalDivider()
                }
            }
            Spacer(Modifier.size(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Total", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    money(total, currency),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.size(12.dp))
            Button(onClick = onCharge, modifier = Modifier.fillMaxWidth()) { Text("Charge") }
        }
    }
}
