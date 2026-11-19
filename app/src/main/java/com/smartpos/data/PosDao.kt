package com.smartpos.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PosDao {
    // ---- Products ----
    @Query("SELECT * FROM products ORDER BY name COLLATE NOCASE ASC")
    fun observeProducts(): Flow<List<Product>>

    @Query("SELECT * FROM products WHERE barcode = :barcode LIMIT 1")
    suspend fun findByBarcode(barcode: String): Product?

    @Query("SELECT * FROM products WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): Product?

    @Insert
    suspend fun insertProduct(p: Product): Long

    @Update
    suspend fun updateProduct(p: Product)

    @Query("DELETE FROM products WHERE id = :id")
    suspend fun deleteProduct(id: Long)

    @Query("UPDATE products SET stock = stock - :qty WHERE id = :id")
    suspend fun decrementStock(id: Long, qty: Int)

    @Query("UPDATE products SET stock = stock + :qty WHERE id = :id")
    suspend fun incrementStock(id: Long, qty: Int)

    // ---- Labels ----
    @Query("SELECT * FROM labels")
    fun observeLabels(): Flow<List<LabelEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLabel(l: LabelEntity): Long

    @Query("UPDATE labels SET colorIndex = :idx WHERE name = :name")
    suspend fun setLabelColor(name: String, idx: Int)

    // ---- Sales ----
    @Insert
    suspend fun insertSale(s: Sale): Long

    @Insert
    suspend fun insertSaleItems(items: List<SaleItem>)

    @Transaction
    @Query("SELECT * FROM sales ORDER BY timestamp DESC LIMIT 300")
    fun observeSales(): Flow<List<SaleWithItems>>

    @Transaction
    @Query("SELECT * FROM sales ORDER BY timestamp DESC")
    suspend fun allSales(): List<SaleWithItems>

    @Query("DELETE FROM sales WHERE id = :id")
    suspend fun deleteSale(id: Long)

    // ---- Backup / restore ----
    @Query("SELECT * FROM labels")
    suspend fun allLabels(): List<LabelEntity>

    @Query("SELECT * FROM products")
    suspend fun allProducts(): List<Product>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreLabels(list: List<LabelEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreProducts(list: List<Product>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreSales(list: List<Sale>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreSaleItems(list: List<SaleItem>)

    @Query("DELETE FROM sale_items")
    suspend fun clearSaleItems()

    @Query("DELETE FROM sales")
    suspend fun clearSales()

    @Query("DELETE FROM products")
    suspend fun clearProducts()

    @Query("DELETE FROM labels")
    suspend fun clearLabels()
}
