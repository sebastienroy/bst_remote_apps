package com.photographyelectronics.bstviewer

import android.R
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons // Conteneur principal
import androidx.compose.material.icons.filled.Close // Exemple pour l'icône Close
import androidx.compose.material.icons.filled.Refresh // Exemple pour l'icône Refresh
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.photographyelectronics.bstviewer.ui.theme.BabyShutterTesterViewerTheme
import androidx.lifecycle.viewmodel.compose.viewModel


class MainActivity : ComponentActivity() {

    private val serialViewModel: SerialViewModel by viewModels()

    private val ACTION_USB_PERMISSION = "com.photographyelectronics.bstviewer.USB_PERMISSION"
    private lateinit var usbManager: UsbManager
    private var currentPort: UsbSerialPort? = null

    // --- Fonctions d'extension pour la compatibilité Android ---

    inline fun <reified T : android.os.Parcelable> Intent.safeGetParcelableExtra(key: String): T? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> getParcelableExtra(key, T::class.java)
        else -> @Suppress("DEPRECATION") getParcelableExtra(key) as? T
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        enableEdgeToEdge()


        setContent {
            BabyShutterTesterViewerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DataScreen(
                        viewModel = serialViewModel,
                        onConnectClicked = { findAndConnectSerialPort() },
                        onDisconnectClicked = { currentPort?.let { serialViewModel.closePort(it) } }
                    )
                }

            }

        }

        // Enregistrement du BroadcastReceiver (avec correction Android 13+)
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            this,
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        findAndConnectSerialPort()
    }
    override fun onDestroy() {
        // Fermer le port et annuler la coroutine lors de la destruction de l'activité
        currentPort?.let { serialViewModel.closePort(it) }
        unregisterReceiver(usbReceiver)
        super.onDestroy()
    }

    // --- LOGIQUE DE CONNEXION USB ---

    private fun findAndConnectSerialPort() {
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

        if (availableDrivers.isEmpty()) {
            serialViewModel.updateStatus("Statut : Aucun périphérique USB série trouvé.")
            return
        }

        val driver = availableDrivers[0]
        val port = driver.ports[0]
        val device = port.device

        if (!usbManager.hasPermission(device)) {
            requestUsbPermission(device)
            return
        }

        connectToSerialPort(port)
    }

    private fun requestUsbPermission(device: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val permissionIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_USB_PERMISSION),
            flags
        )
        usbManager.requestPermission(device, permissionIntent)
        serialViewModel.updateStatus("Statut : Permission USB demandée...")

    }

    private fun connectToSerialPort(port: UsbSerialPort) {
        try {
            val connection = usbManager.openDevice(port.device)
            if (connection == null) {
                serialViewModel.updateStatus("Erreur: Connexion refusée.")
                return
            }

            port.open(connection)
            port.setParameters(
                115200,
                8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )
            // Activation DTR/RTS pour Pico (fix du problème de lecture)
            port.dtr = true
            port.rts = true

            currentPort = port
            serialViewModel.startReading(port)

        } catch (e: Exception) {
            serialViewModel.updateStatus("Erreur: Échec de l'ouverture du port. (${e.message})")
        }
    }

    // --- GESTION DES ÉVÉNEMENTS USB (BroadcastReceiver) ---

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device: UsbDevice? = intent.safeGetParcelableExtra(UsbManager.EXTRA_DEVICE)

            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null) {
                        findAndConnectSerialPort()
                    } else if (device != null) {
                        serialViewModel.updateStatus("Statut : Permission refusée.")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    if (device != null) {
                        serialViewModel.updateStatus("Périphérique branché. Connexion auto tentée.")
                        findAndConnectSerialPort()
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    currentPort?.let {
                        if (it.device == device) {
                            serialViewModel.closePort(it)
                        }
                    }
                }
            }
        }
    }
}

// --- INTERFACE UTILISATEUR JETPACK COMPOSE ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataScreen(
    viewModel: SerialViewModel = viewModel(),
    onConnectClicked: () -> Unit,
    onDisconnectClicked: () -> Unit
) {
    val data = viewModel.currentData
    val isConnected = viewModel.isConnected

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pico Serial Monitor") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (isConnected) Color(0xFF4CAF50) else Color(0xFFF44336), // Vert/Rouge
                    titleContentColor = Color.White,
                    actionIconContentColor = Color.White
                ),
                actions = {
                    // Statut de la connexion
                    Text(
                        text = viewModel.statusMessage,
                        color = Color.White,
                        modifier = Modifier.padding(end = 8.dp)
                    )

                    // Bouton Connecter/Déconnecter
                    IconButton(
                        onClick = {
                            if (isConnected) onDisconnectClicked() else onConnectClicked()
                        }
                    ) {
                        Icon(
                            imageVector = if (isConnected) Icons.Default.Close else Icons.Default.Refresh,
                            contentDescription = if (isConnected) "Déconnecter" else "Connecter",
                            tint = Color.White
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // --- 1. Durée Effective (Tout en haut) ---
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    //.weight(1f) // Prend le maximum d'espace disponible
                    //.background(Color.White)
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center

            ) {
                Text(
                    text = "Effective time",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Light,
                    //color = Color.Black
                )
                Text(
                    text = "${data.effectiveTime/1000.0} ms",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 32.dp)
                )

            }

            Spacer(modifier = Modifier.height(32.dp))

            // --- 2. Vitesse (Grande, Centrée, Fond Blanc) ---
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f) // Prend le maximum d'espace disponible
                    //.background(Color.White)
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Speed",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    //color = Color.Black
                )
                Text(
                    //text = "1/${viewModel.formatDouble(1000000.0/data.effectiveTime)}s",
                    text = "1/${viewModel.formatDoubleAsInteger(1000000.0/data.effectiveTime)}s",
                    fontSize = 60.sp,
                    fontWeight = FontWeight.ExtraBold,
                    //color = Color.Black
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // --- 3. Ligne des 3 Valeurs Restantes (Bas de l'écran) ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                SmallDataColumn(title = "Total time", value = "${data.totalTime/1000.0} ms", modifier = Modifier.weight(1f))

                val tauxFmt = viewModel.formatDouble(data.effectiveTime.toDouble() /data.totalTime.toDouble() * 100.0)
                SmallDataColumn(title = "Efficiency", value = "$tauxFmt %", modifier = Modifier.weight(1f))

                val signalFmt = viewModel.formatDouble(data.relativeSignal.toDouble() * 100.0)
                SmallDataColumn(title = "Signal", value = "$signalFmt %", modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun SmallDataColumn(title: String, value: String, modifier: Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Light)
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}