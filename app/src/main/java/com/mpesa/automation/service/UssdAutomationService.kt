package com.mpesa.automation.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.mpesa.automation.data.local.database.AppDatabase
import com.mpesa.automation.data.local.entity.TransactionStatus
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class UssdAutomationService : AccessibilityService() {
    
    companion object {
        private const val USSD_PACKAGE = "com.android.phone"
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val NODE_POLLING_DELAY_MS = 500L
        private const val MAX_STEP_WAIT_TIME_MS = 15000L
    }
    
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private val isExecutingStep = AtomicBoolean(false)
    private val database by lazy { AppDatabase.getDatabase(this) }
    
    private var currentTransactionId: Long = -1
    private var currentUssdSteps: List<String> = emptyList()
    private var currentStepIndex: Int = 0
    private var retryCount: Int = 0
    private var stepStartTime: Long = 0
    
    private val stepDelayRunnable = Runnable {
        executeCurrentStep()
    }
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or 
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.DEFAULT
            notificationTimeout = 100
        }
        
        serviceInfo = info
        
        loadCurrentTransaction()
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        
        if (event.packageName?.toString()?.contains(USSD_PACKAGE) == true) {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    handleUssdWindowChanged()
                }
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    handleUssdContentChanged()
                }
            }
        }
    }
    
    override fun onInterrupt() {
        // Service interrupted, clean up
        handler.removeCallbacksAndMessages(null)
        serviceScope.cancel()
    }
    
    private fun loadCurrentTransaction() {
        val prefs = getSharedPreferences("automation_prefs", Context.MODE_PRIVATE)
        currentTransactionId = prefs.getLong("current_transaction_id", -1)
        val ussdString = prefs.getString("current_ussd_string", "") ?: ""
        
        if (currentTransactionId != -1L && ussdString.isNotEmpty()) {
            currentUssdSteps = parseUssdString(ussdString)
            currentStepIndex = 0
        }
    }
    
    private fun parseUssdString(ussdString: String): List<String> {
        // Parse USSD string like "*180*5*2#" into steps
        val cleanedString = ussdString.replace("#", "")
        val steps = mutableListOf<String>()
        val parts = cleanedString.split("*")
        
        for (i in 1 until parts.size) {
            if (parts[i].isNotEmpty()) {
                steps.add(parts[i])
            }
        }
        
        // Add final confirmation if needed
        steps.add("CONFIRM")
        
        return steps
    }
    
    private fun handleUssdWindowChanged() {
        if (isExecutingStep.get()) return
        
        val rootNode = rootInActiveWindow ?: return
        
        try {
            if (isUssdDialog(rootNode)) {
                processUssdDialog(rootNode)
            }
        } finally {
            rootNode.recycle()
        }
    }
    
    private fun handleUssdContentChanged() {
        if (isExecutingStep.get()) return
        
        handler.postDelayed({
            val rootNode = rootInActiveWindow
            if (rootNode != null) {
                try {
                    if (isUssdDialog(rootNode)) {
                        processUssdDialog(rootNode)
                    }
                } finally {
                    rootNode.recycle()
                }
            }
        }, NODE_POLLING_DELAY_MS)
    }
    
    private fun isUssdDialog(node: AccessibilityNodeInfo): Boolean {
        return node.findAccessibilityNodeInfosByText("USSD").isNotEmpty() ||
               node.findAccessibilityNodeInfosByText("M-PESA").isNotEmpty() ||
               node.findAccessibilityNodeInfosByText("Safaricom").isNotEmpty()
    }
    
    private fun processUssdDialog(rootNode: AccessibilityNodeInfo) {
        if (currentStepIndex >= currentUssdSteps.size) {
            completeTransaction(true)
            return
        }
        
        if (hasErrorOccurred(rootNode)) {
            handleError("USSD error detected in dialog")
            return
        }
        
        if (!isExecutingStep.compareAndSet(false, true)) {
            return
        }
        
        stepStartTime = System.currentTimeMillis()
        
        serviceScope.launch {
            try {
                executeStepWithTimeout(rootNode)
            } catch (e: Exception) {
                handleError("Step execution failed: ${e.message}")
            }
        }
    }
    
    private suspend fun executeStepWithTimeout(rootNode: AccessibilityNodeInfo) {
        withTimeout(MAX_STEP_WAIT_TIME_MS) {
            val stepExecuted = executeStep(rootNode)
            
            if (!stepExecuted) {
                // Retry after delay
                delay(1000L)
                val freshRoot = rootInActiveWindow
                if (freshRoot != null) {
                    try {
                        val retrySuccessful = executeStep(freshRoot)
                        if (!retrySuccessful) {
                            handleError("Failed to execute step after retry")
                        }
                    } finally {
                        freshRoot.recycle()
                    }
                }
            }
        }
    }
    
    private fun executeStep(rootNode: AccessibilityNodeInfo): Boolean {
        if (currentStepIndex >= currentUssdSteps.size) {
            return false
        }
        
        val currentStep = currentUssdSteps[currentStepIndex]
        
        // Try to find and interact with appropriate UI elements
        val editTexts = findEditTextNodes(rootNode)
        val buttons = findButtonNodes(rootNode)
        
        if (editTexts.isNotEmpty()) {
            // Input field found, enter the step value
            val editText = editTexts[0]
            val success = performInput(editText, currentStep)
            editTexts.forEach { it.recycle() }
            return success
        } else if (buttons.isNotEmpty()) {
            // Buttons found, try to click appropriate one
            if (currentStep == "CONFIRM") {
                val success = clickConfirmButton(buttons)
                buttons.forEach { it.recycle() }
                return success
            } else {
                // Try to send the step as text input if applicable
                val success = clickButtonByText(buttons, currentStep)
                buttons.forEach { it.recycle() }
                return success
            }
        }
        
        return false
    }
    
    private fun findEditTextNodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val editTexts = mutableListOf<AccessibilityNodeInfo>()
        findNodesByClass(node, "android.widget.EditText", editTexts)
        return editTexts
    }
    
    private fun findButtonNodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val buttons = mutableListOf<AccessibilityNodeInfo>()
        findNodesByClass(node, "android.widget.Button", buttons)
        return buttons
    }
    
    private fun findNodesByClass(
        node: AccessibilityNodeInfo, 
        className: String, 
        result: MutableList<AccessibilityNodeInfo>
    ) {
        if (node.className?.toString() == className && node.isEnabled) {
            result.add(AccessibilityNodeInfo.obtain(node))
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                findNodesByClass(child, className, result)
                child.recycle()
            }
        }
    }
    
    private fun performInput(node: AccessibilityNodeInfo, text: String): Boolean {
        val success = performAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT, text)
        
        if (success) {
            // Find and click Send/OK button
            val rootNode = rootInActiveWindow
            if (rootNode != null) {
                try {
                    val buttons = findButtonNodes(rootNode)
                    val sendClicked = clickSendButton(buttons) || clickOkButton(buttons)
                    buttons.forEach { it.recycle() }
                    
                    if (sendClicked) {
                        advanceStep()
                    }
                    
                    return sendClicked
                } finally {
                    rootNode.recycle()
                }
            }
        }
        
        return false
    }
    
    private fun performAction(
        node: AccessibilityNodeInfo, 
        action: Int, 
        text: String? = null
    ): Boolean {
        val arguments = if (text != null) {
            android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
        } else {
            null
        }
        
        return node.performAction(action, arguments)
    }
    
    private fun clickSendButton(buttons: List<AccessibilityNodeInfo>): Boolean {
        return clickButtonByText(buttons, "Send")
    }
    
    private fun clickOkButton(buttons: List<AccessibilityNodeInfo>): Boolean {
        return clickButtonByText(buttons, "OK")
    }
    
    private fun clickConfirmButton(buttons: List<AccessibilityNodeInfo>): Boolean {
        return clickButtonByText(buttons, "Accept") || 
               clickButtonByText(buttons, "Confirm") ||
               clickButtonByText(buttons, "OK")
    }
    
    private fun clickButtonByText(buttons: List<AccessibilityNodeInfo>, text: String): Boolean {
        for (button in buttons) {
            if (button.text?.toString()?.equals(text, ignoreCase = true) == true ||
                button.contentDescription?.toString()?.equals(text, ignoreCase = true) == true) {
                val success = button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (success) {
                    if (text != "Send" && text != "OK") {
                        advanceStep()
                    }
                    return true
                }
            }
        }
        return false
    }
    
    private fun advanceStep() {
        currentStepIndex++
        retryCount = 0
        isExecutingStep.set(false)
    }
    
    private fun hasErrorOccurred(rootNode: AccessibilityNodeInfo): Boolean {
        val errorKeywords = listOf(
            "insufficient", "failed", "error", "invalid", "timeout",
            "cancelled", "not available", "try again", "unable"
        )
        
        for (keyword in errorKeywords) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(keyword)
            if (nodes.isNotEmpty()) {
                nodes.forEach { it.recycle() }
                return true
            }
        }
        
        return false
    }
    
    private fun handleError(errorMessage: String) {
        serviceScope.launch {
            try {
                val dao = database.automationDao()
                dao.updateTransactionStatusWithError(
                    currentTransactionId,
                    TransactionStatus.FAILED,
                    errorMessage
                )
                
                // Clear transaction data
                val prefs = getSharedPreferences("automation_prefs", Context.MODE_PRIVATE)
                prefs.edit().clear().apply()
                
                currentTransactionId = -1
                currentStepIndex = 0
                currentUssdSteps = emptyList()
                
            } finally {
                isExecutingStep.set(false)
            }
        }
    }
    
    private fun completeTransaction(success: Boolean) {
        serviceScope.launch {
            try {
                val dao = database.automationDao()
                if (success) {
                    dao.updateTransactionStatus(currentTransactionId, TransactionStatus.COMPLETED)
                } else {
                    dao.updateTransactionStatusWithError(
                        currentTransactionId,
                        TransactionStatus.FAILED,
                        "Transaction completion failed"
                    )
                }
                
                // Clear transaction data
                val prefs = getSharedPreferences("automation_prefs", Context.MODE_PRIVATE)
                prefs.edit().clear().apply()
                
                currentTransactionId = -1
                currentStepIndex = 0
                currentUssdSteps = emptyList()
                
            } finally {
                isExecutingStep.set(false)
            }
        }
    }
}
