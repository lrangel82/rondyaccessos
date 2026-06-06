package com.larangel.rondyaccesos.peatonal

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.larangel.rondyaccesos.databinding.ActivityIngresoPeatonalBinding
import com.larangel.rondyaccesos.peatonal.IngresoPeatonalViewModel
import com.larangel.rondyaccesos.utils.CloudStorageManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class IngresoPeatonalActivity : AppCompatActivity() {

    private lateinit var binding: ActivityIngresoPeatonalBinding
    private val viewModel: IngresoPeatonalViewModel by viewModels()

    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIngresoPeatonalBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        iniciarComponenteCamaraFrontal()
        configurarListeners()
        observarEstadoPeatonal()
    }

    private fun iniciarComponenteCamaraFrontal() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewViewRostro.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            // Usar cámara frontal para Totem Peatonal
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            } catch (exc: Exception) {
                Log.e("PeatonalCam", "Fallo al iniciar cámara", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun configurarListeners() {
        binding.btnTomarFoto.setOnClickListener {
            capturarFotoRostro()
        }

//        binding.btnReiniciarPeatonal.setOnClickListener {
//            viewModel.reiniciarAsistentePeatonal()
//        }

        binding.layoutSplash.setOnClickListener {
            viewModel.procesarEntradaVoz("Hola")
        }

//        binding.btnGuardarPeatonal.setOnClickListener {
//            val calle = binding.txtInputManual.text.toString() // O el valor acumulado en el VM
//            viewModel.validarYProcesarAcceso(...)
//        }
    }

    private fun capturarFotoRostro() {
        val imageCapture = imageCapture ?: return
        val photoFile = File(externalCacheDir, "rostro_temp.jpg")

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    // Notificar al VM que la foto está lista
                    binding.lblTopMensajePeatonal.text = "ROSTRO CAPTURADO"
                    binding.indicadorColorMicro.backgroundTintList = ColorStateList.valueOf(Color.GREEN)
                }
                override fun onError(exc: ImageCaptureException) {
                    Log.e("PeatonalCam", "Error captura: ${exc.message}")
                }
            })
    }

    private fun observarEstadoPeatonal() {
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                // Protocolo Cyber-Dark: Actualización de UI
                binding.layoutSplash.visibility = if (state.mostrarSplash) View.VISIBLE else View.GONE
                binding.lblTimerInactividad.text = "${state.segundosRestantes}s"

                //binding.lblInstruccionSeccion.text = state.instruccionActual
                //binding.lblSubtitulosPeatonal.text = state.subtitulosIA

                //if (state.esMoroso) {
                //    actualizarInterfazError("ACCESO DENEGADO")
                //}

                // Actualizar LED de Micro
                //val colorLed = if (state.escuchando) Color.CYAN else Color.GRAY
                //binding.indicadorColorMicro.backgroundTintList = ColorStateList.valueOf(colorLed)
            }
        }
    }

    private fun actualizarInterfazError(msg: String) {
       // binding.panelColorContenedor.setBackgroundColor(Color.parseColor("#440000")) // Rojo oscuro Cyber
        binding.tvEstatusGrande.text = msg
        binding.layoutVisualEstatusDerecho.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}