package com.larangel.rondyaccesos.models.network

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.larangel.rondyaccesos.models.MySettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

// Data Transfer Objects (DTO) para los Payloads de Render
@Serializable
data class ValidarVisitaRequest(
    var telefono: String,
    val calle: String,
    val numero: String,
    val placas: String,
    val nombre: String,
    val tiporegistro: String,
    var quien_valido: String? = null,
    var respuesta: String? = null
)
@Serializable
data class ValidarVisitaIVRRequest(
    var telefono: String,
    val calle: String,
    val numero: String,
    val conductor: String,
    val motivo: String
)

@Serializable
data class ValidarVisitaResponse(
    val last_actividad: String?,
    val fecha_ultima_actualizacion: String? // String ISO-8601 UTC
)
@Serializable
data class ValidarVisitaIVRResponse(
    val last_actividad: String?,
    val fecha_ultima_actualizacion: String?, // String ISO-8601 UTC
    val resultado: Int?,
    val telefono: String?
)

interface BotCasetaApiService {
    @POST("validar_visita")
    suspend fun validarVisita(
        @Header("Authorization") token: String,
        @Body request: ValidarVisitaRequest
    ): Response<ValidarVisitaResponse>

    @POST("informar_respuesta_visita")
    suspend fun informarRespuestaVisita(
        @Header("Authorization") token: String,
        @Body request: ValidarVisitaRequest
    ): Response<Unit>

    @POST("ivr_validar")
    suspend fun validarVisitaIVR(
        @Header("Authorization") token: String,
        @Body request: ValidarVisitaIVRRequest
    ): Response<ValidarVisitaIVRResponse>

    companion object {
        fun create(mySettings: MySettings): BotCasetaApiService {
            // Extrae la URL del servidor Render o local desde MySettings
            val baseUrl = mySettings.getString("API_BOTCASETA","https://botguadalupeinn.onrender.com")

            // 2. Configura el motor de JSON (ignora campos nuevos o desconocidos del servidor para evitar crashes)
            val jsonConfig = Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
            }
            val contentType = "application/json".toMediaType()

            return Retrofit.Builder()
                .baseUrl(baseUrl)
                .addConverterFactory(jsonConfig.asConverterFactory(contentType))
                .build()
                .create(BotCasetaApiService::class.java)
        }
    }
}

@Serializable
sealed class WhatsappAuthStatus {
    object Idle : WhatsappAuthStatus()
    data class Solicitando(val segundos: Int, val nombre: String, val calle: String, val numero: String) : WhatsappAuthStatus()
    object Autorizado : WhatsappAuthStatus()
    object Denegado : WhatsappAuthStatus()
    object Timeout : WhatsappAuthStatus()
    data class Alerta(val msg:String): WhatsappAuthStatus()
    data class Info(val msg:String): WhatsappAuthStatus()
    data class Error(val msg: String) : WhatsappAuthStatus()
}