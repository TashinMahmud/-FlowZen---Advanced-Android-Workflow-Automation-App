package com.google.ai.edge.gallery.ui.maps

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompleteSessionToken
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.model.TypeFilter
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.PlacesClient
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.maps.android.compose.*
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class NotificationRecord(
    val timestamp: Long,
    val type: String, // "ENTER" or "EXIT"
    val message: String
)

object CreateMapDestination { val route = "maps" }
object GeofenceHistoryDestination { val route = "geofence_history" }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeofenceMapScreen(
    navigateUp: () -> Unit,
    navigateToHistory: () -> Unit = {},
    preSelectedLocation: LatLng? = null,
    preSelectedRadius: Float = 500f,
    sharedGeofenceData: String? = null,
    autoActivate: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gson = remember { Gson() }

    // Initialize Places API
    val placesClient = remember {
        if (!Places.isInitialized()) {
            try {
                Places.initialize(context, "AIzaSyDoCRItfgc8fXsR3HU2PZQP_Ype4aoDTxI")
                Log.d("GeofenceMap", "Places API initialized successfully")
            } catch (e: Exception) {
                Log.e("GeofenceMap", "Failed to initialize Places API", e)
                Toast.makeText(context, "Failed to initialize Places API", Toast.LENGTH_SHORT).show()
            }
        }
        Places.createClient(context)
    }

    // Search states
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<com.google.android.libraries.places.api.model.AutocompletePrediction>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var showSearchResults by remember { mutableStateOf(false) }
    var autocompleteToken by remember { mutableStateOf(AutocompleteSessionToken.newInstance()) }

    // ----- State -----
    var selectedGeoPoint by remember { mutableStateOf<LatLng?>(preSelectedLocation) }
    var selectedRadius by remember { mutableStateOf(preSelectedRadius) }
    var isGeofenceActive by remember { mutableStateOf(false) }

    // Email
    var emailAddress by remember { mutableStateOf("") }
    var emailConnected by remember { mutableStateOf(false) }

    // Telegram
    var telegramConnected by remember { mutableStateOf(false) }
    var chatId by remember { mutableStateOf<Long?>(null) }
    var tgStatus by remember { mutableStateOf("Not linked. Share invite, then tap START in Telegram.") }
    var tgConnecting by remember { mutableStateOf(false) }

    // Bot info
    val botToken = "8123513934:AAHybG4oY02mdAwcr8odWwjtD_X5eoOcpvA"
    val botUsername = "flow_aibot"

    // Geofence inputs
    var geofenceName by remember { mutableStateOf("") }
    var expirationOption by remember { mutableStateOf("Never") }
    var customExpirationHours by remember { mutableStateOf("24") }
    var showExpirationDialog by remember { mutableStateOf(false) }

    // Location clients
    var geofencingClient by remember { mutableStateOf<GeofencingClient?>(null) }
    var fusedLocationClient by remember { mutableStateOf<FusedLocationProviderClient?>(null) }

    // Camera
    val cameraPositionState = rememberCameraPositionState {
        position = preSelectedLocation?.let { CameraPosition.fromLatLngZoom(it, 15f) }
            ?: CameraPosition.fromLatLngZoom(LatLng(23.8103, 90.4125), 15f)
    }
    val markerState = rememberMarkerState()

    // ----- Preselected pin -----
    LaunchedEffect(preSelectedLocation) {
        preSelectedLocation?.let {
            markerState.position = it
            selectedGeoPoint = it
        }
    }

    // ----- Handle shared geofence (deep linked) -----
    LaunchedEffect(sharedGeofenceData) {
        sharedGeofenceData?.let { data ->
            try {
                val jsonBytes = Base64.decode(data, Base64.URL_SAFE or Base64.NO_WRAP)
                val jsonString = String(jsonBytes)
                val jsonObject = JSONObject(jsonString)
                geofenceName = jsonObject.getString("name")
                val latitude = jsonObject.getDouble("latitude")
                val longitude = jsonObject.getDouble("longitude")
                val radius = jsonObject.getDouble("radius").toFloat()
                val expirationTime = jsonObject.getLong("expirationTime")
                selectedGeoPoint = LatLng(latitude, longitude)
                selectedRadius = radius
                expirationOption = if (expirationTime == 0L) {
                    "Never"
                } else {
                    val diff = expirationTime - System.currentTimeMillis()
                    when {
                        diff <= TimeUnit.HOURS.toMillis(1) -> "1 Hour"
                        diff <= TimeUnit.HOURS.toMillis(6) -> "6 Hours"
                        diff <= TimeUnit.HOURS.toMillis(12) -> "12 Hours"
                        diff <= TimeUnit.DAYS.toMillis(1) -> "1 Day"
                        diff <= TimeUnit.DAYS.toMillis(7) -> "1 Week"
                        diff <= TimeUnit.DAYS.toMillis(30) -> "1 Month"
                        else -> {
                            customExpirationHours = TimeUnit.MILLISECONDS.toHours(diff).toString()
                            "Custom"
                        }
                    }
                }
                Toast.makeText(context, "Geofence data loaded", Toast.LENGTH_SHORT).show()
                if (autoActivate) {
                    scope.launch {
                        kotlinx.coroutines.delay(500)
                        activateGeofence(
                            context, scope, selectedGeoPoint, geofenceName, selectedRadius,
                            expirationOption, customExpirationHours, telegramConnected, emailConnected,
                            geofencingClient, fusedLocationClient
                        ) { isActive -> isGeofenceActive = isActive }
                    }
                }
            } catch (e: Exception) {
                Log.e("GeofenceMapScreen", "decode error: ${e.message}", e)
                Toast.makeText(context, "Error processing shared geofence", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ----- Permissions -----
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fine = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarse = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        val bg = permissions[Manifest.permission.ACCESS_BACKGROUND_LOCATION] ?: false
        when {
            fine && coarse && bg -> Toast.makeText(context, "✅ All location permissions granted", Toast.LENGTH_SHORT).show()
            fine && coarse -> Toast.makeText(context, "⚠️ Background location recommended for reliable geofencing", Toast.LENGTH_LONG).show()
            else -> Toast.makeText(context, "❌ Location permissions required", Toast.LENGTH_LONG).show()
        }
    }

    // ----- Init -----
    LaunchedEffect(Unit) {
        if (!hasLocationPermissions(context)) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                )
            )
        }

        // Load saved Telegram + email
        val tprefs = context.getSharedPreferences("TELEGRAM_PREFS", Context.MODE_PRIVATE)
        val savedChatId = tprefs.getLong("chat_id", -1)
        if (savedChatId != -1L) {
            chatId = savedChatId
            telegramConnected = true
            tgStatus = "Connected (chat_id: $savedChatId)"
        } else {
            // try a quick auto-resolve once on launch in case START already happened
            val resolved = GeofenceTelegramLinkManager.tryResolveChatId(context, botToken)
            if (resolved != null) {
                chatId = resolved
                telegramConnected = true
                tprefs.edit().putLong("chat_id", resolved).apply()
                tgStatus = "Connected (chat_id: $resolved)"
            } else {
                telegramConnected = false
                tgStatus = "Not linked. Share invite and tap START in Telegram."
            }
        }

        val savedEmail = tprefs.getString("email_address", "")
        if (!savedEmail.isNullOrBlank()) {
            emailAddress = savedEmail
            emailConnected = true
        }

        geofencingClient = LocationServices.getGeofencingClient(context)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    }

    LaunchedEffect(selectedGeoPoint) { selectedGeoPoint?.let { markerState.position = it } }
    val myLocationEnabled = rememberSafeMyLocationEnabled()

    // Search function - FIXED
    fun searchPlaces(query: String) {
        if (query.isBlank()) {
            searchResults = emptyList()
            showSearchResults = false
            return
        }

        isSearching = true
        showSearchResults = true
        scope.launch(Dispatchers.IO) {
            try {
                Log.d("GeofenceMap", "Searching for: $query")
                val request = FindAutocompletePredictionsRequest.builder()
                    .setSessionToken(autocompleteToken)
                    .setQuery(query)
                    .setTypeFilter(TypeFilter.ESTABLISHMENT)
                    .build()

                val response = placesClient.findAutocompletePredictions(request).await()
                searchResults = response.autocompletePredictions
                Log.d("GeofenceMap", "Search results count: ${searchResults.size}")

                withContext(Dispatchers.Main) {
                    if (searchResults.isEmpty()) {
                        Toast.makeText(context, "No results found", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("GeofenceMap", "searchPlaces error: ${e.message}", e)
                searchResults = emptyList()
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Search error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                isSearching = false
            }
        }
    }

    // Place selection function - FIXED
    fun onPlaceSelected(placeId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                Log.d("GeofenceMap", "Fetching place details for: $placeId")
                val placeFields = listOf(Place.Field.ID, Place.Field.NAME, Place.Field.LAT_LNG, Place.Field.ADDRESS)
                val request = FetchPlaceRequest.newInstance(placeId, placeFields)

                val response = placesClient.fetchPlace(request).await()
                val place = response.place
                Log.d("GeofenceMap", "Place fetched: ${place.name}, ${place.latLng}")

                place.latLng?.let { latLng ->
                    withContext(Dispatchers.Main) {
                        selectedGeoPoint = latLng
                        cameraPositionState.position = CameraPosition.fromLatLngZoom(latLng, 15f)
                        markerState.position = latLng
                        showSearchResults = false
                        searchQuery = place.name ?: ""
                        Toast.makeText(context, "📍 Selected: ${place.name}", Toast.LENGTH_SHORT).show()
                    }
                }

                // Reset the token for a new session
                autocompleteToken = AutocompleteSessionToken.newInstance()
            } catch (e: Exception) {
                Log.e("GeofenceMap", "onPlaceSelected error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "❌ Error selecting place: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ----- UI -----
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("📍 Geofence Map") },
                    navigationIcon = {
                        IconButton(onClick = navigateUp) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                    },
                    actions = {
                        IconButton(onClick = navigateToHistory) { Icon(Icons.Default.History, contentDescription = "History") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )

                // Search Bar - FIXED
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shadowElevation = 2.dp
                ) {
                    Column {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = {
                                searchQuery = it
                                searchPlaces(it)
                            },
                            label = { Text("Search for a location") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = {
                                        searchQuery = ""
                                        searchResults = emptyList()
                                        showSearchResults = false
                                    }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear search")
                                    }
                                }
                            }
                        )

                        // Search Results Dropdown - FIXED
                        if (showSearchResults) {
                            if (isSearching) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp)
                                        .height(56.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator()
                                }
                            } else if (searchResults.isEmpty()) {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp)
                                        .height(56.dp),
                                    elevation = CardDefaults.cardElevation(4.dp)
                                ) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("No results found", style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            } else {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp)
                                        .heightIn(max = 200.dp),
                                    elevation = CardDefaults.cardElevation(4.dp)
                                ) {
                                    LazyColumn {
                                        items(
                                            count = searchResults.size,
                                            key = { index -> searchResults[index].placeId }
                                        ) { index ->
                                            val prediction = searchResults[index]
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        Log.d("GeofenceMap", "Clicked on: ${prediction.getPrimaryText(null)}")
                                                        onPlaceSelected(prediction.placeId)
                                                    }
                                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.LocationOn,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Column {
                                                    Text(
                                                        text = prediction.getPrimaryText(null).toString(),
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        text = prediction.getSecondaryText(null).toString(),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
                .verticalScroll(rememberScrollState())
        ) {
            // Map
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(400.dp)
                    .padding(16.dp),
                elevation = CardDefaults.cardElevation(4.dp)
            ) {
                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = cameraPositionState,
                    properties = MapProperties(
                        isMyLocationEnabled = myLocationEnabled,
                        mapType = MapType.NORMAL
                    ),
                    uiSettings = MapUiSettings(zoomControlsEnabled = true, myLocationButtonEnabled = true),
                    onMapClick = { latLng ->
                        selectedGeoPoint = latLng
                        Toast.makeText(context, "📍 Location selected", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    selectedGeoPoint?.let {
                        Marker(state = markerState, title = "Selected Area", snippet = "Radius: ${selectedRadius.toInt()}m")
                        Circle(
                            center = it,
                            radius = selectedRadius.toDouble(),
                            strokeColor = androidx.compose.ui.graphics.Color.Blue,
                            strokeWidth = 4f,
                            fillColor = androidx.compose.ui.graphics.Color.Blue.copy(alpha = 0.3f)
                        )
                    }
                }
            }

            // Controls
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Geofence Controls", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 16.dp))

                    // Name
                    OutlinedTextField(
                        value = geofenceName,
                        onValueChange = { geofenceName = it },
                        label = { Text("Geofence Name") },
                        placeholder = { Text("Enter a name for this geofence") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        singleLine = true
                    )

                    // Radius
                    Text("Radius: ${selectedRadius.toInt()} meters", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
                    Slider(
                        value = selectedRadius,
                        onValueChange = { selectedRadius = it },
                        valueRange = 100f..1000f,
                        steps = 18,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    // Expiration
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Expiration:", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { showExpirationDialog = true }) { Text(expirationOption) }
                    }

                    // Email status + Save
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (emailConnected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                            contentDescription = "Email Status",
                            tint = if (emailConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (emailConnected) "✅ Email Connected" else "⚠️ Connect Email", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.weight(1f))
                        Button(
                            onClick = {
                                if (emailAddress.isNotBlank()) {
                                    val prefs = context.getSharedPreferences("TELEGRAM_PREFS", Context.MODE_PRIVATE)
                                    prefs.edit().putString("email_address", emailAddress).apply()
                                    emailConnected = true
                                    Toast.makeText(context, "✅ Email saved!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "❌ Enter a valid email", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (emailConnected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(Icons.Default.Email, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(if (emailConnected) "Update" else "Save")
                        }
                    }

                    // Email input (+ clear that also wipes prefs)
                    OutlinedTextField(
                        value = emailAddress,
                        onValueChange = {
                            emailAddress = it
                            if (emailConnected) emailConnected = false
                        },
                        label = { Text("Email Address") },
                        placeholder = { Text("Enter your email address") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        singleLine = true,
                        trailingIcon = {
                            if (emailAddress.isNotBlank()) {
                                IconButton(onClick = {
                                    emailAddress = ""
                                    emailConnected = false
                                    // ALSO clear saved email so background code can't use it
                                    val prefs = context.getSharedPreferences("TELEGRAM_PREFS", Context.MODE_PRIVATE)
                                    prefs.edit().remove("email_address").apply()
                                    Toast.makeText(context, "Email cleared", Toast.LENGTH_SHORT).show()
                                }) { Icon(Icons.Default.Clear, contentDescription = "Clear email") }
                            }
                        }
                    )

                    // Disconnect Email button (explicit)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(
                            enabled = emailConnected || emailAddress.isNotBlank(),
                            onClick = {
                                val prefs = context.getSharedPreferences("TELEGRAM_PREFS", Context.MODE_PRIVATE)
                                prefs.edit().remove("email_address").apply()
                                emailAddress = ""
                                emailConnected = false
                                Toast.makeText(context, "Disconnected from Email", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (emailConnected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onError
                            )
                        ) {
                            Text("Disconnect Email")
                        }
                    }

                    // Telegram row (status chip)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (telegramConnected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                            contentDescription = "Telegram Status",
                            tint = if (telegramConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (telegramConnected) "✅ Telegram Connected" else "⚠️ Connect Telegram", style = MaterialTheme.typography.bodyMedium)
                    }

                    // Telegram deep-link UI (no manual button, no visible timer)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(text = tgStatus, style = MaterialTheme.typography.bodyMedium)
                        if (tgConnecting) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                enabled = !tgConnecting,
                                onClick = {
                                    val link = GeofenceTelegramLinkManager.createShareLink(context, botUsername)
                                    TelegramDeepLinkHelperGeo.shareText(
                                        context,
                                        "Tap to connect with my app: $link"
                                    )
                                    // Background resolve; update IMMEDIATELY when found
                                    scope.launch {
                                        tgConnecting = true
                                        tgStatus = "Invite shared. Waiting for START in Telegram…"
                                        try {
                                            var resolved: Long? = null
                                            for (i in 0 until 60) {
                                                resolved = GeofenceTelegramLinkManager.tryResolveChatId(context, botToken)
                                                if (resolved != null) {
                                                    chatId = resolved
                                                    telegramConnected = true
                                                    context.getSharedPreferences("TELEGRAM_PREFS", Context.MODE_PRIVATE)
                                                        .edit().putLong("chat_id", resolved).apply()
                                                    tgStatus = "Connected (chat_id: $resolved)"
                                                    Toast.makeText(context, "✅ Telegram connected (ID: $resolved)", Toast.LENGTH_LONG).show()
                                                    tgConnecting = false
                                                    return@launch
                                                }
                                                kotlinx.coroutines.delay(2000)
                                            }
                                            tgStatus = "Not linked yet. Open Telegram and tap START, then share invite again."
                                        } finally {
                                            tgConnecting = false
                                        }
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Share Telegram Invite")
                            }
                            OutlinedButton(
                                enabled = (chatId != null) || telegramConnected,
                                onClick = {
                                    val prefs = context.getSharedPreferences("TELEGRAM_PREFS", Context.MODE_PRIVATE)
                                    prefs.edit().remove("chat_id").apply()
                                    chatId = null
                                    telegramConnected = false
                                    tgStatus = "Disconnected. Share invite to link again."
                                    Toast.makeText(context, "Disconnected from Telegram", Toast.LENGTH_SHORT).show()
                                }
                            ) { Text("Disconnect") }
                        }
                    }

                    // Permission status
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val hasPermission = hasLocationPermissions(context)
                        Icon(
                            imageVector = if (hasPermission) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                            contentDescription = "Permission Status",
                            tint = if (hasPermission) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (hasPermission) "✅ Location Permissions Granted" else "❌ Location Permissions Required")
                        Spacer(Modifier.weight(1f))
                        if (!hasPermission) {
                            Button(onClick = {
                                permissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION,
                                        Manifest.permission.ACCESS_BACKGROUND_LOCATION
                                    )
                                )
                            }) { Text("Grant") }
                        }
                    }

                    // Test Telegram
                    Button(
                        onClick = {
                            scope.launch {
                                val prefs = context.getSharedPreferences("TELEGRAM_PREFS", Context.MODE_PRIVATE)
                                val cid = prefs.getLong("chat_id", -1)
                                if (cid != -1L) {
                                    val ok = TelegramSender.sendMessage(cid, "🧪 Test message from FlowZen")
                                    Toast.makeText(context, if (ok) "✅ Test message sent!" else "❌ Failed to send", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(context, "❌ Not linked. Share invite first.", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    ) {
                        Icon(Icons.Default.Send, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Test Telegram Connection")
                    }

                    // Test Email (only when connected)
                    if (emailConnected) {
                        Button(
                            onClick = {
                                scope.launch {
                                    val prefs = context.getSharedPreferences("TELEGRAM_PREFS", Context.MODE_PRIVATE)
                                    val to = prefs.getString("email_address", null)
                                    if (!to.isNullOrBlank()) {
                                        val ok = EmailSender.sendTestEmail(to, context)
                                        Toast.makeText(context, if (ok) "✅ Test email sent!" else "❌ Failed to send email", Toast.LENGTH_LONG).show()
                                    } else {
                                        Toast.makeText(context, "❌ Save your email first", Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        ) {
                            Icon(Icons.Default.Email, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Test Email Connection")
                        }
                    }

                    // Activate
                    Button(
                        onClick = {
                            activateGeofence(
                                context, scope, selectedGeoPoint, geofenceName, selectedRadius,
                                expirationOption, customExpirationHours, telegramConnected, emailConnected,
                                geofencingClient, fusedLocationClient
                            ) { isActive -> isGeofenceActive = isActive }
                        },
                        enabled = selectedGeoPoint != null &&
                                geofenceName.isNotBlank() &&
                                (telegramConnected || emailConnected) &&
                                hasLocationPermissions(context),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isGeofenceActive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.LocationOn, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (isGeofenceActive) "Geofence Active" else "Activate Geofence")
                        }
                    }

                    // Debug
                    if (isGeofenceActive) {
                        Spacer(Modifier.height(16.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Debug Information", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = "Geofence is active at:\n" +
                                            "Name: $geofenceName\n" +
                                            "Lat: ${selectedGeoPoint?.latitude}\n" +
                                            "Lng: ${selectedGeoPoint?.longitude}\n" +
                                            "Radius: ${selectedRadius.toInt()}m\n" +
                                            "Expiration: $expirationOption\n\n" +
                                            "Notifications will be sent to:\n" +
                                            (if (telegramConnected) "✅ Telegram (chat_id: ${chatId ?: "unknown"})\n" else "") +
                                            (if (emailConnected) "✅ Email: $emailAddress\n" else "") +
                                            "\nTo test the geofence:\n" +
                                            "1. Move outside the geofence area\n" +
                                            "2. Then move back into the area\n" +
                                            "3. Wait 1–2 minutes for detection",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Expiration dialog
    if (showExpirationDialog) {
        AlertDialog(
            onDismissRequest = { showExpirationDialog = false },
            title = { Text("Set Expiration Time") },
            text = {
                Column {
                    val options = listOf("Never", "1 Hour", "6 Hours", "12 Hours", "1 Day", "1 Week", "1 Month", "Custom")
                    options.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = expirationOption == option,
                                onClick = {
                                    expirationOption = option
                                    if (option != "Custom") showExpirationDialog = false
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(option)
                        }
                    }
                    if (expirationOption == "Custom") {
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = customExpirationHours,
                            onValueChange = { customExpirationHours = it },
                            label = { Text("Hours") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("Enter number of hours until expiration", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = {
                val ctx = LocalContext.current
                Button(onClick = {
                    if (expirationOption != "Custom" || customExpirationHours.isNotBlank()) {
                        showExpirationDialog = false
                    } else {
                        Toast.makeText(ctx, "Please enter a valid number of hours", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showExpirationDialog = false }) { Text("Cancel") } }
        )
    }
}

/** Safely compute whether the map may enable my-location layer without throwing. */
@Composable
private fun rememberSafeMyLocationEnabled(): Boolean {
    val context = LocalContext.current
    val hasPerm = hasLocationPermissions(context)
    return try { hasPerm } catch (_: SecurityException) { false }
}

// ===== Logic below (history, geofence, location) =====
fun activateGeofence(
    context: Context,
    scope: CoroutineScope,
    selectedGeoPoint: LatLng?,
    geofenceName: String,
    selectedRadius: Float,
    expirationOption: String,
    customExpirationHours: String,
    telegramConnected: Boolean,
    emailConnected: Boolean,
    geofencingClient: GeofencingClient?,
    fusedLocationClient: FusedLocationProviderClient?,
    onGeofenceActiveChanged: (Boolean) -> Unit
) {
    if (selectedGeoPoint == null) { Toast.makeText(context, "📍 Select a location first!", Toast.LENGTH_SHORT).show(); return }
    if (geofenceName.isBlank()) { Toast.makeText(context, "📝 Enter a name", Toast.LENGTH_SHORT).show(); return }
    if (!telegramConnected && !emailConnected) { Toast.makeText(context, "⚠️ Connect Telegram or Email first!", Toast.LENGTH_SHORT).show(); return }
    if (!hasLocationPermissions(context)) { Toast.makeText(context, "❌ Location permissions required", Toast.LENGTH_SHORT).show(); return }

    scope.launch {
        try {
            val prefs = context.getSharedPreferences("TELEGRAM_PREFS", Context.MODE_PRIVATE)
            val chatId = prefs.getLong("chat_id", -1)
            val expirationTime = when (expirationOption) {
                "1 Hour" -> System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1)
                "6 Hours" -> System.currentTimeMillis() + TimeUnit.HOURS.toMillis(6)
                "12 Hours" -> System.currentTimeMillis() + TimeUnit.HOURS.toMillis(12)
                "1 Day" -> System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1)
                "1 Week" -> System.currentTimeMillis() + TimeUnit.DAYS.toMillis(7)
                "1 Month" -> System.currentTimeMillis() + TimeUnit.DAYS.toMillis(30)
                "Custom" -> System.currentTimeMillis() + TimeUnit.HOURS.toMillis((customExpirationHours.toIntOrNull() ?: 24).toLong())
                else -> 0L
            }

            saveGeofenceToHistory(context, geofenceName, selectedGeoPoint, selectedRadius, expirationTime, Gson())
            addGeofence(geofenceName, selectedGeoPoint, selectedRadius, expirationTime, context, geofencingClient, fusedLocationClient, chatId, scope)
            onGeofenceActiveChanged(true)
            Toast.makeText(context, "✅ Geofence activated!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("GeofenceMap", "activate error: ${e.message}", e)
            Toast.makeText(context, "❌ Error activating geofence: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}

private fun hasLocationPermissions(context: Context): Boolean {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
    val bg = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    return fine == PackageManager.PERMISSION_GRANTED &&
            coarse == PackageManager.PERMISSION_GRANTED &&
            bg == PackageManager.PERMISSION_GRANTED
}

private fun saveGeofenceToHistory(
    context: Context,
    name: String,
    point: LatLng,
    radius: Float,
    expirationTime: Long,
    gson: Gson
) {
    try {
        val prefs = context.getSharedPreferences("GEOFENCE_PREFS", Context.MODE_PRIVATE)
        val historyJson = prefs.getString("geofence_history", "[]")
        val type = object : TypeToken<List<GeofenceHistoryItem>>() {}.type
        val historyList = gson.fromJson<List<GeofenceHistoryItem>>(historyJson, type).toMutableList()

        val newGeofence = GeofenceHistoryItem(
            id = "geofence_${System.currentTimeMillis()}",
            name = name,
            latitude = point.latitude,
            longitude = point.longitude,
            radius = radius,
            createdAt = System.currentTimeMillis(),
            expirationTime = expirationTime,
            isActive = true,
            notifications = emptyList()
        )

        historyList.add(newGeofence)
        prefs.edit().putString("geofence_history", gson.toJson(historyList)).apply()
        Log.d("GeofenceMap", "history saved")
    } catch (e: Exception) {
        Log.e("GeofenceMap", "save history error: ${e.message}", e)
    }
}

private suspend fun addGeofence(
    name: String,
    point: LatLng,
    radius: Float,
    expirationTime: Long,
    context: Context,
    geofencingClient: GeofencingClient?,
    fusedLocationClient: FusedLocationProviderClient?,
    chatId: Long,
    scope: CoroutineScope
) {
    try {
        if (!hasLocationPermissions(context)) { Toast.makeText(context, "❌ Location permissions required", Toast.LENGTH_SHORT).show(); return }
        if (geofencingClient == null || fusedLocationClient == null) { Toast.makeText(context, "❌ Location services not available", Toast.LENGTH_SHORT).show(); return }

        val requestId = "User_Area_${System.currentTimeMillis()}"
        val duration = if (expirationTime > 0) {
            val left = expirationTime - System.currentTimeMillis()
            if (left > 0) left else Geofence.NEVER_EXPIRE
        } else Geofence.NEVER_EXPIRE

        val geofence = Geofence.Builder()
            .setRequestId(requestId)
            .setCircularRegion(point.latitude, point.longitude, radius)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
            .setExpirationDuration(duration)
            .setNotificationResponsiveness(10_000)
            .build()

        val geofencingRequest = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_DWELL)
            .addGeofence(geofence)
            .build()

        val intent = Intent(context, GeofenceBroadcastReceiver::class.java).apply {
            putExtra("geofence_request_id", requestId)
            putExtra("chat_id", chatId)
            putExtra("geofence_latitude", point.latitude)
            putExtra("geofence_longitude", point.longitude)
            putExtra("geofence_radius", radius)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
        )

        geofencingClient.addGeofences(geofencingRequest, pendingIntent)
            .addOnSuccessListener {
                Toast.makeText(context, "🎉 Geofence added!", Toast.LENGTH_LONG).show()
                val prefs = context.getSharedPreferences("GEOFENCE_PREFS", Context.MODE_PRIVATE)
                prefs.edit()
                    .putString("request_id", requestId)
                    .putString("geofence_name", name)
                    .putFloat("latitude", point.latitude.toFloat())
                    .putFloat("longitude", point.longitude.toFloat())
                    .putFloat("radius", radius)
                    .putLong("expiration_time", expirationTime)
                    .apply()

                scope.launch {
                    checkIfUserIsInsideGeofence(context, fusedLocationClient, point, radius, requestId, chatId)
                }
            }
            .addOnFailureListener { e ->
                Log.e("GeofenceMap", "addGeofences failed: ${e.message}", e)
                Toast.makeText(context, "❌ Failed to add geofence: ${e.message}", Toast.LENGTH_LONG).show()
            }
    } catch (e: SecurityException) {
        Log.e("GeofenceMap", "SecurityException: ${e.message}", e)
        Toast.makeText(context, "❌ Security error: permission denied", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Log.e("GeofenceMap", "setup error: ${e.message}", e)
        Toast.makeText(context, "❌ Error setting up geofence: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

private suspend fun checkIfUserIsInsideGeofence(
    context: Context,
    fusedLocationClient: FusedLocationProviderClient?,
    geofenceCenter: LatLng,
    radius: Float,
    requestId: String,
    chatId: Long
) {
    try {
        val location = safeLastLocation(context, fusedLocationClient)
        if (location != null) {
            val distance = FloatArray(1)
            android.location.Location.distanceBetween(
                geofenceCenter.latitude, geofenceCenter.longitude,
                location.latitude, location.longitude,
                distance
            )

            if (distance[0] <= radius) {
                val prefs = context.getSharedPreferences("GEOFENCE_PREFS", Context.MODE_PRIVATE)
                prefs.edit().putLong("last_notification_time", System.currentTimeMillis()).apply()

                val intent = Intent(context, GeofenceBroadcastReceiver::class.java).apply {
                    action = "com.google.ai.edge.gallery.ui.maps.MANUAL_GEOFENCE_TRIGGER"
                    putExtra("geofence_request_id", requestId)
                    putExtra("chat_id", chatId)
                    putExtra("geofence_latitude", geofenceCenter.latitude)
                    putExtra("geofence_longitude", geofenceCenter.longitude)
                    putExtra("geofence_radius", radius)
                    putExtra("manual_trigger", true)
                }

                context.sendBroadcast(intent)
                Toast.makeText(context, "📍 You're already inside the geofence area!", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(context, "⚠️ Could not determine your current location", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        Log.e("GeofenceMap", "inside check error: ${e.message}", e)
    }
}

/** Safe wrapper for last known location to avoid SecurityException / missing permission crashes. */
private suspend fun safeLastLocation(
    context: Context,
    fused: FusedLocationProviderClient?
): android.location.Location? {
    if (fused == null) return null
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        != PackageManager.PERMISSION_GRANTED
    ) return null

    return try {
        fused.lastLocation.await()
    } catch (se: SecurityException) {
        Log.w("GeofenceMap", "Location permission revoked mid-call", se)
        null
    } catch (e: Exception) {
        Log.e("GeofenceMap", "lastLocation error: ${e.message}", e)
        null
    }
}

// Extension function for Tasks
suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T {
    return suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result ->
            continuation.resume(result)
        }
        addOnFailureListener { exception ->
            continuation.resumeWithException(exception)
        }
        addOnCanceledListener {
            continuation.cancel()
        }
    }
}