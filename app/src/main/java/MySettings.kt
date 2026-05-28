package com.larangel.rondyaccesos.models

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.util.Xml
import androidx.core.content.edit
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.xmlpull.v1.XmlPullParser
import com.larangel.rondyaccesos.models.com.larangel.rondyaccesos.utils.S3XmlParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.StringReader
import java.time.LocalDate
import java.util.Properties
import okhttp3.OkHttpClient
import okhttp3.Request

class MySettings(private val context: Context) {

    private val sharedPreferences: SharedPreferences by lazy {
        context.getSharedPreferences("rondy_prefs_v2", Context.MODE_PRIVATE)
    }

    suspend fun fetchAndProcessS3Config(bucketName: String, regionStr: String, targetHKey: String): Boolean = withContext(Dispatchers.IO) {
//        //Verificar si ya procesamos esto hoy
//        val numDayValidado = getInt("DIA_VALIDADO_CODIGO",0)
//        // Si ya validamos el día de hoy con éxito, omitimos la descarga de red y retornamos true
//        if (numDayValidado == LocalDate.now().dayOfMonth && getInt("APP_ACTIVADA", 0) == 1) {
//            return@withContext true
//        }

        val regex = Regex("configCasetaApp/config\\.ini_.*[0-9.]+")
        val bucketUrl = "https://$bucketName.s3.$regionStr.amazonaws.com"
        val client = OkHttpClient()
        saveInt("APP_ACTIVADA",0)

        var isSuccessfullyActivated = false

        try {
            val request = Request.Builder().url(bucketUrl).build()
            client.newCall(request).execute().use { response ->
                val xmlBody = response.body?.string() ?: ""

                if (!response.isSuccessful) throw Exception("Error al obtener lista S3: ${response.code}")

                // Usamos nuestro parser nativo de Android
                val filesFoundKey = S3XmlParser.parseS3XmlForMatchingKey(xmlBody, regex)

                for (fileKey in filesFoundKey) {
                    val fileUrl = if (bucketUrl.endsWith("/")) "$bucketUrl$fileKey" else "$bucketUrl/$fileKey"
                    val fileRequest = Request.Builder().url(fileUrl).build()

                    client.newCall(fileRequest).execute().use { fileResponse ->
                        val iniContent = fileResponse.body?.string() ?: ""

                        val properties = Properties()
                        properties.load(StringReader(iniContent))
                        val hKeyVal = properties.getProperty("hkeyseguridad")

                        if (hKeyVal == targetHKey) {
                            isSuccessfullyActivated = true
                            cleanPreferenceS3Config()
                            saveToPreferences(properties)
                            saveInt("DIA_VALIDADO_CODIGO", LocalDate.now().dayOfMonth)
                            Log.d("ConfigS3", "Configuración de S3 validada y guardada exitosamente.")
                            return@use
                        } else {
                            Log.e("ConfigS3", "HKey inválido para el archivo: $fileKey")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ConfigS3", "Error descargando o procesando desde S3: ${e.message}")
        }
        return@withContext isSuccessfullyActivated
    }
    private fun saveToPreferences(props: Properties) {
        saveInt("APP_ACTIVADA",1)
        with(sharedPreferences.edit()) {
            val APP_NAME                        = if (props.getProperty("appname").length > 0 ) props.getProperty("appname") else "Version Gratuita"
            val POSTFIX_SHEETNAME                = props.getProperty("postfix_sheetname") ?: ""
            val REGISTRO_CARROS_SPREADSHEET_ID  = props.getProperty("googlesheet_registro_carros_id") ?: ""
            val PARKING_SPREADSHEET_ID          = props.getProperty("googlesheet_parking_id") ?: ""
            val PERMISOS_SPREADSHEET_ID         = props.getProperty("googlesheet_permisos_ids") ?: "[]"
            val WHATSAPP_SPREADSHEET_ID         = props.getProperty("googlesheet_telefonos_whatsapp") ?: "[]"
            val SALDOS_SPREADSHEET_ID          = props.getProperty("googlesheet_saldos_ids") ?: "[]"
            val COTO                            = if(props.getProperty("apptype") == "admon1") "coto1" else "coto2"
            val WS_AUTOS_REGISTRADOS            = props.getProperty("worksheet_autos_registrados") + "$POSTFIX_SHEETNAME"
            val WS_DOMICILIOS_UBICACION         = props.getProperty("worksheet_domicilios") + "$POSTFIX_SHEETNAME"
            val WS_POR_REVISAR                  = props.getProperty("worksheet_porrevisar") + "$POSTFIX_SHEETNAME"
            val WS_PARKING_SLOTS                = props.getProperty("worksheet_porkingslots") + "$POSTFIX_SHEETNAME"
            val WS_AUTOS_EVENTOS                = props.getProperty("worksheet_autos_eventos") + "$POSTFIX_SHEETNAME"
            val WS_INCIDENCIAS_CONFIG           = props.getProperty("worksheet_incidencias_config") + "$POSTFIX_SHEETNAME"
            val WS_INCIDENCIAS_EVENTOS          = props.getProperty("worksheet_incidencias_eventos") + "$POSTFIX_SHEETNAME"
            val WS_MULTAS_GENERADAS             = props.getProperty("worksheet_multas") + "$POSTFIX_SHEETNAME"
            val WS_DOMICILIO_WARNINGS           = props.getProperty("worksheet_domicilio_warnings") + "$POSTFIX_SHEETNAME"
            val WS_RESIDENTES_UNIDAD            = props.getProperty("worksheet_residentes") + "$POSTFIX_SHEETNAME"
            val WS_ALARMAS_RONDIN               = props.getProperty("worksheet_alarmas_rondin") + "$POSTFIX_SHEETNAME"
            val WS_DOMICILIOS_MOROSOS           = props.getProperty("worksheet_name_saldos") + "$POSTFIX_SHEETNAME"
            val GEMINI_API_KEY                  = props.getProperty("gemini_api_key")
            val CREDENTIALS_GOOGLE_API          = props.getProperty("credentialsgoogleapi")
            val TOKEN_API_BOTCASETA             = props.getProperty("token_api_botcaseta")
            val API_BOTCASETA                   = props.getProperty("api_botcaseta")
            val LIMITE_MOROSO                   = props.getProperty("limite_moroso")
            val WHATSAPP_PHONE_ID               = props.getProperty("phone_id")
            val WHATSAPP_TOKEN                  = props.getProperty("token")

            putString("APP_NAME", APP_NAME)
            putString("POSTFIX_SHEETNAME", POSTFIX_SHEETNAME)
            putString("REGISTRO_CARROS_SPREADSHEET_ID", REGISTRO_CARROS_SPREADSHEET_ID)
            putString("PARKING_SPREADSHEET_ID", PARKING_SPREADSHEET_ID)
            putString("PERMISOS_SPREADSHEET_ID", PERMISOS_SPREADSHEET_ID)
            putString("WHATSAPP_SPREADSHEET_ID", WHATSAPP_SPREADSHEET_ID)
            putString("SALDOS_SPREADSHEET_ID", SALDOS_SPREADSHEET_ID)
            putString("COTO", COTO)
            putString("WS_AUTOS_REGISTRADOS", WS_AUTOS_REGISTRADOS)
            putString("WS_DOMICILIOS_UBICACION", WS_DOMICILIOS_UBICACION)
            putString("WS_POR_REVISAR", WS_POR_REVISAR)
            putString("WS_PARKING_SLOTS", WS_PARKING_SLOTS)
            putString("WS_AUTOS_EVENTOS", WS_AUTOS_EVENTOS)
            putString("WS_INCIDENCIAS_CONFIG", WS_INCIDENCIAS_CONFIG)
            putString("WS_INCIDENCIAS_EVENTOS", WS_INCIDENCIAS_EVENTOS)
            putString("WS_MULTAS_GENERADAS", WS_MULTAS_GENERADAS)
            putString("WS_DOMICILIO_WARNINGS", WS_DOMICILIO_WARNINGS)
            putString("WS_RESIDENTES_UNIDAD", WS_RESIDENTES_UNIDAD)
            putString("WS_ALARMAS_RONDIN", WS_ALARMAS_RONDIN)
            putString("WS_DOMICILIOS_MOROSOS", WS_DOMICILIOS_MOROSOS)
            putString("GEMINI_API_KEY", GEMINI_API_KEY)
            putString("CREDENTIALS_GOOGLE_API", CREDENTIALS_GOOGLE_API)
            putString("TOKEN_API_BOTCASETA", TOKEN_API_BOTCASETA)
            putString("API_BOTCASETA", API_BOTCASETA)
            putString("LIMITE_MOROSO", LIMITE_MOROSO)
            putString("WHATSAPP_PHONE_ID", WHATSAPP_PHONE_ID)
            putString("WHATSAPP_TOKEN", WHATSAPP_TOKEN)


            //CLEAN CACHE TIME
            SheetTable.values().forEach{table ->
                putLong(table.timestampKey,0)
            }

            //IMAGENES
            putString("IMAGEN_LOGO_PNG",props.getProperty("imagen_logo_png"))


            //Validar ADMIN para PERMISOS
            val jsondata = Json.decodeFromString<List<String>>(PERMISOS_SPREADSHEET_ID)
            putInt("ESADMIN", 0)
            if (jsondata.size >= 1) {
                val sheet_permisos_id = jsondata[0].toString()
                val pwdPermisos = getString("PASSWORD_PERMISOS", "").toString()
                if (pwdPermisos.length > 3 && sheet_permisos_id.startsWith(pwdPermisos)) {
                    putInt("ESADMIN", 1)
                }else {
                    putString("PASSWORD_PERMISOS", " ") //limpar passwordd
                }
            }
            apply()
        }
    }
    fun cleanPreferenceS3Config(){
        with(sharedPreferences.edit()) {
            putInt("APP_ACTIVADA", 0)
            putInt("ESADMIN",0)
            putString("REGISTRO_CARROS_SPREADSHEET_ID", "")
            putString("PARKING_SPREADSHEET_ID", "")
            putString("PERMISOS_SPREADSHEET_ID", "[]")
            putString("WHATSAPP_SPREADSHEET_ID", "[]")
            putString("SALDOS_SPREADSHEET_ID", "[]")
            //putString("COTO", COTO)
            putString("WS_AUTOS_REGISTRADOS", "")
            putString("WS_DOMICILIOS_UBICACION", "")
            putString("WS_POR_REVISAR", "")
            putString("WS_PARKING_SLOTS", "")
            putString("WS_AUTOS_EVENTOS", "")
            putString("WS_INCIDENCIAS_CONFIG", "")
            putString("WS_INCIDENCIAS_EVENTOS", "")
            putString("WS_MULTAS_GENERADAS", "")
            putString("WS_DOMICILIO_WARNINGS", "")
            putString("WS_RESIDENTES_UNIDAD", "")
            putString("WS_ALARMAS_RONDIN", "")
            putString("WS_DOMICILIOS_MOROSOS", "")
            putString("GEMINI_API_KEY", "")
            putString("CREDENTIALS_GOOGLE_API","")
            putString("TOKEN_API_BOTCASETA", "")
            putString("API_BOTCASETA", "")
            putString("LIMITE_MOROSO", "0.0")
            putString("WHATSAPP_PHONE_ID", "")
            putString("WHATSAPP_TOKEN", "")

            //CLEAN CACHE
            SheetTable.values().forEach{table ->
                putLong(table.timestampKey,0)
                saveList(table.saveKey, listOf<List<String>>())
                saveList(table.updateKey, listOf<List<String>>())
                saveList(table.updateIdxKey, listOf<List<String>>())
                saveList(table.deleteIdxKey, listOf<List<String>>())
                saveList("${table.cacheKey}_CACHE", listOf<List<String>>())
            }

            //IMAGENES
            putString("IMAGEN_LOGO_PNG","")

            apply()
        }
    }
    private fun parseS3XmlForMatchingKey(xml: String, regex: Regex): MutableList<String>? {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        var eventType = parser.eventType
        var currentTag: String? = null

        var listResult: MutableList<String> = mutableListOf()
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                currentTag = parser.name
            } else if (eventType == XmlPullParser.TEXT && currentTag == "Key") {
                val key = parser.text
                if (key.matches(regex)){
                    listResult.add( key )
                }
            }
            eventType = parser.next()
        }
        return listResult
    }
//    fun saveListCheckPoint(key: String, ListaCheckP: List<CheckPoint>){
//        val jsonString = Json.encodeToString(ListaCheckP)
//        sharedPreferences.edit() { putString(key, jsonString) }
//    }
//    fun getListCheckPoint(key: String): List<CheckPoint> {
//        val jsonString=sharedPreferences.getString(key, "[]") ?: "[]"
//        val objectList = Json.decodeFromString<List<CheckPoint>>(jsonString)
//        return objectList
//    }

    fun saveList(key: String, DataList: List<List<String>>){
        val jsonString = Json.encodeToString(DataList)
        sharedPreferences.edit() { putString(key, jsonString) }
    }
    fun saveSingleList(key: String, DataList: List<String>){
        val jsonString = Json.encodeToString(DataList)
        sharedPreferences.edit() { putString(key, jsonString) }
    }
    fun getList(key: String): List<List<String>>{
        val jsonString=sharedPreferences.getString(key, "[]") ?: "[]"
        val objectList = Json.decodeFromString<List<List<String>>>(jsonString)
        return objectList
    }
    fun getSimpleList(key: String): List<String>{
        val jsonString=sharedPreferences.getString(key, "[]") ?: "[]"
        val objectList = Json.decodeFromString<List<String>>(jsonString)
        return objectList
    }
    fun getJson(key: String): Json{
        val jsonString=sharedPreferences.getString(key, "{}") ?: "{}"
        return Json.decodeFromString(jsonString)
    }

    fun saveLong(key: String, value: Long){
        sharedPreferences.edit() { putLong(key, value) }
    }
    fun getLong(key: String, defaultValue: Long): Long{
        return sharedPreferences.getLong(key, defaultValue) ?: defaultValue
    }

    fun saveString(key: String, value: String) {
        sharedPreferences.edit(commit = true) { putString(key, value) }
    }

    fun getString(key: String, defaultValue: String): String {
        return sharedPreferences.getString(key, defaultValue) ?: defaultValue
    }

    fun saveBoolean(key: String, value: Boolean) {
        sharedPreferences.edit() { putBoolean(key, value) }
    }

    fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return sharedPreferences.getBoolean(key, defaultValue)
    }

    fun saveInt(key: String, value: Int) {
        sharedPreferences.edit() { putInt(key, value) }
    }

    fun getInt(key: String, defaultValue: Int): Int {
        return sharedPreferences.getInt(key, defaultValue)
    }

    fun clearAllPreferences() {
        sharedPreferences.edit() { clear() }
    }

    fun removePreference(key: String) {
        sharedPreferences.edit() { remove(key) }
    }
}