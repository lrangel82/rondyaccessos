package com.larangel.rondyaccesos.models.com.larangel.rondyaccesos.salida

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.larangel.rondyaccesos.databinding.ActivityContactoExpressSalidaBinding
import com.larangel.rondyaccesos.models.AccesoBitacora
import com.larangel.rondyaccesos.models.sockets.MessageType
import com.larangel.rondyaccesos.models.sockets.RondySocketClient
import com.larangel.rondyaccesos.models.sockets.SocketMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class ContactoExpressSalidaActivity : AppCompatActivity() {

    private lateinit var binding: ActivityContactoExpressSalidaBinding
    private val socketClient = RondySocketClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityContactoExpressSalidaBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configurarEventosClicks()
    }

    private fun configurarEventosClicks() {
        binding.btnCancelarExpress.setOnClickListener {
            finish() // Cierra la pantalla y regresa al monitor de salida anterior
        }

        binding.btnGuardarExpress.setOnClickListener {
            val placa = binding.txtPlacaExpress.text.toString().replace(Regex("[^a-zA-Z0-9]"), "").uppercase().trim()
            val calle = binding.txtCalleExpress.text.toString().trim()
            val numero = binding.txtNumeroExpress.text.toString().trim()
            val conductor = binding.txtConductorExpress.text.toString().trim().uppercase()
            val motivo = binding.txtMotivoExpress.text.toString().trim()

            if (placa.isEmpty() || calle.isEmpty() || numero.isEmpty()) {
                Toast.makeText(this, "Para regularizar se requiere obligatoriamente PLACA, CALLE y NUMERO", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            lifecycleScope.launch(Dispatchers.IO) {
                val timestampBase = (LocalTime.now().toSecondOfDay() * -1).toString()

                // 1. Crear el Objeto de Regularización de Salida
                val registroRegularizado = AccesoBitacora(
                    fechaCreado = LocalTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).toString(), // ID temporal offline
                    fechaIngreso = LocalTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).toString(),
                    placa = placa,
                    calle = calle,
                    numero = numero,
                    tipo = "Salida Regularizada",
                    conductor = conductor,
                    descripcion = if (motivo.isEmpty()) "Vehículo sin registro de entrada" else motivo,
                    foto1Url = "local/express_placa.jpg",
                    foto2Url = "",
                    qrData = "",
                    status = "salida registrada"
                )

                // 2. Transmitir el JSON de Regularización a la Caseta Central (Padre) vía TCP
                val msgSocket = SocketMessage(MessageType.REGISTRO_INGRESO, "client_ip", "SALIDA_VEHICULAR", registroRegularizado)
                socketClient.enviarRegistroACaseta(msgSocket)

                // 3. Persistir en la cola local de Google Sheets de DataRawRondin para push en background
                // dataRawRondin.sync(SheetTable.BITACORA_ACCESOS, Operation.APPEND, listOf(...))

                launch(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "¡Vehículo Regularizado! Abriendo barrera de salida.", Toast.LENGTH_LONG).show()
                    // helpers.ejecutarBarreraSalida(1) -> delay(500) -> helpers.ejecutarBarreraSalida(0)
                    finish()
                }
            }
        }
    }
}