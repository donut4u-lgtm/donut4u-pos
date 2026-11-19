package com.smartpos.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.smartpos.PosViewModel
import com.smartpos.data.Backup
import com.smartpos.util.formatDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Back up to the user's own cloud (via the share menu) and restore from a backup file. */
@Composable
fun BackupDialog(vm: PosViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var pendingText by remember { mutableStateOf<String?>(null) }
    var last by remember { mutableLongStateOf(Backup.lastBackup(context)) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    } catch (e: Exception) {
                        null
                    }
                }
                if (text.isNullOrBlank()) vm.toast("Could not read that file") else pendingText = text
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Backup and restore") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Backup saves all products, stock, labels and sales in one small file. " +
                        "Save it to Google Drive or email it to yourself. If you lose this phone, " +
                        "install the app on a new phone and restore from that file. " +
                        "Product photos are not included.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    if (last == 0L) "Last backup: never" else "Last backup: ${formatDateTime(last)}",
                    style = MaterialTheme.typography.labelLarge
                )
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                context.startActivity(Backup.shareIntent(context))
                                last = Backup.lastBackup(context)
                            } catch (e: Exception) {
                                vm.toast("Could not create the backup: ${e.message}")
                            }
                            busy = false
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Back up now") }
                OutlinedButton(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Restore from a backup file") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )

    pendingText?.let { text ->
        AlertDialog(
            onDismissRequest = { pendingText = null },
            title = { Text("Replace everything?") },
            text = {
                Text(
                    "All products and sales on this phone will be erased and replaced " +
                        "by the ones in the backup file."
                )
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        busy = true
                        val error = Backup.restore(context, text)
                        busy = false
                        pendingText = null
                        if (error == null) {
                            vm.clearCart()
                            vm.toast("Backup restored")
                            onDismiss()
                        } else {
                            vm.toast(error)
                        }
                    }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { pendingText = null }) { Text("Cancel") } }
        )
    }
}
