package com.larangel.rondyaccesos.salida

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.larangel.rondyaccesos.databinding.ActivitySalidaPeatonalBinding
import com.larangel.rondyaccesos.models.com.larangel.rondyaccesos.salida.SalidaPeatonalViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SalidaPeatonalActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySalidaPeatonalBinding
    private val viewModel: SalidaPeatonalViewModel by viewModels()
    private var rutaFotoSalidaPeatonal: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySalidaPeatonalBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configurarAccionesDeBotones()
        observarEstadoDeSalidaPeatonal()
    }

    private fun configurarAccionesDeBotones() {
        binding.btnBuscarPeaton.setOnClickListener {
            val criterio = binding.txtBuscarVisitante.text.toString().trim()
            if (criterio.isNotEmpty()) {
                viewModel.buscarRegistroEntradaPeatonal(criterio)
            } else {
                Toast.makeText(this, "Por favor ingrese un nombre o domicilio", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnCapturarFotoSalida.setOnClickListener {
            // Lógica de disparo del caso de uso ImageCapture de CameraX Frontal
            rutaFotoSalidaPeatonal = "storage/emulated/0/Android/data/rostro_egreso.jpg"
            Toast.makeText(this, "Foto de salida capturada con éxito", Toast.LENGTH_SHORT).show()
        }

        binding.btnConfirmarSalidaPeatonal.setOnClickListener {
            viewModel.registrarEgresoPeatonal(rutaFotoSalidaPeatonal)
            binding.txtBuscarVisitante.text.clear()
        }
    }

    private fun observarEstadoDeSalidaPeatonal() {
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                binding.lblTopSalidaPeatonal.text = state.mensajeSuperior

                if (state.peatonEncontrado != null) {
                    binding.cardPeatonEncontrado.visibility = View.VISIBLE
                    val p = state.peatonEncontrado
                    binding.lblDetallePeaton.text = """
                        👤 VISITANTE: ${p.conductor}
                        🏡 DOMICILIO VISITADO: ${p.calle} #${p.numero}
                        🕒 MOTIVO: ${p.descripcion}
                        ⏳ FECHA INGRESO: ${p.fechaIngreso}
                    """.trimIndent()
                } else {
                    binding.cardPeatonEncontrado.visibility = View.GONE
                }
            }
        }
    }
}