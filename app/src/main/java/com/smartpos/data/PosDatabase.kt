package com.smartpos.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Product::class, LabelEntity::class, Sale::class, SaleItem::class],
    version = 2,
    exportSchema = false
)
abstract class PosDatabase : RoomDatabase() {
    abstract fun dao(): PosDao

    companion object {
        @Volatile
        private var instance: PosDatabase? = null

        /** Version 2: wholesale price on products and the sale type (retail / wholesale). */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE products ADD COLUMN wholesale REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sales ADD COLUMN type TEXT NOT NULL DEFAULT 'retail'")
            }
        }

        fun get(context: Context): PosDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PosDatabase::class.java,
                    "smartpos.db"
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
