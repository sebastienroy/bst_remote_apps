package com.photographyelectronics.bstviewer

import com.photographyelectronics.bstviewer.data.DefaultSensorData

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import java.text.DecimalFormat
import android.util.Log // N'oubliez pas l'import pour le Logcat
import com.photographyelectronics.bstviewer.data.SensorData

class SerialViewModel : ViewModel() {

    // --- Variables d'État (Observables par Compose) ---
    var statusMessage by mutableStateOf("Déconnecté. Branchez le Pico.")
        private set

    var isConnected by mutableStateOf(false)
        private set

    var currentData by mutableStateOf(DefaultSensorData)
        private set

    // --- Outils ---

    // Parser JSON configuré pour ignorer les clés inconnues
    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // Formatage pour une décimale
    private val decimalFormat = DecimalFormat("#.0")

    // 🚨 Nouveau formatage pour les entiers (Vitesse)
    private val integerFormat = DecimalFormat("#")

    // Buffer de lecture pour JSON
    private val jsonBuffer = StringBuilder()
    private var readJob: Job? = null


    fun updateStatus(message: String) {
        statusMessage = message // Ceci est autorisé car on est dans la classe SerialViewModel
    }

    // --- LECTURE ET TRAITEMENT DES DONNÉES ---

    /**
     * Démarre la coroutine de lecture sur le port série spécifié.
     */
    fun startReading(port: UsbSerialPort) {
        if (readJob?.isActive == true) return

        isConnected = true
        statusMessage = "Connecté. En attente de données."

        readJob = viewModelScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(8192)
            while (isActive) {
                try {
                    // Lecture synchrone et bloquante (timeout 100ms)
                    val numBytesRead = port.read(buffer, 100)

                    if (numBytesRead > 0) {
                        val rawData = String(buffer, 0, numBytesRead, Charsets.UTF_8)
                        jsonBuffer.append(rawData)

                        Log.d("SERIAL_DIAG", "Bytes lus: $numBytesRead. Fragment: '$rawData'")

                        // Traitement par ligne (recherche du saut de ligne '\n')
                        val newlineIndex = jsonBuffer.indexOf('\n')
                        if (newlineIndex != -1) {
                            val fullMessage = jsonBuffer.substring(0, newlineIndex)
                            // Vider le tampon des données traitées (+1 pour le '\n')
                            jsonBuffer.delete(0, newlineIndex + 1)

                            processJson(fullMessage.trim())
                        }
                    }
                } catch (e: Exception) {
                    // Log d'erreur
                    Log.e("SERIAL_VM", "Erreur de lecture ou port fermé: ${e.message}", e)
                    if (isActive) {
                        withContext(Dispatchers.Main) {
                            statusMessage = "Erreur de lecture: ${e.message}"
                            closePort(port)
                        }
                    }
                    break
                }
            }
        }
    }

    /**
     * Désérialise la chaîne JSON reçue et met à jour l'état de l'UI.
     */
    private suspend fun processJson(jsonString: String) {
        if (jsonString.isBlank()) return
        try {
            val data = jsonParser.decodeFromString<SensorData>(jsonString)
            // Mise à jour de l'état
            currentData = data
            // Le statut peut être mis à jour plus brièvement pour le bon fonctionnement.
            withContext(Dispatchers.Main) {
                statusMessage = if (isConnected) "Connecté" else "Déconnecté"
            }
        } catch (e: Exception) {
            Log.e("SERIAL_VM", "Erreur JSON, chaîne non valide: '$jsonString'", e)
            // Ne pas surcharger l'UI avec les erreurs JSONs répétées, utilisez Logcat
        }
    }

    // --- GESTION DE LA CONNEXION ---

    /**
     * Ferme le port série et met à jour l'état.
     */
    fun closePort(port: UsbSerialPort) {
        readJob?.cancel()
        try {
            port.close()
        } catch (_: Exception) {}
        isConnected = false
        statusMessage = "Déconnecté."
        jsonBuffer.clear() // Vider le buffer
    }

    /**
     * Fonction utilitaire pour le formatage des Doubles pour l'UI.
     */
    fun formatDouble(value: Double): String {
        return decimalFormat.format(value)
    }

    fun formatDoubleAsInteger(value: Double): String {
        // Le formatage convertit et tronque la partie décimale
        return integerFormat.format(value)
    }
}