package com.smartpos.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.smartpos.data.Sale
import com.smartpos.data.SaleItem
import com.smartpos.data.SaleWithItems
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun money(v: Double, symbol: String): String = symbol + String.format(Locale.US, "%,.2f", v)

fun plainAmount(v: Double): String = String.format(Locale.US, "%.2f", v)

fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0

fun formatDateTime(ts: Long): String =
    SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()).format(Date(ts))

fun formatTime(ts: Long): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ts))

fun receiptText(shop: String, symbol: String, sale: Sale, items: List<SaleItem>): String = buildString {
    appendLine(shop)
    appendLine(formatDateTime(sale.timestamp))
    appendLine("Receipt #${sale.id}")
    appendLine("------------------------------")
    items.forEach {
        appendLine("${it.qty} x ${it.name}")
        appendLine("     @ ${money(it.price, symbol)} = ${money(it.price * it.qty, symbol)}")
    }
    appendLine("------------------------------")
    appendLine("TOTAL:  ${money(sale.total, symbol)}")
    appendLine("CASH:   ${money(sale.paid, symbol)}")
    appendLine("CHANGE: ${money(sale.change, symbol)}")
    appendLine()
    append("Thank you!")
}

fun shareText(context: Context, subject: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun csvCell(s: String): String =
    if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

/** Writes all sales to a CSV file in the cache dir and returns a share chooser intent. */
suspend fun buildSalesCsvIntent(context: Context, sales: List<SaleWithItems>): Intent =
    withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val file = File(dir, "sales_$stamp.csv")
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        file.bufferedWriter().use { w ->
            w.appendLine("sale_id,date,item,qty,unit_price,line_total,sale_total,cash_paid,change")
            for (s in sales) {
                for (item in s.items) {
                    w.appendLine(
                        listOf(
                            s.sale.id.toString(),
                            df.format(Date(s.sale.timestamp)),
                            csvCell(item.name),
                            item.qty.toString(),
                            plainAmount(item.price),
                            plainAmount(item.price * item.qty),
                            plainAmount(s.sale.total),
                            plainAmount(s.sale.paid),
                            plainAmount(s.sale.change)
                        ).joinToString(",")
                    )
                }
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        Intent.createChooser(send, "Export sales").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
