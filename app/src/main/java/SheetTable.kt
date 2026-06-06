package com.larangel.rondyaccesos.models

import com.larangel.rondyaccesos.models.MySettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.Serializable

enum class SheetTable(
    var sheetName: String,
    val cacheKey: String,
    val range: String = "A:Z",
    val headers: List<String>
) {
    PARKING_SLOTS("ParkingSlots", "parkingSlots", "A:C", listOf("Latitud", "Longitud", "ParkingKeySlot")), //
    AUTOS_EVENTOS("AutosEventos", "autosEventos", "A:E", listOf("placa", "date", "time", "localPhotoPath", "ParkingSlotKey")), //
    INCIDENCIAS("IncidenciaEventos", "incidenciaEventos", "A:G", listOf("calle", "numero", "date", "time", "Tipo", "localPhotoPath", "descripcion")), //
    INCIDENCIAS_CONFIG("IncidenciaConfig", "incidenciaConfig", "A:D", listOf("key", "textoButton", "maxWarning", "descLegal")),//
    POR_REVISAR("PorRevisar", "porRevisar", "A:G", listOf("calle","numero","fecha","slotkey","verificado","lat","lon")),
    MULTAS("MultasGeneradas", "MultaGenerada", "A:E", listOf("Fecha", "Calle",	"Numero",	"Placa", "PerkingSlot - (Fecha)")), //
    DOMICILIO_WARNINGS("DomicilioWarnings", "DomicilioWarnings", "A:D", listOf("Calle","Numero","ContadorWarnings","Tipo")),
    VEHICULOS("AutosRegistrados", "VEHICLE", "A:C", listOf("Placas","Calle","Numero","Marca","Modelo","Color","Tag","Tag2","userid")),
    TAGS("AutosRegistrados", "TAGS", "A:H", listOf("Placas","Calle","Numero","Marca","Modelo","Color","Tag","Tag2","userid")),
    PERMISOS("", "PERMISOS", "A:N", listOf("Marca temporal","Calle","Numero de casa","Nombre de quien solicita el permiso",	"Correo electrónico", "Permiso para:","Tipo Permiso (nota: si es renta o venta del inmueble indique en la descripcion el telefono a comunicarse)",	"Fecha Inicio del permiso",	"Fecha Fin del permiso","Descripción y/o trabajos a realizar	Nombre de la(s) persona(s) a Ingresar",	"Aprobado","Motivo Denegado","Procesado por ROBOT")),
    DIRECCIONES("DomicilioUbicacion", "directions", "A:F", listOf("calle", "numero", "latitud", "longitud","ext","fijo")),  //
    AUTOS_REGISTRADOS("AutosRegistrados", "autosRegistrados", "A:I", listOf("Placas","Calle","Numero","Marca","Modelo","Color","Tag","Tag2","userid")), //placa,calle,numero,marca,modelo,color,tag
    RESIDENTES_UNIDAD("ResidentesUnidad", "residentesUnidad", "A:Q", listOf("userid","clave","calle","numero","tipo","nombre","telefono","email","celular","notas","ciudad","estado","fecha_updated_condovive","fecha_updated_app","es_nuevo","es_actualizado","es_eliminado")),
    ALARMAS_RONDIN("AlarmasRondin","alarmasRondin","A:B", listOf("Hora","Nombre")),


    // --- NUEVAS TABLAS PARA EL FLUJO RONDY ACCESOS ---
    TIPO_ACCESOS("tipoAccesos","TipoAccesos","A:H",listOf("name","excepcionesDinamicas","variosDomicilios","calleDefault","numeroDefault","autorizadoPorCaseta","EsEmergencia","RequierePermisoAdmon")),
    BITACORA_ACCESOS("ingreso", "BitacoraAccesos", "A:M",listOf("FechaCreado", "FechaIngreso", "Placa", "Calle", "Numero", "Tipo", "Conductor", "Desc", "Foto1", "Foto2", "qr_data", "fechaSalida","status")),     //
    EXCEPCIONES("Excepciones", "ExcepcionesDom", "A:L", listOf("id","calle","numero","conductor","placas","descripcion","status_vs_descripcion","fechainicio","fechafin","fecha_creado","status","coto")),     // Calle, Numero, TipoExcepcion, ValidoDesde, ValidoHasta, Notas
    DOMICILIOS_MOROSOS("saldos", "DomiciliosMorosos", "A:E", listOf("ID","Calle","Numero","Deuda","Fecha")),
    TERRAZA_RESERVAS("CasaClub", "terrazaReservas", "A:E", listOf("fecha","contador_ingresos","direccion_responsable","telefono_responsable","qr_data")),    // Coto/Terraza, Fecha, Hora, Evento, QrCode
    PLACAS_PROHIBIDAS("PlacasProhibidas", "placasProhibidas", "A:E", listOf("ID","Fecha_Creado","Placa","Razon_Bloqueo","Coto")),
    TELEFONOS_WHATSAPP("telefonos", "WhatsappTelefonos", "A:E", listOf("calle","numero","telefono","coto","nombre")),
    QRS("qrs","QRsGenerados","A:H", listOf("md5","calle","numero","nombre","placas","telefono_creador","fecha_creado","vencido"));

    val saveKey get() = "CACHE_forSave_$cacheKey"
    val updateKey get() = "CACHE_forUpdate_$cacheKey"
    val updateIdxKey get() = "CACHE_forUpdate_${cacheKey}Index"
    val deleteIdxKey get() = "CACHE_forDelete_${cacheKey}Index"
    val timestampKey get() = "${cacheKey}_CACHE_TIMESTAMP"

    companion object {
        fun initializeAll(settings: MySettings?) {
            if (settings == null) return

            val configKeys = mapOf(
                VEHICULOS to "WS_AUTOS_REGISTRADOS",
                TAGS to "WS_AUTOS_REGISTRADOS",
                DIRECCIONES to "WS_DOMICILIOS_UBICACION",
                AUTOS_REGISTRADOS to "WS_AUTOS_REGISTRADOS",
                RESIDENTES_UNIDAD to "WS_RESIDENTES_UNIDAD",
                ALARMAS_RONDIN to "WS_ALARMAS_RONDIN",
                // Mapeos del Condominio Rondy Accesos
                EXCEPCIONES to "WS_EXCEPCIONES",
                DOMICILIOS_MOROSOS to "WS_DOMICILIOS_MOROSOS",
                TERRAZA_RESERVAS to "WS_TERRAZA_RESERVAS",
                PLACAS_PROHIBIDAS to "WS_PLACAS_PROHIBIDAS"
            )

            configKeys.forEach { (entry, settingKey) ->
                // Intenta leer desde SharedPreferences, si no usa el fallback por defecto
                entry.sheetName = settings.getString(settingKey, entry.sheetName)
            }
        }
    }
}

