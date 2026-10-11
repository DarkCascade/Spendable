package com.darkcascade.spendable.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    /** Returns the row id per item, or -1 where the dedupe key already existed. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<TransactionEntity>): List<Long>

    @Query("DELETE FROM transactions WHERE pending = 1")
    suspend fun deletePending(): Int

    @Query("SELECT * FROM transactions ORDER BY epochDay DESC")
    suspend fun all(): List<TransactionEntity>

    @Query("SELECT * FROM transactions ORDER BY epochDay DESC, description LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TransactionEntity>>

    @Query("SELECT COUNT(*) FROM transactions")
    fun observeCount(): Flow<Int>

    @Query("SELECT MIN(epochDay) FROM transactions")
    fun observeFirstDay(): Flow<Long?>

    @Query("SELECT MAX(epochDay) FROM transactions")
    fun observeLastDay(): Flow<Long?>

    @Query("DELETE FROM transactions")
    suspend fun clear()
}

@Dao
interface RecurringDao {
    @Query("SELECT * FROM recurring ORDER BY flow, score DESC")
    fun observeAll(): Flow<List<RecurringEntity>>

    @Query("SELECT * FROM recurring")
    suspend fun all(): List<RecurringEntity>

    @Query("DELETE FROM recurring")
    suspend fun clear()

    @Insert
    suspend fun insertAll(rows: List<RecurringEntity>)

    /** Analysis results are always recomputed from scratch, so replace atomically. */
    @Transaction
    suspend fun replaceAll(rows: List<RecurringEntity>) {
        clear()
        insertAll(rows)
    }
}
