package com.mpesa.automation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mpesa.automation.data.local.database.AppDatabase
import com.mpesa.automation.data.local.entity.BundleProduct
import com.mpesa.automation.data.local.entity.TransactionQueue
import com.mpesa.automation.data.local.entity.TransactionStatus
import com.mpesa.automation.service.AutomationForegroundService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    
    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 1001
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.POST_NOTIFICATIONS
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        requestRequiredPermissions()
        
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme()
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        onRequestPermissions = { requestRequiredPermissions() },
                        onOpenAccessibilitySettings = { openAccessibilitySettings() }
                    )
                }
            }
        }
    }
    
    private fun requestRequiredPermissions() {
        val missingPermissions = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missingPermissions.isNotEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                requestPermissions(missingPermissions.toTypedArray(), REQUEST_CODE_PERMISSIONS)
            }
        }
    }
    
    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (!allGranted) {
                Toast.makeText(
                    this,
                    "Some permissions were denied. The app may not function properly.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onRequestPermissions: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit
) {
    val viewModel: MainViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "M-Pesa Automation",
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                actions = {
                    IconButton(onClick = { viewModel.toggleMockDialog() }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Mock Data")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // Status Cards
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatusCard(
                    modifier = Modifier.weight(1f),
                    title = "Pending",
                    count = uiState.pendingCount,
                    color = Color(0xFFFFC107)
                )
                StatusCard(
                    modifier = Modifier.weight(1f),
                    title = "Processing",
                    count = uiState.processingCount,
                    color = Color(0xFF2196F3)
                )
                StatusCard(
                    modifier = Modifier.weight(1f),
                    title = "Completed",
                    count = uiState.completedCount,
                    color = Color(0xFF4CAF50)
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Permission Status
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        "System Status",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Permissions")
                        TextButton(onClick = onRequestPermissions) {
                            Text("Grant")
                        }
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Accessibility Service")
                        TextButton(onClick = onOpenAccessibilitySettings) {
                            Text("Enable")
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Transaction List
            Text(
                "Recent Transactions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            if (uiState.transactions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No transactions yet",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(uiState.transactions) { transaction ->
                        TransactionItem(transaction = transaction)
                    }
                }
            }
        }
        
        // Mock Data Dialog
        if (uiState.showMockDialog) {
            MockDataDialog(
                onDismiss = { viewModel.toggleMockDialog() },
                onAddBundle = { amount, ussdString, description ->
                    viewModel.addBundleProduct(amount, ussdString, description)
                },
                onAddTransaction = { phoneNumber, amount ->
                    viewModel.addMockTransaction(phoneNumber, amount)
                }
            )
        }
    }
}

@Composable
fun StatusCard(
    modifier: Modifier = Modifier,
    title: String,
    count: Int,
    color: Color
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.1f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                count.toString(),
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                title,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
fun TransactionItem(transaction: TransactionQueue) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "KES ${String.format("%.2f", transaction.amount)}",
                    fontWeight = FontWeight.Bold
                )
                Text(
                    transaction.phoneNumber,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Text(
                    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                        .format(java.util.Date(transaction.timestamp)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            
            StatusChip(status = transaction.status)
        }
    }
}

@Composable
fun StatusChip(status: TransactionStatus) {
    val (text, color) = when (status) {
        TransactionStatus.PENDING -> "PENDING" to Color(0xFFFFC107)
        TransactionStatus.PROCESSING -> "PROCESSING" to Color(0xFF2196F3)
        TransactionStatus.COMPLETED -> "COMPLETED" to Color(0xFF4CAF50)
        TransactionStatus.FAILED -> "FAILED" to Color(0xFFF44336)
    }
    
    Surface(
        color = color.copy(alpha = 0.2f),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun MockDataDialog(
    onDismiss: () -> Unit,
    onAddBundle: (Double, String, String) -> Unit,
    onAddTransaction: (String, Double) -> Unit
) {
    var bundleAmount by remember { mutableStateOf("") }
    var ussdString by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var phoneNumber by remember { mutableStateOf("") }
    var transactionAmount by remember { mutableStateOf("") }
    var activeTab by remember { mutableStateOf(0) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Mock Data Generator")
        },
        text = {
            Column {
                TabRow(selectedTabIndex = activeTab) {
                    Tab(
                        selected = activeTab == 0,
                        onClick = { activeTab = 0 },
                        text = { Text("Bundle") }
                    )
                    Tab(
                        selected = activeTab == 1,
                        onClick = { activeTab = 1 },
                        text = { Text("Transaction") }
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                when (activeTab) {
                    0 -> {
                        OutlinedTextField(
                            value = bundleAmount,
                            onValueChange = { bundleAmount = it },
                            label = { Text("Amount (KES)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = ussdString,
                            onValueChange = { ussdString = it },
                            label = { Text("USSD String (*180*5*2#)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = description,
                            onValueChange = { description = it },
                            label = { Text("Description") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                val amount = bundleAmount.toDoubleOrNull()
                                if (amount != null && ussdString.isNotEmpty()) {
                                    onAddBundle(amount, ussdString, description.ifEmpty { "Bundle $amount KES" })
                                    bundleAmount = ""
                                    ussdString = ""
                                    description = ""
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Add Bundle")
                        }
                    }
                    1 -> {
                        OutlinedTextField(
                            value = phoneNumber,
                            onValueChange = { phoneNumber = it },
                            label = { Text("Phone Number (07XXXXXXXX)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = transactionAmount,
                            onValueChange = { transactionAmount = it },
                            label = { Text("Amount (KES)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                val amount = transactionAmount.toDoubleOrNull()
                                if (amount != null && phoneNumber.isNotEmpty()) {
                                    onAddTransaction(phoneNumber, amount)
                                    phoneNumber = ""
                                    transactionAmount = ""
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Add Transaction")
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

class MainViewModel : ViewModel() {
    
    private val database = AppDatabase.getDatabase(MyApplication.instance)
    private val dao = database.automationDao()
    
    private val _showMockDialog = MutableStateFlow(false)
    val showMockDialog: StateFlow<Boolean> = _showMockDialog.asStateFlow()
    
    data class UiState(
        val transactions: List<TransactionQueue> = emptyList(),
        val pendingCount: Int = 0,
        val processingCount: Int = 0,
        val completedCount: Int = 0,
        val showMockDialog: Boolean = false
    )
    
    val uiState: StateFlow<UiState> = combine(
        dao.getAllTransactions(),
        _showMockDialog
    ) { transactions, showDialog ->
        UiState(
            transactions = transactions,
            pendingCount = transactions.count { it.status == TransactionStatus.PENDING },
            processingCount = transactions.count { it.status == TransactionStatus.PROCESSING },
            completedCount = transactions.count { it.status == TransactionStatus.COMPLETED },
            showMockDialog = showDialog
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = UiState()
    )
    
    fun toggleMockDialog() {
        _showMockDialog.value = !_showMockDialog.value
    }
    
    fun addBundleProduct(amount: Double, ussdString: String, description: String) {
        viewModelScope.launch {
            val bundle = BundleProduct(
                amount = amount,
                description = description,
                ussdString = ussdString
            )
            dao.insertBundleProduct(bundle)
        }
    }
    
    fun addMockTransaction(phoneNumber: String, amount: Double) {
        viewModelScope.launch {
            val normalizedPhone = when {
                phoneNumber.startsWith("07") -> "254${phoneNumber.substring(1)}"
                phoneNumber.startsWith("011") -> "254${phoneNumber.substring(1)}"
                else -> phoneNumber
            }
            
            val transaction = TransactionQueue(
                phoneNumber = normalizedPhone,
                amount = amount,
                status = TransactionStatus.PENDING
            )
            
            dao.insertTransaction(transaction)
            
            // Start foreground service to process
            val intent = Intent(MyApplication.instance, AutomationForegroundService::class.java).apply {
                action = AutomationForegroundService.ACTION_PROCESS_QUEUE
            }
            MyApplication.instance.startForegroundService(intent)
        }
    }
}

class MyApplication : android.app.Application() {
    companion object {
        lateinit var instance: MyApplication
            private set
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
