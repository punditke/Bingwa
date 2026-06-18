package com.mpesa.automation.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Telephony
import android.telephony.SmsMessage
import com.mpesa.automation.data.local.database.AppDatabase
import com.mpesa.automation.data.local.entity.TransactionQueue
import com.mpesa.automation.data.local.entity.TransactionStatus
import com.mpesa.automation.service.AutomationForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.regex.Pattern

class MpesaReceiver : BroadcastReceiver() {
    
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    
    companion object {
        private val AMOUNT_PATTERN = Pattern.compile(
            "Ksh\\s*([0-9,]+\\.?[0-9]*)\\s*"
        )
        
        private val PHONE_PATTERN = Pattern.compile(
            "(?:07\\d{8}|011\\d{7}|2547\\d{8}|25411\\d{7})"
        )
        
        private val MPESA_MERCHANT_PATTERN = Pattern.compile(
            "confirmed.*?(?:Ksh\\s*[0-9,]+\\.?[0-9]*).*?(?:from|to)\\s*(?:07\\d{8}|011\\d{7}|2547\\d{8}|25411\\d{7})",
            Pattern.CASE_INSENSITIVE
        )
        
        private val MPESA_P2P_PATTERN = Pattern.compile(
            "(?:received|sent).*?(?:Ksh\\s*[0-9,]+\\.?[0-9]*).*?(?:from|to)\\s*(?:07\\d{8}|011\\d{7}|2547\\d{8}|25411\\d{7})",
            Pattern.CASE_INSENSITIVE
        )
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            return
        }
        
        val bundle = intent.extras ?: return
        val pdus = bundle["pdus"] as? Array<*> ?: return
        
        val messages = pdus.mapNotNull { pdu ->
            try {
                val format = bundle.getString("format")
                SmsMessage.createFromPdu(pdu as ByteArray, format)
            } catch (e: Exception) {
                null
            }
        }
        
        for (message in messages) {
            val messageBody = message.messageBody
            val originatingAddress = message.originatingAddress ?: ""
            
            if (isMpesaTransaction(messageBody, originatingAddress)) {
                processTransaction(context, messageBody)
            }
        }
    }
    
    private fun isMpesaTransaction(message: String, sender: String): Boolean {
        val mpesaKeywords = listOf("M-PESA", "MPESA", "M-Pesa", "Safaricom", "Ksh")
        val mpesaSenders = listOf("MPESA", "M-PESA", "Safaricom")
        
        val containsKeyword = mpesaKeywords.any { 
            message.contains(it, ignoreCase = true) 
        }
        
        val isValidSender = mpesaSenders.any { 
            sender.contains(it, ignoreCase = true) 
        } || sender.isEmpty()
        
        return containsKeyword && isValidSender && 
               (MPESA_MERCHANT_PATTERN.matcher(message).find() || 
                MPESA_P2P_PATTERN.matcher(message).find())
    }
    
    private fun processTransaction(context: Context, message: String) {
        val amount = extractAmount(message)
        val phoneNumber = extractPhoneNumber(message)
        
        if (amount > 0.0 && phoneNumber.isNotEmpty()) {
            coroutineScope.launch {
                try {
                    val database = AppDatabase.getDatabase(context)
                    val dao = database.automationDao()
                    
                    val transaction = TransactionQueue(
                        phoneNumber = phoneNumber,
                        amount = amount,
                        status = TransactionStatus.PENDING
                    )
                    
                    dao.insertTransaction(transaction)
                    
                    val serviceIntent = Intent(context, AutomationForegroundService::class.java).apply {
                        action = AutomationForegroundService.ACTION_PROCESS_QUEUE
                    }
                    context.startForegroundService(serviceIntent)
                    
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
    
    private fun extractAmount(message: String): Double {
        val matcher = AMOUNT_PATTERN.matcher(message)
        return if (matcher.find()) {
            try {
                matcher.group(1)?.replace(",", "")?.toDouble() ?: 0.0
            } catch (e: Exception) {
                0.0
            }
        } else {
            0.0
        }
    }
    
    private fun extractPhoneNumber(message: String): String {
        val matcher = PHONE_PATTERN.matcher(message)
        return if (matcher.find()) {
            val number = matcher.group(0)
            normalizePhoneNumber(number)
        } else {
            ""
        }
    }
    
    private fun normalizePhoneNumber(phone: String): String {
        return when {
            phone.startsWith("254") -> phone
            phone.startsWith("07") -> "254${phone.substring(1)}"
            phone.startsWith("011") -> "254${phone.substring(1)}"
            else -> phone
        }
    }
}
