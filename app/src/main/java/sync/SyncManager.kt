package com.larangel.rondyaccesos.models.sync

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

object SyncManager {

    /**
     * Programa una tarea única en segundo plano que se ejecutará en cuanto
     * el dispositivo recupere conexión a internet de forma verificada.
     */
    fun programarSincronizacionAlRecuperarInternet(context: Context) {
        // Restricciones estrictas: El dispositivo DEBE tener internet activo
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = OneTimeWorkRequestBuilder<RondySyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL, // Si falla, reintenta a los 5 min, luego 10, luego 20...
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .build()

        // Encolar con política KEEP: Si ya hay un Worker esperando internet, no lo duplica
        WorkManager.getInstance(context).enqueueUniqueWork(
            "RondyUniqueSyncJob",
            ExistingWorkPolicy.KEEP,
            syncRequest
        )
    }
}