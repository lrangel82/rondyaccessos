package com.larangel.rondyaccesos.models

//import Operation
//import SheetTable
import android.content.Context
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest
import com.google.api.services.sheets.v4.model.DeleteDimensionRequest
import com.google.api.services.sheets.v4.model.DimensionRange
import com.google.api.services.sheets.v4.model.Request
import com.google.api.services.sheets.v4.model.ValueRange
import com.google.api.services.sheets.v4.SheetsScopes
import com.google.api.services.sheets.v4.model.AddSheetRequest
import com.google.api.services.sheets.v4.model.SheetProperties
import com.larangel.rondyaccesos.R
import com.larangel.rondyaccesos.utils.extraerColor
import com.larangel.rondyaccesos.utils.extraerMarcaAuto
import com.larangel.rondyaccesos.utils.extraerPlaca
import com.larangel.rondyaccesos.utils.extraerTAG
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.collections.get
import kotlin.time.Duration.Companion.minutes
import kotlin.toString

class DataRawRondin(
    private val context: Context,
    private val coroutineScopeObject: CoroutineScope
) {
    private val mySettings = MySettings(context)
    private lateinit var sheetsService: Sheets
    private val TAG = "DataRawRondin"
    private val CACHE_DURATION_MS = 10 * 60 * 1000 // 10 min
    private val syncMutex = Mutex()

    // Estado en Memoria (Tu patrón original)
    private val tableStates = mutableMapOf<SheetTable, TableState>().apply {
        SheetTable.values().forEach { put(it, TableState()) }
    }
    private val activeSyncJobs = mutableSetOf<String>()

    class TableState {
        var cache: List<List<Any>>? = null
        var timestamp: Long = 0
        val forSave = mutableListOf<List<Any>>()
        val forUpdate = mutableListOf<List<Any>>()
        val forUpdateIndexes = mutableListOf<Int>()
        val forDeleteIndexes = mutableListOf<Int>()
    }

    init {
        initializeGoogleServices()
        checarPendientesAlInicio()
    }

    private fun initializeGoogleServices() {
        try {
            val datafromMemory =mySettings.getString("CREDENTIALS_GOOGLE_API","{}")
            val credentialsgoogleapi = ByteArrayInputStream(datafromMemory.toByteArray(StandardCharsets.UTF_8))
            //val serviceAccountStream = context.resources.openRawResource(R.raw.json_google_service_account)
            val credential = GoogleCredential.fromStream(credentialsgoogleapi)
                .createScoped(listOf(SheetsScopes.SPREADSHEETS))
            val requestInitializer = credential as com.google.api.client.http.HttpRequestInitializer
            this.sheetsService = Sheets.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), requestInitializer)
                .setApplicationName("RondyAccessos").build()
        } catch (e: Exception) {
            Log.e(TAG, "Error inicializando credenciales de Google: ${e.message}")
        }
    }
    private fun checarPendientesAlInicio() {
        SheetTable.values().forEach { table ->
            val s = tableStates[table]!!

            // Cargar de disco a RAM y disparar sync si hay datos (APPEND)
            val saved = mySettings.getList(table.saveKey)
            if (saved.isNotEmpty()) {
                s.forSave.addAll(saved)
                sync(table, Operation.APPEND)
            }

            // Cargar pendientes de modificación (UPDATE)
            val updated = mySettings.getList(table.updateKey)
            val updatedIndex = mySettings.getSimpleList(table.updateIdxKey).map { it.toIntOrNull() ?: 0 }
            if (updated.isNotEmpty()) {
                s.forUpdate.addAll(updated)
                s.forUpdateIndexes.addAll(updatedIndex)
                sync(table, Operation.UPDATE)
            }

            // Cargar pendientes de eliminación (DELETE)
            val deletedIndex = mySettings.getSimpleList(table.deleteIdxKey).map { it.toIntOrNull() ?: 0 }
            if (deletedIndex.isNotEmpty()) {
                s.forDeleteIndexes.addAll(deletedIndex)
                sync(table, Operation.DELETE)
            }
        }
    }
    fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val cap = cm.getNetworkCapabilities(net) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            val networkInfo = cm.activeNetworkInfo
            networkInfo != null && networkInfo.isConnected
        }
    }
    fun sonCadenasSimilares(str1: String, str2: String): Boolean {
        val s1 = str1.filter { it.isLetterOrDigit() }.uppercase()
        val s2 = str2.filter { it.isLetterOrDigit() }.uppercase()
        if (s1 == s2) return true
        if (Math.abs(s1.length - s2.length) > 1) return false

        var i = 0
        var j = 0
        var errores = 0

        while (i < s1.length && j < s2.length) {
            if (s1[i] != s2[j]) {
                errores++
                if (errores > 1) return false
                if (s1.length > s2.length) i++
                else if (s2.length > s1.length) j++
                else { i++; j++ }
            } else { i++; j++ }
        }
        if (i < s1.length || j < s2.length) errores++
        return errores <= 1
    }

    // --- SECCIÓN LECTURA Y CACHÉ INTELIGENTE ---
    suspend fun getSmartCache(table: SheetTable, forceLoad: Boolean = false, fetcher: suspend () -> List<List<Any>>): List<List<Any>> {
        val state = tableStates[table]!!
        val now = System.currentTimeMillis()
        val isConnected = isNetworkAvailable()

        if (state.cache != null && (now - state.timestamp <= CACHE_DURATION_MS || !isConnected) && !forceLoad)
            return state.cache!!

        val diskCache = mySettings.getList("${table.cacheKey}_CACHE") ?: emptyList()
        val diskTs = mySettings.getLong(table.timestampKey, 0L)
        if (diskCache.isNotEmpty() && (now - diskTs <= CACHE_DURATION_MS || !isConnected) && !forceLoad) {
            state.cache = diskCache
            state.timestamp = diskTs
            return diskCache
        }

        return if (isConnected) {
            withContext(Dispatchers.IO) {
                try {
                    val freshData = fetcher()
                    mySettings.saveList("${table.cacheKey}_CACHE", freshData as List<List<String>>)
                    mySettings.saveLong(table.timestampKey, now)
                    state.cache = freshData
                    state.timestamp = now
                    freshData
                } catch (e: Exception) {
                    Log.e(TAG, "Error leyendo datos en red (${table.cacheKey}): ${e.message}")
                    diskCache
                }
            }
        } else diskCache
    }

    // --- SECCIÓN ESCRITURA Y COLA RESILIENTE ---
    fun sync(table: SheetTable, op: Operation, data: List<String>? = null, index: Int? = -1) {
        val state = tableStates[table]!!
        val jobId = "${table.name}_${op.name}"

        // Persistencia visual inmediata (Optimistic UI)
        when (op) {
            Operation.APPEND -> data?.let {
                state.forSave.add(it)
                mySettings.saveList(table.saveKey, state.forSave as List<List<String>>)
            }
            Operation.UPDATE -> if (data != null && index != null && index >= 0) {
                state.forUpdate.add(data)
                state.forUpdateIndexes.add(index)
                mySettings.saveList(table.updateKey, state.forUpdate as List<List<String>>)
                mySettings.saveSingleList(table.updateIdxKey, state.forUpdateIndexes.map { it.toString() })
            }
            Operation.DELETE -> if (index != null && index >= 0) {
                state.forDeleteIndexes.add(index)
                mySettings.saveSingleList(table.deleteIdxKey, state.forDeleteIndexes.map { it.toString() })
            }
        }

        if (activeSyncJobs.contains(jobId)) return

        coroutineScopeObject.launch {
            activeSyncJobs.add(jobId)
            while (isActive) {
                val success = try {
                    when (op) {
                        Operation.APPEND -> executeAppend(table)
                        Operation.UPDATE -> executeUpdate(table)
                        Operation.DELETE -> false // El flujo base delega eliminaciones duras
                    }
                } catch (e: Exception) { false }

                if (success) break
                delay(5.minutes) // Reintento en background si falla la red
            }
            activeSyncJobs.remove(jobId)
        }
    }

    private suspend fun executeAppend(table: SheetTable): Boolean = withContext(Dispatchers.IO) {
        if (!isNetworkAvailable()) return@withContext false
        val state = tableStates[table]!!
        val spreadId = mySettings.getString("PARKING_SPREADSHEET_ID", "")
        if (spreadId.isEmpty()) return@withContext false
        //val rangeStr = "${table.sheetName}!${table.range}"
        val rangeStr = "${table.sheetName}!A:A"

        return@withContext try {
            syncMutex.withLock {
                if (state.forSave.isEmpty()) return@withLock true
                val body = ValueRange().setValues(state.forSave)
                sheetsService.spreadsheets().values().append(spreadId, rangeStr, body)
                    .setValueInputOption("RAW").execute()
                state.forSave.clear()
                mySettings.saveList(table.saveKey, emptyList<List<String>>())
                true
            }
        } catch (e: Exception) { false }
    }
    private suspend fun executeUpdate(table: SheetTable): Boolean = withContext(Dispatchers.IO) {
        if (!isNetworkAvailable()) return@withContext false
        val state = tableStates[table]!!
        val spreadId = mySettings.getString("PARKING_SPREADSHEET_ID", "")
        if (spreadId.isEmpty()) return@withContext false
        val iterator = state.forUpdate.indices.reversed()

        return@withContext try {
            syncMutex.withLock {
                for (i in iterator) {
                    val row = state.forUpdate[i]
                    val idx = state.forUpdateIndexes[i] + 2 // Corrección matemática de renglón
                    val range = "${table.sheetName}!A$idx:Z$idx"

                    sheetsService.spreadsheets().values().update(spreadId, range, ValueRange().setValues(listOf(row)))
                        .setValueInputOption("RAW").execute()

                    state.forUpdate.removeAt(i)
                    state.forUpdateIndexes.removeAt(i)
                }
                mySettings.saveList(table.updateKey, state.forUpdate as List<List<String>>)
                mySettings.saveSingleList(table.updateIdxKey, state.forUpdateIndexes.map { it.toString() })
                true
            }
        } catch (e: Exception) { false }
    }
    private suspend fun executeDelete(table: SheetTable): Boolean = withContext(Dispatchers.IO) {
        if (!isNetworkAvailable()) return@withContext false

        val state = tableStates[table] ?: return@withContext true
        if (state.forDeleteIndexes.isEmpty()) return@withContext true

        var spreadsheetId = mySettings.getString("PARKING_SPREADSHEET_ID", "") ?: ""
        var rangeStr = "${table.sheetName}!${table.range}".toString()

        if (table == SheetTable.PERMISOS) {
            rangeStr = table.range.toString()
            spreadsheetId = mySettings.getSimpleList("PERMISOS_SPREADSHEET_ID")[0].toString()
        }

        if (spreadsheetId.isEmpty()) return@withContext false

        try {
            // 1. Obtener el ID numérico de la hoja (SheetId) si no lo tenemos
            val sheetId = getSheetIdByName(spreadsheetId, table.sheetName) ?: return@withContext false

            // 2. Crear una lista de peticiones (Requests).
            // IMPORTANTE: Para borrar múltiples filas, debemos ordenarlas de MAYOR a MENOR
            // para que el borrado de una fila no cambie la posición de las siguientes.
            val sortedIndexes = state.forDeleteIndexes.distinct().sortedDescending()

            val requests = sortedIndexes.map { index ->
                Request().setDeleteDimension(
                    DeleteDimensionRequest().setRange(
                        DimensionRange()
                            .setSheetId(sheetId)
                            .setDimension("ROWS")
                            .setStartIndex(index - 1) // Google Sheets es 0-indexed
                            .setEndIndex(index)
                    )
                )
            }

            // 3. Ejecutar el Batch Update
            val batchRequest = BatchUpdateSpreadsheetRequest().setRequests(requests)
            sheetsService.spreadsheets().batchUpdate(spreadsheetId, batchRequest).execute()

            // 4. Limpiar datos locales tras éxito
            state.forDeleteIndexes.clear()
            mySettings.saveSingleList(table.deleteIdxKey, emptyList<String>())

            true
        } catch (e: Exception) {
            Log.e(TAG, "Error en executeDelete para ${table.sheetName}: ${e.message}")
            false
        }
    }
    private fun getSheetIdByName(spreadsheetId: String, sheetName: String): Int? {
        return try {
            val spreadsheet = sheetsService.spreadsheets().get(spreadsheetId).execute()
            if (sheetName.isNotEmpty()) {
                spreadsheet.sheets.find { it.properties.title == sheetName }?.properties?.sheetId
            }else{
                spreadsheet.sheets.first()?.properties?.sheetId
            }
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo obtener el ID de la hoja $sheetName")
            null
        }
    }
    private fun createWorkSheetNew(spreadsheetId: String,table: SheetTable){
        val targetSheetName = table.sheetName
        Log.w(TAG, "La pestaña '${targetSheetName}' no existe. Creando e inicializando estructura...")
        if (targetSheetName.isBlank()) {
            Log.e(TAG, "Error: Intento de creación abortado. El nombre de hoja para ${table.name} está vacío.")
            return
        }

        try {
            Log.w(TAG, "Iniciando aprovisionamiento de pestaña remota: '$targetSheetName'")

            // 1. Empaquetar la solicitud estructural BatchUpdate para insertar el Worksheet físico
            val addSheetRequest = Request().setAddSheet(
                AddSheetRequest().setProperties(
                    SheetProperties().setTitle(targetSheetName)
                )
            )

            val batchUpdateRequest = BatchUpdateSpreadsheetRequest()
                .setRequests(listOf(addSheetRequest))

            // Ejecución de la mutación del libro
            sheetsService.spreadsheets()
                .batchUpdate(spreadsheetId, batchUpdateRequest)
                .execute()

            // 2. Preparar el lote de inicialización de datos para la fila 1 (Cabeceras)
            val headersList = table.headers

            // Google Sheets API espera un formato List<List<Any>> para representar filas y columnas
            val valueRange = ValueRange().setValues(listOf(headersList as List<Any>))

            // Escribir en la celda inicial A1 de la pestaña recién creada
            sheetsService.spreadsheets().values()
                .update(spreadsheetId, "$targetSheetName!A1", valueRange)
                .setValueInputOption("RAW")
                .execute()

            Log.d(TAG, "Pestaña '$targetSheetName' creada e inicializada con ${headersList.size} columnas de forma exitosa.")

        } catch (e: Exception) {
            Log.e(TAG, "Fallo crítico en el despliegue automático de la tabla remota: $targetSheetName", e)
            // Lanzamos la excepción para interrumpir el flujo del SmartCache y evitar almacenar estados vacíos o corruptos
            throw e
        }
    }


    // --- NUEVO INTEGRANTE: FUNCIÓN ATÓMICA REQUERIDA POR EL WORKMANAGER ---
    suspend fun forzarVaciadoDeColasDesdeWorkerBackend(): Boolean = withContext(Dispatchers.IO) {
        if (!isNetworkAvailable()) return@withContext false
        var exitoGlobal = true

        for (table in SheetTable.values()) {
            val exitoAppend = executeAppend(table)
            val exitoUpdate = executeUpdate(table)
            if (!exitoAppend || !exitoUpdate) {
                exitoGlobal = false
            }
        }
        return@withContext exitoGlobal
    }

    // VEHICULOS
    fun getCachedVehiclesData(forceLoad: Boolean = false): List<List<Any>> = runBlocking {
        // Llamamos a la función genérica usando el Enum de VEHICULOS
        getSmartCache(SheetTable.VEHICULOS,forceLoad) {
            val allRows = mutableListOf<List<Any>>()

            // 1. Obtener Vehículos de RESIDENTES
            val stateResidentes = tableStates[SheetTable.RESIDENTES_UNIDAD]

            // 2. Aseguramos que la RAM tenga datos (Carga desde RAM -> Disco -> Red)
            if (stateResidentes?.cache == null) runBlocking { getResidentes() }
            stateResidentes?.cache?.forEach { row ->
                //tipo == automovil
                if (row[4].toString().startsWith("auto",true)){
                    val placa: String? = row.firstNotNullOfOrNull { it.toString().extraerPlaca() }
                    if (placa != null ){
                        val calle = row[2].toString()
                        val numero= row[3].toString()
                        allRows.add(listOf(placa,calle,numero))
                    }
                }
            }

//            val residentsId = mySettings.getString("PARKING_SPREADSHEET_ID", "")!!
//            val residentsSheet = SheetTable.VEHICULOS.sheetName //mySettings.getString("WS_AUTOS_REGISTRADOS", "AutosRegistrados")!!
//
//            if (residentsId.isEmpty()) throw IllegalArgumentException("No hay Sheet configurado")
//
//            //try {
//                val response = sheetsService.spreadsheets().values()
//                    .get(residentsId, "$residentsSheet!A:C") // placa, calle, numero
//                    .execute()
//                response.getValues().drop(1)?.let { allRows.addAll(it) }
//            //} catch (e: Exception) {
//            //    Log.e(TAG, "Error leyendo Residentes: ${e.message}")
//            //}

            // 2. Obtener Vehículos VISITANTES (Hojas: ingreso y salida)
            val visitorsId = mySettings.getString("REGISTRO_CARROS_SPREADSHEET_ID", "")
            if (!visitorsId.isNullOrEmpty()) {
                val visitorSheets = listOf("ingreso", "salida")
                for (sheetName in visitorSheets) {
                    //try {
                    val response = sheetsService.spreadsheets().values()
                        .get(visitorsId, "$sheetName!C:E") // placa, calle, numero
                        .execute()
                    // drop(1) para omitir los encabezados de la tabla de visitantes
                    val rows = response.getValues()?.drop(1) ?: emptyList()
                    allRows.addAll(rows)
                    //} catch (e: Exception) {
                    //    Log.e(TAG, "Error leyendo Visitantes ($sheetName): ${e.message}")
                    //}
                }
            }

            // Retornamos la lista unificada al SmartCache para que la guarde en RAM y Disco
            allRows
        }
    }
    fun getTagsCache(forceLoad: Boolean = false): List<List<Any>> = runBlocking {
        getSmartCache(SheetTable.TAGS,forceLoad) {
            val allParsedTags = mutableListOf<List<Any>>()
            val spreadsheetId = mySettings.getString("PARKING_SPREADSHEET_ID", "")!!
            val nameWS = SheetTable.TAGS.sheetName //mySettings.getString("WS_AUTOS_REGISTRADOS", "AutosRegistrados")!!
            if (spreadsheetId.isEmpty()) throw IllegalArgumentException("No hay Sheet configurado")

            // 1. Obtener Vehículos de RESIDENTES
            val stateResidentes = tableStates[SheetTable.RESIDENTES_UNIDAD]

            // 2. Aseguramos que la RAM tenga datos (Carga desde RAM -> Disco -> Red)
            if (stateResidentes?.cache == null) runBlocking { getResidentes() }
            stateResidentes?.cache?.forEach { row ->
                //tipo == automovil
                if (row[4].toString().startsWith("auto",true)){
                    val tagValue: String? = row.firstNotNullOfOrNull { it.toString().extraerTAG() }
                    val placa: String? = row.firstNotNullOfOrNull { it.toString().extraerPlaca() }
                    if (tagValue != null ){
                        val calle = row[2].toString()
                        val numero= row[3].toString()
                        allParsedTags.add(listOf(tagValue,calle,numero,placa.toString()))
                    }
                }
            }

//            //try {
//            // Obtenemos el rango A:H (Placas, Calle, Numero, Marca, Modelo, Color, Tag1, Tag2, userid)
//            val response = sheetsService.spreadsheets().values()
//                .get(spreadsheetId, "$nameWS!A:I")
//                .execute()
//
//            // Omitimos el encabezado
//            val rows = response.getValues()?.drop(1) ?: emptyList()
//
//            rows.forEach { row ->
//                // Índices: Calle(1), Numero(2), Tag1(6), Tag2(7), userid(8)
//                val calle = row.getOrNull(1)?.toString() ?: ""
//                val numero = row.getOrNull(2)?.toString() ?: ""
//
//                // Procesamos cada columna de Tag si existe y no está vacía
//                for (i in 6..7) {
//                    val tagValue = row.getOrNull(i)?.toString()
//                    if (!tagValue.isNullOrBlank()) {
//                        allParsedTags.add(listOf(tagValue, calle, numero))
//                    }
//                }
//            }
//            //} catch (e: Exception) {
//            //    Log.e(TAG, "Error procesando Tags desde Google Sheets: ${e.message}")
//            //}
//
//            // Retornamos la lista de [Tag, Calle, Numero]
            allParsedTags
        }
    }
    fun getAutoRegistrados(forceLoad: Boolean = false): List<List<Any>> = runBlocking {
        getSmartCache(SheetTable.AUTOS_REGISTRADOS, forceLoad) {
            val allRows = mutableListOf<List<Any>>()
            val stateResidentes = tableStates[SheetTable.RESIDENTES_UNIDAD]

            if (stateResidentes?.cache == null) runBlocking { getResidentes() }
            stateResidentes?.cache?.forEach { row ->
                if (row.getOrNull(4).toString().startsWith("auto", true)) {
                    val placa = row.firstNotNullOfOrNull { it.toString().extraerPlaca() }
                    val tag = row.firstNotNullOfOrNull { it.toString().extraerTAG() }
                    if (placa != null) {
                        allRows.add(listOf(
                            placa, row.getOrNull(2).toString(), row.getOrNull(3).toString(),
                            row.getOrNull(5).toString().extraerMarcaAuto() ?: "", "",
                            row.getOrNull(5).toString().extraerColor() ?: "", tag ?: "", "", row.getOrNull(0).toString()
                        ))
                    }
                }
            }
            allRows
        }
    }
    private fun _get_strclave_unidad(calle: String, numero: String): String{
        val abreviations = mapOf(
            "casaclub" to "CA",
            "acantilado" to "AC",
            "cipres" to "CI",
            "ciruelo" to "CR",
            "durazno" to "DR",
            "encino" to "EN",
            "enramada" to "ER",
            "eucalipto" to "EC",
            "guadalupe" to "GP",
            "naranjo" to "NR",
            "manzano" to "MN",
            "mezquite" to "MZ",
            "olmo" to "OL",
            "primavera" to "PR",
            "roble" to "RB",
            "administracion" to "AD",
            "prueba" to "PB"
        )

        val calleNormalizada = calle.lowercase().trim()
        val abrev = abreviations[calleNormalizada] ?: calleNormalizada.take(3).uppercase()
        val numFormateado = numero.trim().padStart(3, '0')

        return "$abrev$numFormateado"
    }
    fun addAutoRegistrados(row: List<String>): Boolean{
        val table = SheetTable.AUTOS_REGISTRADOS
        val state = tableStates[table] ?: return false
        val userid = if(row[8].toIntOrNull() != null) row[8].toInt() else LocalTime.now().toSecondOfDay() * -1
        //SET UserID
        val _row = row.toMutableList()
        _row[8] = userid.toString()

        //### CACHE VIRTUAL DE AUTOS
        // 1. Aseguramos que la RAM tenga datos (si estaba nulo, lo cargamos)
        if (state.cache == null) {
            runBlocking { getAutoRegistrados() }
        }

        // 2. Actualizar RAM (Optimistic UI: el usuario ve el cambio al instante)
        val currentCache = state.cache?.toMutableList() ?: mutableListOf()
        currentCache.add(_row)
        state.cache = currentCache
        //println("DEBUG: _row content: $_row")
        //val foo=Json.encodeToString(_row)

        // 3. Persistir el cambio visual en el caché de disco (MySettings)
        mySettings.saveList("${table.cacheKey}_CACHE", currentCache as List<List<String>>)
        mySettings.saveLong(table.timestampKey, System.currentTimeMillis())

        // 4. Salvar async
        mySettings.saveList("${table.cacheKey}_CACHE", currentCache as List<List<String>>)
        sync(table, Operation.APPEND, data = _row)
        //### FIN CACHE VIRTUAL DE AUTOS

        //###### GUARDAR EN RESIDENTES ######
        if (userid.toInt() != -987654321) {
            val strNowTime =
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            val rowResidente = listOf<Any>(
                userid.toString(),
                _get_strclave_unidad(row[1].toString(), row[2].toString()), //Clave
                row[1].toString(),                                          //calle
                row[2].toString(),                                          //numero
                "Automóvil",                                                //Tipo
                "${row[3]} ${row[4]} ${row[5]}",                            //nombre
                row[0],                                                     //telefono (placa)
                "",                                                         //email
                row[6],                                                     //celular (tag)
                "RondyApp[${strNowTime}]",                                  //notas
                "",                                                         //ciudad
                "",                                                         //estado
                "2000-01-01 00:00:00",                                      //fecha_updated_condovive
                strNowTime,                                                 //fecha_updated_app
                "1",                                                        //es_nuevo
                "0",                                                        //es_actualizado
                "0"                                                         //es_eliminado
            )
            updateResidentes(rowResidente as List<String>)
        }

        return true
    }
    fun updateAutoRegistrados(newData: List<String>): Boolean{
        val table = SheetTable.AUTOS_REGISTRADOS
        val state = tableStates[table] ?: return false

        // 1. Aseguramos que la RAM tenga datos (Carga desde RAM -> Disco -> Red)
        if (state.cache == null) runBlocking { getAutoRegistrados() }

        val currentCache = state.cache?.toMutableList() ?: mutableListOf()
        var indexFind = -1

        // 2. Búsqueda por userID
        currentCache.forEachIndexed { index, autoR ->
            if (autoR.size >= 4
                && autoR[8] == newData[8] ){
                indexFind = index
                return@forEachIndexed
            }
        }

        if (indexFind >= 0) {
            // CASO A: ACTUALIZAR EXISTENTE
            currentCache[indexFind] = newData
            state.cache = currentCache

            // Persistir en disco para acceso offline inmediato
            mySettings.saveList("${table.cacheKey}_CACHE", currentCache as List<List<String>>)
            mySettings.saveLong(table.timestampKey, System.currentTimeMillis())

            // Sincronizar Update (index + 2 por el encabezado de Google Sheets)
            sync(table, Operation.UPDATE, data = newData, index = indexFind + 2)

            //UPDATE RESIDENTE
            val strNowTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            val rowResidente = listOf<Any>(
                newData[8],                                                 //userid
                _get_strclave_unidad(newData[1].toString(), newData[2].toString()), //Clave
                newData[1].toString(),                                      //calle
                newData[2].toString(),                                      //numero
                "Automóvil",                                                //Tipo
                "${newData[3]} ${newData[4]} ${newData[5]}",                //nombre
                newData[0],                                                 //telefono (placa)
                "",                                                         //email
                newData[6],                                                 //celular (tag)
                "RondyApp[${strNowTime}]",                                  //notas
                "",                                                         //ciudad
                "",                                                         //estado
                strNowTime,                                                 //fecha_updated_condovive
                strNowTime,                                                 //fecha_updated_app
                "0",                                                        //es_nuevo
                "1",                                                        //es_actualizado
                "0"                                                         //es_eliminado
            )
            updateResidentes(rowResidente as List<String>)

        } else {
            // CASO B: ES NUEVO (APPEND)
            addAutoRegistrados(newData)
        }

        return true
    }

    //RESIDENTES
    fun getResidentes(forceLoad: Boolean = false): List<List<Any>> = runBlocking {
        getSmartCache(SheetTable.RESIDENTES_UNIDAD, forceLoad) {
            val spreadsheetId = mySettings.getString("PARKING_SPREADSHEET_ID", "")
            if (spreadsheetId.isEmpty()) throw IllegalArgumentException("No hay Sheet configurado")
            val nameWS = SheetTable.RESIDENTES_UNIDAD.sheetName

            val response = sheetsService.spreadsheets().values()
                .get(spreadsheetId, "$nameWS!${SheetTable.RESIDENTES_UNIDAD.range}")
                .execute()

            response.getValues()?.drop(1) ?: emptyList()
        }
    }
    fun updateResidentes( rowData:List<String>): Boolean{
        val table = SheetTable.RESIDENTES_UNIDAD
        val state = tableStates[table] ?: return false

        // 1. Aseguramos que la RAM tenga datos (Carga desde RAM -> Disco -> Red)
        if (state.cache == null) runBlocking { getResidentes() }

        val currentCache = state.cache?.toMutableList() ?: mutableListOf()
        var indexFind = -1

        // 2. Búsqueda por Calle(0), Número(1) y Tipo(3)
        currentCache.forEachIndexed { index, resUni ->
            if (resUni.size >= 4 &&
                resUni[0].toString().toInt() == rowData[0].toString().toInt()) { //Validando el userid
                indexFind = index
                return@forEachIndexed
            }
        }

        if (indexFind >= 0) {
            // CASO A: ACTUALIZAR EXISTENTE
            currentCache[indexFind] = rowData
            state.cache = currentCache

            // Persistir en disco para acceso offline inmediato
            mySettings.saveList("${table.cacheKey}_CACHE", currentCache as List<List<String>>)
            mySettings.saveLong(table.timestampKey, System.currentTimeMillis())

            // Sincronizar Update (index + 2 por el encabezado de Google Sheets)
            sync(table, Operation.UPDATE, data = rowData, index = indexFind + 2)
        } else {
            // CASO B: ES NUEVO (APPEND)
            currentCache.add(rowData)
            state.cache = currentCache

            mySettings.saveList("${table.cacheKey}_CACHE", currentCache as List<List<String>>)
            sync(table, Operation.APPEND, data = rowData)
        }

        return true
    }

    // Permisos
    fun getPermisosCache(forceLoad: Boolean = false): List<List<Any>> = runBlocking {
        // Usamos el Enum de PERMISOS y el SmartCache genérico
        getSmartCache(SheetTable.PERMISOS,forceLoad) {
            val allPermisos = mutableListOf<List<Any>>()

            // Obtenemos la lista de IDs de Spreadsheets desde MySettings
            val spreadsheetIds = mySettings.getSimpleList("PERMISOS_SPREADSHEET_ID") ?: emptyList<String>()
            if (spreadsheetIds.isEmpty()) throw IllegalArgumentException("No hay Sheet configurado")

            for (id in spreadsheetIds) {
                //try {
                // Consultamos el rango A:N (desde Marca temporal hasta Procesado por ROBOT)
                val response = sheetsService.spreadsheets().values()
                    .get(id, "A:N")
                    .execute()

                // Omitimos la primera fila (encabezados) de cada hoja
                val rows = response.getValues()?.drop(1) ?: emptyList()
                allPermisos.addAll(rows)

                //} catch (e: Exception) {
                //    Log.e(TAG, "Error leyendo permisos del ID: $id - ${e.message}")
                //}
            }

            // Retornamos la lista consolidada al SmartCache
            allPermisos
        }
    }
    fun getPermisosCache_DeHoy(forceLoad: Boolean = false): List<List<Any>> {
        val rows = getPermisosCache(forceLoad)
        val stringTrue = arrayOf("1", "Si", "si", "SI", "x", "X")
        if (rows.isEmpty()) return emptyList()

        val hoy = LocalDate.now()
        val esAdmin = mySettings?.getInt("ESADMIN",0)

        return rows.filter { row ->
            try {
                // Índices basados en tu Parte 6: Fecha Inicio(7), Fecha Fin(8)
                val fechaInicioStr = row.getOrNull(7)?.toString() ?: ""
                val fechaFinStr = row.getOrNull(8)?.toString() ?: ""
                val procesadoRobot = row.getOrNull(13)?.toString() ?: ""

                if ( !stringTrue.contains(procesadoRobot) && esAdmin == 1){ //Si es Admin regresar los no validados
                    //No ha sido procesado y es admin
                    true
                }
                else if (fechaInicioStr.isNotBlank() && fechaFinStr.isNotBlank() && stringTrue.contains(procesadoRobot)) {
                    //Esta dentro de las fechas y fue procesado
                    val inicio = parseLenientDate(fechaInicioStr)
                    val fin = parseLenientDate(fechaFinStr)
                    // Verificamos si hoy está dentro del rango (inclusive)
                    if(esAdmin == 1)
                        hoy <= fin
                    else
                        hoy >= inicio && hoy <= fin
                }
                else {
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error al parsear fechas en fila: $row e:${e.message}")
                false
            }
        }.reversed() // Los más recientes primero
    }
    fun updatePermisoCache(row: List<String>): Boolean{
        val _row: MutableList<String> = row as MutableList<String>
        val table = SheetTable.PERMISOS
        val state = tableStates[table] ?: return false

        // 1. Aseguramos que la RAM tenga datos (Carga desde RAM -> Disco -> Red)
        if (state.cache == null) runBlocking { getPermisosCache() }

        val currentCache = state.cache?.toMutableList() ?: mutableListOf()
        var indexFind = -1

        // 2. Búsqueda por fecha de creacion
        val pCreadp = parseLenientDateTime(row[0].toString())
        currentCache.forEachIndexed { index, permiso ->
            val _fCreado = parseLenientDateTime(permiso[0].toString())
            if (permiso.size >= 4 &&
                pCreadp == _fCreado ) {
                indexFind = index
                //Perservar valores de fecha
                _row[0] = permiso[0].toString() //MarcaTemporal
                _row[7] = permiso[7].toString() //Fecha Inicio
                _row[8] = permiso[8].toString() //Fecha Fin
                return@forEachIndexed
            }
        }

        if (indexFind >= 0) {
            // CASO A: ACTUALIZAR EXISTENTE
            currentCache[indexFind] = row as List<String>
            state.cache = currentCache

            // Persistir en disco para acceso offline inmediato
            mySettings.saveList("${table.cacheKey}_CACHE", currentCache as List<List<String>>)
            mySettings.saveLong(table.timestampKey, System.currentTimeMillis())

            // Sincronizar Update (index + 2 por el encabezado de Google Sheets)
            sync(table, Operation.UPDATE, data = row, index = indexFind + 2)
        } else {
            return false
        }

        return true
    }
    fun eliminarPermisoCache(row: List<String>): Boolean{
        val state = tableStates[SheetTable.PERMISOS] ?: return false

        var indexFind = -1
        // 1. Aseguramos que la RAM tenga datos (Carga desde RAM -> Disco -> Red)
        if (state.cache == null) runBlocking { getPermisosCache() }
        val currentCache = state.cache?.toMutableList() ?: return false

        // 2. Búsqueda por fecha de creacion
        val pCreadp = parseLenientDateTime(row[0].toString())
        currentCache.forEachIndexed { index, permiso ->
            val _fCreado = parseLenientDateTime(permiso[0].toString())
            if (permiso.size >= 4 &&
                pCreadp == _fCreado ) {
                indexFind = index
                return@forEachIndexed
            }
        }

        if (indexFind >= 0) {
            // 2. Eliminar de la RAM inmediatamente para que el usuario ya no lo vea
            currentCache.removeAt(indexFind)
            state.cache = currentCache

            // 3. Actualizar el caché de disco (MySettings) para persistir el cambio visual
            mySettings.saveList("${SheetTable.PERMISOS.cacheKey}_CACHE", currentCache as List<List<String>>)
            mySettings.saveLong(SheetTable.PERMISOS.timestampKey, System.currentTimeMillis())

            /**
             * 4. Disparar el borrado en la Nube.
             * Usamos indexFind + 1 (si no hay encabezado) o + 2 (si hay encabezado).
             */
            sync(SheetTable.PERMISOS, Operation.DELETE, index = indexFind + 2)

            return true
        }

        return false // No se encontró el registro
    }

    //Direcciones de casas y ubicacion
    fun getDomiciliosUbicacion(forceLoad: Boolean = false): List<List<Any>> = runBlocking {
        // Usamos el SmartCache genérico con el Enum correspondiente
        getSmartCache(SheetTable.DIRECCIONES,forceLoad) {
            val allDirections = mutableListOf<List<Any>>()
            val spreadsheetId = mySettings.getString("PARKING_SPREADSHEET_ID", "")!!
            if (spreadsheetId.isEmpty()) throw IllegalArgumentException("No hay Sheet configurado")

            // Intentamos obtener el nombre de la hoja desde settings o usamos el default
            val nameWS = SheetTable.DIRECCIONES.sheetName

            //try {
            // Leemos el rango configurado (ej. A:C para Calle, Número, ID)
            val response = sheetsService.spreadsheets().values()
                .get(spreadsheetId, "$nameWS!${SheetTable.DIRECCIONES.range}")
                .execute()

            // Omitimos encabezados si es necesario con .drop(1)
            val rows = response.getValues().drop(1) ?: emptyList()
            allDirections.addAll(rows)

//            } catch (e: Exception) {
//                Log.e(TAG, "Error leyendo catálogo de direcciones: ${e.message}")
//            }

            // Retornamos la lista para que SmartCache la guarde en RAM y Disco
            allDirections
        }
    }
    fun getDomiciliosSimilares(calle: String, numero: String): List<List<Any>>{
        val _calle=calle.filter { it.isLetterOrDigit() }.uppercase()
        val _numero=numero.filter { it.isLetterOrDigit() }.uppercase()
        val rows = getDomiciliosUbicacion()
        val result = mutableListOf<List<Any>>()
        run loop@{
            rows.forEach { row ->
                val _rcalle = row[0].toString().uppercase()
                val _rnumer = row[1].toString().uppercase()
                if (_rcalle == _calle && _rnumer == _numero) {
                    //Concidencia exacta
                    result.clear()
                    result.add(row)
                    return@loop
                }
                if (sonCadenasSimilares("${_rcalle}:${_rnumer}", "${_calle}:${_numero}")) {
                    result.add(row)
                }
            }
        }
        return result as List<List<Any>>
    }

    //Por Revisar registros
    fun getPorRevisar(forceLoad: Boolean = false): List<List<Any>> = runBlocking {
        // Usamos el SmartCache genérico con el Enum POR_REVISAR
        getSmartCache(SheetTable.POR_REVISAR,forceLoad) {
            val allPending = mutableListOf<List<Any>>()
            val spreadsheetId = mySettings.getString("PARKING_SPREADSHEET_ID", "")!!
            if (spreadsheetId.isEmpty()) throw IllegalArgumentException("No hay Sheet configurado")

            // Intentamos obtener el nombre de la hoja (o usamos el default "PorRevisar")
            val nameWS = SheetTable.POR_REVISAR.sheetName

            //try {
            // Leemos el rango A:G (basado en tu Parte 2: calle, número, tiempo, slotkey, veridicado, lat, lon)
            val response = sheetsService.spreadsheets().values()
                .get(spreadsheetId, "$nameWS!${SheetTable.POR_REVISAR.range}")
                .execute()

            // Obtenemos los valores. Omitimos encabezados con .drop(1) si tu hoja los tiene
            val rows = response.getValues().drop(1) ?: emptyList()

            // Agregamos a la lista maestra
            allPending.addAll(rows)

//            } catch (e: Exception) {
//                Log.e(TAG, "Error leyendo lista de Por Revisar: ${e.message}")
//            }

            // Retornamos para que SmartCache lo guarde en RAM y Disco
            allPending
        }
    }
    fun getPorRevisar_20horas(forceLoad: Boolean = false): MutableList<List<Any>>?{
        val rows = getPorRevisar(forceLoad)
        val date20HoursAgo = LocalDateTime.now().minusHours(20)
        val porRevisar = rows?.filter { parseLenientDateTime(it[2].toString()) >= date20HoursAgo }
            ?.reversed()
        if (porRevisar!=null && porRevisar.isNotEmpty())
            return porRevisar as MutableList<List<Any>>?
        return mutableListOf<List<Any>>()
    }
    fun eliminarPorRevisar(calle: String, numero: String, slotKey: String): Boolean {
        val state = tableStates[SheetTable.POR_REVISAR] ?: return false

        if (state.cache == null) runBlocking { getPorRevisar() }
        val currentCache = state.cache?.toMutableList() ?: return false

        var indexFind = -1

        // 1. Buscar el índice en la lista actual (RAM)
        currentCache.forEachIndexed { index, row ->
            // Basado en tu estructura: Calle(0), Número(1), SlotKey(3)
            if (row.size >= 4 &&
                row[0].toString() == calle &&
                row[1].toString() == numero &&
                row[3].toString() == slotKey) {
                indexFind = index
                return@forEachIndexed
            }
        }

        if (indexFind >= 0) {
            // 2. Eliminar de la RAM inmediatamente para que el usuario ya no lo vea
            currentCache.removeAt(indexFind)
            state.cache = currentCache

            // 3. Actualizar el caché de disco (MySettings) para persistir el cambio visual
            mySettings.saveList("${SheetTable.POR_REVISAR.cacheKey}_CACHE", currentCache as List<List<String>>)
            mySettings.saveLong(SheetTable.POR_REVISAR.timestampKey, System.currentTimeMillis())

            /**
             * 4. Disparar el borrado en la Nube.
             * Usamos indexFind + 1 (si no hay encabezado) o + 2 (si hay encabezado).
             */
            sync(SheetTable.POR_REVISAR, Operation.DELETE, index = indexFind + 2)

            return true
        }

        return false // No se encontró el registro
    }
    fun _addPorRevisarCache(row: List<String>): Boolean {
        val table = SheetTable.POR_REVISAR
        val state = tableStates[table] ?: return false

        // 1. Aseguramos que la RAM tenga datos (si estaba nulo, lo cargamos)
        if (state.cache == null) {
            runBlocking { getPorRevisar() }
        }

        // 2. Actualizar RAM (Optimistic UI: el usuario ve el cambio al instante)
        val currentCache = state.cache?.toMutableList() ?: mutableListOf()
        currentCache.add(row)
        state.cache = currentCache

        // 3. Persistir el cambio visual en el caché de disco (MySettings)
        mySettings.saveList("${table.cacheKey}_CACHE", currentCache as List<List<String>>)
        mySettings.saveLong(table.timestampKey, System.currentTimeMillis())

        /**
         * 4. Sincronización con Google Sheets.
         * Llamamos a sync() con Operation.APPEND.
         * Internamente, esto gestiona la cola de reintentos y la red.
         */
        sync(table, Operation.APPEND, data = row)

        return true
    }

    //Lugares de VISITAS
    fun getParkingSlots(forceLoad: Boolean = false): List<List<Any>> = runBlocking {
        // Usamos el SmartCache genérico con el Enum correspondiente
        getSmartCache(SheetTable.PARKING_SLOTS,forceLoad) {
            val allSlots = mutableListOf<List<Any>>()
            val spreadsheetId = mySettings.getString("PARKING_SPREADSHEET_ID", "")
            if (spreadsheetId.isEmpty()) throw IllegalArgumentException("No hay Sheet configurado")

            // Buscamos el nombre de la hoja en settings o usamos el default "ParkingSlots"
            val nameWS = SheetTable.PARKING_SLOTS.sheetName

            //try {
            // Consultamos el rango A:E de la hoja de Google Sheets
            val response = sheetsService.spreadsheets().values()
                .get(spreadsheetId, "$nameWS!${SheetTable.PARKING_SLOTS.range}")
                .execute()

            val rows = response.getValues().drop(1) ?: emptyList()
            allSlots.addAll(rows)

//            } catch (e: Exception) {
//                Log.e(TAG, "Error leyendo Parking Slots desde la red: ${e.message}")
//            }

            // Retornamos la lista para que SmartCache la guarde en RAM y Disco local
            allSlots
        }
    }
    fun _addPartkingSlotCache(row: List<String>): Boolean{
        val table = SheetTable.PARKING_SLOTS
        val state = tableStates[table] ?: return false
        // 1. Load
        if (state.cache == null) { runBlocking { getParkingSlots() } }

        // 2. Actualizar RAM (Optimistic UI: el usuario ve el cambio al instante)
        val currentCache = state.cache?.toMutableList() ?: mutableListOf()
        currentCache.add(row)
        state.cache = currentCache

        // 3. Persistir el cambio visual en el caché de disco (MySettings)
        mySettings.saveList("${table.cacheKey}_CACHE", currentCache as List<List<String>>)
        mySettings.saveLong(table.timestampKey, System.currentTimeMillis())

        //4. Sincronización con Google Sheets.
        sync(table, Operation.APPEND, data = row)

        return true
    }

    //AutoEventos
    fun getAutosEventos(forceLoad: Boolean = false): List<List<Any>> = runBlocking {
        // Usamos el SmartCache genérico con el Enum correspondiente
        getSmartCache(SheetTable.AUTOS_EVENTOS,forceLoad) {
            val allEvents = mutableListOf<List<Any>>()
            val spreadsheetId = mySettings.getString("PARKING_SPREADSHEET_ID", "")!!
            if (spreadsheetId.isEmpty()) throw IllegalArgumentException("No hay Sheet configurado")

            // Buscamos el nombre de la hoja en settings o usamos el default
            val nameWS = SheetTable.AUTOS_EVENTOS.sheetName

            //try {
            // Consultamos el rango A:E (o el que tengas configurado)
            val response = sheetsService.spreadsheets().values()
                .get(spreadsheetId, "$nameWS!${SheetTable.AUTOS_EVENTOS.range}")
                .execute()

            val rows = response.getValues().drop(1) ?: emptyList()
            allEvents.addAll(rows)

//            } catch (e: Exception) {
//                Log.e(TAG, "Error leyendo Autos Eventos desde la red: ${e.message}")
//            }

            // Retornamos la lista para que SmartCache la guarde en RAM y Disco
            allEvents
        }
    }
    fun getAutosEventos_6horas(forceLoad: Boolean = false ): List<List<Any>>{
        val rows = getAutosEventos(forceLoad)
        val date6HoursAgo = LocalDateTime.now().minusHours(6)
        val plateEvents = rows.filter {
            parseLenientDateTime(it[2].toString()) >= date6HoursAgo
        }
            .reversed()
        return plateEvents ?: emptyList()
    }
    fun _addAutosEventCache(row: List<String>): Boolean{

        val table = SheetTable.AUTOS_EVENTOS
        val state = tableStates[table] ?: return false
        // 1. Load
        if (state.cache == null) { runBlocking { getAutosEventos()} }

        // 2. Actualizar RAM (Optimistic UI: el usuario ve el cambio al instante)
        val currentCache = state.cache?.toMutableList() ?: mutableListOf()
        currentCache.add(row)
        state.cache = currentCache

        // 3. Persistir el cambio visual en el caché de disco (MySettings)
        mySettings.saveList("${table.cacheKey}_CACHE", currentCache as List<List<String>>)
        mySettings.saveLong(table.timestampKey, System.currentTimeMillis())

        //4. Sincronización con Google Sheets.
        sync(table, Operation.APPEND, data = row)

        return true
    }
    fun eliminarAutosEventoCache(row: List<String>): Boolean{
        val state = tableStates[SheetTable.AUTOS_EVENTOS] ?: return false

        var indexFind = -1
        // 1. Aseguramos que la RAM tenga datos (Carga desde RAM -> Disco -> Red)
        if (state.cache == null) runBlocking { getAutosEventos() }
        val currentCache = state.cache?.toMutableList() ?: return false

        // 2. Búsqueda por fecha de creacion
        val pCreadp = parseLenientDateTime(row[2].toString())
        currentCache.forEachIndexed { index, evento ->
            val _fCreado = parseLenientDateTime(evento[2].toString())
            if (evento.size >= 4 &&
                pCreadp == _fCreado ) {
                indexFind = index
                return@forEachIndexed
            }
        }

        if (indexFind >= 0) {
            // 2. Eliminar de la RAM inmediatamente para que el usuario ya no lo vea
            currentCache.removeAt(indexFind)
            state.cache = currentCache

            // 3. Actualizar el caché de disco (MySettings) para persistir el cambio visual
            mySettings.saveList("${SheetTable.AUTOS_EVENTOS.cacheKey}_CACHE", currentCache as List<List<String>>)
            mySettings.saveLong(SheetTable.AUTOS_EVENTOS.timestampKey, System.currentTimeMillis())

            /**
             * 4. Disparar el borrado en la Nube.
             * Usamos indexFind + 1 (si no hay encabezado) o + 2 (si hay encabezado).
             */
            sync(SheetTable.AUTOS_EVENTOS, Operation.DELETE, index = indexFind + 2)

            return true
        }

        return false // No se encontró el registro
    }

    //IncidenciasEventos
    fun getIncidenciasConfig(forceLoad: Boolean = false): List<List<Any>> = runBlocking {
        // Usamos el SmartCache genérico con el Enum correspondiente
        getSmartCache(SheetTable.INCIDENCIAS_CONFIG,forceLoad) {
            val allRows = mutableListOf<List<Any>>()
            val spreadsheetId = mySettings.getString("PARKING_SPREADSHEET_ID", "")
            if (spreadsheetId.isEmpty()) throw IllegalArgumentException("No hay Sheet configurado")

            // Buscamos el nombre de la hoja en settings o usamos el default
            val nameWS = SheetTable.INCIDENCIAS_CONFIG.sheetName

            //try {
            // Consultamos el rango A:E (o el que tengas configurado)
            val response = sheetsService.spreadsheets().values()
                .get(spreadsheetId, "$nameWS!${SheetTable.INCIDENCIAS_CONFIG.range}")
                .execute()

            val rows = response.getValues().drop(1) ?: emptyList()
            allRows.addAll(rows)

//            } catch (e: Exception) {
//                Log.e(TAG, "Error leyendo ${nameWS} config desde la red: ${e.message}")
//            }

            // Retornamos la lista para que SmartCache la guarde en RAM y Disco
            allRows
        }
    }
    fun getIncidenciasEventos(forceLoad: Boolean = false): List<List<Any>> = runBlocking {
        // Usamos el SmartCache genérico con el Enum correspondiente
        getSmartCache(SheetTable.INCIDENCIAS,forceLoad) {
            val allRows = mutableListOf<List<Any>>()
            val spreadsheetId = mySettings.getString("PARKING_SPREADSHEET_ID", "")
            if (spreadsheetId.isEmpty()) throw IllegalArgumentException("No hay Sheet configurado")

            // Buscamos el nombre de la hoja en settings o usamos el default
            val nameWS = SheetTable.INCIDENCIAS.sheetName

            //try {
            // Consultamos el rango A:E (o el que tengas configurado)
            val response = sheetsService.spreadsheets().values()
                .get(spreadsheetId, "$nameWS!${SheetTable.INCIDENCIAS.range}")
                .execute()

            val rows = response.getValues().drop(1) ?: emptyList()
            allRows.addAll(rows)

//            } catch (e: Exception) {
//                Log.e(TAG, "Error leyendo ${nameWS} config desde la red: ${e.message}")
//            }

            // Retornamos la lista para que SmartCache la guarde en RAM y Disco
            allRows
        }
    }
    fun getIncidenciasEventosDesde(fechaDay: LocalDate = LocalDate.now()): List<List<Any>>{
        val rows = getIncidenciasEventos()
        return rows.filter{ parseLenientDate(it[2].toString()) >= fechaDay}
    }
    fun getIncidenciasEventosTipo(Tipo: String, fechaDay: LocalDate = LocalDate.now()): List<List<Any>>{
        val rows = getIncidenciasEventos()
        //val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val IncidenciaEvents = rows.filter {
            parseLenientDate(it[2].toString()) == fechaDay
                    && it[4].toString().uppercase() == Tipo.uppercase() }
        return IncidenciaEvents ?: emptyList()

    }
    fun addIncidenciaEvento(row: List<String>): Boolean{

        val table = SheetTable.INCIDENCIAS
        val state = tableStates[table] ?: return false
        // 1. Load
        if (state.cache == null) { runBlocking { getIncidenciasEventos()} }

        // 2. Actualizar RAM (Optimistic UI: el usuario ve el cambio al instante)
        val currentCache = state.cache?.toMutableList() ?: mutableListOf()
        currentCache.add(row)
        state.cache = currentCache

        // 3. Persistir el cambio visual en el caché de disco (MySettings)
        mySettings.saveList("${table.cacheKey}_CACHE", currentCache as List<List<String>>)
        mySettings.saveLong(table.timestampKey, System.currentTimeMillis())

        //4. Sincronización con Google Sheets.
        sync(table, Operation.APPEND, data = row)

        return true
    }

    //DomicilioWarnings
    fun getDomicilioWarnings(forceLoad: Boolean = false): List<List<Any>> = runBlocking {
        // Usamos el SmartCache genérico con el Enum correspondiente
        getSmartCache(SheetTable.DOMICILIO_WARNINGS,forceLoad) {
            val allRows = mutableListOf<List<Any>>()
            val spreadsheetId = mySettings.getString("PARKING_SPREADSHEET_ID", "")
            if (spreadsheetId.isEmpty()) throw IllegalArgumentException("No hay Sheet configurado")

            // Buscamos el nombre de la hoja en settings o usamos el default
            val nameWS = SheetTable.DOMICILIO_WARNINGS.sheetName

            //try {
            // Consultamos el rango A:E (o el que tengas configurado)
            val response = sheetsService.spreadsheets().values()
                .get(spreadsheetId, "$nameWS!${SheetTable.DOMICILIO_WARNINGS.range}")
                .execute()

            val rows = response.getValues().drop(1) ?: emptyList()
            allRows.addAll(rows)

//            } catch (e: Exception) {
//                Log.e(TAG, "Error leyendo ${nameWS} config desde la red: ${e.message}")
//            }

            // Retornamos la lista para que SmartCache la guarde en RAM y Disco
            allRows
        }
    }
    fun addDomicilioWarning(row: List<String>): Boolean{

        val table = SheetTable.DOMICILIO_WARNINGS
        val state = tableStates[table] ?: return false
        // 1. Load
        if (state.cache == null) { runBlocking { getDomicilioWarnings()} }

        // 2. Actualizar RAM (Optimistic UI: el usuario ve el cambio al instante)
        val currentCache = state.cache?.toMutableList() ?: mutableListOf()
        currentCache.add(row)
        state.cache = currentCache

        // 3. Persistir el cambio visual en el caché de disco (MySettings)
        mySettings.saveList(table.cacheKey, currentCache as List<List<String>>)
        mySettings.saveLong(table.timestampKey, System.currentTimeMillis())

        //4. Sincronización con Google Sheets.
        sync(table, Operation.APPEND, data = row)

        return true
    }
    fun updateDomicilioWarning(row: List<String>): Int {
        val table = SheetTable.DOMICILIO_WARNINGS
        val state = tableStates[table] ?: return 0

        // 1. Aseguramos que la RAM tenga datos (Carga desde RAM -> Disco -> Red)
        if (state.cache == null) runBlocking { getDomicilioWarnings() }

        val currentCache = state.cache?.toMutableList() ?: mutableListOf()
        var indexFind = -1
        var currentCount = 0

        // 2. Búsqueda por Calle(0), Número(1) y Tipo(3)
        currentCache.forEachIndexed { index, domWarn ->
            if (domWarn.size >= 4 &&
                domWarn[0].toString() == row[0].toString() &&
                domWarn[1].toString() == row[1].toString() &&
                domWarn[3].toString() == row[3].toString()) {
                indexFind = index
                currentCount = domWarn[2].toString().toIntOrNull() ?: 0
                return@forEachIndexed
            }
        }

        val newCount = currentCount + 1
        val newData = listOf(
            row[0].toString(),            // Calle
            row[1].toString(),            // Numero
            newCount.toString(),          // Nuevo Contador
            row[3].toString()             // Tipo (ej. Ruido, Obstrucción)
        )

        if (indexFind >= 0) {
            // CASO A: ACTUALIZAR EXISTENTE
            currentCache[indexFind] = newData
            state.cache = currentCache

            // Persistir en disco para acceso offline inmediato
            mySettings.saveList("${table.cacheKey}_CACHE", currentCache as List<List<String>>)
            mySettings.saveLong(table.timestampKey, System.currentTimeMillis())

            // Sincronizar Update (index + 2 por el encabezado de Google Sheets)
            sync(table, Operation.UPDATE, data = newData, index = indexFind + 2)
        } else {
            // CASO B: ES NUEVO (APPEND)
            currentCache.add(newData)
            state.cache = currentCache

            mySettings.saveList("${table.cacheKey}_CACHE", currentCache as List<List<String>>)
            sync(table, Operation.APPEND, data = newData)
        }

        return newCount
    }

    //BITACORA_ACCESOS
    fun getBitacoraAccesos(forceLoad: Boolean = false, createIfNotExist: Boolean = false): List<List<Any>> = runBlocking {
        // Usamos el SmartCache genérico con el Enum correspondiente
        // FechaCreado, FechaIngreso, Placa, Calle, Numero, Tipo, Conductor, Desc, Foto1, Foto2, qr_data, fechaSalida,status
        getSmartCache(SheetTable.BITACORA_ACCESOS,forceLoad) {
            val allRows = mutableListOf<List<Any>>()
            val spreadsheetId = mySettings.getString("REGISTRO_CARROS_SPREADSHEET_ID", "")
            if (spreadsheetId.isEmpty()) throw IllegalArgumentException("No hay Sheet configurado")

            // Buscamos el nombre de la hoja en settings o usamos el default
            val nameWS = SheetTable.BITACORA_ACCESOS.sheetName
            val idsheet = getSheetIdByName(spreadsheetId,nameWS)
            if (idsheet == null && createIfNotExist)
                createWorkSheetNew(spreadsheetId,SheetTable.BITACORA_ACCESOS)

            // Consultamos el rango A:E (o el que tengas configurado)
            val response = sheetsService.spreadsheets().values()
                .get(spreadsheetId, "$nameWS!${SheetTable.BITACORA_ACCESOS.range}")
                .execute()

            val rows = response.getValues().drop(1) ?: emptyList()
            allRows.addAll(rows)

            // Retornamos la lista para que SmartCache la guarde en RAM y Disco
            allRows
        }
    }
    fun addBitacoraAccesos(acceso: AccesoBitacora): Boolean{
        val table = SheetTable.BITACORA_ACCESOS
        val state = tableStates[table] ?: return false
        // 1. Load
        if (state.cache == null) { runBlocking { getBitacoraAccesos()} }

        val row = acceso.toSheetRow()

        // 2. Actualizar RAM (Optimistic UI: el usuario ve el cambio al instante)
        val currentCache = state.cache?.toMutableList() ?: mutableListOf()
        currentCache.add(row)
        state.cache = currentCache

        // 3. Persistir el cambio visual en el caché de disco (MySettings)
        mySettings.saveList("${table.cacheKey}_CACHE", currentCache as List<List<String>>)
        mySettings.saveLong(table.timestampKey, System.currentTimeMillis())

        //4. Sincronización con Google Sheets.
        sync(table, Operation.APPEND, data = row)

        return true
    }
    fun getBitacoraUltimoAcceso(placa: String): List<Any>{
        val rows = getBitacoraAccesos()
        var result = mutableListOf<Any>()
        if (rows.isNotEmpty()) {
            run loop@{
                rows.forEach { row ->
                    if (row.size > 4) {
                        val _placa = row[2].toString().uppercase()
                        if (_placa == placa.uppercase()) {
                            //Concidencia exacta
                            result = row as MutableList<Any>
                            return@loop
                        }
                    }
                }
            }
        }
        return result as List<Any>
    }
    fun actulizarSalidaAccesos(placaTarget:String, fechaSalidaNueva: String): Boolean {
        if (placaTarget.isBlank()) return false

        val table = SheetTable.BITACORA_ACCESOS
        val state = tableStates[table] ?: return false

        try {
            // 1. Aseguramos que la RAM tenga datos (Carga desde RAM -> Disco -> Red)
            if (state.cache == null) runBlocking { getBitacoraAccesos() }

            val currentCache = state.cache?.toMutableList() ?: mutableListOf()
            var indexFind = -1

            // 2. Búsqueda por ID
            var rowData: MutableList<Any> = mutableListOf()
            currentCache.forEachIndexed { index, bitacora ->
                if (bitacora.size >= 4 &&
                    bitacora[2].toString() == placaTarget &&
                    bitacora[11].toString().isEmpty()
                ) {
                    indexFind = index
                    rowData = bitacora.toMutableList()
                    return@forEachIndexed
                }
            }

            //Vencer
            if (indexFind >= 0) {
                rowData[11] = fechaSalidaNueva
                rowData[12] = "salida CORREGIDA POR APP"
                currentCache[indexFind] = rowData
                state.cache = currentCache

                // Persistir en disco para acceso offline inmediato
                mySettings.saveList("${table.cacheKey}_CACHE", currentCache as List<List<String>>)
                mySettings.saveLong(table.timestampKey, System.currentTimeMillis())

                // Sincronizar Update (index + 2 por el encabezado de Google Sheets)
                sync(table, Operation.UPDATE, data = rowData as List<String>, index = indexFind + 2)
            }
        }catch (e: Exception) {
            Log.e("DataRawRondin", "Excepcion critica durante el cierre automatico de reentradas por placa.", e)
            return false
        }
        return true

    }

    //Whatsapp Telefonos
    fun getWhatsappTelefonos(forceLoad: Boolean = false, createIfNotExist: Boolean = false): List<List<Any>> = runBlocking {
        // Usamos el Enum de TELEFONOS_WHATSAPP y el SmartCache genérico
        getSmartCache(SheetTable.TELEFONOS_WHATSAPP,forceLoad) {
            val sheetName = SheetTable.TELEFONOS_WHATSAPP.sheetName
            val range = SheetTable.TELEFONOS_WHATSAPP.range
            val allRows = mutableListOf<List<Any>>()

            // Obtenemos la lista de IDs de Spreadsheets desde MySettings
            val spreadsheetIds = mySettings.getSimpleList("WHATSAPP_SPREADSHEET_ID") ?: emptyList<String>()
            if (spreadsheetIds.isEmpty()) throw IllegalArgumentException("No hay Sheet configurado")

            for (id in spreadsheetIds) {
                val idsheet = getSheetIdByName(id,sheetName)
                if (idsheet == null && createIfNotExist)
                    createWorkSheetNew(id,SheetTable.TELEFONOS_WHATSAPP)

                // Consultamos el rango A:N (desde Marca temporal hasta Procesado por ROBOT)
                val response = sheetsService.spreadsheets().values()
                    .get(id, "${sheetName}!${range}")
                    .execute()

                // Omitimos la primera fila (encabezados) de cada hoja
                val rows = response.getValues()?.drop(1) ?: emptyList()
                allRows.addAll(rows)

            }

            // Retornamos la lista consolidada al SmartCache
            allRows
        }
    }
    fun getWhatsappTelefonosDomicilio(calle: String, numero: String): List<List<Any>>{
        val _calle=calle.filter { it.isLetterOrDigit() }.uppercase()
        val _numero=numero.filter { it.isLetterOrDigit() }.uppercase()
        val rows = getWhatsappTelefonos()
        val result = mutableListOf<List<Any>>()
        run loop@{
            rows.forEach { row ->
                val _rcalle = row[0].toString().uppercase()
                val _rnumer = row[1].toString().uppercase()
                if (_rcalle == _calle && _rnumer == _numero) {
                    //Concidencia exacta
                    result.clear()
                    result.add(row)
                    return@loop
                }
            }
        }
        return result as List<List<Any>>
    }

    //Morosos
    fun getMorosos(forceLoad: Boolean = false, createIfNotExist: Boolean = false): List<List<Any>> = runBlocking {
        // Usamos el Enum de DOMICILIOS_MOROSOS y el SmartCache genérico
        getSmartCache(SheetTable.DOMICILIOS_MOROSOS,forceLoad) {
            val sheetName = SheetTable.DOMICILIOS_MOROSOS.sheetName
            val range = SheetTable.DOMICILIOS_MOROSOS.range
            val allRows = mutableListOf<List<Any>>()

            // Obtenemos la lista de IDs de Spreadsheets desde MySettings
            val spreadsheetIds = mySettings.getSimpleList("SALDOS_SPREADSHEET_ID") ?: emptyList<String>()
            if (spreadsheetIds.isEmpty()) throw IllegalArgumentException("No hay Sheet configurado")

            for (id in spreadsheetIds) {
                val idsheet = getSheetIdByName(id,sheetName)
                if (idsheet == null && createIfNotExist)
                    createWorkSheetNew(id,SheetTable.DOMICILIOS_MOROSOS)

                // Consultamos el rango A:N (desde Marca temporal hasta Procesado por ROBOT)
                val response = sheetsService.spreadsheets().values()
                    .get(id, "${sheetName}!${range}")
                    .execute()

                // Omitimos la primera fila (encabezados) de cada hoja
                val rows = response.getValues()?.drop(1) ?: emptyList()
                allRows.addAll(rows)

            }

            // Retornamos la lista consolidada al SmartCache
            allRows
        }
    }
    fun esDomicilioMoroso(calle: String, numero: String): Boolean{
        val _calle=calle.filter { it.isLetterOrDigit() }.uppercase()
        val _numero=numero.filter { it.isLetterOrDigit() }.uppercase()
        val limite_moroso = mySettings.getString("LIMITE_MOROSO","0.0").replace("$", "").replace(" ", "").replace(",", "").toFloatOrNull() ?: 0.0f
        val rows = getMorosos()
        val result = mutableListOf<List<Any>>()
        rows.forEach { row ->
                val _rcalle = row[1].toString().uppercase()
                val _rnumer = row[2].toString().uppercase()
                val _deuda = row[3].toString().replace("$", "").replace(" ", "").replace(",", "").toFloatOrNull() ?: 0.0f
                if (_rcalle == _calle && _rnumer == _numero && _deuda > limite_moroso) {
                    //Concidencia exacta y es moroso
                    result.clear()
                    result.add(row)
                    return true
                }
            }
        return false
    }

    //Excepciones
    fun getExcepciones(forceLoad: Boolean = false, createIfNotExist: Boolean = false): List<List<Any>> = runBlocking {
        // Usamos el Enum de EXCEPCIONES y el SmartCache genérico
        getSmartCache(SheetTable.EXCEPCIONES,forceLoad) {
            val sheetName = SheetTable.EXCEPCIONES.sheetName
            val range = SheetTable.EXCEPCIONES.range
            val allRows = mutableListOf<List<Any>>()

            // Obtenemos la lista de IDs de Spreadsheets desde MySettings
            val spreadsheetIds = mySettings.getString("REGISTRO_CARROS_SPREADSHEET_ID","") //?: emptyList<String>()
            if (spreadsheetIds.isEmpty()) throw IllegalArgumentException("No hay Sheet configurado")

            //for (id in spreadsheetIds) {
                val idsheet = getSheetIdByName(spreadsheetIds,sheetName)
                if (idsheet == null && createIfNotExist)
                    createWorkSheetNew(spreadsheetIds,SheetTable.EXCEPCIONES)

                // Consultamos el rango A:N (desde Marca temporal hasta Procesado por ROBOT)
                val response = sheetsService.spreadsheets().values()
                    .get(spreadsheetIds, "${sheetName}!${range}")
                    .execute()

                // Omitimos la primera fila (encabezados) de cada hoja
                val rows = response.getValues()?.drop(1) ?: emptyList()
                allRows.addAll(rows)

            //}

            // Retornamos la lista consolidada al SmartCache
            allRows
        }
    }
    fun getExcepcionesDomicilio(calle: String, numero: String): List<List<Any>>{
        val _calle=calle.filter { it.isLetterOrDigit() }.uppercase()
        val _numero=numero.filter { it.isLetterOrDigit() }.uppercase()
        val rows = getExcepciones()
        val result = mutableListOf<List<Any>>()
        run loop@{
            rows.forEach { row ->
                val _rcalle = row[1].toString().uppercase()
                val _rnumer = row[2].toString().uppercase()
                if (_rcalle == _calle && _rnumer == _numero) {
                    //Concidencia exacta
                    result.clear()
                    result.add(row)
                    return@loop
                }
            }
        }
        return result as List<List<Any>>
    }
    fun vencerExcepcion(id:String){
        val table = SheetTable.EXCEPCIONES
        val state = tableStates[table] ?: return

        // 1. Aseguramos que la RAM tenga datos (Carga desde RAM -> Disco -> Red)
        if (state.cache == null) runBlocking { getExcepciones() }

        val currentCache = state.cache?.toMutableList() ?: mutableListOf()
        var indexFind = -1

        // 2. Búsqueda por ID
        var rowData: MutableList<Any> = mutableListOf()
        currentCache.forEachIndexed { index, excep ->
            if (excep.size >= 4 &&
                excep[0].toString() == id) {
                indexFind = index
                rowData=excep.toMutableList()
                return@forEachIndexed
            }
        }

        //Vencer
        if (indexFind >= 0) {
            rowData[7] = LocalDate.now().minusDays(1).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            rowData[8] = LocalDate.now().minusDays(1).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            currentCache[indexFind] = rowData
            state.cache = currentCache

            // Persistir en disco para acceso offline inmediato
            mySettings.saveList("${table.cacheKey}_CACHE", currentCache as List<List<String>>)
            mySettings.saveLong(table.timestampKey, System.currentTimeMillis())

            // Sincronizar Update (index + 2 por el encabezado de Google Sheets)
            sync(table, Operation.UPDATE, data = rowData as List<String>, index = indexFind + 2)
        }

        return
    }

    // --- PARSEADORES DE FECHA TOLERANTES (Tu lógica de la Parte 1) ---
    fun parseLenientDateTime(dateTimeString: String): LocalDateTime {
        val formats = listOf("d/MM/yyyy H:mm:ss", "yyyy-MM-dd HH:mm:ss", "dd/MM/yyyy HH:mm:ss")
            .map { DateTimeFormatter.ofPattern(it) }
        for (format in formats) {
            try { return LocalDateTime.parse(dateTimeString, format) } catch (e: Exception) {}
        }
        return LocalDateTime.MIN
    }
    private fun parseLenientDate(dateTimeString: String): LocalDate {
        val formats = listOf(
            "d/MM/yyyy",
            "yyyy/MM/dd",
            "yyyy-MM-dd",
            "dd-MM-yyyy",
            "dd/MM/yyyy",
            "MM-dd-yyyy",
            "MM/dd/yyyy",
            "M/dd/yyyy",
            "yyyyMMdd"
        ).map { DateTimeFormatter.ofPattern(it) }

        for (format in formats) {
            try {
                return LocalDate.parse(dateTimeString, format)
            } catch (e: Exception) {
                // Try the next format if parsing fails
            }
        }
        return LocalDate.MIN // Return null if no format matches
    }
}