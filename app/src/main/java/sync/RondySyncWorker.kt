package com.larangel.rondyaccesos.models.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.larangel.rondyaccesos.models.DataRawRondin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class RondySyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d("RondySyncWorker", "¡Disparador de internet activado! Iniciando vaciado de colas offline...")

        val scopeFicticio = CoroutineScope(Dispatchers.IO + SupervisorJob())

        // Instancia directa y limpia de la clase unificada
        val dataRawRondin = DataRawRondin(applicationContext, scopeFicticio)

        // Ejecuta el vaciado de todas las colas JSON locales hacia las pestañas correspondientes
        val exitoSincronizacion = dataRawRondin.forzarVaciadoDeColasDesdeWorkerBackend()

        return if (exitoSincronizacion) {
            Result.success()
        } else {
            Result.retry() // Si sigue sin red estable o hay error en Sheets, reintenta después
        }
    }
}