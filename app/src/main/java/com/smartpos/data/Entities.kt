package com.smartpos.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/** Number of distinct column colors in the UI palette. */
const val LABEL_COLOR_COUNT = 12

/** A label (= a colored column on the board). Name is the unique key. */
@Entity(tableName = "labels")
data class LabelEntity(
    @PrimaryKey val name: String,
    val colorIndex: Int
)

@Entity(
    tableName = "products",
    indices = [Index(value = ["barcode"], unique = true)]
)
data class Product(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val barcode: String,
    val name: String,
    val price: Double,
    val stock: Int,
    val label: String,
    val imagePath: String?,
    val createdAt: Long = System.currentTimeMillis(),
    val wholesale: Double = 0.0
)

@Entity(tableName = "sales")
data class Sale(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val total: Double,
    val paid: Double,
    val change: Double,
    /** "retail" or "wholesale" */
    val type: String = "retail"
)

@Entity(
    tableName = "sale_items",
    foreignKeys = [
        ForeignKey(
            entity = Sale::class,
            parentColumns = ["id"],
            childColumns = ["saleId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("saleId")]
)
data class SaleItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val saleId: Long,
    val productId: Long,
    val name: String,
    val price: Double,
    val qty: Int
)

data class SaleWithItems(
    @Embedded val sale: Sale,
    @Relation(parentColumn = "id", entityColumn = "saleId")
    val items: List<SaleItem>
)
