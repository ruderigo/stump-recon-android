package com.stump.recon

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.stump.recon.ui.FlasherScreen
import com.stump.recon.ui.RadarScreen
import com.stump.recon.ui.ReconViewModel
import com.stump.recon.ui.StreamScreen
import com.stump.recon.ui.StumpColors
import com.stump.recon.ui.StumpTheme
import com.stump.recon.ui.TunerExportScreen
import com.stump.recon.ui.WaterfallScreen

class MainActivity : ComponentActivity() {

    private val vm: ReconViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            StumpTheme {
                val state by vm.state.collectAsState()
                var tabIndex by remember { mutableIntStateOf(0) }
                var pendingBinSlot by remember { mutableStateOf("merged") }
                var pendingExportFormat by remember { mutableStateOf("json") }
                val snackbarHostState = remember { SnackbarHostState() }

                val binPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) vm.loadLocalBin(pendingBinSlot, uri)
                }

                val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
                    if (uri != null) vm.exportToUri(pendingExportFormat, uri)
                }

                LaunchedEffect(state.toastMessage) {
                    state.toastMessage?.let {
                        snackbarHostState.showSnackbar(it)
                        vm.clearToast()
                    }
                }

                val tabs = listOf(
                    "Radar" to Icons.Default.Radar,
                    "Waterfall" to Icons.Default.ShowChart,
                    "Stream" to Icons.Default.List,
                    "Flasher" to Icons.Default.Memory,
                    "Tuner" to Icons.Default.Tune
                )

                Scaffold(
                    containerColor = StumpColors.Canvas,
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    topBar = {
                        TopAppBar(
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = StumpColors.Surface),
                            title = {
                                Column {
                                    Text("STUMP RECON", color = StumpColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                    Text(state.usbLabel, color = if (state.usbConnected) StumpColors.Emerald else StumpColors.TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                }
                            },
                            actions = {
                                TextButton(onClick = {
                                    if (state.usbConnected) vm.disconnectUsb() else vm.connectUsbSniffer()
                                }) {
                                    Icon(Icons.Default.Usb, contentDescription = "USB-OTG", tint = if (state.usbConnected) StumpColors.Emerald else StumpColors.Cyan)
                                    Text(if (state.usbConnected) " Disconnect" else " Connect USB", color = if (state.usbConnected) StumpColors.Emerald else StumpColors.Cyan, fontSize = 12.sp)
                                }
                            }
                        )
                    },
                    bottomBar = {
                        NavigationBar(containerColor = StumpColors.Surface) {
                            tabs.forEachIndexed { idx, (label, icon) ->
                                NavigationBarItem(
                                    selected = tabIndex == idx,
                                    onClick = { tabIndex = idx },
                                    icon = { Icon(icon, contentDescription = label) },
                                    label = { Text(label, fontSize = 10.sp) }
                                )
                            }
                        }
                    }
                ) { padding ->
                    Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                        when (tabIndex) {
                            0 -> RadarScreen(
                                state = state,
                                onSelectSort = vm::setSortMode,
                                onSelectBand = vm::setBandFilter,
                                onSelectCadence = vm::setRefreshCadence,
                                onToggleFreeze = vm::toggleFreezeList,
                                onConnectUsb = vm::connectUsbSniffer
                            )
                            1 -> WaterfallScreen(
                                state = state,
                                onSelectHopBand = vm::selectHopBand,
                                onSelectDwell = vm::setHopDwellMs,
                                onStartHop = { vm.startFrequencyHopping() },
                                onStartFastScan = vm::startFastScan,
                                onStopHop = { vm.stopHopping(sendSniff = true) },
                                onLockFreq = vm::lockFrequency,
                                onResetPeaks = vm::resetWaterfallBins
                            )
                            2 -> StreamScreen(state)
                            3 -> FlasherScreen(
                                state = state,
                                onSelectBoard = vm::setBoard,
                                onSelectMode = vm::setFlashMode,
                                onSelectBaud = vm::setFlashBaud,
                                onPickBin = { slot ->
                                    pendingBinSlot = slot
                                    binPicker.launch(arrayOf("application/octet-stream", "*/*"))
                                },
                                onStartFlash = vm::startUsbOtgFlash,
                                onCancelFlash = vm::cancelFlash,
                                onReconnectSniffer = {
                                    vm.connectUsbSniffer()
                                    tabIndex = 0
                                }
                            )
                            4 -> TunerExportScreen(
                                state = state,
                                onSendCommand = vm::sendCliCommand,
                                onClearCaptures = vm::clearCaptures,
                                onExport = { fmt ->
                                    pendingExportFormat = fmt
                                    val ext = if (fmt == "charles") "chlsj" else fmt
                                    exportLauncher.launch("stump_recon_${System.currentTimeMillis()}.$ext")
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
