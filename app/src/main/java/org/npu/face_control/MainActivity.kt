package org.npu.face_control

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.npu.face_control.config.ConfigRepository
import org.npu.face_control.ui.SettingsScreen
import org.npu.face_control.ui.theme.FaceControlTheme

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Toast.makeText(this, "Camera permission granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConfigRepository.init(applicationContext)
        setContent {
            FaceControlTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var showSettings by rememberSaveable { mutableStateOf(false) }
                    if (showSettings) {
                        SettingsScreen(onBack = { showSettings = false })
                    } else {
                        MainScreen(
                            onStartService = { startFaceControlService() },
                            onOpenAccessibility = { openAccessibilitySettings() },
                            onOpenOverlay = { openOverlaySettings() },
                            onRequestCamera = { requestPermissionLauncher.launch(Manifest.permission.CAMERA) },
                            onOpenSettings = { showSettings = true }
                        )
                    }
                }
            }
        }
    }

    private fun startFaceControlService() {
        val intent = Intent(this, FaceControlForegroundService::class.java)
        startForegroundService(intent)
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }
}

@Composable
fun MainScreen(
    onStartService: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenOverlay: () -> Unit,
    onRequestCamera: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "FaceControl MVP", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(32.dp))

        Button(onClick = onRequestCamera, modifier = Modifier.fillMaxWidth()) {
            Text("1. Request Camera Permission")
        }
        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = onOpenAccessibility, modifier = Modifier.fillMaxWidth()) {
            Text("2. Enable Accessibility Service")
        }
        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = onOpenOverlay, modifier = Modifier.fillMaxWidth()) {
            Text("3. Enable Overlay Permission")
        }
        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onStartService,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Text("START FACECONTROL")
        }

        Spacer(modifier = Modifier.height(16.dp))
        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
            Text("SETTINGS / 手势设置")
        }
    }
}
