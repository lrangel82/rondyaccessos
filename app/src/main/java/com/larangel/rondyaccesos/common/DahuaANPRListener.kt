package com.larangel.rondyaccesos.common

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.regex.Pattern

class DahuaANPRListener(
    private val ip: String,
    private val username: String,
    private val password: String,
    private val onPlateDetected: (String) -> Unit
) {
    private val TAG = "DahuaANPRListener"
    private var isRunning = false
    private var listenerJob: Job? = null

    fun start(scope: CoroutineScope) {
        if (isRunning) return
        if (ip.isBlank() || ip.length <= 7) return
        isRunning = true

        listenerJob = scope.launch(Dispatchers.IO) {
            val urlString = "http://$ip/cgi-bin/eventManager.cgi?action=attach&codes=[All]"
            val pattern = Pattern.compile("\"PlateNumber\" : \"([\\w]+)\"")

            while (isRunning && isActive) {
                try {
                    val client = OkHttpClient.Builder().build()
                    val request = Request.Builder().url(urlString).get().build()

                    client.newCall(request).execute().use { response ->
                        if (response.code == 401) {
                            val authHeader = response.header("WWW-Authenticate")
                            if (authHeader != null && authHeader.contains("Digest")) {
                                val digestHeader = calculateDigest(authHeader, username, password, "GET", "/cgi-bin/eventManager.cgi?action=attach&codes=[All]")
                                val authenticatedRequest = Request.Builder()
                                    .url(urlString)
                                    .addHeader("Authorization", digestHeader)
                                    .get()
                                    .build()

                                client.newCall(authenticatedRequest).execute().use { authResponse ->
                                    if (authResponse.isSuccessful) {
                                        Log.i(TAG, "[+] Conectado con éxito a la cámara ANPR Dahua ($ip). Esperando eventos...")
                                        val reader = BufferedReader(InputStreamReader(authResponse.body?.byteStream() ?: return@use))
                                        var line: String? = reader.readLine()
                                        while (isRunning && isActive && line != null) {
                                            val matcher = pattern.matcher(line)
                                            if (matcher.find()) {
                                                val plate = matcher.group(1)
                                                if (!plate.isNullOrBlank() && plate != "Unknown") {
                                                    Log.i(TAG, "ANPR DETECTADO: $plate")
                                                    onPlateDetected(plate)
                                                    delay(1000) // 1 second cooldown
                                                }
                                            }
                                            line = reader.readLine()
                                        }
                                    } else {
                                        Log.e(TAG, "[-] Error de autenticación Digest ANPR: ${authResponse.code}")
                                    }
                                }
                            }
                        } else if (response.isSuccessful) {
                            Log.i(TAG, "[+] Conectado a ANPR sin auth ($ip)...")
                            val reader = BufferedReader(InputStreamReader(response.body?.byteStream() ?: return@use))
                            var line: String? = reader.readLine()
                            while (isRunning && isActive && line != null) {
                                val matcher = pattern.matcher(line)
                                if (matcher.find()) {
                                    val plate = matcher.group(1)
                                    if (!plate.isNullOrBlank() && plate != "Unknown") {
                                        Log.i(TAG, "ANPR DETECTADO: $plate")
                                        onPlateDetected(plate)
                                        delay(1000)
                                    }
                                }
                                line = reader.readLine()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "[-] Conexión ANPR perdida o error: ${e.message}. Reintentando en 5s...")
                }
                delay(5000)
            }
        }
    }

    fun stop() {
        isRunning = false
        listenerJob?.cancel()
        Log.i(TAG, "[*] Deteniendo escucha ANPR...")
    }

    private fun calculateDigest(authHeader: String, user: String, pass: String, method: String, uri: String): String {
        val params = authHeader.replace("Digest ", "").split(",").associate {
            val parts = it.trim().split("=", limit = 2)
            if (parts.size == 2) parts[0].trim() to parts[1].trim().removeSurrounding("\"") else "" to ""
        }
        val realm = params["realm"] ?: ""
        val nonce = params["nonce"] ?: ""
        val qop = params["qop"] ?: "auth"
        val nc = "00000001"
        val cnonce = "0a4f113b"

        val ha1 = md5("$user:$realm:$pass")
        val ha2 = md5("$method:$uri")
        val response = md5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")

        return "Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", qop=\"$qop\", nc=$nc, cnonce=\"$cnonce\", response=\"$response\""
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
