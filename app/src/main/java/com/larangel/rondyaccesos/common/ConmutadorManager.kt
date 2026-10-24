package com.larangel.rondyaccesos.common
import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit

class ConmutadorManager(private val context: Context) {

    // Cliente HTTP con tiempos de lectura configurados
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS) // Un poco más alto que el timeout de la llamada
        .build()

    /**
     * Función principal solicitada
     * Retorna: 1 (Autorizado), 3 (Denegado/Invalido), 4 (Sin Respuesta)
     */
    suspend fun gestionarAutorizacionAcceso(
        extension: String,
        ipConmutador: String,
        conductor: String,
        domicilio: String,
        tipoIngreso: String,
        userApi: String,       // Proporcionado en el flujo posterior
        passApi: String        // Proporcionado en el flujo posterior
    ): Int = withContext(Dispatchers.IO) {

        val textoAnuncio = "Hola, se solicita autorizacion para dar acceso a $conductor " +
                "al domicilio $domicilio que ingresa como $tipoIngreso, " +
                "precione 1 para autorizar, precione 3 para denegar."

        // 1. Generar audio dinámico localmente en Android (.wav)
        val archivoAudio = generarAudioWavDesdeTexto(textoAnuncio) ?: return@withContext 3

        val baseUrl = "http://$ipConmutador:8443" // Puerto API por defecto del Grandstream

        // 2. Autenticarse en el Conmutador para obtener la cookie de sesión
        val cookieSesion = autenticarEnConmutador(baseUrl, userApi, passApi) ?: return@withContext 4

        // 3. Subir el archivo de audio dinámico al UCM
        val audioSubidoExito = subirAudioCustomPrompt(baseUrl, cookieSesion, archivoAudio)
        if (!audioSubidoExito) return@withContext 3

        // Bucle de Control de Reintentos (Máximo 2 intentos)
        for (intento in 1..2) {
            // 4. Lanzar la llamada mediante API Originate
            val callUniqueId = lanzarLlamadaAExtension(baseUrl, cookieSesion, extension)

            if (callUniqueId != null) {
                // Monitorear la llamada con un Timeout Estricto de 30 segundos
                val respuestaUsuario = esperarYProcesarRespuestaCDR(baseUrl, cookieSesion, callUniqueId, 30000L)

                if (respuestaUsuario != 4) {
                    // Si devolvió 1 o 3 (hubo respuesta del usuario), retornamos el valor de inmediato
                    return@withContext respuestaUsuario
                }
            }
            // Si el callUniqueId fue nulo o devolvió 4 (no contestó), espera un breve momento antes de reintentar
            delay(5000)
        }

        // Si se agotan los 2 intentos sin contestar, la respuesta final es 4
        return@withContext 4
    }

    /**
     * Convierte texto a un archivo de audio WAV usando el TTS nativo de Android
     */
    private suspend fun generarAudioWavDesdeTexto(texto: String): File? = suspendCancellableCoroutine { continuation ->
        var tts: TextToSpeech? = null
        val fileTemp = File(context.cacheDir, "prompt_temp.wav")

        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("es", "MX") // Español Latino/México

                // Configurar listener para saber cuándo termina de crearse el archivo
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        tts?.shutdown()
                        if (continuation.isActive) continuation.resume(fileTemp, null)
                    }
                    override fun onError(utteranceId: String?) {
                        tts?.shutdown()
                        if (continuation.isActive) continuation.resume(null, null)
                    }
                })

                // Forzar formato compatible con Grandstream UCM (PCM 16Bit, 8000Hz o similar si el motor lo provee)
                val params = android.os.Bundle()
                tts?.synthesizeToFile(texto, params, fileTemp, "UTTERANCE_ID_IVR")
            } else {
                if (continuation.isActive) continuation.resume(null, null)
            }
        }

        continuation.invokeOnCancellation { tts?.shutdown() }
    }

    /**
     * Login en la API de Grandstream
     */
    private fun autenticarEnConmutador(baseUrl: String, user: String, pass: String): String? {
        val url = "$baseUrl/api?action=login&username=$user&secret=$pass"
        val request = Request.Builder().url(url).build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    // Extraemos las cookies de respuesta necesarias para mantener la sesión viva
                    response.header("Set-Cookie")
                } else null
            }
        } catch (e: IOException) { null }
    }

    /**
     * Sube el archivo .wav a los prompts personalizados del UCM
     */
    private fun subirAudioCustomPrompt(baseUrl: String, sessionCookie: String, archivo: File): Boolean {
        val url = "$baseUrl/api?action=uploadfile&type=customprompt"
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("uploadfile", "ivr_dinamico.wav",
                archivo.asRequestBody("audio/wav".toMediaTypeOrNull()))
            .build()

        val request = Request.Builder()
            .url(url)
            .addHeader("Cookie", sessionCookie)
            .post(requestBody)
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: IOException) { false }
    }

    /**
     * Ejecuta el comando Originate conectando la extensión con el IVR
     */
    private fun lanzarLlamadaAExtension(baseUrl: String, sessionCookie: String, extension: String): String? {
        // Explicación: Llama a la extensión y la envía al contexto interno asignando el IVR dinámico
        val url = "$baseUrl/api?action=originate&channel=SIP/$extension&context=from-internal&exten=9999&priority=1"
        val request = Request.Builder()
            .url(url)
            .addHeader("Cookie", sessionCookie)
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "")
                    // El conmutador responde con un ID de llamada único (actionid o uniqueid)
                    json.optString("uniqueid", null)
                } else null
            }
        } catch (e: IOException) { null }
    }

    /**
     * Monitorea el CDR de la llamada de manera asíncrona controlando el timeout de 30 segundos
     */
    private suspend fun esperarYProcesarRespuestaCDR(
        baseUrl: String,
        sessionCookie: String,
        uniqueId: String,
        timeoutMs: Long
    ): Int {
        val inicio = System.currentTimeMillis()

        // Bucle de consulta constante (Polling) hasta que termine la llamada o se agote el tiempo
        while (System.currentTimeMillis() - inicio < timeoutMs) {
            val dtmfDigit = consultarDigitoPresionadoCDR(baseUrl, sessionCookie, uniqueId)

            if (dtmfDigit != null) {
                // Si la llamada finalizó y se capturó un dígito
                return when (dtmfDigit) {
                    "1" -> 1
                    "3" -> 3
                    else -> 3 // Cualquier otra respuesta inválida se considera 3 (Denegado)
                }
            }
            delay(2000) // Consultar al conmutador cada 2 segundos
        }
        return 4 // Si pasaron los 30 segundos sin completarse exitosamente, retorna 4
    }

    /**
     * Consulta el registro de llamadas remotas (RDR/CDR) mediante la API buscando el UniqueID
     */
    private fun consultarDigitoPresionadoCDR(baseUrl: String, sessionCookie: String, uniqueId: String): String? {
        val url = "$baseUrl/api?action=CDR&uniqueid=$uniqueId"
        val request = Request.Builder()
            .url(url)
            .addHeader("Cookie", sessionCookie)
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "")
                    val status = json.optString("disposition") // ANSWERED, NO ANSWER, BUSY, etc.

                    // Si el estado ya es finalizado
                    if (status == "ANSWERED") {
                        // "userfield" o "custom_field" suele almacenar los tonos DTMF recolectados en el IVR del UCM
                        val dtmf = json.optString("userfield", "")
                        return dtmf.trim()
                    }
                }
                null
            }
        } catch (e: Exception) { null }
    }
}
