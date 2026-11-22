package com.smartpos.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.smartpos.data.LabelEntity
import com.smartpos.data.Product
import com.smartpos.util.money
import java.io.File

/** All products that share one label = one colored column. */
data class LabelGroup(val label: String, val colorIndex: Int, val products: List<Product>)

fun groupProducts(products: List<Product>, labels: List<LabelEntity>): List<LabelGroup> {
    val colorOf = labels.associate { it.name to it.colorIndex }
    return products
        .groupBy { it.label }
        .map { (label, list) -> LabelGroup(label, colorOf[label] ?: 0, list) }
        .sortedBy { it.label.lowercase() }
}

@Composable
fun ProductImage(path: String?, modifier: Modifier, accent: Color = MaterialTheme.colorScheme.primary) {
    if (path != null) {
        AsyncImage(
            model = File(path),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            error = ColorPainter(accent.copy(alpha = 0.25f)),
            placeholder = ColorPainter(accent.copy(alpha = 0.12f)),
            modifier = modifier
        )
    } else {
        Box(modifier.background(accent.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
            Text("📦")
        }
    }
}

/**
 * The board: one vertical, color-coded column per label, scrolling sideways.
 * [onHeaderClick] (optional) is called when a column header is tapped.
 */
@Composable
fun LabelBoard(
    groups: List<LabelGroup>,
    qtyInCart: Map<Long, Int>,
    currency: String,
    emptyText: String,
    onProductClick: (Product) -> Unit,
    modifier: Modifier = Modifier,
    onHeaderClick: ((LabelGroup) -> Unit)? = null
) {
    if (groups.isEmpty()) {
        Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                emptyText,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    LazyRow(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(groups, key = { it.label }) { g ->
            LabelColumn(g, qtyInCart, currency, onProductClick, onHeaderClick)
        }
    }
}

@Composable
private fun LabelColumn(
    g: LabelGroup,
    qtyInCart: Map<Long, Int>,
    currency: String,
    onProductClick: (Product) -> Unit,
    onHeaderClick: ((LabelGroup) -> Unit)?
) {
    val color = labelColor(g.colorIndex)
    val onColor = onLabelColor(color)
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .width(210.dp)
            .fillMaxHeight()
            .clip(shape)
            .background(color.copy(alpha = 0.18f))
            .border(2.dp, color, shape)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Brush.horizontalGradient(listOf(color, lerp(color, Color.Black, 0.32f))))
                .then(if (onHeaderClick != null) Modifier.clickable { onHeaderClick(g) } else Modifier)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                g.label,
                color = onColor,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Box(
                Modifier
                    .background(onColor.copy(alpha = 0.22f), CircleShape)
                    .padding(horizontal = 9.dp, vertical = 2.dp)
            ) {
                Text(
                    "${g.products.size}",
                    color = onColor,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(g.products, key = { it.id }) { p ->
                ProductCard(p, color, qtyInCart[p.id] ?: 0, currency) { onProductClick(p) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProductCard(
    p: Product,
    accent: Color,
    inCart: Int,
    currency: String,
    onClick: () -> Unit
) {
    val stockColor = when {
        p.stock <= 0 -> Color(0xFFD32F2F)
        p.stock <= 5 -> Color(0xFFEF6C00)
        else -> Color(0xFF00A859)
    }
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        border = BorderStroke(1.5.dp, accent.copy(alpha = 0.6f))
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ProductImage(p.imagePath, Modifier.size(54.dp).clip(RoundedCornerShape(10.dp)), accent)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    p.name,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    money(p.price, currency),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Box(
                    Modifier
                        .background(stockColor.copy(alpha = 0.16f), CircleShape)
                        .padding(horizontal = 8.dp, vertical = 1.dp)
                ) {
                    Text(
                        if (p.stock <= 0) "Out of stock" else "Stock: ${p.stock}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = stockColor
                    )
                }
            }
            if (inCart > 0) {
                Box(
                    Modifier.size(26.dp).background(accent, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "$inCart",
                        color = onLabelColor(accent),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
