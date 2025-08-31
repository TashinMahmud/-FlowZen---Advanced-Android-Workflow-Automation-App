package com.google.ai.edge.gallery.ui.workflow
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.ViewModelProvider
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
object CreateWorkflowDestination {
    const val route = "create_workflow"
}
@Composable
fun CreateWorkflowScreen(
    modelManagerViewModel: ModelManagerViewModel,
    navigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WorkflowViewModel = viewModel(factory = ViewModelProvider.Factory)
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
// Initialize preferences when the screen is created
    LaunchedEffect(Unit) {
        viewModel.initializePreferences(context)
// Refresh workflow states from preferences to show current status
        viewModel.refreshWorkflowStatesFromPreferences(context)
    }
// Auto-save current workflow when leaving the screen
    BackHandler(enabled = true) {
        viewModel.saveCurrentWorkflowDraft()
        navigateUp()
    }
    WorkflowViewWrapper(
        viewModel = viewModel,
        modelManagerViewModel = modelManagerViewModel,
        navigateUp = navigateUp,
        modifier = modifier,
        scope = scope
    )
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowViewWrapper(
    viewModel: WorkflowViewModel,
    modelManagerViewModel: ModelManagerViewModel,
    navigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    scope: CoroutineScope
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }
    var workflowName by remember { mutableStateOf("") }
    var workflowDescription by remember { mutableStateOf("") }
// Initialize model when screen is first created
    LaunchedEffect(Unit) {
        if (uiState.selectedModel == null) {
            viewModel.initializeModel(context)
        }
    }
// Periodically refresh workflow states to keep UI updated during background execution
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000) // Refresh every 5 seconds
            viewModel.refreshWorkflowStatesFromPreferences(context)
        }
    }
// Auto-save workflow when it changes
    LaunchedEffect(uiState.currentWorkflow) {
        viewModel.saveCurrentWorkflowDraft()
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Workflow Manager") },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.saveCurrentWorkflowDraft()
                        navigateUp()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        workflowName = ""
                        workflowDescription = ""
                        showCreateDialog = true
                    }) {
                        Icon(Icons.Default.Add, contentDescription = "Create Workflow")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
// Selected model
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = "Selected Model",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = uiState.selectedModel?.name ?: "No model selected",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Your Workflows",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (uiState.workflows.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No workflows yet. Tap + to create one.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(uiState.workflows) { workflow ->
                        WorkflowCard(
                            workflow = workflow,
                            isSelected = workflow.id == uiState.currentWorkflow?.id,
                            executionProgress = uiState.executionProgress,
                            onSelect = {
                                viewModel.saveCurrentWorkflowDraft()
                                viewModel.selectWorkflow(workflow.id)
                            },
                            onExecute = {
// Use background execution to ensure it continues even if app is closed
                                viewModel.executeWorkflowInBackground(context, workflow.id)
                            },
                            onToggleExecution = { viewModel.toggleWorkflowExecution(workflow.id) },
                            onEdit = { /* Edit mode is handled within the card */ },
                            onDelete = { viewModel.deleteWorkflow(workflow.id) },
                            onDuplicate = { viewModel.duplicateWorkflow(workflow.id) },
                            onReset = { viewModel.resetWorkflow(workflow.id) },
                            onSaveChanges = { updatedWorkflow ->
                                viewModel.updateWorkflow(updatedWorkflow)
                            },
                            onStartDeepLinkSetup = { workflowId ->
                                scope.launch {
                                    viewModel.startTelegramDeepLinkSetup(workflowId)
                                }
                            },
                            onDisconnectTelegram = { workflowId ->
                                scope.launch {
                                    viewModel.disconnectTelegram(workflowId)
                                }
                            }
                        )
                    }
                }
            }
            uiState.executionError?.let { error ->
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }
    }
