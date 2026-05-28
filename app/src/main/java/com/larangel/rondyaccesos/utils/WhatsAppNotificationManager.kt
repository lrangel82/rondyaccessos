package com.larangel.rondyaccesos.utils

import android.util.Log
import com.larangel.rondyaccesos.models.AccesoBitacora
import com.larangel.rondyaccesos.models.MySettings
import com.larangel.rondyaccesos.models.network.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URL

object WhatsAppNotificationManager {
    private val client = OkHttpClient()
    private val jsonConfig = Json { ignoreUnknownKeys = true }
    private val mediaTypeJson = "application/json".toMediaType()

    /**
     * Primary Asynchronous Orchestrator entry point.
     * Evaluates transaction states and fires template + image packages sequentially.
     */
    suspend fun despacharNotificacionesDomicilio(
        telefonos: List<String>,
        acceso: AccesoBitacora,
        mySettings: MySettings
    ) = withContext(Dispatchers.IO) {
        if (telefonos.isEmpty()) {
            Log.d("WhatsAppNotify", "No targets registered for destination address.")
            return@withContext
        }

        // Extract credentials from active disk storage variables
        val tokenMeta = mySettings.getString("WHATSAPP_TOKEN","")
        val phoneIdMeta = mySettings.getString("WHATSAPP_PHONE_ID","")

        val domicilioFormateado = "${acceso.calle} #${acceso.numero}"
        val posiblesDenegados: List<String> = listOf("NO","MOROSO","NIEGA ACCESO")
        val esDenegado = posiblesDenegados.any { palabra ->
            acceso.status.contains(palabra, ignoreCase = true)
        }
        val esAutorizado = !esDenegado

        // 1. Dispatch corresponding textual template layout
        if (esAutorizado) {
            sendMessageIngreso(
                toNumbers = telefonos,
                tipo = acceso.tipo,
                conductor = acceso.conductor,
                placa = acceso.placa,
                domicilio = domicilioFormateado,
                token = tokenMeta,
                phoneId = phoneIdMeta
            )
        } else {
            denegarAcceso(
                toNumbers = telefonos,
                conductor = acceso.conductor,
                placa = acceso.placa,
                domicilio = domicilioFormateado,
                motivo = acceso.descripcion,
                token = tokenMeta,
                phoneId = phoneIdMeta
            )
        }

        // 2. Dispatch Camera Evidence package if references contain valid cloud resource links
        if (acceso.foto1Url.startsWith("http")) {
            sendImagen(telefonos, acceso.foto1Url, tokenMeta, phoneIdMeta)
        }
        if (acceso.foto2Url.startsWith("http")) {
            sendImagen(telefonos, acceso.foto2Url, tokenMeta, phoneIdMeta)
        }
    }

    private suspend fun sendMessageIngreso(
        toNumbers: List<String>,
        tipo: String,
        conductor: String,
        placa: String,
        domicilio: String,
        token: String,
        phoneId: String
    ) {
        val urlStr = "https://graph.facebook.com/v23.0/${phoneId}/messages"

        for (number in toNumbers) {
            try {
                val payload = WhatsAppTemplateRequest(
                    to = number,
                    template = WhatsAppTemplate(
                        name = "ingreso_tipo",
                        components = listOf(
                            WhatsAppComponent(
                                type = "header",
                                parameters = listOf(WhatsAppTextParam(parameter_name = "tipo", text = tipo))
                            ),
                            WhatsAppComponent(
                                type = "body",
                                parameters = listOf(
                                    WhatsAppTextParam(parameter_name = "conductor", text = conductor),
                                    WhatsAppTextParam(parameter_name = "placa", text = placa),
                                    WhatsAppTextParam(parameter_name = "domicilio", text = domicilio)
                                )
                            )
                        )
                    )
                )

                val requestBody = jsonConfig.encodeToString(payload).toRequestBody(mediaTypeJson)
                val request = Request.Builder()
                    .url(urlStr)
                    .addHeader("Authorization", "Bearer $token")
                    .post(requestBody)
                    .build()

                client.newCall(request).execute().use { response ->
                    Log.d("WhatsAppNotify", "Template entry response for $number: ${response.code}")
                }
            } catch (e: Exception) {
                Log.e("WhatsAppNotify", "Error firing admission template to $number", e)
            }
        }
    }

