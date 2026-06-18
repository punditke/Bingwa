kotlin
package com.mpesa.automation.data.local.dao

import androidx.room.*
import com.mpesa.automation.data.local.entity.BundleProduct
import com.mpesa.automation.data.local.entity.TransactionQueue
import com.mpesa.automation.data.local.entity.TransactionStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface AutomationDao {
    
    // Bundle Products
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBundleProduct(bundle: BundleProduct): Long
    
    @Query("SELECT * FROM bundle_products WHERE amount = :amount AND isActive = 1 LIMIT 1")
    suspend fun getBundleByAmount(amount: Double): BundleProduct?
    
    @Query("SELECT * FROM bundle_products WHERE isActive = 1")
    fun getAllActiveBundles(): Flow<List<BundleProduct>>
    
    @Delete
    suspend fun deleteBundleProduct(bundle: BundleProduct)
    
    @Update
    suspend fun updateBundleProduct(bundle: BundleProduct)
    
    // Transaction Queue
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(transaction: TransactionQueue): Long
    
    @Query("SELECT * FROM transaction_queue WHERE status = 'PENDING' ORDER BY timestamp ASC LIMIT 1")
    suspend fun getNextPendingTransaction(): TransactionQueue?
    
    @Query("UPDATE transaction_queue SET status = :status WHERE id = :transactionId")
    suspend fun updateTransactionStatus(transactionId: Long, status: TransactionStatus)
    
    @Query("UPDATE transaction_queue SET status = :status, errorMessage = :error, completedTimestamp = :completedTime WHERE id = :transactionId")
    suspend fun updateTransactionStatusWithError(
        transactionId: Long, 
        status: TransactionStatus, 
        error: String?,
        completedTime: Long = System.currentTimeMillis()
    )
    
    @Query("SELECT * FROM transaction_queue ORDER BY timestamp DESC")
    fun getAllTransactions(): Flow<List<TransactionQueue>>
    
    @Query("SELECT * FROM transaction_queue WHERE status = 'PROCESSING'")
    suspend fun getProcessingTransactions(): List<TransactionQueue>
    
    @Query("DELETE FROM transaction_queue WHERE status = 'COMPLETED' AND completedTimestamp < :beforeTimestamp")
    suspend fun cleanOldCompletedTransactions(beforeTimestamp: Long)
}
