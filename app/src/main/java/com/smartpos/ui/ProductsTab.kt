package com.smartpos.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smartpos.PosViewModel
import com.smartpos.Screen

/** Inventory view: the same colored columns, but tapping a product edits it. */
@Composable
fun ProductsTab(vm: PosViewModel) {
    val products by vm.products.collectAsState()
    val labels by vm.labels.collectAsState()
    val currency by vm.settings.currency.collectAsState()

    var query by remember { mutableStateOf("") }
    var colorTarget by remember { mutableStateOf<LabelGroup?>(null) }

    val filtered = remember(products, query) {
        val q = query.trim()
        if (q.isEmpty()) products
        else products.filter { it.name.contains(q, ignoreCase = true) || it.barcode.contains(q) }
    }
    val groups = remember(filtered, labels) { groupProducts(filtered, labels) }
    val lowStock = products.count { it.stock <= 5 }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            singleLine = true,
            placeholder = { Text("Search products") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                    }
                }
            }
        )
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${products.size} products · ${groups.size} labels" +
                    if (lowStock > 0) " · $lowStock low/out of stock" else "",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LabelBoard(
            groups = groups,
            qtyInCart = emptyMap(),
            currency = currency,
            emptyText = if (products.isEmpty())
                "Nothing here yet.\nTap \"Add product\": take a picture, scan the barcode, and the label (column) is created automatically."
            else "No products match \"$query\".",
            onProductClick = { vm.screen.value = Screen.EditProduct(it.id) },
            onHeaderClick = { colorTarget = it },
            modifier = Modifier.weight(1f)
        )
    }

    colorTarget?.let { g ->
        ColorPickerDialog(
            label = g.label,
            currentIndex = g.colorIndex,
            onPick = {
                vm.setLabelColor(g.label, it)
                colorTarget = null
            },
            onDismiss = { colorTarget = null }
        )
    }
}
