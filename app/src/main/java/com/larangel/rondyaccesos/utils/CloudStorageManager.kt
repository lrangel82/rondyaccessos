package com.larangel.rondyaccesos.utils

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.larangel.rondyaccesos.models.MySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream

object CloudStorageManager {
    private val client = OkHttpClient()

    /**
     * Compresses a raw memory bitmap to WebP/JPEG at 60% quality
     * and uploads it to your ephemeral storage node.
     */
    suspend fun subirImagenTemporal(
        context: Context,
        fileName: String,
        bitmap: Bitmap?,
        calidad: Int = 60,
        mySettings: MySettings

    ): String = withContext(Dispatchers.IO) {
        val bucketName = "gpeinn-rondyaccesos-309883496951-us-east-2-an"//mySettings.getString("BUCKET_NAME", "luisrangelapps").toString()
        val region     = mySettings.getString("REGION_STR", "us-east-2").toString()
        val bucketUrl = "https://$bucketName.s3.$region.amazonaws.com/vehicular/$fileName"
        if (bitmap == null) {
            Log.e("CloudStorage", "Bitmap null for $fileName. Returning fallback indicator.")
            return@withContext "internal/fallback_missing.jpg"
        }

        try {
            // Compress in memory stream to reduce data costs (<150KB target)
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, calidad, outputStream)
            val byteArray = outputStream.toByteArray()
            val requestBody = byteArray.toRequestBody("image/jpeg".toMediaTypeOrNull(), 0, byteArray.size)


            val request = Request.Builder()
                .url(bucketUrl)
                .put(requestBody)
                // --- CAPA DE AHORRO CRÍTICA ---
                // Fuerza a S3 a guardar la foto directamente en la categoría ultra-barata de acceso inmediato
                .addHeader("x-amz-storage-class", "GLACIER_IR")
                // Encabezados estándar de seguridad (Si usas URLs firmadas, este PUT requerirá la firma)
                .addHeader("Content-Type", "image/jpeg")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    // Endpoint returns JSON or direct URL path string
                    return@withContext response.body?.string() ?: "internal/upload_error.jpg"
                } else {
                    Log.e("CloudStorage", "Upload failed with HTTP code: ${response.code}")
                    return@withContext "internal/http_error_${response.code}.jpg"
                }
            }
        } catch (e: Exception) {
            Log.e("S3Storage", "Excepción de red al subir a AWS S3", e)
            return@withContext "internal/exception_error.jpg"
        }
    }
}