package com.larangel.rondyaccesos

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.larangel.rondyaccesos.R
import com.larangel.rondyaccesos.admin.AdminMainActivity
import com.larangel.rondyaccesos.caseta.CasetaCentralActivity
import com.larangel.rondyaccesos.models.MySettings
import com.larangel.rondyaccesos.models.SheetTable
import com.larangel.rondyaccesos.ui.LimitedFeatureActivity
import com.larangel.rondyaccesos.ui.SeleccionarRolActivity
import com.larangel.rondyaccesos.ui.VigilanteConfigActivity
import com.larangel.rondyaccesos.peatonal.IngresoPeatonalActivity
import com.larangel.rondyaccesos.salida.SalidaPeatonalActivity
import com.larangel.rondyaccesos.salida.SalidaVehicularActivity
import com.larangel.rondyaccesos.vehicular.IngresoVehicularActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

import android.app.AlertDialog
import android.content.Context
import android.widget.EditText
import android.widget.LinearLayout
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

// Agrega esta función dentro de la clase SplashActivity
private suspend fun mostrarDialogoCapturaHKey(context: Context): String = suspendCancellableCoroutine { continuation ->
    val input = EditText(context).apply {
        hint = "Ej: Hash_Seguridad_2026"
        setTextColor(android.graphics.Color.WHITE)
        setHintTextColor(android.graphics.Color.GRAY)
    }

    // Contenedor con márgenes limpios para el campo de texto
    val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(50, 20, 50, 10)
        addView(input)
    }

    val dialog = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        .setTitle("Activación Requerida")
        .setMessage("Por favor, ingrese el Código de Activación (HKey) para aprovisionar el systema RONDY:")
        .setView(container)
        .setCancelable(false) // Obliga al usuario a ingresar datos para poder continuar
        .setPositiveButton("Aceptar") { _, _ ->
            val codigoTecleado = input.text.toString().trim()
            if (continuation.isActive) continuation.resume(codigoTecleado)
        }
        .create()

    dialog.show()

    // Si la corrutina se cancela externamente por el sistema operativo, cerramos el diálogo
    continuation.invokeOnCancellation {
        dialog.dismiss()
    }
}

class SplashActivity : AppCompatActivity() {
    private lateinit var mySettings: MySettings
    private lateinit var txtStatus: TextView
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        mySettings = MySettings(this)
        txtStatus = findViewById(R.id.txtStatus)
        progressBar = findViewById(R.id.progressBar)

