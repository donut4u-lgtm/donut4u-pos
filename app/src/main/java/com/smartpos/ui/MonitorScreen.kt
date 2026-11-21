package com.smartpos.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.smartpos.cloud.Cloud
import com.smartpos.util.formatDateTime
import com.smartpos.util.money
import java.util.Calendar

private fun startOfDay(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** Read-only live view of the shop, for the monitoring phone. */
@Composable
fun MonitorScreen() {
    val products by Cloud.monitorProducts.collectAsState()
    val labels by Cloud.monitorLabels.collectAsState()
    val sales by Cloud.monitorSales.collectAsState()
    val currency by Cloud.monitorCurrency.collectAsState()
    val status by Cloud.monitorStatus.collectAsState()
    var expandedId by remember { mutableStateOf<Long?>(null) }

    val todayStart = remember(sales) { startOfDay() }
    val today = sales.filter { it.sale.timestamp >= todayStart }
    val lowStock = products.filter { it.stock <= 5 }.sortedBy { it.stock }.take(20)
    val groups = remember(products, labels) { groupProducts(products, labels) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            status,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Today", style = MaterialTheme.typography.labelLarge)
                Text(
                    money(today.sumOf { it.sale.total }, currency),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "${today.size} sale${if (today.size == 1) "" else "s"} · " +
                        "${today.sumOf { s -> s.items.sumOf { it.qty } }} items sold",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Low or empty stock", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (lowStock.isEmpty()) {
                    Text("Nothing is running low.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                lowStock.forEach { p ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, Modifier.weight(1f))
                        Text(
                            if (p.stock <= 0) "Out of stock" else "${p.stock} left",
                            fontWeight = FontWeight.Bold,
                            color = if (p.stock <= 0) Color(0xFFD32F2F) else Color(0xFFEF6C00)
                        )
                    }
                }
            }
        }

        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Recent sales", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (sales.isEmpty()) {
                    Text("No sales yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                sales.take(30).forEach { s ->
                    val expanded = expandedId == s.sale.id
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { expandedId = if (expanded) null else s.sale.id }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Sale #${s.sale.id}", fontWeight = FontWeight.SemiBold)
                                Text(
                                    formatDateTime(s.sale.timestamp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                money(s.sale.total, currency),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (expanded) {
                            s.items.forEach {
                                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                    Text("${it.qty} × ${it.name}", Modifier.weight(1f))
                                    Text(money(it.price * it.qty, currency))
                                }
                            }
                        }
                    }
                }
            }
        }

        Text("Products by label", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Box(Modifier.fillMaxWidth().height(440.dp)) {
            LabelBoard(
                groups = groups,
                qtyInCart = emptyMap(),
                currency = currency,
                emptyText = "No products received yet.\nOpen the selling phone and make sure it is online.",
                onProductClick = {}
            )
        }
    }
}
