package com.example.privprint.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ShopEntity::class,
        PrinterEntity::class,
        PrintJobEntity::class,
        SessionEntity::class,
        AuditEventEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class PrivPrintDatabase : RoomDatabase() {

    abstract fun privPrintDao(): PrivPrintDao

    companion object {
        @Volatile
        private var INSTANCE: PrivPrintDatabase? = null

        fun getInstance(context: Context): PrivPrintDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    PrivPrintDatabase::class.java,
                    "privprint_secure.db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