    private suspend fun denegarAcceso(
        toNumbers: List<String>,
        conductor: String,
        placa: String,
        domicilio: String,
        motivo: String,
        token: String,
        phoneId: String
    ) {
        val urlStr = "https://graph.facebook.com/v23.0/${phoneId}/messages"

        for (number in toNumbers) {
            try {
                val payload = WhatsAppTemplateRequest(
                    to = number,
                    template = WhatsAppTemplate(
                        name = "adevertencia_acceso",
                        components = listOf(
                            WhatsAppComponent(
                                type = "body",
                                parameters = listOf(
                                    WhatsAppTextParam(parameter_name = "conductor", text = conductor),
                                    WhatsAppTextParam(parameter_name = "placa", text = placa),
                                    WhatsAppTextParam(parameter_name = "domicilio", text = domicilio),
                                    WhatsAppTextParam(parameter_name = "motivo", text = motivo)
                                )
                            )
                        )
                    )
                )

                val requestBody = jsonConfig.encodeToString(payload).toRequestBody(mediaTypeJson)
                val request = Request.Builder()
                    .url(urlStr)
                    .addHeader("Authorization", "Bearer $token")
                    .post(requestBody)
                    .build()

                client.newCall(request).execute().use { response ->
                    Log.d("WhatsAppNotify", "Template denial response for $number: ${response.code}")
                }
            } catch (e: Exception) {
                Log.e("WhatsAppNotify", "Error firing restriction template to $number", e)
            }
        }
    }

    private suspend fun sendImagen(
        toNumbers: List<String>,
        imageUrl: String,
        token: String,
        phoneId: String
    ) {
        try {
            // Fetch Meta internal binary token matching python lookup loop
            val mediaId = uploadImagenUrlToMeta(imageUrl, token, phoneId)
            if (mediaId.isNullOrBlank()) {
                Log.e("WhatsAppNotify", "Aborting upload. Meta Handle ID generation failed.")
                return
            }

            val urlStr = "https://graph.facebook.com/v23.0/${phoneId}/messages"

            for (number in toNumbers) {
                try {
                    // Match python exact format layout using the 'whatsapp:+' routing header prefix
                    val destinationPhone = "whatsapp:+$number"

                    val payload = WhatsAppImageRequest(
                        to = destinationPhone,
                        image = WhatsAppImageRef(id = mediaId)
                    )

                    val requestBody = jsonConfig.encodeToString(payload).toRequestBody(mediaTypeJson)
                    val request = Request.Builder()
                        .url(urlStr)
                        .addHeader("Authorization", "Bearer $token")
                        .post(requestBody)
                        .build()

                    client.newCall(request).execute().use { response ->
                        Log.d("WhatsAppNotify", "Image push transaction code: ${response.code}")
                    }
                } catch (e: Exception) {
                    Log.e("WhatsAppNotify", "Failed broadcasting graphic segment row to $number", e)
                }
            }
        } catch (e: Exception) {
            Log.e("WhatsAppNotify", "Error processing media container attachment pipeline", e)
        }
    }

    /**
     * Bridges URL binaries to fetch a clean WhatsApp platform index reference
     */
    private suspend fun uploadImagenUrlToMeta(imageUrl: String, token: String, phoneId: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                // Read fresh image array binaries from temporary ephemeral cloud bucket
                val imageBytes = URL(imageUrl).readBytes()
                val uploadUrl = "https://graph.facebook.com/v23.0/${phoneId}/media"

                // Create multi-part matching graph requirements
                val requestBody = okhttp3.MultipartBody.Builder()
                    .setType(okhttp3.MultipartBody.FORM)
                    .addFormDataPart("messaging_product", "whatsapp")
                    .addFormDataPart(
                        "file",
                        "evidence.jpg",
                        imageBytes.toRequestBody("image/jpeg".toMediaType())
                    )
                    .build()

                val request = Request.Builder()
                    .url(uploadUrl)
                    .addHeader("Authorization", "Bearer $token")
                    .post(requestBody)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful && response.body != null) {
                        val responseString = response.body!!.string()
                        val mediaObject = jsonConfig.decodeFromString<MetaMediaUploadResponse>(responseString)
                        return@withContext mediaObject.id
                    }
                    Log.e("WhatsAppNotify", "Meta Media Engine rejected payload frame: ${response.code}")
                    return@withContext null
                }
            } catch (e: Exception) {
                Log.e("WhatsAppNotify", "Meta media indexing pipeline structural exception.", e)
                return@withContext null
            }
        }
    }
}