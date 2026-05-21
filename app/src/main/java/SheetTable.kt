package com.larangel.rondyaccesos.models

import com.larangel.rondyaccesos.models.MySettings

enum class SheetTable(
    var sheetName: String,
    val cacheKey: String,
    val range: String = "A:Z"
) {
    PARKING_SLOTS("ParkingSlots", "parkingSlots", "A:C"), //Latitud, Longitud, ParkingKeySlot
    AUTOS_EVENTOS("AutosEventos", "autosEventos", "A:E"), // placa, date, time, localPhotoPath, ParkingSlotKey
    INCIDENCIAS("IncidenciaEventos", "incidenciaEventos", "A:G"), // calle, numero, date, time, Tipo, localPhotoPath, descripcion
    INCIDENCIAS_CONFIG("IncidenciaConfig", "incidenciaConfig", "A:D"),// key, textoButton, maxWarning, descLegal
    POR_REVISAR("PorRevisar", "porRevisar", "A:G"),
    MULTAS("MultasGeneradas", "MultaGenerada", "A:E"), // Fecha, Calle,	Numero,	Placa, PerkingSlot - (Fecha)
    DOMICILIO_WARNINGS("DomicilioWarnings", "DomicilioWarnings", "A:D"),
    VEHICULOS("AutosRegistrados", "VEHICLE", "A:C"),
    TAGS("AutosRegistrados", "TAGS", "A:H"),
    PERMISOS("", "PERMISOS", "A:N"),
    DIRECCIONES("Direcciones", "directions", "A:D"),  // calle, numero, latitud, longitud
    AUTOS_REGISTRADOS("AutosRegistrados", "autosRegistrados", "A:I"), //placa,calle,numero,marca,modelo,color,tag
    RESIDENTES_UNIDAD("ResidentesUnidad", "residentesUnidad", "A:Q"),
    ALARMAS_RONDIN("AlarmasRondin","alarmasRondin","A:B"), //userid,clave,calle,numero,tipo,nombre,telefono,email,celular,notas,ciudad,estado,fecha_updated_condovive,fecha_updated_app,es_nuevo,es_actualizado,es_eliminado


    // --- NUEVAS TABLAS PARA EL FLUJO RONDY ACCESOS ---
    BITACORA_ACCESOS("BitacoraAccesos", "bitacoraAccesos", "A:K"),     // Fecha, Hora, Placa, Calle, Numero, Tipo, Conductor, Desc, Foto1, Foto2, Status
    EXCEPCIONES("ExcepcionesDomicilio", "excepcionesDom", "A:F"),     // Calle, Numero, TipoExcepcion, ValidoDesde, ValidoHasta, Notas
    DOMICILIOS_MOROSOS("DomiciliosMorosos", "morosos", "A:C"),        // Calle, Numero, EstatusDeuda
    TERRAZA_RESERVAS("TerrazaReservas", "terrazaReservas", "A:E"),    // Coto/Terraza, Fecha, Hora, Evento, QrCode
    PLACAS_PROHIBIDAS("PlacasProhibidas", "placasProhibidas", "A:D"); // Placa, RazonBloqueo, FechaCreado, CreadoPor

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
                BITACORA_ACCESOS to "WS_BITACORA_ACCESOS",
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
    CAPTURA_NOMBRE,
    CAPTURA_PLACA,
    PROCESANDO_AUTORIZACION
}