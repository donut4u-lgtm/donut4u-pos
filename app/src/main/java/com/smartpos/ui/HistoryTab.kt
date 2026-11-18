package com.smartpos.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.smartpos.PosViewModel
import com.smartpos.data.SaleWithItems
import com.smartpos.util.buildSalesCsvIntent
import com.smartpos.util.formatDateTime
import com.smartpos.util.money
import com.smartpos.util.receiptText
import com.smartpos.util.shareText
import kotlinx.coroutines.launch
import java.util.Calendar

private fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

@Composable
fun HistoryTab(vm: PosViewModel) {
    val sales by vm.sales.collectAsState()
    val currency by vm.settings.currency.collectAsState()
    val shop by vm.settings.shopName.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var expandedId by remember { mutableStateOf<Long?>(null) }
    var voidTarget by remember { mutableStateOf<SaleWithItems?>(null) }

    val todayStart = remember(sales) { startOfToday() }
    val today = sales.filter { it.sale.timestamp >= todayStart }
    val todayTotal = today.sumOf { it.sale.total }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Today", style = MaterialTheme.typography.labelLarge)
                    Text(
                        money(todayTotal, currency),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${today.size} sale${if (today.size == 1) "" else "s"} · " +
                            "${today.sumOf { s -> s.items.sumOf { it.qty } }} items",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val all = vm.allSales()
                                if (all.isEmpty()) {
                                    vm.toast("No sales to export yet")
                                } else {
                                    context.startActivity(buildSalesCsvIntent(context, all))
                                }
                            }
                        },
                        modifier = Modifier.padding(top = 8.dp)
                    ) { Text("Export all sales (CSV)") }
                }
            }
        }

        if (sales.isEmpty()) {
            item {
                Text(
                    "No sales yet. Completed sales will show up here.",
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        items(sales, key = { it.sale.id }) { s ->
            val expanded = expandedId == s.sale.id
            Card(
                Modifier.fillMaxWidth().clickable { expandedId = if (expanded) null else s.sale.id }
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Sale #${s.sale.id}", fontWeight = FontWeight.SemiBold)
                            Text(
                                formatDateTime(s.sale.timestamp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                money(s.sale.total, currency),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "${s.items.sumOf { it.qty }} items",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (expanded) {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        s.items.forEach {
                            Row(Modifier.fillMaxWidth()) {
                                Text("${it.qty} × ${it.name}", Modifier.weight(1f))
                                Text(money(it.price * it.qty, currency))
                            }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text("Cash ${money(s.sale.paid, currency)} · Change ${money(s.sale.change, currency)}")
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                shareText(
                                    context,
                                    "Receipt #${s.sale.id}",
                                    receiptText(shop, currency, s.sale, s.items)
                                )
                            }) { Text("Share receipt") }
                            OutlinedButton(onClick = { voidTarget = s }) { Text("Void") }
                        }
                    }
                }
            }
        }
    }

    voidTarget?.let { s ->
        AlertDialog(
            onDismissRequest = { voidTarget = null },
            title = { Text("Void sale #${s.sale.id}?") },
            text = { Text("The sale is deleted and its items are put back into stock.") },
            confirmButton = {
                Button(onClick = {
                    vm.voidSale(s)
                    voidTarget = null
                    expandedId = null
                }) { Text("Void sale") }
            },
            dismissButton = { TextButton(onClick = { voidTarget = null }) { Text("Keep") } }
        )
    }
}