        ejecutarFlujoDeArranque()
    }

    private fun ejecutarFlujoDeArranque() {
        lifecycleScope.launch {
            txtStatus.text = "Verificando conexión de red..."
            val tieneInternet = verificarInternet()

            if (tieneInternet) {
                txtStatus.text = "Validando licencia desde servidor remoto..."

                // Reemplaza con tus datos reales de producción de AWS S3
                val bucketName = mySettings?.getString("BUCKET_NAME", "luisrangelapps").toString()
                val region     = mySettings?.getString("REGION_STR", "us-east-2").toString()
                var targetHKey = mySettings?.getString("CODIGO_ACTIVACION", "").toString()

                if (targetHKey.isEmpty()) {
                    txtStatus.text = "Esperando código de activación..."

                    // Levantamos el diálogo y esperamos la respuesta del usuario de forma asíncrona
                    targetHKey = mostrarDialogoCapturaHKey(this@SplashActivity)

                    // Validar que el usuario no haya dejado el campo en blanco
                    if (targetHKey.isEmpty()) {
                        txtStatus.text = "Código inválido. Iniciando modo sin licencia..."
                        delay(2000)
                        determinarRutaYMenuPrincipal()
                        return@launch
                    }

                    // 3. Guardar el código tecleado en tus configuraciones de MySettings para el siguiente arranque
                    mySettings.saveString("CODIGO_ACTIVACION", targetHKey)
                }

                val validadoExitosamente = mySettings.fetchAndProcessS3Config(bucketName, region, targetHKey)

                if (validadoExitosamente) {
                    txtStatus.text = "Sincronizando catálogos de red..."
                    // Inicializar nombres de hojas basados en la configuración descargada de S3
                    SheetTable.initializeAll(mySettings)

                    // TODO: Disparar la descarga forzada o sync offline de DataRawRondin aquí
                    val dataRaw = (application as RondyApplication).dataRawRondin
                    val bitacoraAccesos = dataRaw.getBitacoraAccesos(forceLoad = true, createIfNotExist = true)
                    txtStatus.text = txtStatus.text.toString() + "\n Bitacora:${bitacoraAccesos.count()}"
                    delay(500)
                    val domicilios      = dataRaw.getDomiciliosUbicacion(forceLoad = true)
                    txtStatus.text = txtStatus.text.toString() + "\n Domicilios:${domicilios.count()}"
                    delay(500)
                    val whatsappTelefonos=dataRaw.getWhatsappTelefonos(forceLoad = true, createIfNotExist = true)
                    txtStatus.text = txtStatus.text.toString() + "\n Telefonos Whatsapp:${whatsappTelefonos.count()}"
                    delay(500)
                    val excepciones=dataRaw.getExcepciones(forceLoad = true, createIfNotExist = true)
                    txtStatus.text = txtStatus.text.toString() + "\n Excepciones:${excepciones.count()}"
                    delay(500)
                    val morosos=dataRaw.getMorosos(forceLoad = true, createIfNotExist = true)
                    txtStatus.text = txtStatus.text.toString() + "\n Morosos:${morosos.count()}"
                    delay(500)
                } else {
                    txtStatus.text = "Licencia Inválida. Iniciando con funciones restringidas..."
                    delay(2000)
                }
            } else {
                txtStatus.text = "Sin internet disponible. Cargando base de datos local..."
                SheetTable.initializeAll(mySettings)
                delay(2000)
            }

            determinarRutaYMenuPrincipal()
        }
    }

    private suspend fun verificarInternet(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val url = URL("https://google.com")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 3000
            connection.connect()
            connection.responseCode == 200
        } catch (e: Exception) {
            false
        }
    }

    private fun determinarRutaYMenuPrincipal() {
        val appActivada = mySettings.getInt("APP_ACTIVADA", 0)

        // Supongamos que guardas el ROL preferido en SharedPreferences: "ADMIN", "VIGILANTE" o no asignado
        val rolConfigurado = mySettings.getString("ROLY_MODE", "")
        val sateliteConfigurado = mySettings.getString("SATELITE_NODE_MODE", "")

        val intent = when {
            appActivada == 0 -> Intent(this, LimitedFeatureActivity::class.java)
            rolConfigurado == "ADMINISTRADOR" -> Intent(this, AdminMainActivity::class.java)
            rolConfigurado == "VIGILANTE" -> {
                // Si es vigilante, evaluamos qué satélite es este hardware para mandarlo directo a su pantalla operativa
                when (sateliteConfigurado) {
                    "CASETA" -> Intent(this, CasetaCentralActivity::class.java)
                    "INGRESO_VEHICULAR" -> Intent(this, IngresoVehicularActivity::class.java)
                    "INGRESO_PEATONAL" -> Intent(this, IngresoPeatonalActivity::class.java)
                    "SALIDA_VEHICULAR" -> Intent(this, SalidaVehicularActivity::class.java)
                    "SALIDA_PEATONAL" -> Intent(this, SalidaPeatonalActivity::class.java)
                    else -> Intent(this, VigilanteConfigActivity::class.java) // Si no se ha configurado el satélite
                }
            }
            else -> Intent(this, SeleccionarRolActivity::class.java) // Primer arranque limpio con licencia
        }

        startActivity(intent)
        finish() // Elimina el Splash de la pila de actividades
    }
}