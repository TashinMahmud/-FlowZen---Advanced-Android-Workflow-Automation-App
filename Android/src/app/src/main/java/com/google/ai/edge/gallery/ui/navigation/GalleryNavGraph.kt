/*
 * Copyright 2025 Google LLC
 * ...
 */
package com.google.ai.edge.gallery.ui.navigation
import android.net.Uri
import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.EaseOutExpo
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.*
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import com.google.ai.edge.gallery.auth.AuthViewModel
import com.google.ai.edge.gallery.data.*
import com.google.ai.edge.gallery.ui.ViewModelProvider
import com.google.ai.edge.gallery.ui.home.HomeScreen
import com.google.ai.edge.gallery.ui.llmchat.*
import com.google.ai.edge.gallery.ui.llmsingleturn.LlmSingleTurnDestination
import com.google.ai.edge.gallery.ui.llmsingleturn.LlmSingleTurnScreen
import com.google.ai.edge.gallery.ui.maps.*
import com.google.ai.edge.gallery.ui.modelmanager.ModelManager
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.workflow.CreateWorkflowDestination
import com.google.ai.edge.gallery.ui.workflow.CreateWorkflowScreen
import com.google.ai.edge.gallery.ui.camflow.CamFlowDestination
import com.google.ai.edge.gallery.ui.camflow.CamFlowHistoryDestination
import com.google.ai.edge.gallery.ui.camflow.CamFlowHistoryScreen
import com.google.ai.edge.gallery.ui.camflow.CamFlowScreen
import com.google.ai.edge.gallery.ui.camflow.CamFlowViewModel
import com.google.ai.edge.gallery.ui.camflow.FaceIdDestination
import com.google.ai.edge.gallery.ui.camflow.FaceIdScreen
import com.google.ai.edge.gallery.ui.camflow.CamFlowDocumentDestination
import com.google.ai.edge.gallery.ui.camflow.CamFlowDocumentScreen
import com.google.ai.edge.gallery.ui.aiassistant.AiAssistantDestination
import com.google.ai.edge.gallery.ui.aiassistant.AiAssistantScreen
const val TAG = "AGGalleryNavGraph"
private const val ROUTE_PLACEHOLDER = "placeholder"
private const val ENTER_ANIMATION_DURATION_MS = 500
private const val ENTER_ANIMATION_DELAY_MS = 100
private const val EXIT_ANIMATION_DURATION_MS = 500
private val ENTER_ANIMATION_EASING = EaseOutExpo
private val EXIT_ANIMATION_EASING = EaseOutExpo
private fun enterTween(): FiniteAnimationSpec<IntOffset> = tween(
  ENTER_ANIMATION_DURATION_MS, easing = ENTER_ANIMATION_EASING, delayMillis = ENTER_ANIMATION_DELAY_MS
)
private fun exitTween(): FiniteAnimationSpec<IntOffset> = tween(EXIT_ANIMATION_DURATION_MS, easing = EXIT_ANIMATION_EASING)
private fun AnimatedContentTransitionScope<*>.slideEnter(): EnterTransition = slideIntoContainer(
  animationSpec = enterTween(), towards = AnimatedContentTransitionScope.SlideDirection.Left
)
private fun AnimatedContentTransitionScope<*>.slideExit(): ExitTransition = slideOutOfContainer(
  animationSpec = exitTween(), towards = AnimatedContentTransitionScope.SlideDirection.Right
)
// Function to clear XNNPACK cache
fun clearXnnpackCache(context: android.content.Context) {
  try {
    val cacheDir = context.cacheDir
    val cacheFiles = cacheDir.listFiles { file -> file.name.endsWith(".xnnpack_cache") }
    cacheFiles?.forEach { file ->
      if (file.deleteRecursively()) {
        Log.d(TAG, "Deleted XNNPACK cache: ${file.name}")
      }
    }
  } catch (e: Exception) {
    Log.e(TAG, "Failed to clear XNNPACK cache", e)
  }
}
// Parent graph holder for CamFlow so one VM is shared by its children
object CamFlowGraph {
  const val route = "camflow_graph"
  const val argModelName = "modelName"
  fun routeWithArg(modelName: String) = "$route/$modelName"
}
@Composable
fun GalleryNavHost(
  navController: NavHostController,
  modifier: Modifier = Modifier,
  modelManagerViewModel: ModelManagerViewModel = viewModel(factory = ViewModelProvider.Factory),
  authViewModel: AuthViewModel = viewModel(factory = ViewModelProvider.Factory),
  onDeepLink: (Uri) -> Unit = {}
) {
  val context = LocalContext.current
  LaunchedEffect(Unit) { clearXnnpackCache(context) }
  val lifecycleOwner = LocalLifecycleOwner.current
  var showModelManager by remember { mutableStateOf(false) }
  var pickedTask by remember { mutableStateOf<Task?>(null) }
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_START,
        Lifecycle.Event.ON_RESUME -> modelManagerViewModel.setAppInForeground(true)
        Lifecycle.Event.ON_STOP,
        Lifecycle.Event.ON_PAUSE -> modelManagerViewModel.setAppInForeground(false)
        else -> {}
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }
  // Deep links
  val intent = androidx.activity.compose.LocalActivity.current?.intent
  val data = intent?.data
  LaunchedEffect(data) {
    if (data != null) {
      Log.d(TAG, "Deep link received in NavHost: $data")
      when {
        data.toString().startsWith("com.google.ai.edge.gallery://model/") -> {
          val modelName = data.pathSegments.last()
          getModelByName(modelName)?.let { model ->
            navigateToTaskScreen(
              navController = navController,
              taskType = TaskType.LLM_CHAT,
              model = model,
            )
          }
        }
        data.toString().startsWith("com.google.ai.edge.gallery://geofence/share") -> {
          onDeepLink(data)
        }
      }
    }
  }
  // Keep HomeScreen as the app's entry; when CAMFLOW is picked, we route to History first (inside parent graph)
  HomeScreen(
    modelManagerViewModel = modelManagerViewModel,
    authViewModel = authViewModel,
    navigateToTaskScreen = { task ->
      // Special handling for AI Assistant to check if it has models
      if (task.type == TaskType.AI_ASSISTANT) {
        if (task.models.isEmpty()) {
          // Show a message or handle the case when AI Assistant has no models
          Log.w(TAG, "AI Assistant task has no models available")
          // You could show a toast or snackbar here
          return@HomeScreen
        }
      }
      if (task.type == TaskType.MAPS) {
        navigateToTaskScreen(navController, task.type)
      } else {
        pickedTask = task
        showModelManager = true
      }
    },
  )
  AnimatedVisibility(
    visible = showModelManager,
    enter = slideInHorizontally(initialOffsetX = { it }),
    exit = slideOutHorizontally(targetOffsetX = { it }),
  ) {
    pickedTask?.let { curPickedTask ->
      ModelManager(
        viewModel = modelManagerViewModel,
        task = curPickedTask,
        onModelClicked = { model ->
          clearXnnpackCache(context)
          navigateToTaskScreen(
            navController = navController,
            taskType = curPickedTask.type,
            model = model,
          )
        },
        navigateUp = { showModelManager = false },
      )
    }
  }
  NavHost(
    navController = navController,
    startDestination = ROUTE_PLACEHOLDER,
    enterTransition = { EnterTransition.None },
    exitTransition = { ExitTransition.None },
    modifier = modifier.zIndex(1f),
  ) {
    composable(route = ROUTE_PLACEHOLDER) { Text("") }
    composable(
      route = "${LlmChatDestination.route}/{modelName}",
      arguments = listOf(navArgument("modelName") { type = NavType.StringType }),
      enterTransition = { slideEnter() },
      exitTransition = { slideExit() },
    ) {
      getModelFromNavigationParam(it, TASK_LLM_CHAT)?.let { model ->
        modelManagerViewModel.selectModel(model)
        LlmChatScreen(
          modelManagerViewModel = modelManagerViewModel,
          navigateUp = { navController.navigateUp() },
        )
      }
    }
    composable(
      route = "${LlmSingleTurnDestination.route}/{modelName}",
      arguments = listOf(navArgument("modelName") { type = NavType.StringType }),
      enterTransition = { slideEnter() },
      exitTransition = { slideExit() },
    ) {
      getModelFromNavigationParam(it, TASK_LLM_PROMPT_LAB)?.let { model ->
        modelManagerViewModel.selectModel(model)
        LlmSingleTurnScreen(
          modelManagerViewModel = modelManagerViewModel,
          navigateUp = { navController.navigateUp() },
        )
      }
    }
    composable(
      route = "${LlmAskImageDestination.route}/{modelName}",
      arguments = listOf(navArgument("modelName") { type = NavType.StringType }),
      enterTransition = { slideEnter() },
      exitTransition = { slideExit() },
    ) {
      getModelFromNavigationParam(it, TASK_LLM_ASK_IMAGE)?.let { model ->
        modelManagerViewModel.selectModel(model)
        LlmAskImageScreen(
          modelManagerViewModel = modelManagerViewModel,
          navigateUp = { navController.navigateUp() },
        )
      }
    }
    composable(
      route = "${LlmAskAudioDestination.route}/{modelName}",
      arguments = listOf(navArgument("modelName") { type = NavType.StringType }),
      enterTransition = { slideEnter() },
      exitTransition = { slideExit() },
    ) {
      getModelFromNavigationParam(it, TASK_LLM_ASK_AUDIO)?.let { model ->
        modelManagerViewModel.selectModel(model)
        LlmAskAudioScreen(
          modelManagerViewModel = modelManagerViewModel,
          navigateUp = { navController.navigateUp() },
        )
      }
    }
    composable(
      route = "${CreateWorkflowDestination.route}/{modelName}",
      arguments = listOf(navArgument("modelName") { type = NavType.StringType }),
      enterTransition = { slideEnter() },
      exitTransition = { slideExit() },
    ) {
      getModelFromNavigationParam(it, TASK_CREATE_WORKFLOW)?.let { model ->
        modelManagerViewModel.selectModel(model)
        CreateWorkflowScreen(
          modelManagerViewModel = modelManagerViewModel,
          navigateUp = { navController.navigateUp() },
        )
      }
    }
    // ===================== CAMFLOW PARENT GRAPH (shared ViewModel scope) =====================
    navigation(
      startDestination = CamFlowHistoryDestination.route,
      route = "${CamFlowGraph.route}/{${CamFlowGraph.argModelName}}",
      arguments = listOf(navArgument(CamFlowGraph.argModelName) { type = NavType.StringType }),
      enterTransition = { slideEnter() },
      exitTransition = { slideExit() },
    ) {
      // History screen (uses shared VM from parent graph)
      composable(route = CamFlowHistoryDestination.route) { entry ->
        // Scope VM to the parent graph's back stack entry
        val parentEntry = remember(entry) {
          navController.getBackStackEntry("${CamFlowGraph.route}/{${CamFlowGraph.argModelName}}")
        }
        val contextLocal = LocalContext.current
        val camFlowViewModel: CamFlowViewModel = viewModel(
          parentEntry,
          factory = CamFlowViewModel.provideFactory(contextLocal, modelManagerViewModel)
        )
        // Get model name from parent graph arg and select it
        val modelName = parentEntry.arguments?.getString(CamFlowGraph.argModelName)
        modelName?.let { name ->
          getModelByName(name)?.let { model -> modelManagerViewModel.selectModel(model) }
        }
        CamFlowHistoryScreen(
          camFlowViewModel = camFlowViewModel,
          modelManagerViewModel = modelManagerViewModel,
          navigateUp = { navController.navigateUp() },
          navigateToCreate = { navController.navigate(CamFlowDestination.route) },
          navigateToFaceId = { navController.navigate(FaceIdDestination.route) },
          navigateToDocument = { navController.navigate(CamFlowDocumentDestination.route) }
        )
      }
      // Create/Analysis screen (uses the same shared VM)
      composable(route = CamFlowDestination.route) { entry ->
        val parentEntry = remember(entry) {
          navController.getBackStackEntry("${CamFlowGraph.route}/{${CamFlowGraph.argModelName}}")
        }
        val contextLocal = LocalContext.current
        val camFlowViewModel: CamFlowViewModel = viewModel(
          parentEntry,
          factory = CamFlowViewModel.provideFactory(contextLocal, modelManagerViewModel)
        )
        // (Optional) ensure model is still selected from parent arg
        val modelName = parentEntry.arguments?.getString(CamFlowGraph.argModelName)
        modelName?.let { name ->
          getModelByName(name)?.let { model -> modelManagerViewModel.selectModel(model) }
        }
        CamFlowScreen(
          camFlowViewModel = camFlowViewModel,
          modelManagerViewModel = modelManagerViewModel,
          navigateUp = { navController.navigateUp() }
        )
      }
      // Face ID screen (uses the same shared VM)
      composable(route = FaceIdDestination.route) { entry ->
        val parentEntry = remember(entry) {
          navController.getBackStackEntry("${CamFlowGraph.route}/{${CamFlowGraph.argModelName}}")
        }
        val contextLocal = LocalContext.current
        val camFlowViewModel: CamFlowViewModel = viewModel(
          parentEntry,
          factory = CamFlowViewModel.provideFactory(contextLocal, modelManagerViewModel)
        )
        // (Optional) ensure model is still selected from parent arg
        val modelName = parentEntry.arguments?.getString(CamFlowGraph.argModelName)
        modelName?.let { name ->
          getModelByName(name)?.let { model -> modelManagerViewModel.selectModel(model) }
        }
        FaceIdScreen(
          camFlowViewModel = camFlowViewModel,
          modelManagerViewModel = modelManagerViewModel,
          navigateUp = { navController.navigateUp() }
        )
      }
      // Document Detection screen (uses the same shared VM)
      composable(route = CamFlowDocumentDestination.route) { entry ->
        val parentEntry = remember(entry) {
          navController.getBackStackEntry("${CamFlowGraph.route}/{${CamFlowGraph.argModelName}}")
        }
        val contextLocal = LocalContext.current
        val camFlowViewModel: CamFlowViewModel = viewModel(
          parentEntry,
          factory = CamFlowViewModel.provideFactory(contextLocal, modelManagerViewModel)
        )
        // (Optional) ensure model is still selected from parent arg
        val modelName = parentEntry.arguments?.getString(CamFlowGraph.argModelName)
        modelName?.let { name ->
          getModelByName(name)?.let { model -> modelManagerViewModel.selectModel(model) }
        }
        CamFlowDocumentScreen(
          camFlowViewModel = camFlowViewModel,
          modelManagerViewModel = modelManagerViewModel,
          navigateUp = { navController.navigateUp() }
        )
      }
    }
    // =========================================================================================
    // AI Assistant
    composable(
      route = "${AiAssistantDestination.route}/{modelName}",
      arguments = listOf(navArgument("modelName") { type = NavType.StringType; defaultValue = "" }),
      enterTransition = { slideEnter() },
      exitTransition = { slideExit() },
    ) { backStackEntry ->
      val modelName = backStackEntry.arguments?.getString("modelName") ?: ""
      val model = if (modelName.isNotEmpty()) {
        getModelByName(modelName)
      } else {
        // If no model name provided, use the first available model for AI Assistant
        TASK_AI_ASSISTANT.models.firstOrNull()
      }
      model?.let {
        Log.d("GalleryNavGraph", "📱 AI Assistant: Selected model: ${it.name}")
        modelManagerViewModel.selectModel(it)
        AiAssistantScreen(
          modelManagerViewModel = modelManagerViewModel,
          navigateUp = { navController.navigateUp() },
          navigateToGeofencing = { navController.navigate("maps?lat=23.8103&lng=90.4125&radius=500") },
          navigateToCamFlow = { 
            // Navigate to CamFlow with the current model
            val modelName = it.name
            Log.d("GalleryNavGraph", "📷 Navigating to CamFlow with model: $modelName")
            navController.navigate(CamFlowGraph.routeWithArg(modelName))
          }
        )
      }
    }
    // =========================================================================================
    // Gmail Forwarder
    composable(
      route = "gmail_forwarder",
      enterTransition = { slideEnter() },
      exitTransition = { slideExit() },
    ) {
      // Placeholder for Gmail Forwarder screen
      // You would replace this with your actual Gmail Forwarder screen
      Text("Gmail Forwarder Screen - Coming Soon")
    }
    // =========================================================================================
    // Maps
    composable(
      route = "maps?lat={lat}&lng={lng}&radius={radius}&sharedData={sharedData}&autoActivate={autoActivate}",
      arguments = listOf(
        navArgument("lat") { type = NavType.FloatType; defaultValue = 23.8103f },
        navArgument("lng") { type = NavType.FloatType; defaultValue = 90.4125f },
        navArgument("radius") { type = NavType.FloatType; defaultValue = 500f },
        navArgument("sharedData") { type = NavType.StringType; nullable = true; defaultValue = null },
        navArgument("autoActivate") { type = NavType.BoolType; defaultValue = false }
      ),
      enterTransition = { slideEnter() },
      exitTransition = { slideExit() },
    ) { backStackEntry ->
      val lat = backStackEntry.arguments?.getFloat("lat")?.toDouble() ?: 23.8103
      val lng = backStackEntry.arguments?.getFloat("lng")?.toDouble() ?: 90.4125
      val radius = backStackEntry.arguments?.getFloat("radius") ?: 500f
      val sharedData = backStackEntry.arguments?.getString("sharedData")
      val autoActivate = backStackEntry.arguments?.getBoolean("autoActivate") ?: false
      Log.d(TAG, "Map screen opened with sharedData: $sharedData, autoActivate: $autoActivate")
      GeofenceMapScreen(
        navigateUp = { navController.navigateUp() },
        navigateToHistory = { navController.navigate(GeofenceHistoryDestination.route) },
        preSelectedLocation = if (lat != 23.8103 || lng != 90.4125) com.google.android.gms.maps.model.LatLng(lat, lng) else null,
        preSelectedRadius = radius,
        sharedGeofenceData = sharedData,
        autoActivate = autoActivate
      )
    }
    composable(
      route = GeofenceHistoryDestination.route,
      enterTransition = { slideEnter() },
      exitTransition = { slideExit() },
    ) {
      GeofenceHistoryScreen(
        navigateUp = { navController.navigateUp() },
        navigateToMap = { latLng, radius, sharedData, autoActivate ->
          val route = "maps?lat=${latLng.latitude}&lng=${latLng.longitude}&radius=$radius"
          val routeWithSharedData = if (sharedData != null) {
            "$route&sharedData=${Uri.encode(sharedData)}"
          } else route
          val finalRoute = if (autoActivate) {
            "$routeWithSharedData&autoActivate=true"
          } else routeWithSharedData
          navController.navigate(finalRoute)
        }
      )
    }
  }
}
// Route helper: send CAMFLOW into its parent graph so one VM is shared
fun navigateToTaskScreen(
  navController: NavHostController,
  taskType: TaskType,
  model: Model? = null,
) {
  val modelName = model?.name ?: ""
  when (taskType) {
    TaskType.LLM_CHAT -> navController.navigate("${LlmChatDestination.route}/$modelName")
    TaskType.LLM_ASK_IMAGE -> navController.navigate("${LlmAskImageDestination.route}/$modelName")
    TaskType.LLM_ASK_AUDIO -> navController.navigate("${LlmAskAudioDestination.route}/$modelName")
    TaskType.LLM_PROMPT_LAB -> navController.navigate("${LlmSingleTurnDestination.route}/$modelName")
    TaskType.CREATE_WORKFLOW -> navController.navigate("${CreateWorkflowDestination.route}/$modelName")
    TaskType.CAMFLOW -> navController.navigate(CamFlowGraph.routeWithArg(modelName)) // << parent graph
    TaskType.MAPS -> navController.navigate("maps?lat=23.8103&lng=90.4125&radius=500")
    TaskType.AI_ASSISTANT -> {
      // Always pass the model name for AI Assistant
      if (modelName.isNotEmpty()) {
        navController.navigate("${AiAssistantDestination.route}/$modelName")
      } else {
        // If no model is selected, use the first available model
        val firstModel = TASK_AI_ASSISTANT.models.firstOrNull()
        if (firstModel != null) {
          navController.navigate("${AiAssistantDestination.route}/${firstModel.name}")
        } else {
          // Fallback to route without model name if no models available
          navController.navigate("${AiAssistantDestination.route}/")
        }
      }
    }
    TaskType.GMAIL_FORWARD -> navController.navigate("gmail_forwarder") // Gmail Forwarder route
    TaskType.TEST_TASK_1, TaskType.TEST_TASK_2 -> {}
  }
}
fun getModelFromNavigationParam(entry: NavBackStackEntry, task: Task): Model? {
  val modelName = entry.arguments?.getString("modelName").orEmpty()
    .ifEmpty { task.models.firstOrNull()?.name ?: "" }
  return getModelByName(modelName)
}