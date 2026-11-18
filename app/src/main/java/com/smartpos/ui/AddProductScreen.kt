package com.smartpos.ui

import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.smartpos.PosViewModel
import com.smartpos.Screen
import com.smartpos.data.Product
import com.smartpos.vision.BarcodeAnalyzer
import kotlinx.coroutines.launch
import java.io.File

/**
 * Add-product wizard:
 *   1. Take a picture  -> the on-device labeler proposes a label (= the colored column)
 *   2. Scan the barcode (camera, barcode gun, or type it)
 *   3. Name / price / stock -> Save
 * If opened from the sell scanner with an unknown barcode, step 2 is skipped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddProductScreen(vm: PosViewModel, prefillBarcode: String?) {
    val scope = rememberCoroutineScope()
    val labels by vm.labels.collectAsState()
    val currency by vm.settings.currency.collectAsState()

    var step by remember { mutableIntStateOf(0) }            // 0 photo, 1 barcode, 2 details
    var photoPath by remember { mutableStateOf<String?>(null) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var chosenLabel by remember { mutableStateOf("") }
    var analyzing by remember { mutableStateOf(false) }
    var barcode by remember { mutableStateOf(prefillBarcode ?: "") }
    var saved by remember { mutableStateOf(false) }
    var duplicate by remember { mutableStateOf<Product?>(null) }
    var scanLock by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }

    val exitTo: Screen = if (prefillBarcode != null) Screen.Scanner else Screen.Home

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1280, 960),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()
            )
            .build()
    }

    fun exit() {
        if (!saved) photoPath?.let { File(it).delete() }
        vm.screen.value = exitTo
    }

    fun onBarcodeRead(code: String) {
        if (scanLock) return
        scanLock = true
        scope.launch {
            val existing = vm.findByBarcode(code)
            if (existing != null) {
                duplicate = existing
            } else {
                barcode = code
                step = 2
            }
        }
    }

    // Allow the next scan every time the barcode step is (re-)entered.
    LaunchedEffect(step) { if (step == 1) scanLock = false }

    BackHandler {
        when (step) {
            0 -> exit()
            1 -> step = 0
            else -> step = if (prefillBarcode != null) 0 else 1
        }
    }

    val titles = listOf("1 · Take a picture", "2 · Scan the barcode", "3 · Details")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titles[step], fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { exit() }) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                    }
                }
            )
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (step) {
                0 -> PhotoStep(
                    imageCapture = imageCapture,
                    photoPath = photoPath,
                    suggestions = suggestions,
                    chosenLabel = chosenLabel,
                    analyzing = analyzing,
                    onPickLabel = { chosenLabel = it },
                    onCancel = { exit() },
                    onCaptured = { file ->
                        photoPath = file.absolutePath
                        analyzing = true
                        scope.launch {
                            val result = vm.suggestLabels(file)
                            suggestions = result
                            chosenLabel = result.firstOrNull() ?: "Other"
                            analyzing = false
                        }
                    },
                    onCaptureError = { vm.toast("Could not take the picture, try again") },
                    newFile = { vm.newImageFile() },
                    onRetake = {
                        photoPath?.let { File(it).delete() }
                        photoPath = null
                        suggestions = emptyList()
                        chosenLabel = ""
                    },
                    onNext = { step = if (prefillBarcode != null) 2 else 1 }
                )

                1 -> BarcodeStep(
                    photoPath = photoPath,
                    onCode = { onBarcodeRead(it) },
                    onManual = { showManual = true },
                    onBack = { step = 0 }
                )

                else -> key(barcode) {
                    ProductForm(
                        imagePath = photoPath,
                        initialBarcode = barcode,
                        initialName = "",
                        initialLabel = chosenLabel,
                        initialPrice = "",
                        initialStock = "1",
                        suggestions = suggestions,
                        labels = labels,
                        colorIndexFor = { vm.colorIndexFor(it) },
                        currency = currency,
                        saveText = "Save product",
                        onSave = { code, name, label, price, stock ->
                            vm.saveProduct(null, code, name, label, price, stock, photoPath) { ok ->
                                if (ok) {
                                    saved = true
                                    vm.toast("Saved \"${name.trim()}\"")
                                    vm.screen.value = exitTo
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    duplicate?.let { p ->
        AlertDialog(
            onDismissRequest = { duplicate = null; scanLock = false },
            title = { Text("Already registered") },
            text = { Text("This barcode belongs to \"${p.name}\" (${p.label}).") },
            confirmButton = {
                Button(onClick = {
                    duplicate = null
                    if (!saved) photoPath?.let { File(it).delete() }
                    vm.screen.value = Screen.EditProduct(p.id)
                }) { Text("Edit that product") }
            },
            dismissButton = {
                TextButton(onClick = { duplicate = null; scanLock = false }) { Text("Scan another") }
            }
        )
    }

    if (showManual) {
        var typed by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showManual = false },
            title = { Text("Type the barcode") },
            text = {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it.trim() },
                    label = { Text("Barcode number") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    enabled = typed.isNotEmpty(),
                    onClick = {
                        showManual = false
                        scanLock = false
                        onBarcodeRead(typed)
                    }
                ) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showManual = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun PhotoStep(
    imageCapture: ImageCapture,
    photoPath: String?,
    suggestions: List<String>,
    chosenLabel: String,
    analyzing: Boolean,
    onPickLabel: (String) -> Unit,
    onCancel: () -> Unit,
    onCaptured: (File) -> Unit,
    onCaptureError: () -> Unit,
    newFile: () -> File,
    onRetake: () -> Unit,
    onNext: () -> Unit
) {
    val context = LocalContext.current
    var capturing by remember { mutableStateOf(false) }

    if (photoPath == null) {
        RequireCamera(altLabel = "Cancel", onAlt = onCancel) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                CameraView(Modifier.fillMaxSize(), imageCapture = imageCapture)
                Text(
                    "Put the product in the frame, then tap the button",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(16.dp)
                        .background(Color(0x99000000), RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
                // Shutter button
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .safeDrawingPadding()
                        .padding(bottom = 24.dp)
                        .size(76.dp)
                        .border(4.dp, Color.White, CircleShape)
                        .padding(8.dp)
                        .background(if (capturing) Color.Gray else Color.White, CircleShape)
                        .clickable(enabled = !capturing) {
                            capturing = true
                            val file = newFile()
                            imageCapture.takePicture(
                                ImageCapture.OutputFileOptions.Builder(file).build(),
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                        capturing = false
                                        onCaptured(file)
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        capturing = false
                                        onCaptureError()
                                    }
                                }
                            )
                        }
                )
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            ProductImage(
                photoPath,
                Modifier.weight(1f).fillMaxWidth()
            )
            Card(Modifier.fillMaxWidth().padding(12.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (analyzing) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                            Text("Recognizing the product…")
                        }
                    } else {
                        Text(
                            "Automatic label (tap to change)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            suggestions.forEach { option ->
                                FilterChip(
                                    selected = chosenLabel.equals(option, ignoreCase = true),
                                    onClick = { onPickLabel(option) },
                                    label = { Text(option) }
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onRetake, modifier = Modifier.weight(1f)) { Text("Retake") }
                        Button(
                            onClick = onNext,
                            enabled = !analyzing,
                            modifier = Modifier.weight(1f)
                        ) { Text("Next: barcode") }
                    }
                }
            }
        }
    }
}

@Composable
private fun BarcodeStep(
    photoPath: String?,
    onCode: (String) -> Unit,
    onManual: () -> Unit,
    onBack: () -> Unit
) {
    var torch by remember { mutableStateOf(false) }
    val currentOnCode by androidx.compose.runtime.rememberUpdatedState(onCode)
    val analyzer = remember { BarcodeAnalyzer { code -> currentOnCode(code) } }

    RequireCamera(altLabel = "Type the barcode instead", onAlt = onManual) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            CameraView(Modifier.fillMaxSize(), analyzer = analyzer, torch = torch)
            Box(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.8f)
                    .height(180.dp)
                    .border(3.dp, Color.White, RoundedCornerShape(16.dp))
            )
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (photoPath != null) {
                    ProductImage(photoPath, Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)))
                }
                Text(
                    "Scan the barcode of this product",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier
                        .background(Color(0x99000000), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .safeDrawingPadding()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilledTonalButton(onClick = onBack) { Text("◀ Retake picture") }
                FilledTonalButton(onClick = { torch = !torch }) {
                    Text(if (torch) "🔦 On" else "🔦 Off")
                }
                FilledTonalButton(onClick = onManual) { Text("⌨ Type it") }
            }
        }
    }
}
