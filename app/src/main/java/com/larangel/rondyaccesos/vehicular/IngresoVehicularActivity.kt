package com.larangel.rondyaccesos.vehicular

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.larangel.rondyaccesos.RondyApplication
import com.larangel.rondyaccesos.databinding.ActivityIngresoVehicularBinding
import com.larangel.rondyaccesos.models.CaptureStep
import com.larangel.rondyaccesos.models.WhatsappAuthStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

class IngresoVehicularActivity : AppCompatActivity() {

    private lateinit var binding: ActivityIngresoVehicularBinding
    private val viewModel: IngresoVehicularViewModel by viewModels {
        IngresoVehicularViewModelFactory(application as RondyApplication)
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var speechIntent: Intent? = null
    private val RECORD_AUDIO_REQUEST_CODE = 101

    private var reintentandoEscucha = false
    private var ultimoPasoProcesado: CaptureStep? = null
    private var microfonoHabilitadoPorPaso = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIngresoVehicularBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configurarAccionesGlobales()
        observarCicloDelAsistente()
        verificarPermisosYConfigurarEscucha()
    }

    private fun configurarAccionesGlobales() {
        binding.btnReiniciarRegistro.setOnClickListener {
            ultimoPasoProcesado = null // Forzar refresco visual
            viewModel.reiniciarAsistenteCompleto()
        }

        binding.btnSiguientePasoManual.setOnClickListener {
            val valorManual = binding.txtInputManual.text.toString().trim()
            if (valorManual.isEmpty()) {
                Toast.makeText(this, "Por favor complete el campo requerido", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val paso = viewModel.uiState.value.currentStep
            when (paso) {
                CaptureStep.CAPTURA_NOMBRE -> {
                    viewModel.guardarNombreYPasarAPlacas(valorManual)
                    binding.txtInputManual.text.clear()
                }
                CaptureStep.CAPTURA_PLACA -> {
                    viewModel.dispararProtocoloDeSeguridadYWhatsApp(valorManual, "Captura Manual Integrada")
                    binding.txtInputManual.text.clear()
                }
                else -> {}
            }
        }
    }

    // --- MANEJO OPTIMIZADO DEL HARDWARE DE AUDIO (MODO HÍBRIDO) ---

    private fun verificarPermisosYConfigurarEscucha() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_REQUEST_CODE)
        } else {
            inicializarSpeechRecognizer()
        }
    }

    private fun inicializarSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

            speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale("es", "MX").toString())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }

            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    binding.lblSubtitulosAsistente.text = "🤖 Micrófono activo (Modo Híbrido habilitado)"
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {} // Totalmente vacío para liberar rendimiento táctil
                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    binding.lblSubtitulosAsistente.text = "🤖 Procesando voz..."
                }

                override fun onError(error: Int) {
                    // Si el usuario toca la pantalla o el sistema está ocupado, metemos una pausa larga de enfriamiento
                    intentarReinicioSeguroDelMicrofono(delayMillis = 1500)
                }

                override fun onResults(results: Bundle?) {
                    val partidos = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val textoEscuchadoCompleto = partidos?.get(0) ?: ""

                    if (textoEscuchadoCompleto.isNotEmpty()) {
                        onTranscripcionDeVozRecibidaPorAsistente(textoEscuchadoCompleto)
                    } else {
                        intentarReinicioSeguroDelMicrofono(delayMillis = 1000)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            encenderMicrofonoAsistente()
        }
    }

    private fun encenderMicrofonoAsistente() {
        if (microfonoHabilitadoPorPaso && viewModel.uiState.value.currentStep != CaptureStep.PROCESANDO_AUTORIZACION) {
            try {
                speechRecognizer?.startListening(speechIntent)
            } catch (e: Exception) {
                Log.e("VozRondy", "Error al encender micro: ${e.message}")
            }
        }
    }

    private fun intentarReinicioSeguroDelMicrofono(delayMillis: Long) {
        if (reintentandoEscucha || !microfonoHabilitadoPorPaso) return
        reintentandoEscucha = true

        lifecycleScope.launch(Dispatchers.Main) {
            try {
                speechRecognizer?.stopListening()
                delay(delayMillis) // Pausa de alivio para que el sistema operativo atienda los clics físicos
                encenderMicrofonoAsistente()
            } catch (e: Exception) {
                Log.e("VozRondy", "Error en reinicio diferido: ${e.message}")
            } finally {
                reintentandoEscucha = false
            }
        }
    }

    fun onTranscripcionDeVozRecibidaPorAsistente(textoDictado: String) {
        if (textoDictado.isNotEmpty()) {
            viewModel.procesarEntradaVozAsistenteGemini(textoDictado)
        }
        intentarReinicioSeguroDelMicrofono(delayMillis = 1200)
    }

    // --- RENDERIZACIÓN REACTIVA CONTROLADA (SOLUCIÓN AL COINCIDIR CLICS) ---

    private fun observarCicloDelAsistente() {
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                // Componentes volátiles que sí pueden refrescarse constantemente sin romper botones
                binding.lblTopMensaje.text = state.lblTopMensaje
                binding.lblTimerInactividad.text = "Inactividad: ${state.segundosRestantes}s"
                binding.lblSubtitulosAsistente.text = state.subtitulosAsistente

                // 🛑 CORRECCIÓN ANR CRÍTICA: Solo regenerar los Chips si el paso cambió físicamente
                if (ultimoPasoProcesado != state.currentStep) {
                    ultimoPasoProcesado = state.currentStep

                    binding.containerGridBotones.removeAllViews() // Vaciar de forma controlada

                    when (state.currentStep) {
                        CaptureStep.SELECCION_MOTIVO -> {
                            controlarEstadoMicrofono(habilitar = true)
                            binding.lblInstruccionSeccion.text = "1. Indique el Motivo de Entrada:"
                            binding.txtInputManual.visibility = View.GONE
                            binding.btnSiguientePasoManual.visibility = View.GONE

                            viewModel.listadoMotivosPredefinidos.forEach { motivo ->
                                inyectarBotonEnMalla(motivo) {
                                    controlarEstadoMicrofono(habilitar = false) // Pausa preventiva táctil
                                    viewModel.seleccionarMotivo(motivo)
                                }
                            }
                        }

                        CaptureStep.SELECCION_CALLE -> {
                            controlarEstadoMicrofono(habilitar = true)
                            binding.lblInstruccionSeccion.text = "2. Seleccione la Calle de Destino:"
                            binding.txtInputManual.visibility = View.GONE
                            binding.btnSiguientePasoManual.visibility = View.GONE

                            val callesUnicas = viewModel.todosLosDomiciliosCache.map { it[0].toString() }.distinct()
                            callesUnicas.forEach { calle ->
                                inyectarBotonEnMalla(calle) {
                                    controlarEstadoMicrofono(habilitar = false)
                                    viewModel.seleccionarCalle(calle)
                                }
                            }
                        }

                        CaptureStep.SELECCION_NUMERO -> {
                            controlarEstadoMicrofono(habilitar = true)
                            binding.lblInstruccionSeccion.text = "3. Seleccione el Número de Casa:"
                            binding.txtInputManual.visibility = View.GONE
                            binding.btnSiguientePasoManual.visibility = View.GONE

                            val numerosFiltrados = viewModel.todosLosDomiciliosCache
                                .filter { it[0].toString() == state.calleInput }
                                .map { it[1].toString() }

                            numerosFiltrados.forEach { numero ->
                                inyectarBotonEnMalla(numero) {
                                    controlarEstadoMicrofono(habilitar = false)
                                    viewModel.seleccionarNumero(numero)
                                }
                            }
                        }

                        CaptureStep.CAPTURA_NOMBRE -> {
                            // En pasos de teclado manual, apagamos el micro para liberar el búfer táctil al 100%
                            controlarEstadoMicrofono(habilitar = false)
                            binding.lblInstruccionSeccion.text = "4. Ingrese Nombre del Conductor:"
                            binding.txtInputManual.visibility = View.VISIBLE
                            binding.txtInputManual.hint = "Escriba el nombre aquí..."
                            binding.btnSiguientePasoManual.visibility = View.VISIBLE
                            binding.btnSiguientePasoManual.text = "Continuar ➡️"
                        }

                        CaptureStep.CAPTURA_PLACA -> {
                            controlarEstadoMicrofono(habilitar = false)
                            binding.lblInstruccionSeccion.text = "5. Ingrese Matrícula Vehicular:"
                            binding.txtInputManual.visibility = View.VISIBLE
                            binding.txtInputManual.hint = "Escriba la placa aquí..."
                            binding.btnSiguientePasoManual.visibility = View.VISIBLE
                            binding.btnSiguientePasoManual.text = "Solicitar Autorización 🔐"
                        }

                        CaptureStep.PROCESANDO_AUTORIZACION -> {
                            controlarEstadoMicrofono(habilitar = false)
                            binding.lblInstruccionSeccion.text = "Procesando Estatus de Seguridad..."
                            binding.txtInputManual.visibility = View.GONE
                            binding.btnSiguientePasoManual.visibility = View.GONE

                            if (state.whatsappStatus is WhatsappAuthStatus.Solicitando) {
                                inyectarBotonEnMalla("⌛ Esperando Respuesta WhatsApp (Render API)...") {}
                            }
                        }
                    }
                }
            }
        }
    }

    private fun controlarEstadoMicrofono(habilitar: Boolean) {
        microfonoHabilitadoPorPaso = habilitar
        if (!habilitar) {
            speechRecognizer?.stopListening()
        } else {
            encenderMicrofonoAsistente()
        }
    }

    private fun inyectarBotonEnMalla(texto: String, alPresionar: () -> Unit) {
        val chip = Chip(this).apply {
            text = texto
            isClickable = true
            textSize = 20f
            setTextColor(Color.WHITE)
            setChipBackgroundColorResource(android.R.color.darker_gray)
            // Al hacer clic, la acción responde de inmediato al primer toque
            setOnClickListener { alPresionar() }
        }
        binding.containerGridBotones.addView(chip)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_REQUEST_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            inicializarSpeechRecognizer()
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        viewModel.iniciarTimerInactividad()
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
    }
}