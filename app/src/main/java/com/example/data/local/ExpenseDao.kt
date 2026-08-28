package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.Expense
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM expenses WHERE isDeleted = 0 ORDER BY dateEpoch DESC, createdAt DESC")
    fun getAllActiveExpenses(): Flow<List<Expense>>

    @Query("SELECT * FROM expenses ORDER BY dateEpoch DESC, createdAt DESC")
    fun getAllExpenses(): Flow<List<Expense>>

    @Query("SELECT * FROM expenses ORDER BY dateEpoch DESC, createdAt DESC")
    fun getAllExpensesWithDeleted(): Flow<List<Expense>>

    @Query("SELECT * FROM expenses WHERE id = :id LIMIT 1")
    suspend fun getExpenseById(id: String): Expense?

    @Query("SELECT * FROM expenses WHERE isDeleted = 0 AND dateEpoch >= :startEpoch AND dateEpoch <= :endEpoch ORDER BY dateEpoch DESC")
    fun getExpensesInRange(startEpoch: Long, endEpoch: Long): Flow<List<Expense>>

    @Query("SELECT SUM(amount) FROM expenses WHERE isDeleted = 0 AND dateEpoch >= :startEpoch AND dateEpoch <= :endEpoch")
    fun getExpensesSumInRange(startEpoch: Long, endEpoch: Long): Flow<Long?>

    @Query("SELECT SUM(amount) FROM expenses WHERE isDeleted = 0")
    fun getTotalExpensesSum(): Flow<Long?>

    @Query("SELECT * FROM expenses WHERE isDeleted = 0 AND sourceAccountId = :accountId ORDER BY dateEpoch DESC")
    fun getExpensesForAccount(accountId: String): Flow<List<Expense>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpense(expense: Expense)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpenses(expenses: List<Expense>)

    @Update
    suspend fun updateExpense(expense: Expense)

    @Delete
    suspend fun deleteExpense(expense: Expense)

    @Query("DELETE FROM expenses WHERE isSampleData = 1")
    suspend fun deleteSampleExpenses()
}
