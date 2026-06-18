package com.mpesa.automation.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transaction_queue")
data class TransactionQueue(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val phoneNumber: String,
    val amount: Double,
    val timestamp: Long = System.currentTimeMillis(),
    val status: TransactionStatus = TransactionStatus.PENDING,
    val ussdString: String? = null,
    val errorMessage: String? = null,
    val completedTimestamp: Long? = null
)

enum class TransactionStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED
}