// Create Workflow Dialog
    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Create New Workflow") },
            text = {
                Column {
                    OutlinedTextField(
                        value = workflowName,
                        onValueChange = { workflowName = it },
                        label = { Text("Workflow Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            focusedLabelColor = MaterialTheme.colorScheme.primary,
                            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = workflowDescription,
                        onValueChange = { workflowDescription = it },
                        label = { Text("Description (Optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            focusedLabelColor = MaterialTheme.colorScheme.primary,
                            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (workflowName.isNotBlank()) {
                            Log.d("CreateWorkflowScreen", "📝 Creating new workflow: $workflowName")
                            val workflowId = viewModel.createNewWorkflow(
                                name = workflowName,
                                description = workflowDescription
                            )
                            Log.d("CreateWorkflowScreen", "✅ Created workflow with ID: $workflowId")
                            viewModel.selectWorkflow(workflowId)
                            showCreateDialog = false
                        }
                    }
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showCreateDialog = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowCard(
    workflow: Workflow,
    isSelected: Boolean,
    executionProgress: Float,
    onSelect: () -> Unit,
    onExecute: () -> Unit,
    onToggleExecution: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onReset: () -> Unit,
    onSaveChanges: (Workflow) -> Unit,
    onStartDeepLinkSetup: (String) -> Unit,
    onDisconnectTelegram: (String) -> Unit
) {
    var isEditing by remember { mutableStateOf(false) }
    var editedName by remember { mutableStateOf(workflow.name) }
    var editedDescription by remember { mutableStateOf(workflow.description) }
// Initialize from workflow's own settings
    var editedDestinationEmail by remember { mutableStateOf(workflow.destinationEmail ?: "") }
    var editedDestinationChatId by remember { mutableStateOf(workflow.destinationChatId ?: "") }
    var editedDestinationType by remember { mutableStateOf(workflow.destinationType) }
    var editedSenderFilter by remember { mutableStateOf(workflow.senderFilter ?: "") }
    var editedSubjectFilter by remember { mutableStateOf(workflow.subjectFilter ?: "") }
    var expirationOption by remember { mutableStateOf(workflow.expirationOption) }
    var customExpirationDate by remember { mutableStateOf(workflow.customExpirationDate) }
    var showDatePicker by remember { mutableStateOf(false) }
    var intervalMinutes by remember { mutableStateOf(workflow.interval / (60 * 1000)) }
    var isRecurring by remember { mutableStateOf(workflow.interval > 0) }
    var intervalError by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()) }
// Initialize filter values when entering edit mode
    LaunchedEffect(isEditing) {
        if (isEditing) {
// Find the SEND_BATCH_SUMMARIES step
            val batchStep = workflow.steps.find { it.type == StepType.SEND_BATCH_SUMMARIES }
            editedSenderFilter = batchStep?.parameters?.get("senderFilter") as? String ?: workflow.senderFilter ?: ""
            editedSubjectFilter = batchStep?.parameters?.get("subjectFilter") as? String ?: workflow.subjectFilter ?: ""
// Find the FORWARD_EMAILS step for destination
            val forwardStep = workflow.steps.find { it.type == StepType.FORWARD_EMAILS }
            editedDestinationEmail = forwardStep?.parameters?.get("destination") as? String ?: workflow.destinationEmail ?: ""
            editedDestinationChatId = forwardStep?.parameters?.get("destination") as? String ?: workflow.destinationChatId ?: ""
            editedDestinationType = forwardStep?.parameters?.get("destinationType") as? String ?: workflow.destinationType
// Initialize interval
            intervalMinutes = workflow.interval / (60 * 1000)
            isRecurring = workflow.interval > 0
        }
    }
// Update interval and recurring state when interval changes
    LaunchedEffect(intervalMinutes) {
        isRecurring = intervalMinutes > 0
        intervalError = isRecurring && intervalMinutes <= 0
    }
// Function to auto-save the workflow
    val autoSaveWorkflow: () -> Unit = {
// Update the workflow steps with the new filter values
        val updatedSteps = workflow.steps.map { step ->
            when (step.type) {
                StepType.SEND_BATCH_SUMMARIES -> {
                    step.copy(
                        parameters = step.parameters.toMutableMap().apply {
                            put("senderFilter", editedSenderFilter)
                            put("subjectFilter", editedSubjectFilter)
                            put("destination", if (editedDestinationType == "deeplink") "deeplink" else editedDestinationEmail)
                            put("destinationType", editedDestinationType)
                        }
                    )
                }
                StepType.FORWARD_EMAILS -> {
                    step.copy(
                        parameters = step.parameters.toMutableMap().apply {
                            put("destination", if (editedDestinationType == "deeplink") "deeplink" else editedDestinationEmail)
                            put("destinationType", editedDestinationType)
                        }
                    )
                }
                else -> step
            }
        }
        val intervalMs = if (isRecurring) intervalMinutes * 60 * 1000 else 0
        val updatedWorkflow = workflow.copy(
            name = editedName,
            description = editedDescription,
            destinationEmail = editedDestinationEmail.takeIf { it.isNotBlank() },
            destinationChatId = editedDestinationChatId.takeIf { it.isNotBlank() },
            destinationType = editedDestinationType,
            senderFilter = editedSenderFilter.takeIf { it.isNotBlank() },
            subjectFilter = editedSubjectFilter.takeIf { it.isNotBlank() },
            expirationOption = expirationOption,
            customExpirationDate = customExpirationDate,
            steps = updatedSteps,
            interval = intervalMs,
            isContinuous = false, // Always set to false since we removed the option
            stepDelay = 0 // Always set to 0 since we removed the option
        )
        onSaveChanges(updatedWorkflow)
    }
// Auto-save when values change
    LaunchedEffect(editedName, editedDescription, editedDestinationEmail, editedDestinationType,
        editedSenderFilter, editedSubjectFilter, expirationOption, customExpirationDate,
        intervalMinutes, isRecurring) {
        if (isSelected) {
// Debounce auto-save to avoid too frequent saves
            delay(1000)
            autoSaveWorkflow()
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
        ),
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (!isEditing) {
                        onSelect()
                    }
                }
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (isEditing) {
                        OutlinedTextField(
                            value = editedName,
                            onValueChange = { editedName = it },
                            label = { Text("Workflow Name") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                                focusedLabelColor = MaterialTheme.colorScheme.primary,
                                unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = editedDescription,
                            onValueChange = { editedDescription = it },
                            label = { Text("Description") },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                                focusedLabelColor = MaterialTheme.colorScheme.primary,
                                unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    } else {
                        Text(
                            text = workflow.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (isSelected)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else
                                MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = workflow.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isSelected)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        WorkflowStatusChip(workflow.status)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${workflow.steps.size} steps",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isSelected)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
// Show active indicator if workflow is active
                        if (workflow.isActive) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Text(
                                    text = "ACTIVE",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
                }
                IconButton(onClick = { onDelete() }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete Workflow",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            if (isSelected) {
                Spacer(modifier = Modifier.height(16.dp))
// Destination type selection
                Text(
                    text = "Destination Type",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isSelected)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = editedDestinationType == "gmail",
                        onClick = {
                            editedDestinationType = "gmail"
                        },
                        label = { Text("Gmail") },
                        leadingIcon = if (editedDestinationType == "gmail") {
                            { Icon(Icons.Default.Email, contentDescription = null) }
                        } else null,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                    FilterChip(
                        selected = editedDestinationType == "deeplink",
                        onClick = {
                            editedDestinationType = "deeplink"
                        },
                        label = { Text("Telegram (Deep Link)") },
                        leadingIcon = if (editedDestinationType == "deeplink") {
                            { Icon(Icons.Default.Send, contentDescription = null) }
                        } else null,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
// Destination field based on type
                if (editedDestinationType == "gmail") {
                    OutlinedTextField(
                        value = editedDestinationEmail,
                        onValueChange = { editedDestinationEmail = it },
                        label = { Text("Destination Email") },
                        placeholder = { Text("Enter email address") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = {
                            Icon(Icons.Default.Email, contentDescription = null)
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            focusedLabelColor = MaterialTheme.colorScheme.primary,
                            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                } else if (editedDestinationType == "deeplink") {
// Deep Link setup card - use workflow's own connection status
                    val isDeepLinkSetup = workflow.telegramChatId != null
                    val deepLinkStatus = workflow.telegramConnectionStatus ?: "Not set up"
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isDeepLinkSetup)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isDeepLinkSetup) Icons.Default.Link else Icons.Default.LinkOff,
                                    contentDescription = null,
                                    tint = if (isDeepLinkSetup)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column {
                                    Text(
                                        text = "Telegram Deep Link",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = if (isDeepLinkSetup)
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        else
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = deepLinkStatus,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isDeepLinkSetup)
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        else
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            if (isDeepLinkSetup) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { onDisconnectTelegram(workflow.id) },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error
                                        )
                                    ) {
                                        Icon(Icons.Default.LinkOff, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Disconnect")
                                    }
                                    Button(
                                        onClick = { onStartDeepLinkSetup(workflow.id) },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.secondary
                                        )
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Reconnect")
                                    }
                                }
                            } else {
                                Button(
                                    onClick = { onStartDeepLinkSetup(workflow.id) },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.secondary
                                    )
                                ) {
                                    Icon(Icons.Default.Link, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Set Up Deep Link")
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
// Email filters section
                Text(
                    text = "Email Filters",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isSelected)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
// Sender filter
                OutlinedTextField(
                    value = editedSenderFilter,
                    onValueChange = { editedSenderFilter = it },
                    label = { Text("Sender Filter (Optional)") },
                    placeholder = { Text("Filter by sender email") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = {
                        Icon(Icons.Default.Person, contentDescription = null)
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                        unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
// Subject filter
                OutlinedTextField(
                    value = editedSubjectFilter,
                    onValueChange = { editedSubjectFilter = it },
                    label = { Text("Subject Filter (Optional)") },
                    placeholder = { Text("Filter by subject keywords") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = {
                        Icon(Icons.Default.Label, contentDescription = null)
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                        unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
                Spacer(modifier = Modifier.height(16.dp))
// Expiration settings
                Text(
                    text = "Workflow Expiration",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isSelected)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = expirationOption == ExpirationOption.UNTIL_DISABLED,
                            onClick = { expirationOption = ExpirationOption.UNTIL_DISABLED }
                        )
                        Text(
                            text = "Until I turn this off or delete it",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = expirationOption == ExpirationOption.ONE_MONTH,
                            onClick = { expirationOption = ExpirationOption.ONE_MONTH }
                        )
                        Text(
                            text = "For a month",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = expirationOption == ExpirationOption.FIXED_DATE,
                            onClick = {
                                expirationOption = ExpirationOption.FIXED_DATE
                                if (customExpirationDate == null) {
                                    customExpirationDate = Date()
                                }
                            }
                        )
                        Text(
                            text = "Until a specific date",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                    if (expirationOption == ExpirationOption.FIXED_DATE) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 32.dp, top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = customExpirationDate?.let { dateFormat.format(it) } ?: "Select date",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.clickable { showDatePicker = true }
                            )
                            if (showDatePicker) {
                                val datePickerState = rememberDatePickerState(
                                    initialSelectedDateMillis = customExpirationDate?.time
                                        ?: System.currentTimeMillis()
                                )
                                DatePickerDialog(
                                    onDismissRequest = { showDatePicker = false },
                                    confirmButton = {
                                        TextButton(
                                            onClick = {
                                                datePickerState.selectedDateMillis?.let { millis ->
                                                    customExpirationDate = Date(millis)
                                                }
                                                showDatePicker = false
                                            }
                                        ) {
                                            Text("OK")
                                        }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showDatePicker = false }) {
                                            Text("Cancel")
                                        }
                                    }
                                ) {
                                    DatePicker(state = datePickerState)
                                }
                            }
                        }
                    }
                }
// New section: Execution Settings
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Execution Settings",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isSelected)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isRecurring,
                            onCheckedChange = {
                                Log.d("CreateWorkflowScreen", "Checkbox changed: $it")
                                isRecurring = it
                                if (!it) intervalMinutes = 0
                                Log.d("CreateWorkflowScreen", "After change: isRecurring=$isRecurring, intervalMinutes=$intervalMinutes")
                            }
                        )
                        Text(
                            text = "Run on schedule",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                    if (isRecurring) {
                        OutlinedTextField(
                            value = intervalMinutes.toString(),
                            onValueChange = {
                                val newValue = it.toLongOrNull() ?: 0
                                intervalMinutes = if (newValue < 0) 0 else newValue
                                intervalError = it.isNotEmpty() && (newValue <= 0)
                                isRecurring = intervalMinutes > 0
                            },
                            label = { Text("Interval (minutes)") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            isError = intervalError,
                            supportingText = {
                                if (intervalError) {
                                    Text("Interval must be greater than 0")
                                }
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                LazyColumn(
                    modifier = Modifier.height(120.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(workflow.steps) { step -> WorkflowStepPreview(step) }
                }
                Spacer(modifier = Modifier.height(16.dp))
// Show auto-save info for new workflows
                if (workflow.status == WorkflowStatus.DRAFT) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "💡 Your settings are automatically saved as draft",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
// Check if destination is configured and show warning if not
                            val hasDestination = when (editedDestinationType) {
                                "deeplink" -> workflow.telegramChatId != null
                                else -> editedDestinationEmail.isNotBlank()
                            }
                            if (!hasDestination) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "⚠️ Configure destination (email or deeplink) for steps to work",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
// Debug: Log current workflow state
                            Log.d("CreateWorkflowScreen", "🔍 BUTTON CLICK DEBUG:")
                            Log.d("CreateWorkflowScreen", " Workflow ID: ${workflow.id}")
                            Log.d("CreateWorkflowScreen", " IsActive: ${workflow.isActive}")
                            Log.d("CreateWorkflowScreen", " Status: ${workflow.status}")
                            Log.d("CreateWorkflowScreen", " Interval: ${workflow.interval}")
                            Log.d("CreateWorkflowScreen", " IsScheduled: ${workflow.isScheduled}")
                            if (workflow.isActive) {
                                Log.d("CreateWorkflowScreen", "🛑 Stopping workflow")
                                onToggleExecution()
                            } else {
                                Log.d("CreateWorkflowScreen", "▶ Executing workflow")
// Auto-save workflow configuration before executing if it's a new workflow
                                if (workflow.status == WorkflowStatus.DRAFT) {
                                    Log.d("CreateWorkflowScreen", "💾 Auto-saving workflow configuration before execution")
// Check if destination is configured
                                    val destinationConfigured = when (editedDestinationType) {
                                        "deeplink" -> workflow.telegramChatId != null
                                        else -> editedDestinationEmail.isNotBlank()
                                    }
                                    if (!destinationConfigured) {
                                        Log.w("CreateWorkflowScreen", "⚠️ No destination configured - workflow steps may fail")
                                    }
                                    val updatedSteps = workflow.steps.map { step ->
                                        when (step.type) {
                                            StepType.SEND_BATCH_SUMMARIES -> {
                                                step.copy(
                                                    parameters = step.parameters.toMutableMap().apply {
                                                        put("senderFilter", editedSenderFilter)
                                                        put("subjectFilter", editedSubjectFilter)
                                                        put("destination", if (editedDestinationType == "deeplink") "deeplink" else editedDestinationEmail)
                                                        put("destinationType", editedDestinationType)
                                                    }
                                                )
                                            }
                                            StepType.FORWARD_EMAILS -> {
                                                step.copy(
                                                    parameters = step.parameters.toMutableMap().apply {
                                                        put("destination", if (editedDestinationType == "deeplink") "deeplink" else editedDestinationEmail)
                                                        put("destinationType", editedDestinationType)
                                                    }
                                                )
                                            }
                                            else -> step
                                        }
                                    }
                                    val intervalMs = if (isRecurring) intervalMinutes * 60 * 1000 else 0
                                    Log.d("CreateWorkflowScreen", "Auto-saving workflow: isRecurring=$isRecurring, intervalMinutes=$intervalMinutes, intervalMs=$intervalMs")
                                    val autoSavedWorkflow = workflow.copy(
                                        name = editedName,
                                        description = editedDescription,
                                        destinationEmail = editedDestinationEmail.takeIf { it.isNotBlank() },
                                        destinationChatId = editedDestinationChatId.takeIf { it.isNotBlank() },
                                        destinationType = editedDestinationType,
                                        senderFilter = editedSenderFilter.takeIf { it.isNotBlank() },
                                        subjectFilter = editedSubjectFilter.takeIf { it.isNotBlank() },
                                        expirationOption = expirationOption,
                                        customExpirationDate = customExpirationDate,
                                        steps = updatedSteps,
                                        interval = intervalMs,
                                        isContinuous = false, // Always set to false since we removed the option
                                        stepDelay = 0 // Always set to 0 since we removed the option
                                    )
                                    Log.d("CreateWorkflowScreen", "✅ Auto-saved workflow settings:")
                                    Log.d("CreateWorkflowScreen", " interval: ${autoSavedWorkflow.interval}ms")
// Save the workflow with current UI settings
                                    onSaveChanges(autoSavedWorkflow)
                                }
// Execute the workflow using background execution
                                onExecute()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = if (workflow.isActive) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (workflow.isActive) "Stop" else "Execute")
                    }
                    if (isEditing) {
                        OutlinedButton(
                            onClick = {
                                Log.d("CreateWorkflowScreen", "💾 SAVE BUTTON CLICKED")
                                Log.d("CreateWorkflowScreen", " isRecurring: $isRecurring")
                                Log.d("CreateWorkflowScreen", " intervalMinutes: $intervalMinutes")
// Update the workflow steps with the new filter values
                                val updatedSteps = workflow.steps.map { step ->
                                    when (step.type) {
                                        StepType.SEND_BATCH_SUMMARIES -> {
                                            step.copy(
                                                parameters = step.parameters.toMutableMap().apply {
                                                    put("senderFilter", editedSenderFilter)
                                                    put("subjectFilter", editedSubjectFilter)
                                                    put("destination", if (editedDestinationType == "deeplink") "deeplink" else editedDestinationEmail)
                                                    put("destinationType", editedDestinationType)
                                                }
                                            )
                                        }
                                        StepType.FORWARD_EMAILS -> {
                                            step.copy(
                                                parameters = step.parameters.toMutableMap().apply {
                                                    put("destination", if (editedDestinationType == "deeplink") "deeplink" else editedDestinationEmail)
                                                    put("destinationType", editedDestinationType)
                                                }
                                            )
                                        }
                                        else -> step
                                    }
                                }
                                val intervalMs = if (isRecurring) intervalMinutes * 60 * 1000 else 0
                                Log.d("CreateWorkflowScreen", "Saving workflow: isRecurring=$isRecurring, intervalMinutes=$intervalMinutes, intervalMs=$intervalMs")
                                val updatedWorkflow = workflow.copy(
                                    name = editedName,
                                    description = editedDescription,
                                    destinationEmail = editedDestinationEmail.takeIf { it.isNotBlank() },
                                    destinationChatId = editedDestinationChatId.takeIf { it.isNotBlank() },
                                    destinationType = editedDestinationType,
                                    senderFilter = editedSenderFilter.takeIf { it.isNotBlank() },
                                    subjectFilter = editedSubjectFilter.takeIf { it.isNotBlank() },
                                    expirationOption = expirationOption,
                                    customExpirationDate = customExpirationDate,
                                    steps = updatedSteps,
                                    interval = intervalMs,
                                    isContinuous = false, // Always set to false since we removed the option
                                    stepDelay = 0 // Always set to 0 since we removed the option
                                )
                                Log.d("CreateWorkflowScreen", "✅ Final workflow settings:")
                                Log.d("CreateWorkflowScreen", " interval: ${updatedWorkflow.interval}ms")
                                onSaveChanges(updatedWorkflow)
                                isEditing = false
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !intervalError && editedName.isNotBlank()
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Save")
                        }
                    } else {
                        OutlinedButton(
                            onClick = { isEditing = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Edit")
                        }
                    }
                    OutlinedButton(onClick = { onDuplicate() }, modifier = Modifier.weight(1f)) {
                        Text("Duplicate")
                    }
                }
                if (workflow.status != WorkflowStatus.DRAFT) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = { onReset() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Reset Workflow")
                    }
                }
// New section: Schedule status indicator
                if (workflow.isScheduled || workflow.isActive) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Schedule,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column {
                                    Text(
                                        text = when {
                                            workflow.status == WorkflowStatus.RUNNING -> "🔄 Currently Running"
                                            workflow.isActive -> "⏰ Scheduled Execution"
                                            else -> "Scheduled Execution"
                                        },
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Text(
                                        text = when {
                                            workflow.status == WorkflowStatus.RUNNING -> "Executing workflow steps..."
                                            workflow.nextExecutionTime != null -> {
                                                val nextTime = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault())
                                                    .format(Date(workflow.nextExecutionTime!!))
                                                "Next run: $nextTime"
                                            }
                                            else -> "Next run: Unknown"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
// Show execution progress if running
                            if (workflow.status == WorkflowStatus.RUNNING) {
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = executionProgress,
                                    modifier = Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Progress: ${(executionProgress * 100).toInt()}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
// Show interval information
                            if (workflow.interval > 0) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Interval: ${workflow.interval / 60000} minute(s)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable
fun WorkflowStepPreview(step: WorkflowStep) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (step.status) {
            StepStatus.PENDING -> Icon(Icons.Default.PlayArrow, contentDescription = null)
            StepStatus.IN_PROGRESS -> CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
            StepStatus.COMPLETED -> Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            StepStatus.ERROR -> Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = step.name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
@Composable
fun WorkflowStatusChip(status: WorkflowStatus) {
    val (text, color) = when (status) {
        WorkflowStatus.DRAFT -> "Draft" to MaterialTheme.colorScheme.outline
        WorkflowStatus.RUNNING -> "Running" to MaterialTheme.colorScheme.primary
        WorkflowStatus.COMPLETED -> "Completed" to MaterialTheme.colorScheme.primary
        WorkflowStatus.ERROR -> "Error" to MaterialTheme.colorScheme.error
        WorkflowStatus.SCHEDULED -> "Scheduled" to MaterialTheme.colorScheme.secondary
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.2f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = color
        )
    }
}