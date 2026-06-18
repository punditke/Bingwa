package com.mpesa.automation.service

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.mpesa.automation.MainActivity
import com.mpesa.automation.R
import com.mpesa.automation.data.local.database.AppDatabase
import com.mpesa.automation.data.local.entity.TransactionStatus
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class AutomationForegroundService : Service() {
    
    companion object {
        const val ACTION_PROCESS_QUEUE = "com.mpesa.automation.action.PROCESS_QUEUE"
        const val CHANNEL_ID = "mpesa_automation_channel"
        const val NOTIFICATION_ID = 1001
        
        private const val POLLING_INTERVAL_MS = 3000L
        private const val MAX_CONSECUTIVE_ERRORS = 5
    }
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isProcessing = AtomicBoolean(false)
    private val database by lazy { AppDatabase.getDatabase(this) }
    private var pollingJob: Job? = null
    private var consecutiveErrors = 0
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        
        when (intent?.action) {
            ACTION_PROCESS_QUEUE -> {
                startQueueProcessing()
            }
        }
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        pollingJob?.cancel()
        serviceScope.cancel()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun buildNotification(): android.app.Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("M-Pesa Automation")
            .setContentText("Monitoring transactions and processing USSD sessions")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }
    
    private fun startQueueProcessing() {
        if (pollingJob?.isActive == true) {
            return
        }
        
        pollingJob = serviceScope.launch {
            while (isActive) {
                try {
                    processNextTransaction()
                    consecutiveErrors = 0
                } catch (e: Exception) {
                    consecutiveErrors++
                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                        updateNotification("Service paused due to errors")
                        delay(30000L) // Wait 30 seconds before retrying
                    }
                }
                delay(POLLING_INTERVAL_MS)
            }
        }
    }
    
    private suspend fun processNextTransaction() {
        if (isProcessing.get()) {
            return // Already processing a transaction
        }
        
        if (!isAccessibilityServiceEnabled()) {
            updateNotification("Please enable Accessibility Service")
            return
        }
        
        val dao = database.automationDao()
        val transaction = dao.getNextPendingTransaction()
        
        if (transaction != null && isProcessing.compareAndSet(false, true)) {
            try {
                val bundle = dao.getBundleByAmount(transaction.amount)
                
                if (bundle != null) {
                    dao.updateTransactionStatus(transaction.id, TransactionStatus.PROCESSING)
                    updateNotification("Processing: ${String.format("%.2f", transaction.amount)} KES")
                    
                    // Initiate USSD call
                    val ussdIntent = Intent(Intent.ACTION_CALL).apply {
                        data = android.net.Uri.parse("tel:${bundle.ussdString.replace("*", "%2A").replace("#", "%23")}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    
                    // Store current transaction info for accessibility service
                    val prefs = getSharedPreferences("automation_prefs", Context.MODE_PRIVATE)
                    prefs.edit().apply {
                        putLong("current_transaction_id", transaction.id)
                        putString("current_ussd_string", bundle.ussdString)
                        apply()
                    }
                    
                    startActivity(ussdIntent)
                    
                    // Wait for accessibility service to handle the USSD session
                    delay(60000L) // Max wait time for USSD session
                    
                    // Check if transaction was completed
                    val currentTransaction = dao.getNextPendingTransaction()
                    if (currentTransaction?.id == transaction.id) {
                        // Transaction still pending, mark as failed
                        dao.updateTransactionStatusWithError(
                            transaction.id,
                            TransactionStatus.FAILED,
                            "USSD session timeout"
                        )
                        updateNotification("Transaction failed: Timeout")
                    }
                } else {
                    dao.updateTransactionStatusWithError(
                        transaction.id,
                        TransactionStatus.FAILED,
                        "No bundle found for amount ${transaction.amount}"
                    )
                    updateNotification("No matching bundle")
                }
            } finally {
                isProcessing.set(false)
                // Clean up old completed transactions
                dao.cleanOldCompletedTransactions(System.currentTimeMillis() - 86400000) // 24 hours
            }
        }
    }
    
    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = "${packageName}/${UssdAutomationService::class.java.name}"
        val enabledServices = try {
            android.provider.Settings.Secure.getString(
                contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        } catch (e: Exception) {
            ""
        }
        return enabledServices?.contains(expectedComponentName) == true
    }
    
    private fun updateNotification(message: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("M-Pesa Automation")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
