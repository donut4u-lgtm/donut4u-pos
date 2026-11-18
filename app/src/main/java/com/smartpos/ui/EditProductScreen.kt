package com.smartpos.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.smartpos.PosViewModel
import com.smartpos.Screen
import com.smartpos.util.plainAmount

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProductScreen(vm: PosViewModel, productId: Long) {
    val products by vm.products.collectAsState()
    val labels by vm.labels.collectAsState()
    val currency by vm.settings.currency.collectAsState()
    val product = products.firstOrNull { it.id == productId }
    var confirmDelete by remember { mutableStateOf(false) }

    BackHandler { vm.screen.value = Screen.Home }

    // The product disappeared (deleted): go back.
    LaunchedEffect(product == null, products.isNotEmpty()) {
        if (product == null && products.isNotEmpty()) vm.screen.value = Screen.Home
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit product", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { vm.screen.value = Screen.Home }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (product != null) {
                key(product.id) {
                    ProductForm(
                        imagePath = product.imagePath,
                        initialBarcode = product.barcode,
                        initialName = product.name,
                        initialLabel = product.label,
                        initialPrice = plainAmount(product.price),
                        initialStock = product.stock.toString(),
                        suggestions = emptyList(),
                        labels = labels,
                        colorIndexFor = { vm.colorIndexFor(it) },
                        currency = currency,
                        saveText = "Save changes",
                        onSave = { code, name, label, price, stock ->
                            vm.saveProduct(product, code, name, label, price, stock, product.imagePath) { ok ->
                                if (ok) {
                                    vm.toast("Saved")
                                    vm.screen.value = Screen.Home
                                }
                            }
                        },
                        onDelete = { confirmDelete = true }
                    )
                }
            }
        }
    }

    if (confirmDelete && product != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete \"${product.name}\"?") },
            text = { Text("The product is removed from your inventory. Past sales keep their records.") },
            confirmButton = {
                Button(onClick = {
                    confirmDelete = false
                    vm.deleteProduct(product)
                    vm.screen.value = Screen.Home
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}