enum class Operation { APPEND, UPDATE, DELETE }

// Modelado del Estado de Roles de la App
enum class AppMode { INACTIVO, VIGILANTE, ADMINISTRADOR }

enum class SateliteMode { NONE, CASETA, INGRESO_VEHICULAR, INGRESO_PEATONAL, SALIDA_VEHICULAR, SALIDA_PEATONAL }

enum class CaptureStep {
    SELECCION_MOTIVO,
    SELECCION_CALLE,
    SELECCION_NUMERO,
    CONFRMAR_DOMICILIO,
    CAPTURA_NOMBRE,
    CAPTURA_PLACA,
    PROCESANDO_AUTORIZACION,
    PREGUNTA_OTRA_DIRECCION
}

@Serializable
data class TipoAccesos(
    val name: String,
    val excepcionesDinamicas: Boolean,
    val variosDomicilios: Boolean,
    val calleDefault: String,
    val numeroDefault: String,
    val autorizadoPorCaseta: Boolean,
    val EsEmergencia: Boolean,
    val RequierePermisoAdmon: Boolean
    ){
    constructor(sheetRow: List<String>) : this(
        name = sheetRow[0].toString(),
        excepcionesDinamicas = "SI 1 TRUE VERDADERO".contains(sheetRow[1].toString().uppercase()),
        variosDomicilios = "SI 1 TRUE VERDADERO".contains(sheetRow[2].toString().uppercase()),
        calleDefault = sheetRow[3].toString(),
        numeroDefault = sheetRow[4].toString(),
        autorizadoPorCaseta = "SI 1 TRUE VERDADERO".contains(sheetRow[5].toString().uppercase()),
        EsEmergencia = "SI 1 TRUE VERDADERO".contains(sheetRow[6].toString().uppercase()),
        RequierePermisoAdmon = "SI 1 TRUE VERDADERO".contains(sheetRow[7].toString().uppercase()),
    )
    //listOf("name","excepcionesDinamicas","variosDomicilios","calleDefault","numeroDefault","autorizadoPorCaseta","EsEmergencia"))
    // Convierte el objeto de negocio en una lista plana de Any/String compatible con Google Sheets API
    fun toSheetRow(): List<String> {
        return listOf(
            name,
            if(excepcionesDinamicas) "1" else "0",
            if(variosDomicilios) "1" else "0",
            calleDefault.trim(),
            numeroDefault.trim(),
            if(autorizadoPorCaseta) "1" else "0",
            if(EsEmergencia) "1" else "0",
            if(RequierePermisoAdmon) "1" else "0"
        )
    }
}
