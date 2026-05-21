package com.larangel.rondyaccesos.utils

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Context.ALARM_SERVICE
import android.content.Intent
import android.location.Address
import android.location.Geocoder
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.icu.util.Calendar
import android.os.Build
import android.util.Base64
import com.google.android.gms.maps.model.LatLng
//import com.larangel.rondyaccesos.AlarmReceiver
import com.larangel.rondyaccesos.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Busca una placa en un texto usando Regex y devuelve el valor o null
 */
fun String.extraerPlaca(): String? {
    val plateRegex = Regex("([A-Z]{3}[0-9]{3,4}[A-Z]?|[0-9]{2}[A-Z][0-9]{3}|[0-9]{3}[A-Z]{3}|[A-Z]{2}[0-9]{4,5}[A-Z]?|[A-Z][0-9]{4}|[A-Z][0-9]{2}[A-Z]{2,3}|[A-Z]{3}[0-9][A-Z]|[A-Z]{5}[0-9]{2})")
    return plateRegex.find(this.uppercase())?.value
}

/**
 * Busca un TAG valido en el texto
 */
fun String.extraerTAG(): String? {
    val TagRegex = Regex("([1-9][0-9]{6,7})")
    return TagRegex.find(this.uppercase())?.value
}
fun String.extraerTAGHexToDec():String?{
    val TagRegex = Regex("AABB([0-9A-F]{10})0{10}")
    val hexadecimal = TagRegex.find(this.uppercase())?.groups?.get(1)?.value
    return try {
        // Convertimos de Hexadecimal (base 16) a Decimal
        // Usamos toLong porque un hex de 10 dígitos puede superar el límite de un Int
        hexadecimal?.toLong(16)?.toString()
    } catch (e: Exception) {
        null
    }
}

/**
 * Busca un COLOR valido en el texto
 */
fun String.extraerColor(): String? {
    val ColorRegex = Regex("(rojo|verde|azul|magenta|lila|morado|rosa|turquesa|amarillo|blanco|negro|cafe|marron|violeta|naranja|beige|gris|plata)")
    return ColorRegex.find(this.lowercase())?.value
}

/**
 * Busca un MARCA de un auto valido en el texto
 */
fun String.extraerMarcaAuto(): String? {
    val MarcaRegex = Regex("(YAMAHA|Acura|Alfa Romeo|Audi|Auteco|Bentley|BMW|Changan|Chirey|Chrysler|Fiat|Ford Motor|Foton|General Motors|Great Wall Motor|Honda|Hyundai|Infiniti|Isuzu|JAC|Jaguar|JETOUR|KIA|Land Rover|Lexus|Lincoln|Mazda|Mercedes Benz|MG Motor|MG ROVER|Mini|Mitsubishi|MOTORNATION|Nissan|Omoda|Peugeot|Porsche|Renault|SEAT|Smart|Subaru|Suzuki|Toyota|Volkswagen|Volvo)",
        RegexOption.IGNORE_CASE)
    return MarcaRegex.find(this)?.value
}

//*** Buscar TAGS/PLACAS validos
var stopSearchLoop = false
fun buscarTagEnListaCache(tagsCache:List<List<Any>>, strLectorRFID: String): List<List<Any>>{
    val allLines = strLectorRFID.split("\n")
    var matches: MutableList<List<Any>> = mutableListOf()
    stopSearchLoop = false
    for (line in allLines) {
        if (stopSearchLoop) return emptyList()
        val tagValue = line.extraerTAGHexToDec()
        if (tagValue != null){
            var foundTag=false
            tagsCache?.forEach { tag ->
                if (stopSearchLoop) return@forEach
                val tagId = tag[0].toString()

                // 1. Coincidencia Exacta (Prioridad máxima)
                if (tagId.equals(tagValue, ignoreCase = true)) {
                    foundTag=true
                    matches.clear()
                    matches.add( tag )
                    stopSearchLoop = true
                    return matches
                }

                // 2. Similares (Sugerencias)
                if (tagId.startsWith(tagValue, true) || tagValue.startsWith(tagId)) {
                    foundTag=true
                    matches.add(tag)
                }
            }
            if (foundTag == false) { //No se encontro ninguno
                matches.add(listOf(tagValue,"No registrado","0")) //Tag, calle, numero
            }
        }
    }
    return matches
}
fun buscarPlacaEnListaCache(placasCache:List<List<Any>>, strPlaca:String):List<List<Any>>{
    var matches: MutableList<List<Any>> = mutableListOf()
    stopSearchLoop = false
    val placaValue = strPlaca.extraerPlaca()
    if (placaValue != null){
        var foundTag=false
        for (row in placasCache) {
            if (stopSearchLoop) return emptyList()
            if (row.isEmpty()) continue
            val placaID = row[0].toString()
            if (placaID.isEmpty()) continue

            // 1. Coincidencia Exacta (Prioridad máxima)
            if (placaID.equals(placaValue, ignoreCase = true)) {
                foundTag=true
                matches.clear()
                matches.add( row )
                stopSearchLoop = true
                return matches
            }

            // 2. Similares (Sugerencias)
            else if (placaID.startsWith(placaValue, true) ) {
                foundTag=true
                matches.add(row)
            }

            // 3. Una letra de diferncia
            else if( oneCharDifference(placaID, placaValue)) {
                foundTag=true
                matches.add(row)
            }
        }
        if (foundTag == false) { //No se encontro ninguno
            matches.add(listOf(placaValue,"No registrado","0")) //Tag, calle, numero
        }
    }
    else if(strPlaca.length > 2 && strPlaca.length<8){
        //Buscar cualquier coincidencia
        var foundTag=false
        for (row in placasCache){
            if (stopSearchLoop) return emptyList()
            if (row.isEmpty()) continue
            val placaID = row[0].toString().uppercase()
            if (placaID.isEmpty()) continue

            if (placaID.contains(strPlaca.uppercase()) ) {
                foundTag=true
                matches.add(row)
            }
        }
        if (foundTag == false) { //No se encontro ninguno
            matches.add(listOf(strPlaca,"No registrado","0")) //Tag, calle, numero
        }
    }
    return matches
}

