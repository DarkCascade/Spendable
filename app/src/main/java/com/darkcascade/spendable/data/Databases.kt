package com.darkcascade.spendable.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** Database #1: raw imported transactions. */
@Database(entities = [TransactionEntity::class], version = 1)
abstract class TransactionsDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
}

/** Database #2: recurring charges/deposits by day of month, with confidence ratings. */
@Database(entities = [RecurringEntity::class], version = 1)
abstract class RecurringDatabase : RoomDatabase() {
    abstract fun recurringDao(): RecurringDao
}

object Databases {
    @Volatile private var tx: TransactionsDatabase? = null
    @Volatile private var rec: RecurringDatabase? = null

    fun transactions(context: Context): TransactionsDatabase =
        tx ?: synchronized(this) {
            tx ?: Room.databaseBuilder(context.applicationContext, TransactionsDatabase::class.java, "transactions.db")
                .build().also { tx = it }
        }

    fun recurring(context: Context): RecurringDatabase =
        rec ?: synchronized(this) {
            // Fully derived from the transactions DB, so it's safe to rebuild on schema changes.
            rec ?: Room.databaseBuilder(context.applicationContext, RecurringDatabase::class.java, "recurring.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build().also { rec = it }
        }
}
