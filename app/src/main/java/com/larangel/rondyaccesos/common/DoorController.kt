package com.larangel.rondyaccesos.common

import android.content.Context
import com.larangel.rondyaccesos.models.MySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.*
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class DoorController(context: Context) {
    private val mySettings = MySettings(context)
    private val httpClient = OkHttpClient()

    // Función principal que llamarás desde cualquier Activity
    suspend fun abrirPuertaConfigurada(): Result<String> = withContext(Dispatchers.IO) {
        val tipo = mySettings.getString("DOOR_CONTROL_TYPE", "HTTP")
        if (tipo == "HTTP") {
            ejecutarPulsadorHttp()
        } else {
            val ip = mySettings.getString("DAHUA_IP", "")
            val user = mySettings.getString("DAHUA_USER", "")
            val pass = mySettings.getString("DAHUA_PASS", "")
            val canal = mySettings.getString("DAHUA_SELECTED_DOOR_CHAN", "")

            if (ip.isEmpty() || canal.isEmpty()) {
                Result.failure(Exception("Configuración Dahua incompleta"))
            } else {
                ejecutarAperturaDahua(ip, user, pass, canal)
            }
        }
    }

    suspend fun ejecutarPulsadorHttp(urlOpenOverride: String? = null, urlCloseOverride: String? = null): Result<String> = withContext(Dispatchers.IO) {
        try {
            val urlOpen = urlOpenOverride ?: mySettings.getString("URL_OPEN", "")
            val urlClose = urlCloseOverride ?: mySettings.getString("URL_CLOSE", "")

            if (urlOpen.isEmpty()) return@withContext Result.failure(Exception("URL de apertura vacía"))

            // Llamada 1: Abrir
            val reqOpen = Request.Builder().url(urlOpen).build()
            httpClient.newCall(reqOpen).execute().use { if (!it.isSuccessful) return@withContext Result.failure(Exception("Error al abrir: ${it.code}")) }

            // Si hay URL de cierre, simular pulsador con delay
            if (urlClose.isNotEmpty()) {
                delay(500)
                val reqClose = Request.Builder().url(urlClose).build()
                httpClient.newCall(reqClose).execute().use { }
            }

            Result.success("Pulsador HTTP ejecutado")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun ejecutarAperturaDahua(ip: String, user: String, pass: String, canal: String): Result<String> = withContext(Dispatchers.IO) {
        val path = "/cgi-bin/accessControl.cgi?action=openDoor&channel=$canal"
        realizarPeticionDigest(ip, user, pass, path)
    }

    suspend fun obtenerPuertasDahua(ip: String, user: String, pass: String): Result<List<String>> = withContext(Dispatchers.IO) {
        val path = "/cgi-bin/accessControl.cgi?action=getDoorList"
        val res = realizarPeticionDigest(ip, user, pass, path)
        res.map { body ->
            body.split("\n")
                .filter { it.contains("Channels[") }
                .map { it.substringAfter("=").trim() }
        }
    }

    private fun realizarPeticionDigest(ip: String, user: String, pass: String, path: String): Result<String> {
        val url = "http://$ip:37777$path"

        // Crear un cliente desechable sin Pool de Conexiones (Evita por completo el "unexpected end of stream")
        val dahuaClient = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1)) // Obligar HTTP/1.1
            .connectionPool(ConnectionPool(0, 1, TimeUnit.NANOSECONDS)) // Configurar Pool a CERO conexiones retenidas
            .retryOnConnectionFailure(false) // No reintentar sobre el mismo socket corrupto
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build()

        try {
            // --- PASO 1: Obtener el reto 401 ---
            val request1 = Request.Builder()
                .url(url)
                .addHeader("Connection", "close") // Indicarle al hardware que cierre el hilo al responder
                .addHeader("User-Agent", "Android/DahuaClient")
                .build()

            dahuaClient.newCall(request1).execute().use { response1 ->
                if (response1.code == 401) {
                    val authHeader = response1.header("WWW-Authenticate")
                        ?: return Result.failure(Exception("No digest header"))

                    // Calcular cabecera con la corrección de formato
                    val digestHeader = calculateDigest(authHeader, user, pass, "GET", path)

                    // --- PASO 2: Enviar petición autenticada ---
                    val request2 = Request.Builder()
                        .url(url)
                        .addHeader("Authorization", digestHeader)
                        .addHeader("Connection", "close") // Volver a forzar el cierre inmediato
                        .addHeader("User-Agent", "Android/DahuaClient")
                        .build()

                    // Ejecutar en una instancia de llamada limpia
                    dahuaClient.newCall(request2).execute().use { response2 ->
                        return if (response2.isSuccessful) {
                            Result.success(response2.body?.string() ?: "")
                        } else {
                            Result.failure(Exception("Auth fallida en Panel: ${response2.code}"))
                        }
                    }
                }

                return if (response1.isSuccessful) {
                    Result.success(response1.body?.string() ?: "")
                } else {
                    Result.failure(Exception("Error inicial: ${response1.code}"))
                }
            }
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    private fun calculateDigest(authHeader: String, user: String, pass: String, method: String, uri: String): String {
        val params = authHeader.replace("Digest ", "").split(",").associate {
            val pair = it.trim().split("=")
            pair[0] to pair[1].replace("\"", "")
        }

        val realm = params["realm"] ?: ""
        val nonce = params["nonce"] ?: ""
        val qop = params["qop"] ?: "auth"
        val nc = "00000001"
        val cnonce = "0a4f113b" // Puedes dejar este valor fijo sin problemas

        val ha1 = md5("$user:$realm:$pass")
        val ha2 = md5("$method:$uri")
        val response = md5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")

        // Nota las comillas exactas: qop="auth", cnonce="...", nc=00000001 (sin comillas)
        return "Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", qop=\"$qop\", nc=$nc, cnonce=\"$cnonce\", response=\"$response\""
    }

    private fun md5(input: String) = MessageDigest.getInstance("MD5").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
}