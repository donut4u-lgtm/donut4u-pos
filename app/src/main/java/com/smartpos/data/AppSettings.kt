package com.smartpos.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/** Tiny SharedPreferences-backed settings: shop name (shown on receipts) and currency symbol. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    val shopName = MutableStateFlow(prefs.getString("shop", null) ?: "donut4u pos")
    val currency = MutableStateFlow(prefs.getString("currency", null) ?: "₱")

    fun save(shop: String, symbol: String) {
        val s = shop.trim().ifEmpty { "donut4u pos" }
        val c = symbol.trim()
        prefs.edit().putString("shop", s).putString("currency", c).apply()
        shopName.value = s
        currency.value = c
    }
}
