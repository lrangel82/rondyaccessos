package com.larangel.rondyaccesos.salida

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.larangel.rondyaccesos.databinding.ActivitySalidaVehicularBinding
import com.larangel.rondyaccesos.models.com.larangel.rondyaccesos.salida.ContactoExpressSalidaActivity
import com.larangel.rondyaccesos.models.com.larangel.rondyaccesos.salida.SalidaVehicularViewModel
import com.larangel.rondyaccesos.vehicular.IngresoVehicularActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SalidaVehicularActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySalidaVehicularBinding
    private val viewModel: SalidaVehicularViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySalidaVehicularBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configurarBotonesDeAccion()
        observarEstadoDeEgresos()
    }

    private fun configurarBotonesDeAccion() {
        binding.btnBuscarManual.setOnClickListener {
            val placaManual = binding.txtBuscarPlacaSalida.text.toString().trim()
            if (placaManual.isNotEmpty()) {
                viewModel.procesarPlacaDetectadaPorCamara(placaManual)
            } else {
                Toast.makeText(this, "Escriba una placa válida", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnDarSalidaVehiculo.setOnClickListener {
            viewModel.ejecutarSalidaVehicular()
        }

        // Caso de Contingencia: El auto no tiene entrada registrada en el sistema
        binding.btnForzarRegistroSalida.setOnClickListener {
            Toast.makeText(this, "Redireccionando a Registro Express de Salida...", Toast.LENGTH_LONG).show()

            // Reutiliza tu pantalla de ingreso vehicular pero pasándole un parámetro para procesarlo como salida
            val intentExpress = Intent(this, ContactoExpressSalidaActivity::class.java)
            startActivity(intentExpress)
        }
    }

    private fun observarEstadoDeEgresos() {
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                binding.lblTopSalida.text = state.mensajeSuperior

                if (state.vehiculoDetectado != null) {
                    binding.cardVehiculoEncontrado.visibility = View.VISIBLE
                    binding.btnForzarRegistroSalida.visibility = View.GONE

                    val v = state.vehiculoDetectado
                    binding.lblDetalleVehiculo.text = """
                        🚗 PLACA IDENTIFICADA: ${v.placa}
                        🏠 PROCEDENCIA: ${v.calle} #${v.numero}
                        👤 CONDUCTOR: ${v.conductor}
                        🕒 ENTRÓ EL DÍA DE HOY A LAS: ${v.hora}
                    """.trimIndent()
                } else {
                    binding.cardVehiculoEncontrado.visibility = View.GONE
                    binding.btnForzarRegistroSalida.visibility = if (state.mostrarBotonForzar) View.VISIBLE else View.GONE
                }
            }
        }
    }
}