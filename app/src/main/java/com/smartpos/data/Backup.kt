package com.smartpos.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backup and restore of all products, labels and sales as ONE small JSON file.
 * The user saves that file to their own cloud (Google Drive, email, ...) with the share menu,
 * and restores it on a new phone. Product photos are not included.
 */
object Backup {
    private const val FORMAT = "donut4u-pos-backup"
    private const val PREFS = "backup"

    fun lastBackup(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("last", 0L)

    private fun markBackedUp(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong("last", System.currentTimeMillis()).apply()
    }

    /** Builds the whole backup as JSON text. */
    suspend fun toJson(context: Context): String = withContext(Dispatchers.IO) {
        val dao = PosDatabase.get(context).dao()
        val labels = dao.allLabels()
        val products = dao.allProducts()
        val sales = dao.allSales()

        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", 1)
        root.put("created", System.currentTimeMillis())

        val labelsJson = JSONArray()
        for (l in labels) {
            labelsJson.put(JSONObject().put("name", l.name).put("colorIndex", l.colorIndex))
        }
        root.put("labels", labelsJson)

        val productsJson = JSONArray()
        for (p in products) {
            productsJson.put(
                JSONObject()
                    .put("id", p.id)
                    .put("barcode", p.barcode)
                    .put("name", p.name)
                    .put("price", p.price)
                    .put("stock", p.stock)
                    .put("label", p.label)
                    .put("createdAt", p.createdAt)
            )
        }
        root.put("products", productsJson)

        val salesJson = JSONArray()
        for (s in sales) {
            val itemsJson = JSONArray()
            for (i in s.items) {
                itemsJson.put(
                    JSONObject()
                        .put("id", i.id)
                        .put("productId", i.productId)
                        .put("name", i.name)
                        .put("price", i.price)
                        .put("qty", i.qty)
                )
            }
            salesJson.put(
                JSONObject()
                    .put("id", s.sale.id)
                    .put("timestamp", s.sale.timestamp)
                    .put("total", s.sale.total)
                    .put("paid", s.sale.paid)
                    .put("change", s.sale.change)
                    .put("items", itemsJson)
            )
        }
        root.put("sales", salesJson)

        root.toString()
    }

    /** Writes the backup file and returns the share-menu intent (choose Drive, email, ...). */
    suspend fun shareIntent(context: Context): Intent {
        val json = toJson(context)
        return withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            dir.listFiles()?.filter { it.name.startsWith("donut4u-pos-backup") }?.forEach { it.delete() }
            val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date())
            val file = File(dir, "donut4u-pos-backup_$stamp.json")
            file.writeText(json)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            markBackedUp(context)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "donut4u pos backup")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            Intent.createChooser(send, "Save backup to...").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Replaces ALL data on this phone with the backup.
     * Returns null on success, or a message describing the problem.
     * Nothing is changed unless the whole file was read successfully.
     */
    suspend fun restore(context: Context, text: String): String? = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject(text)
            if (root.optString("format") != FORMAT) {
                return@withContext "This is not a donut4u pos backup file."
            }

            val labelsJson = root.getJSONArray("labels")
            val labels = ArrayList<LabelEntity>()
            for (n in 0 until labelsJson.length()) {
                val o = labelsJson.getJSONObject(n)
                labels += LabelEntity(name = o.getString("name"), colorIndex = o.getInt("colorIndex"))
            }

            val productsJson = root.getJSONArray("products")
            val products = ArrayList<Product>()
            for (n in 0 until productsJson.length()) {
                val o = productsJson.getJSONObject(n)
                products += Product(
                    id = o.getLong("id"),
                    barcode = o.getString("barcode"),
                    name = o.getString("name"),
                    price = o.getDouble("price"),
                    stock = o.getInt("stock"),
                    label = o.getString("label"),
                    imagePath = null,
                    createdAt = o.optLong("createdAt", System.currentTimeMillis())
                )
            }

            val salesJson = root.getJSONArray("sales")
            val sales = ArrayList<Sale>()
            val items = ArrayList<SaleItem>()
            for (n in 0 until salesJson.length()) {
                val o = salesJson.getJSONObject(n)
                val saleId = o.getLong("id")
                sales += Sale(
                    id = saleId,
                    timestamp = o.getLong("timestamp"),
                    total = o.getDouble("total"),
                    paid = o.getDouble("paid"),
                    change = o.getDouble("change")
                )
                val itemsJson = o.getJSONArray("items")
                for (k in 0 until itemsJson.length()) {
                    val it2 = itemsJson.getJSONObject(k)
                    items += SaleItem(
                        id = it2.getLong("id"),
                        saleId = saleId,
                        productId = it2.getLong("productId"),
                        name = it2.getString("name"),
                        price = it2.getDouble("price"),
                        qty = it2.getInt("qty")
                    )
                }
            }

            val db = PosDatabase.get(context)
            val dao = db.dao()
            db.withTransaction {
                dao.clearSaleItems()
                dao.clearSales()
                dao.clearProducts()
                dao.clearLabels()
                dao.restoreLabels(labels)
                dao.restoreProducts(products)
                dao.restoreSales(sales)
                dao.restoreSaleItems(items)
            }
            markBackedUp(context)
            null
        } catch (e: Exception) {
            "Could not read this backup: ${e.message}"
        }
    }
}
