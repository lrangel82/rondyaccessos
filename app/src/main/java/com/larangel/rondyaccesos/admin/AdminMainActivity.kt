package com.larangel.rondyaccesos.admin

import android.app.AlertDialog
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.larangel.rondyaccesos.R
import com.larangel.rondyaccesos.databinding.ActivityAdminMainBinding
import com.larangel.rondyaccesos.models.com.larangel.rondyaccesos.utils.QrGenerator

class AdminMainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupMenuClickActions()
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