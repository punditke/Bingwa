package com.mpesa.automation.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bundle_products")
data class BundleProduct(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val amount: Double,
    val description: String,
    val ussdString: String,
    val provider: String = "Safaricom",
    val isActive: Boolean = true
)
