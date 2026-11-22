package com.smartpos

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.smartpos.data.AppSettings
import com.smartpos.data.LABEL_COLOR_COUNT
import com.smartpos.data.LabelEntity
import com.smartpos.data.PosDatabase
import com.smartpos.data.Product
import com.smartpos.data.Sale
import com.smartpos.data.SaleItem
import com.smartpos.data.SaleWithItems
import com.smartpos.util.round2
import com.smartpos.vision.Labeler
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

sealed interface Screen {
    data object Home : Screen
    data class AddProduct(val barcode: String? = null) : Screen
    data class EditProduct(val id: Long) : Screen
    data object Scanner : Screen
}

data class CartLine(val product: Product, val qty: Int) {
    val total: Double get() = product.price * qty
}

sealed interface CheckoutResult {
    data class Success(val sale: Sale, val items: List<SaleItem>) : CheckoutResult
    data class Error(val message: String) : CheckoutResult
}

class PosViewModel(private val app: Application) : AndroidViewModel(app) {

    private val db = PosDatabase.get(app)
    private val dao = db.dao()
    val settings = AppSettings(app)

    // ---- UI state ----
    val screen = MutableStateFlow<Screen>(Screen.Home)
    val homeTab = MutableStateFlow(0)
    val cart = MutableStateFlow<List<CartLine>>(emptyList())
    val lastReceipt = MutableStateFlow<CheckoutResult.Success?>(null)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 16)

    // ---- Data ----
    val products: StateFlow<List<Product>> =
        dao.observeProducts().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val labels: StateFlow<List<LabelEntity>> =
        dao.observeLabels().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val sales: StateFlow<List<SaleWithItems>> =
        dao.observeSales().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun toast(msg: String) {
        messages.tryEmit(msg)
    }

    // ---------------------------------------------------------------- products

    suspend fun findByBarcode(code: String): Product? = dao.findByBarcode(code.trim())

    fun newImageFile(): File {
        val dir = File(app.filesDir, "product_images").apply { mkdirs() }
        return File(dir, "p_${System.currentTimeMillis()}.jpg")
    }

    /** Normalizes a label ("drink " -> "Drink") and re-uses an existing label's spelling if there is one. */
    fun canonicalLabel(input: String): String {
        val n = input.trim().replaceFirstChar { it.uppercase() }.ifEmpty { "Other" }
        return labels.value.firstOrNull { it.name.equals(n, ignoreCase = true) }?.name ?: n
    }

    /** Color index a label has (or would get if created now). */
    fun colorIndexFor(label: String): Int {
        val existing = labels.value.firstOrNull { it.name.equals(label.trim(), ignoreCase = true) }
        return existing?.colorIndex ?: nextFreeColor()
    }

    private fun nextFreeColor(): Int {
        val used = labels.value.map { it.colorIndex }.toSet()
        return (0 until LABEL_COLOR_COUNT).firstOrNull { it !in used }
            ?: (labels.value.size % LABEL_COLOR_COUNT)
    }

    /**
     * Runs the on-device image labeler on the photo. Labels that already exist on the board
     * are put first so similar products land in the same column instead of creating new ones.
     * The first element is the automatic label.
     */
    suspend fun suggestLabels(file: File): List<String> {
        val raw = Labeler.suggest(app, file).map { canonicalLabel(it) }
        val existing = labels.value.map { it.name }
        val known = raw.filter { r -> existing.any { it.equals(r, ignoreCase = true) } }
        return (known + raw).distinct().ifEmpty { listOf("Other") }
    }

    fun saveProduct(
        existing: Product?,
        barcode: String,
        name: String,
        label: String,
        price: Double,
        stock: Int,
        imagePath: String?,
        onDone: (Boolean) -> Unit
    ) {
        viewModelScope.launch {
            val code = barcode.trim()
            val cleanName = name.trim()
            if (code.isEmpty() || cleanName.isEmpty()) {
                toast("Name and barcode are required")
                onDone(false)
                return@launch
            }
            val clash = dao.findByBarcode(code)
            if (clash != null && clash.id != existing?.id) {
                toast("Barcode already used by \"${clash.name}\"")
                onDone(false)
                return@launch
            }
            val labelName = canonicalLabel(label)
            try {
                db.withTransaction {
                    dao.insertLabel(LabelEntity(labelName, colorIndexFor(labelName)))
                    if (existing == null) {
                        dao.insertProduct(
                            Product(
                                barcode = code, name = cleanName, price = price, stock = stock,
                                label = labelName, imagePath = imagePath
                            )
                        )
                    } else {
                        dao.updateProduct(
                            existing.copy(
                                barcode = code, name = cleanName, price = price,
                                stock = stock, label = labelName
                            )
                        )
                    }
                }
                onDone(true)
            } catch (e: Exception) {
                toast("Could not save: ${e.message}")
                onDone(false)
            }
        }
    }

    fun deleteProduct(p: Product) {
        viewModelScope.launch {
            dao.deleteProduct(p.id)
            p.imagePath?.let { File(it).delete() }
            cart.value = cart.value.filterNot { it.product.id == p.id }
            toast("Deleted ${p.name}")
        }
    }

    fun setLabelColor(label: String, colorIndex: Int) {
        viewModelScope.launch { dao.setLabelColor(label, colorIndex) }
    }

    // -------------------------------------------------------------------- cart

    /** Adds one unit. Selling is never blocked by the stock number. */
    fun addToCart(p: Product): Boolean {
        val current = cart.value
        val line = current.firstOrNull { it.product.id == p.id }
        // Selling is never blocked by the stock number (stock is only a guide).
        cart.value = if (line == null) {
            current + CartLine(p, 1)
        } else {
            current.map { if (it.product.id == p.id) it.copy(qty = it.qty + 1) else it }
        }
        return true
    }

    fun setQty(productId: Long, qty: Int) {
        val line = cart.value.firstOrNull { it.product.id == productId } ?: return
        when {
            qty <= 0 -> removeLine(productId)
            else -> cart.value = cart.value.map { if (it.product.id == productId) it.copy(qty = qty) else it }
        }
    }

    fun removeLine(productId: Long) {
        cart.value = cart.value.filterNot { it.product.id == productId }
    }

    fun clearCart() {
        cart.value = emptyList()
    }

    // ---------------------------------------------------------------- checkout

    fun checkout(paid: Double, onResult: (CheckoutResult) -> Unit) {
        viewModelScope.launch {
            val lines = cart.value
            if (lines.isEmpty()) {
                onResult(CheckoutResult.Error("Cart is empty"))
                return@launch
            }
            val result: CheckoutResult = try {
                db.withTransaction<CheckoutResult> {
                    val items = ArrayList<SaleItem>()
                    var total = 0.0
                    for (l in lines) {
                        // Re-read from the database so the latest price is used.
                        val fresh = dao.getById(l.product.id)
                            ?: return@withTransaction CheckoutResult.Error("\"${l.product.name}\" was removed")
                        items += SaleItem(
                            saleId = 0, productId = fresh.id, name = fresh.name,
                            price = fresh.price, qty = l.qty
                        )
                        total += fresh.price * l.qty
                    }
                    total = round2(total)
                    if (paid + 0.001 < total) {
                        return@withTransaction CheckoutResult.Error("Cash received is less than the total")
                    }
                    val sale = Sale(
                        timestamp = System.currentTimeMillis(),
                        total = total, paid = paid, change = round2(paid - total)
                    )
                    val saleId = dao.insertSale(sale)
                    val saved = items.map { it.copy(saleId = saleId) }
                    dao.insertSaleItems(saved)
                    for (item in saved) dao.decrementStock(item.productId, item.qty)
                    CheckoutResult.Success(sale.copy(id = saleId), saved)
                }
            } catch (e: Exception) {
                CheckoutResult.Error("Could not save sale: ${e.message}")
            }
            when (result) {
                is CheckoutResult.Success -> {
                    cart.value = emptyList()
                    lastReceipt.value = result
                }
                is CheckoutResult.Error -> toast(result.message)
            }
            onResult(result)
        }
    }

    /** Deletes a sale and puts the items back into stock. */
    fun voidSale(s: SaleWithItems) {
        viewModelScope.launch {
            db.withTransaction {
                for (item in s.items) dao.incrementStock(item.productId, item.qty)
                dao.deleteSale(s.sale.id)
            }
            toast("Sale #${s.sale.id} voided, stock restored")
        }
    }

    suspend fun allSales(): List<SaleWithItems> = dao.allSales()
}