fun oneCharDifference(a: String, b: String): Boolean {
    // Devuelve true si solo difiere en una letra/número
    if (a.length != b.length) return false
    var diff = 0
    for (i in a.indices) {
        if (a[i] != b[i]) diff++
        if (diff > 1) return false
    }
    return diff == 1
}

//***** Imagenes agregar datos unicos
fun fomatearImagenLogo(context: Context,
                       originalBitmap: Bitmap,
                       location: LatLng?,
                       logoBase64: String?,
                       address: String? = null): Bitmap {
    val mutableBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(mutableBitmap)
    val width = canvas.width.toFloat()
    val height = canvas.height.toFloat()

    // Configuración de pinceles (Paints)
    val textPaint = Paint().apply {
        color = Color.WHITE
        typeface = Typeface.MONOSPACE
        textSize = height * 0.08f // Letras grandes
        isAntiAlias = true
    }

    val smallTextPaint = Paint().apply {
        color = Color.WHITE
        typeface = Typeface.SANS_SERIF
        textSize = textPaint.textSize / 4
        isAntiAlias = true
    }

    val linePaint = Paint().apply {
        color = Color.YELLOW
        strokeWidth = 10f
    }

    // 1. Esquina Inferior Izquierda: Hora, Línea y Datos
    val margin = 40f
    val sdfTime = SimpleDateFormat("HH:mm", Locale.getDefault())
    val timeStr = sdfTime.format(Date())

    // Dibujar Hora
    val timeBounds = Rect()
    textPaint.getTextBounds(timeStr, 0, timeStr.length, timeBounds)
    val xTime = margin
    val yTime = height - margin
    canvas.drawText(timeStr, xTime, yTime, textPaint)

    // Dibujar Línea Vertical Amarilla
    val xLine = xTime + timeBounds.width() + 30f
    val lineTop = yTime - timeBounds.height()
    canvas.drawLine(xLine, lineTop, xLine, yTime, linePaint)

    //Address
    address?.let{
        canvas.drawText(it.uppercase(), xTime + 20f, lineTop - smallTextPaint.textSize ,smallTextPaint)
    }

    // Dibujar Fecha, Día y GPS al lado de la línea
    val xData = xLine + 30f
    val sdfDate = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    val sdfDay = SimpleDateFormat("EEE", Locale.getDefault())

    canvas.drawText(sdfDate.format(Date()), xData, lineTop + (smallTextPaint.textSize), smallTextPaint)
    canvas.drawText(sdfDay.format(Date()).uppercase(), xData, lineTop + (timeBounds.height()/2) + (smallTextPaint.textSize/2), smallTextPaint)

    val latLon = "${"%.4f".format(location?.latitude ?: 0.0)}, ${"%.4f".format(location?.longitude ?: 0.0)}"
    canvas.drawText(latLon, xData, yTime, smallTextPaint)

    // 2. Esquina Superior Derecha: Logo Dinámico
    logoBase64?.let { base64 ->
        decodeBase64ToBitmap(base64)?.let { logoBitmap ->
            val scaledLogo = Bitmap.createScaledBitmap(logoBitmap, (width * 0.15f).toInt(), (width * 0.15f).toInt(), true)
            canvas.drawBitmap(scaledLogo, width - scaledLogo.width - margin, margin, null)
        }
    }

    // 3. Esquina Inferior Derecha: Logo Rondy
    val rondyLogo = BitmapFactory.decodeResource(context.resources, R.drawable.logo)
    val scaledRondy = Bitmap.createScaledBitmap(rondyLogo, (width * 0.12f).toInt(), (width * 0.12f).toInt(), true)
    canvas.drawBitmap(scaledRondy, width - scaledRondy.width - margin, height - scaledRondy.height - margin, null)

    return mutableBitmap
}
private fun decodeBase64ToBitmap(base64: String): Bitmap? {
    return try {
        val imageBytes = Base64.decode(base64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    } catch (e: Exception) { null }
}
fun getAddressFromLocation(context: Context, location: LatLng?): String {
    val geocoder = Geocoder(context, Locale.getDefault())
    return try {
        // En versiones recientes de Android (Tiramisu+), se recomienda usar la versión async.
        // Esta es la versión estándar compatible:
        val addresses: List<Address>? = geocoder.getFromLocation(location!!.latitude, location!!.longitude, 1)

        if (!addresses.isNullOrEmpty()) {
            val address = addresses[0]
            // Construye la dirección (Calle número, Ciudad, País)
            address.getAddressLine(0)
        } else {
            "Dirección no encontrada"
        }
    } catch (e: Exception) {
        e.printStackTrace()
        "Error al obtener dirección: ${e.message}"
    }
}