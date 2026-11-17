package local.handheld.tweaks.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import local.handheld.tweaks.core.TweakId
import local.handheld.tweaks.core.TweakStatus
import local.handheld.tweaks.service.RunLog
import local.handheld.tweaks.service.ServiceState
import local.handheld.tweaks.service.TweakService
import local.handheld.tweaks.shizuku.ShizukuGateway
import local.handheld.tweaks.shizuku.ShizukuState

private val labels = mapOf(
    TweakId.REFRESH_120 to "Always 120 Hz",
    TweakId.DEBLOAT to "Bloat removed",
    TweakId.NEUTER_PROTECTED to "Protected bloat blocked",
    TweakId.CUTOUT_OVERRIDE to "Draw over camera cutout",
    TweakId.IDLE_WHITELIST to "Battery whitelist",
)

private val ok = Color(0xFF7BD88F)
private val warn = Color(0xFFF2C14E)
private val bad = Color(0xFFFF6B6B)

@Composable
fun StatusScreen() {
    val ctx = LocalContext.current
    val shizuku by ShizukuGateway.state.collectAsState()
    val results by ServiceState.lastResults.collectAsState()
    val controller by ServiceState.controllerConnected.collectAsState()
    val locked by ServiceState.landscapeLocked.collectAsState()
    var log by remember { mutableStateOf(RunLog.read(ctx).takeLast(100)) }
    LaunchedEffect(Unit) {
        while (true) {
            log = RunLog.read(ctx).takeLast(100)
            delay(2000)
        }
    }

    Row(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(
            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Handheld Tweaks", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = shizuku == ShizukuState.READY,
                    onClick = { TweakService.start(ctx, TweakService.ACTION_REAPPLY) },
                ) { Text("Reapply now") }
                OutlinedButton(
                    enabled = shizuku == ShizukuState.READY,
                    onClick = { TweakService.start(ctx, TweakService.ACTION_TOGGLE_LANDSCAPE) },
                ) { Text(if (locked) "Unlock landscape" else "Lock landscape") }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusRow(
                    "Shizuku",
                    when (shizuku) {
                        ShizukuState.READY -> "ready"
                        ShizukuState.NO_PERMISSION -> "permission needed"
                        ShizukuState.NOT_RUNNING -> "not running"
                    },
                    if (shizuku == ShizukuState.READY) ok else bad,
                    Modifier.weight(1f),
                )
                when (shizuku) {
                    ShizukuState.NO_PERMISSION -> Button(onClick = { ShizukuGateway.requestPermission() }) { Text("Grant") }
                    ShizukuState.NOT_RUNNING -> Button(onClick = {
                        ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                            ?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }) { Text("Open Shizuku") }
                    ShizukuState.READY -> Unit
                }
            }

            StatusRow(
                "Controller",
                "${if (controller) "connected" else "not connected"} · Rotation: ${if (locked) "landscape-locked" else "normal"}",
                if (controller || locked) ok else MaterialTheme.colorScheme.onBackground,
            )

            TweakId.entries.forEach { id ->
                val r = results.firstOrNull { it.id == id }
                val (text, color) = when (r?.status) {
                    null -> "not checked yet" to MaterialTheme.colorScheme.onBackground
                    TweakStatus.APPLIED -> "applied" to ok
                    TweakStatus.REAPPLIED -> "reapplied: ${r.detail}" to ok
                    TweakStatus.PROTECTED -> "protected: ${r.detail}" to warn
                    TweakStatus.FAILED -> "failed: ${r.detail}" to bad
                }
                StatusRow(labels.getValue(id), text, color)
            }

        }

        Column(Modifier.width(560.dp).fillMaxHeight()) {
            Text("Log", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.padding(4.dp))
            val listState = rememberLazyListState()
            LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.scrollToItem(log.size - 1) }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                    .padding(10.dp),
            ) {
                items(log) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(value, color = color, fontSize = 13.sp)
    }
}
