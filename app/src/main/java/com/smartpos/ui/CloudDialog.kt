package com.smartpos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.smartpos.PosViewModel
import com.smartpos.cloud.Cloud
import kotlinx.coroutines.launch

/** Optional online mode: connect to your own Firebase, sign in, and pick selling or monitoring. */
@Composable
fun CloudDialog(vm: PosViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by Cloud.state.collectAsState()

    var setup by remember { mutableStateOf("") }
    var changing by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmRestore by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Online (optional)") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Without this, the app works only on this phone. Online mode uses your own " +
                        "Firebase project: the selling phone uploads sales and stock, and a second " +
                        "phone can watch the shop live from anywhere.",
                    style = MaterialTheme.typography.bodyMedium
                )

                if (!st.configured || changing) {
                    OutlinedTextField(
                        value = setup,
                        onValueChange = { setup = it },
                        label = { Text("Setup code") },
                        placeholder = { Text("Project ID, API key, App ID") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            message = Cloud.saveSetupCode(context, setup)
                            if (message == null) {
                                setup = ""
                                changing = false
                            }
                        },
                        enabled = setup.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Connect") }
                    if (changing) {
                        TextButton(onClick = { changing = false }) { Text("Cancel") }
                    }
                } else if (!st.signedIn) {
                    Text("Connected. Sign in with your email, or create an account.", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Email") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password (6 or more characters)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            scope.launch { message = Cloud.signIn(context, email, password, create = false) }
                        },
                        enabled = !st.busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Sign in") }
                    OutlinedButton(
                        onClick = {
                            scope.launch { message = Cloud.signIn(context, email, password, create = true) }
                        },
                        enabled = !st.busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Create account") }
                    TextButton(onClick = { changing = true }) { Text("Change setup code") }
                } else {
                    Text("Signed in as ${st.email}", fontWeight = FontWeight.SemiBold)
                    Text("What is this phone for?", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = st.role == Cloud.ROLE_SELLER,
                            onClick = { Cloud.setRole(context, Cloud.ROLE_SELLER) },
                            label = { Text("Selling phone") }
                        )
                        FilterChip(
                            selected = st.role == Cloud.ROLE_MONITOR,
                            onClick = { Cloud.setRole(context, Cloud.ROLE_MONITOR) },
                            label = { Text("Monitor phone") }
                        )
                    }
                    when (st.role) {
                        Cloud.ROLE_SELLER -> {
                            Text(
                                "This phone sells as usual and uploads every sale, product and stock change. " +
                                    "New or repaired phone? Restore first, before selling.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            OutlinedButton(
                                onClick = { confirmRestore = true },
                                enabled = !st.busy,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Restore from cloud") }
                        }
                        Cloud.ROLE_MONITOR -> Text(
                            "This phone only watches the shop. Nothing can be changed from here.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        else -> Text(
                            "Choose one of the two above.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    TextButton(onClick = {
                        Cloud.signOut(context)
                        message = null
                    }) { Text("Sign out") }
                }

                message?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                if (st.status.isNotEmpty()) {
                    Text(st.status, style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )

    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("Replace everything?") },
            text = {
                Text(
                    "All products and sales on this phone will be erased and replaced " +
                        "by the ones stored in your cloud."
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmRestore = false
                    scope.launch {
                        val error = Cloud.restoreFromCloud(context)
                        if (error == null) {
                            vm.clearCart()
                            vm.toast("Restored from cloud")
                            message = null
                        } else {
                            message = error
                        }
                    }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("Cancel") } }
        )
    }
}
