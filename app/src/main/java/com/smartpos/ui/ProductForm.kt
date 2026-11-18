package com.smartpos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.smartpos.data.LabelEntity

/**
 * Product details form, used when adding (step 3) and editing a product.
 * The label field is pre-filled with the automatic label from the picture; tapping a chip
 * (suggested labels first, then labels that already exist) or typing changes it.
 */
@Composable
fun ProductForm(
    imagePath: String?,
    initialBarcode: String,
    initialName: String,
    initialLabel: String,
    initialPrice: String,
    initialStock: String,
    suggestions: List<String>,
    labels: List<LabelEntity>,
    colorIndexFor: (String) -> Int,
    currency: String,
    saveText: String,
    onSave: (barcode: String, name: String, label: String, price: Double, stock: Int) -> Unit,
    modifier: Modifier = Modifier,
    onDelete: (() -> Unit)? = null
) {
    var barcode by remember { mutableStateOf(initialBarcode) }
    var name by remember { mutableStateOf(initialName) }
    var label by remember { mutableStateOf(initialLabel) }
    var price by remember { mutableStateOf(initialPrice) }
    var stock by remember { mutableStateOf(initialStock) }
    var error by remember { mutableStateOf<String?>(null) }

    val chipOptions = remember(suggestions, labels) {
        (suggestions + labels.map { it.name }).distinctBy { it.lowercase() }
    }
    val dotColor = labelColor(colorIndexFor(label))

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (imagePath != null) {
            ProductImage(
                imagePath,
                Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(16.dp))
            )
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it; error = null },
            label = { Text("Product name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = barcode,
            onValueChange = { barcode = it; error = null },
            label = { Text("Barcode") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(28.dp).background(dotColor, CircleShape))
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Label (column)") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
        if (chipOptions.isNotEmpty()) {
            Text(
                if (suggestions.isNotEmpty()) "Detected from the picture, then your existing labels:"
                else "Your existing labels:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                chipOptions.forEach { option ->
                    FilterChip(
                        selected = label.trim().equals(option, ignoreCase = true),
                        onClick = { label = option },
                        label = { Text(option) }
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = price,
                onValueChange = { raw ->
                    price = raw.replace(',', '.').filter { c -> c.isDigit() || c == '.' }
                    error = null
                },
                label = { Text("Price ($currency)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = stock,
                onValueChange = { raw ->
                    stock = raw.filter { c -> c.isDigit() }.take(7)
                    error = null
                },
                label = { Text("Stock") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
        }

        Button(
            onClick = {
                val p = price.toDoubleOrNull()
                val s = stock.toIntOrNull() ?: 0
                when {
                    name.isBlank() -> error = "Enter a product name"
                    barcode.isBlank() -> error = "Enter or scan a barcode"
                    p == null || p < 0 -> error = "Enter a valid price"
                    else -> onSave(barcode, name, label, p, s)
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text(saveText) }

        if (onDelete != null) {
            OutlinedButton(
                onClick = onDelete,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Delete product", color = MaterialTheme.colorScheme.error) }
        }
    }
}
