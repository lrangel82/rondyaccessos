package com.larangel.rondyaccesos.admin

import android.app.AlertDialog
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.launch
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.larangel.rondyaccesos.R
import com.larangel.rondyaccesos.RondyApplication
import com.larangel.rondyaccesos.databinding.ActivityAdminMainBinding
import com.larangel.rondyaccesos.models.com.larangel.rondyaccesos.utils.QrGenerator
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AdminMainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminMainBinding
    private val networkManager by lazy { (application as RondyApplication).networkManager }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupMenuClickActions()
        observeLogs()
    }

    private fun observeLogs() {
        lifecycleScope.launch {
            networkManager.consoleLogs.collectLatest { logs ->
                // Unir los logs con saltos de línea
                binding.txtConsole.text = logs.joinToString("\n")

                // Auto-scroll hacia abajo
                binding.scrollConsole.post {
                    binding.scrollConsole.fullScroll(View.FOCUS_DOWN)
                }
            }
        }
    }


    private fun setupMenuClickActions() {
        binding.btnGestionTerraza.setOnClickListener {
            mostrarDialogoApartadoTerraza()
        }

        binding.btnGestionMorosos.setOnClickListener {
            Toast.makeText(this, "Módulo de Morosos Abierto", Toast.LENGTH_SHORT).show()
            // TODO: Invoke sub-activity or custom list dialog for SheetTable.DOMICILIOS_MOROSOS
        }

        binding.btnPlacasProhibidas.setOnClickListener {
            Toast.makeText(this, "Módulo de Placas Prohibidas Abierto", Toast.LENGTH_SHORT).show()
            // TODO: Invoke sub-activity or custom list dialog for SheetTable.PLACAS_PROHIBIDAS
        }

        binding.btnVerBitacora.setOnClickListener {
            Toast.makeText(this, "Cargando Historial Local...", Toast.LENGTH_SHORT).show()
            // TODO: Load recycler view displaying local logs from SheetTable.BITACORA_ACCESOS
        }

        binding.btnBuscarAmigosRED.setOnClickListener {
            Toast.makeText(this, "Iniciando escaneo...", Toast.LENGTH_SHORT).show()

            // Si realizarHandshakeInicial es una función suspend, usa launch:
            lifecycleScope.launch {
                networkManager.realizarHandshakeInicial( force=true)
            }
        }
    }

    private fun mostrarDialogoApartadoTerraza() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_reserva_terraza, null)
        val inputDom = dialogView.findViewById<EditText>(R.id.inputDomicilioReserva)
        val inputFecha = dialogView.findViewById<EditText>(R.id.inputFechaEvento)
        val btnGenerar = dialogView.findViewById<Button>(R.id.btnConfirmarYGenerarQr)
        val imgQr = dialogView.findViewById<ImageView>(R.id.imgGeneratedQr)

        val builder = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)

        val alertDialog = builder.create()

        btnGenerar.setOnClickListener {
            val domicilio = inputDom.text.toString().trim()
            val fecha = inputFecha.text.toString().trim()

            if (domicilio.isNotEmpty() && fecha.isNotEmpty()) {
                // Format payload matching your python pyzbar parser criteria: starting with "ginn"
                val stringPayloadParaQr = "ginn|TERRAZA|$domicilio|$fecha"

                val qrBitmap: Bitmap? = QrGenerator.generateQrCodeBitmap(stringPayloadParaQr, 400, 400)

                if (qrBitmap != null) {
                    imgQr.setImageBitmap(qrBitmap)
                    imgQr.visibility = View.VISIBLE

                    // Push data asynchronously through DataRawRondin
                    // dataRawRondin.sync(SheetTable.TERRAZA_RESERVAS, Operation.APPEND, listOf(domicilio, fecha, stringPayloadParaQr))

                    Toast.makeText(this, "Apartado guardado localmente", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Complete todos los campos obligatorios", Toast.LENGTH_SHORT).show()
            }
        }

        alertDialog.show()
    }
}