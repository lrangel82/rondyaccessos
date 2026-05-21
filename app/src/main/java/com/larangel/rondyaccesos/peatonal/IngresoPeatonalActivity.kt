package com.larangel.rondyaccesos.peatonal

import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.larangel.rondyaccesos.databinding.ActivityIngresoPeatonalBinding
import com.larangel.rondyaccesos.models.com.larangel.rondyaccesos.peatonal.IngresoPeatonalViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class IngresoPeatonalActivity : AppCompatActivity() {

    private lateinit var binding: ActivityIngresoPeatonalBinding
    private val viewModel: IngresoPeatonalViewModel by viewModels()
    private var rutaFotoCapturada: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIngresoPeatonalBinding.inflate(layoutInflater)
        setContentView(binding.root)

        iniciarComponenteCamaraFrontal()
        configurarListeners()
        observarEstadoPeatonal()
    }

    private fun iniciarComponenteCamaraFrontal() {
        // TODO: Configurar el pipeline nativo de CameraX vinculado a binding.previewViewRostro
        // Usar ProcessCameraProvider.getInstance(this) y enlazar el CameraSelector.LENS_FACING_FRONT
    }

    private fun configurarListeners() {
        binding.txtCallePeatonal.addTextChangedListener { viewModel.onCalleChanged(it.toString()) }

        binding.txtNumeroPeatonal.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                viewModel.onNumeroChanged(binding.txtNumeroPeatonal.text.toString())
            }
        }

        binding.txtNombrePeatonal.addTextChangedListener { viewModel.onNombreChanged(it.toString()) }
        binding.txtMotivoPeatonal.addTextChangedListener { viewModel.onMotivoChanged(it.toString()) }

        binding.btnTomarFoto.setOnClickListener {
            // Lógica para capturar la foto física usando el ImageCapture de CameraX
            rutaFotoCapturada = "internal/storage/rostro_visita.jpg"
            binding.lblTopMensajePeatonal.text = "Rostro capturado correctamente."
        }

        binding.btnGuardarPeatonal.setOnClickListener {
            viewModel.registrarIngresoPeatonal(rutaFotoCapturada)
        }
    }

    private fun observarEstadoPeatonal() {
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                binding.lblTopMensajePeatonal.text = state.mensajeSuperior
                binding.lblMorosidadPeatonal.text = state.estatusMorosidadTxt
                binding.lblSubtitulosPeatonal.text = state.subtitulosIA

                if (state.esMoroso) {
                    binding.lblTopMensajePeatonal.setTextColor(Color.RED)
                    binding.lblMorosidadPeatonal.setTextColor(Color.RED)
                    binding.btnGuardarPeatonal.text = "Ingreso Denegado"
                    binding.btnGuardarPeatonal.setBackgroundColor(Color.RED)
                } else {
                    binding.lblTopMensajePeatonal.setTextColor(Color.GREEN)
                    binding.lblMorosidadPeatonal.setTextColor(Color.GREEN)
                    binding.btnGuardarPeatonal.text = "Permitir Ingreso"
                    binding.btnGuardarPeatonal.setBackgroundColor(Color.parseColor("#0288D1"))
                }
            }
        }
    }
}