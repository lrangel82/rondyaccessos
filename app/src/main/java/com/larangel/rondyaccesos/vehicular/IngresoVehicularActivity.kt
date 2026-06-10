package com.larangel.rondyaccesos.vehicular

import android.Manifest
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.mlkit.vision.common.InputImage
import com.larangel.rondyaccesos.RondyApplication
import com.larangel.rondyaccesos.databinding.ActivityIngresoVehicularBinding
import com.larangel.rondyaccesos.models.CaptureStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.larangel.rondyaccesos.ui.VigilanteConfigActivity
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.speech.tts.UtteranceProgressListener
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.larangel.rondyaccesos.models.network.WhatsappAuthStatus
import com.larangel.rondyaccesos.R
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import androidx.core.graphics.toColorInt


class IngresoVehicularActivity : AppCompatActivity() {

    private lateinit var binding: ActivityIngresoVehicularBinding
    private val viewModel: IngresoVehicularViewModel by viewModels {
        IngresoVehicularViewModelFactory(application as RondyApplication)
    }
    private var whatsappDialog: AlertDialog? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private var speechIntent: Intent? = null
    private val RECORD_AUDIO_REQUEST_CODE = 101

    private var reintentandoEscucha = false
    private var ultimoPasoProcesado: CaptureStep? = null
    private var microfonoHabilitadoPorPaso = true

    // LibVLC Native Player parameters
    private var libVLC: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null

    // ML Kit Local OCR Engine instance
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var ocrJob: kotlinx.coroutines.Job? = null

    // QR -- Parámetros de control de hilos para CameraX Frontal
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraExecutor: ExecutorService? = Executors.newSingleThreadExecutor()
    private val qrScannerClient = BarcodeScanning.getClient()
    private var lensFacingSeleccionado = CameraSelector.LENS_FACING_FRONT // ◄ Inicia en Frontal por defecto
    private val solicitarPermisoCamaraLanzador = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permisoConcedido ->
        if (permisoConcedido) {
            // Si el usuario presiona "Permitir", encendemos CameraX de inmediato
            binding.cameraXQrPreview.post {
                configurarEIniciarCameraXFrontal()
            }
        } else {
            Toast.makeText(this, "Se requiere el permiso de cámara para escanear códigos QR", Toast.LENGTH_LONG).show()
            viewModel.reportarFallaConexionQr()
        }
    }

    private var currentPlacaMedia: org.videolan.libvlc.Media? = null
    private val pixelCopyMutex = kotlinx.coroutines.sync.Mutex()
    private lateinit var lastBitmapReadedPlacas: Bitmap

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIngresoVehicularBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configurarAccionesGlobales()

        lastBitmapReadedPlacas = getBitmapFailCapture() //Inizializar

        //Placas
        inicializarContenedorVLC()
        configurarListenersDeFalla()
        observarCicloDeVidaDeCamaras()

        //QR
        verificarPermisoDeCamaraAutomatizado()
        //Microfono
        verificarPermisosYConfigurarEscucha()
        //Internet Sockets
        verificarConexionYFijarAjustes()

        //Whatsapp ciclo
        registrarObservadorWhatsapp()

        //Registrar ciclo
        observarCicloDelAsistente()
    }
    override fun onStart() {
        super.onStart()
        val app = application as RondyApplication

        // Register the functional extractor to serve the current view frames
        app.imagenesCallBackActivo = { tipoCamara ->
            when (tipoCamara) {
                "PLACA" -> obtenerUltimoFramePixelCopyPlacas()
                "ROSTRO" -> binding.cameraXQrPreview.bitmap
                else -> null
            }
        }
    }
    override fun onStop() {
        super.onStop()
        // Nullify on stop to prevent layout context leaks during app minimization
        (application as RondyApplication).registroCallbackActivo = null
    }

    private fun configurarAccionesGlobales() {
        binding.btnReiniciarRegistro.setOnClickListener {
            ultimoPasoProcesado = null // Forzar refresco visual
            ocultarTecladoVirtual()

            whatsappDialog?.dismiss()
            whatsappDialog = null

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
                    binding.txtInputManual.setText("")
                    ocultarTecladoVirtual()
                }
                CaptureStep.CAPTURA_PLACA -> {
                    //viewModel.dispararProtocoloDeSeguridadYWhatsApp(valorManual, "Captura Manual Integrada")
                    viewModel.guardarPlacaYSolicitarAutorizacion(valorManual)
                    binding.txtInputManual.setText("")
                    ocultarTecladoVirtual()
                }
                else -> {}
            }
        }
        binding.btnMenuConfiguracion.setOnClickListener {
            // 1. Detener por completo los hilos de procesamiento y cerrar streams
            apagarStreamVideoRtspPlacas() // Apaga LibVLC y el OcrJob

            cameraProvider?.unbindAll() // Apaga CameraX Frontal de inmediato
            cameraExecutor?.shutdown()  // Destruye el pool de hilos de la cámara

            Toast.makeText(this, "Liberando hardware de video. Abriendo configuración...", Toast.LENGTH_SHORT).show()

            // 2. Redireccionar de vuelta a la pantalla de configuración de satélites
            val intentConfig = Intent(this, VigilanteConfigActivity::class.java)
            startActivity(intentConfig)

            finish() // Destruir esta Activity para limpiar la RAM por completo
        }

        binding.btnSwitchCamera.setOnClickListener {
            // Conmutamos de forma atómica el lente actual
            lensFacingSeleccionado = if (lensFacingSeleccionado == CameraSelector.LENS_FACING_FRONT) {
                CameraSelector.LENS_FACING_BACK // Pasa a Trasera
            } else {
                CameraSelector.LENS_FACING_FRONT // Regresa a Frontal
            }

            Toast.makeText(this, "Cambiando de cámara...", Toast.LENGTH_SHORT).show()

            // Forzamos el reinicio y re-vinculación de CameraX con el nuevo lente asignado
            cameraProvider?.let { provider ->
                binding.cameraXQrPreview.post {
                    configurarEIniciarCameraXFrontal() // Re-ejecuta la configuración del hardware
                }
            }
        }

        binding.btnConfigurarCamaraPlacas.setOnClickListener {
            // Apagamos preventivamente el stream actual para liberar la memoria de LibVLC
            apagarStreamVideoRtspPlacas()

            // Abrimos la pantalla de escaneo y descubrimiento LAN
            val intentScanner = Intent(this, DescubrimientoCamarasActivity::class.java)
            startActivity(intentScanner)
        }

        binding.layoutSplash.setOnClickListener {
            viewModel.despertarAsistente()
        }
    }

    // --- PERMISOS
    private fun verificarPermisosYConfigurarEscucha() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_REQUEST_CODE)
        } else {
            inicializarSpeechRecognizer()
        }
    }
    private fun verificarPermisoDeCamaraAutomatizado() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> {
                // El permiso ya existe (flujo normal de todos los días), encendemos la cámara frontal
                binding.cameraXQrPreview.post {
                    configurarEIniciarCameraXFrontal()
                }
            }
            else -> {
                // El permiso no existe (primer arranque de la app), lanzamos el cuadro de diálogo flotante del sistema
                solicitarPermisoCamaraLanzador.launch(Manifest.permission.CAMERA)
            }
        }
    }
    fun verificarConexionYFijarAjustes() {
        val connectivityManager = this.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)

        // Verifica si hay una red activa con acceso a Internet
        val tieneInternet = capabilities != null && (
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                )

        if (!tieneInternet) {
            Toast.makeText(this, "Se requiere conexión a Internet para esta app", Toast.LENGTH_LONG).show()

            // Abre la pantalla de ajustes de conectividad (Wi-Fi / Datos)
            val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            this.startActivity(intent)
        }
    }

    // --- MICROFONO ----
    private fun inicializarSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

            // 🚀 SOLUCIÓN 1: Forzar de manera estricta el idioma Español (es-MX) con metadatos completos
            speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-MX")
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "es-MX")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }

            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    binding.lbStatusAsistente?.text = "🤖 Asistente Activo. Micrófono escuchando..."
                    actualizarIndicadorVisualVoz("ESCUCHANDO 🎤", "#00E676")
                }

                override fun onBeginningOfSpeech() { actualizarIndicadorVisualVoz("CAPTANDO VOZ 🗣️", "#2196F3") }
                override fun onRmsChanged(rmsdB: Float) {} // Mantener vacío para no congelar rendimiento
                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    binding.lbStatusAsistente?.text = "🤖 Procesando voz..."
                    actualizarIndicadorVisualVoz("PROCESANDO 🧠", "#FFEB3B")
                }

                override fun onError(error: Int) {
                    val motivoError = when (error) {
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "TIMEOUT (SILENCIO)"
                        SpeechRecognizer.ERROR_NO_MATCH -> "NO SE ENTENDIÓ"
                        SpeechRecognizer.ERROR_AUDIO -> "ERROR HARDWARE"
                        SpeechRecognizer.ERROR_CLIENT -> "ERROR CLIENTE"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR EN PERMISOS"
                        SpeechRecognizer.ERROR_NETWORK -> "ERROR DE NETWORK"
                        else -> "ERROR $error"
                    }
                    actualizarIndicadorVisualVoz("PAUSA ($motivoError)", "#FF5252")

                    if (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH) {
                        intentarReinicioSeguroDelMicrofono(delayMillis = 2000)
                    }
                }

                override fun onResults(results: Bundle?) {
                    actualizarIndicadorVisualVoz("LLAMANDO IA 🌐", "#FFEB3B")
                    val partidos = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val textoEscuchadoCompleto = partidos?.get(0) ?: ""

                    if (textoEscuchadoCompleto.isNotEmpty()) {
                        onTranscripcionDeVozRecibidaPorAsistente(textoEscuchadoCompleto)
                    } else {
                        intentarReinicioSeguroDelMicrofono(delayMillis = 1500)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            // 🚀 SOLUCIÓN 3: Configurar el Altavoz (TTS) para apagar el micrófono mientras habla
            configurarCicloDeVidaDelAltavozTTS()

            //encenderMicrofonoAsistente()
        }
    }
    private fun configurarCicloDeVidaDelAltavozTTS() {
        val app = application as com.larangel.rondyaccesos.RondyApplication
        // Accedemos de forma segura a la instancia lazy de la IA que creamos en la Fase 5
        //val asistenteVoz = app.generativeModel // O tu wrapper de la clase GeminiVoiceAssistant

        // Suponiendo que tienes acceso al objeto TextToSpeech interno de GeminiVoiceAssistant,
        // le inyectamos un Listener para saber cuándo empieza y cuándo termina de hablar:
        app.geminiVoiceAssistant.setTtsProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                // En cuanto el parlante empiece a hablar, apagamos de inmediato el micrófono
                // Esto elimina interferencias y permite que el visitante escuche fuerte y claro
                lifecycleScope.launch(Dispatchers.Main) {
                    speechRecognizer?.stopListening()
                    binding.lbStatusAsistente?.text = "🔊 Asistente Hablando..."
                    // 🟣 MORADO: El altavoz del sistema está activo, el micrófono se apaga por seguridad
                    actualizarIndicadorVisualVoz("ASISTENTE HABLANDO 🔊", "#9C27B0")
                }
            }

            override fun onDone(utteranceId: String?) {
                // En cuanto el altavoz termine de hablar, volvemos a encender el micrófono automáticamente
                lifecycleScope.launch(Dispatchers.Main) {
                    delay(500) // Pausa de alivio acústico
                    actualizarIndicadorVisualVoz("REINICIANDO MICRO...", "#FFEB3B")
                    encenderMicrofonoAsistente()
                }
            }

            override fun onError(utteranceId: String?) {
                lifecycleScope.launch(Dispatchers.Main) { encenderMicrofonoAsistente() }
            }
        })
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
        //intentarReinicioSeguroDelMicrofono(delayMillis = 1200)
    }
    private fun actualizarIndicadorVisualVoz(estadoTexto: String, colorHex: String) {
        // Garantizar que la mutación corra en el hilo principal de la UI
        runOnUiThread {
            binding.lblEstadoHardwareVoz.text = estadoTexto

            // Crear un fondo circular o cuadrado de color dinámico
//            val drawable = android.graphics.drawable.GradientDrawable().apply {
//                shape = android.graphics.drawable.GradientDrawable.OVAL
//                setColor(Color.parseColor(colorHex))
//            }
//            binding.indicadorColorMicro.background = drawable
            binding.indicadorColorMicro.backgroundTintList = ColorStateList.valueOf(colorHex.toColorInt())
            binding.lblEstadoHardwareVoz.setTextColor(colorHex.toColorInt())
        }
    }

    // --- RENDERIZACIÓN REACTIVA CONTROLADA (SOLUCIÓN AL COINCIDIR CLICS) ---
    private fun observarCicloDelAsistente() {
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                val datosAcumulados = StringBuilder("Datos: ")
                if (state.tipoInput.isNotEmpty()) datosAcumulados.append(" ${state.tipoInput} | ")
                if (state.calleInput.isNotEmpty()) datosAcumulados.append("Calle: ${state.calleInput} | ")
                if (state.numeroInput.isNotEmpty()) datosAcumulados.append("#: ${state.numeroInput} | ")
                if (state.conductorInput.isNotEmpty()) datosAcumulados.append("N: ${state.conductorInput}")

                // Componentes volátiles que sí pueden refrescarse constantemente sin romper botones
                binding.lblTopMensaje.text = state.lblTopMensaje
                binding.lblTimerInactividad.text = "Inactividad: ${state.segundosRestantes}s"
                binding.lblSubtitulosAsistente.text = state.subtitulosAsistente
                binding.lblHistorialDatos?.text = datosAcumulados.toString()
                binding.lblHistorialDatos?.visibility = if (datosAcumulados.length > 8) View.VISIBLE else View.GONE


                // 🛑 CORRECCIÓN ANR CRÍTICA: Solo regenerar los Chips si el paso cambió físicamente
                if (ultimoPasoProcesado != state.currentStep) {
                    ultimoPasoProcesado = state.currentStep

                    binding.containerGridBotones.removeAllViews() // Vaciar de forma controlada

                    when (state.currentStep) {
                        CaptureStep.SELECCION_MOTIVO -> {
                            controlarEstadoMicrofono(habilitar = true)
                            binding.lblInstruccionSeccion.text = "1. Indique el Motivo de Entrada:"
                            binding.ScrollViewGridBotones.visibility = View.VISIBLE
                            binding.txtInputManual.visibility = View.GONE
                            binding.btnSiguientePasoManual.visibility = View.GONE

                            viewModel.listadoMotivosPredefinidos.forEach { motivo ->
                                inyectarBotonEnMalla(motivo.name) {
                                    controlarEstadoMicrofono(habilitar = false) // Pausa preventiva táctil
                                    viewModel.seleccionarMotivo(motivo.name)
                                }
                            }
                        }

                        CaptureStep.SELECCION_CALLE -> {
                            controlarEstadoMicrofono(habilitar = true)
                            binding.lblInstruccionSeccion.text = "2. Seleccione la Calle de Destino:"
                            binding.ScrollViewGridBotones.visibility = View.VISIBLE
                            binding.txtInputManual.visibility = View.GONE
                            binding.btnSiguientePasoManual.visibility = View.GONE

                            // Evaluar si el ViewModel guardó coincidencias de la búsqueda difusa de Gemini
                            val fuentesDeCalles = if (state.listaDomiciliosFiltrados.isNotEmpty()) {
                                // Extraer únicamente las calles que sobrevivieron al filtro difuso
                                state.listaDomiciliosFiltrados.map { it.getOrNull(0)?.toString() ?: "" }.distinct()
                            } else {
                                // Si no hay filtro de Gemini previo, muestra todas las calles configuradas por defecto
                                viewModel.todosLosDomiciliosCache.map { it.getOrNull(0)?.toString() ?: "" }.distinct()
                            }
                            fuentesDeCalles.forEach { calle ->
                                inyectarBotonEnMalla(calle) {
                                    controlarEstadoMicrofono(habilitar = false)
                                    viewModel.seleccionarCalle(calle)
                                }
                            }
                        }

                        CaptureStep.SELECCION_NUMERO -> {
                            controlarEstadoMicrofono(habilitar = true)
                            binding.lblInstruccionSeccion.text = "3. Seleccione el Número de Casa:"
                            binding.ScrollViewGridBotones.visibility = View.VISIBLE
                            binding.txtInputManual.visibility = View.GONE
                            binding.btnSiguientePasoManual.visibility = View.GONE

                            val fuentesDeNumeros = if (state.listaDomiciliosFiltrados.isNotEmpty()) {
                                state.listaDomiciliosFiltrados
                                    .filter { (it.getOrNull(0)?.toString() ?: "").equals(state.calleInput, ignoreCase = true) }
                                    .map { it.getOrNull(1)?.toString() ?: "" }
                            } else {
                                viewModel.todosLosDomiciliosCache
                                    .filter { (it.getOrNull(0)?.toString() ?: "").equals(state.calleInput, ignoreCase = true) }
                                    .map { it.getOrNull(1)?.toString() ?: "" }
                            }

                            fuentesDeNumeros.forEach { numero ->
                                inyectarBotonEnMalla(numero) {
                                    controlarEstadoMicrofono(habilitar = false)
                                    viewModel.seleccionarNumero(numero)
                                }
                            }
                        }

                        CaptureStep.CONFRMAR_DOMICILIO -> {
                            controlarEstadoMicrofono(habilitar = true)
                            binding.lblInstruccionSeccion.text = "3. Confirme su direccion, es correcta ${state.calleInput} : ${state.numeroInput}?"
                            binding.ScrollViewGridBotones.visibility = View.VISIBLE
                            binding.txtInputManual.visibility = View.GONE
                            binding.btnSiguientePasoManual.visibility = View.GONE

                            listOf<String>("SI ${state.calleInput}:${state.numeroInput}","NO").forEach { respuesta ->
                                inyectarBotonEnMalla(respuesta) {
                                    controlarEstadoMicrofono(habilitar = false)
                                    viewModel.confirmarDireccion(respuesta)
                                }
                            }
                        }

                        CaptureStep.PREGUNTA_OTRA_DIRECCION -> {
                            controlarEstadoMicrofono(habilitar = true)
                            binding.lblInstruccionSeccion.text = "¿El transportista va a entregar a otro domicilio?"
                            binding.ScrollViewGridBotones.visibility = View.VISIBLE
                            binding.txtInputManual.visibility = View.GONE
                            binding.btnSiguientePasoManual.visibility = View.GONE

                            inyectarBotonEnMalla("SÍ, IR A OTRA DIRECCIÓN") {
                                viewModel.responderPreguntaOtraDireccion(quiereOtra = true)
                            }
                            inyectarBotonEnMalla("NO, FINALIZAR CAPTURA") {
                                viewModel.responderPreguntaOtraDireccion(quiereOtra = false)
                            }
                        }

                        CaptureStep.CAPTURA_NOMBRE -> {
                            // En pasos de teclado manual, apagamos el micro para liberar el búfer táctil al 100%
                            controlarEstadoMicrofono(habilitar = true)
                            binding.lblInstruccionSeccion.text = "4. Ingrese Nombre del Conductor:"
                            binding.ScrollViewGridBotones.visibility = View.GONE
                            binding.txtInputManual.visibility = View.VISIBLE
                            binding.txtInputManual.hint = "Escriba el nombre aquí..."
                            binding.btnSiguientePasoManual.visibility = View.VISIBLE
                            binding.btnSiguientePasoManual.text = "Continuar ➡️"
                        }

                        CaptureStep.CAPTURA_PLACA -> {
                            controlarEstadoMicrofono(habilitar = true)
                            binding.lblInstruccionSeccion.text = "5. Ingrese Matrícula Vehicular:"
                            binding.ScrollViewGridBotones.visibility = View.GONE
                            binding.txtInputManual.visibility = View.VISIBLE
                            binding.txtInputManual.hint = "Escriba la placa aquí..."
                            binding.btnSiguientePasoManual.visibility = View.VISIBLE
                            binding.btnSiguientePasoManual.text = "Solicitar Autorización 🔐"
                        }

                        CaptureStep.PROCESANDO_AUTORIZACION -> {
                            ocultarTecladoVirtual()
                            controlarEstadoMicrofono(habilitar = false)
                            binding.lblInstruccionSeccion.text = "Procesando Estatus de Seguridad..."
                            binding.ScrollViewGridBotones.visibility = View.GONE
                            binding.txtInputManual.visibility = View.GONE
                            binding.btnSiguientePasoManual.visibility = View.GONE

                            if (state.whatsappStatus is WhatsappAuthStatus.Solicitando) {
                                inyectarBotonEnMalla("⌛ Esperando Respuesta WhatsApp (Render API)...") {}
                            }
                        }
                    }
                }

                //PANEL MESAJE ACCESO Authorizado/Denegado
                if (state.mostrarPanelResultadoDerecho) {
                    binding.layoutVisualEstatusDerecho.visibility = View.VISIBLE
                    if (state.resultadoEsAutorizado) {
                        // High-contrast: Green Background with Black Text
                        binding.layoutVisualEstatusDerecho.setBackgroundColor(Color.parseColor("#4CAF50"))
                        binding.tvEstatusGrande.setTextColor(Color.BLACK)
                        binding.tvEstatusDetallePeque.setTextColor(Color.BLACK)
                        binding.tvEstatusGrande.text = "AUTORIZADO"
                    } else {
                        // High-contrast: Red Background with White Text
                        binding.layoutVisualEstatusDerecho.setBackgroundColor(Color.parseColor("#E53935"))
                        binding.tvEstatusGrande.setTextColor(Color.WHITE)
                        binding.tvEstatusDetallePeque.setTextColor(Color.WHITE)
                        binding.tvEstatusGrande.text = "DENEGADO"
                    }
                    binding.tvEstatusDetallePeque.text = state.resultadoMotivoDetalle
                } else {
                    binding.layoutVisualEstatusDerecho.visibility = View.GONE
                }

                //SPLASH INACTIVIDAD
                binding.layoutSplash.visibility = if (state.mostrarSplash) View.VISIBLE else View.GONE

            }
        }
    }
    private fun controlarEstadoMicrofono(habilitar: Boolean) {
        microfonoHabilitadoPorPaso = habilitar
        if (!habilitar) {
            speechRecognizer?.stopListening()
            // ⚪ GRIS: Deshabilitado explícitamente porque estás usando el teclado manual
            actualizarIndicadorVisualVoz("TECLADO ACTIVO ⌨️", "#9E9E9E")
        } else {
            encenderMicrofonoAsistente()
        }
    }

    private fun inyectarBotonEnMalla(texto: String, alPresionar: () -> Unit) {
        val chip = Chip(this).apply {
            text = texto
            isClickable = true
            setChipBackgroundColorResource(android.R.color.transparent)
            chipStrokeWidth = 2f
            setChipStrokeColorResource(android.R.color.white) // O tu color #00D4FF
            setTextColor(Color.WHITE)
            textSize = 18f
            //setTextColor(Color.WHITE)
            //setChipBackgroundColorResource(android.R.color.darker_gray)
            // Al hacer clic, la acción responde de inmediato al primer toque
            setOnClickListener { alPresionar() }
        }
        binding.containerGridBotones.addView(chip)
    }

    // --- CAMARAS
    private fun inicializarContenedorVLC() {
        val args = ArrayList<String>().apply {
            add("--rtsp-tcp") // Force RTSP over TCP transport layer to avoid dropped frames
            add("--network-caching=300")       // Reduce el búfer de red a 300ms para tener video en tiempo real (Mínima latencia)
            add("--clock-jitter=0")            // Deshabilita el control de jitter para evitar retrasos artificiales
            add("--clock-synchro=0")

            // 2. Optimización de Desempeño y CPU
            add("--drop-late-frames")          // Descarta frames atrasados inmediatamente si el procesador se satura
            add("--skip-frames")               // Permite saltar cuadros para mantener el flujo síncrono con el lector OCR
            add("--avcodec-hw=any")            // Fuerza la decodificación por hardware nativa del procesador del dispositivo
            add("--avcodec-skiploopfilter=4")  // Reduce la calidad de postprocesado del códec para liberar CPU al 100%

            add("--vout=android_display")   // Utilizar el motor de renderizado estándar de Android
            add("--android-display-chroma=RV32") // Forzar croma de color estándar de 32 bits compatible con layouts
            add("--video-wallpaper")        // Deshabilitar el modo exclusivo de pantalla completa de hardware
        }
        libVLC = LibVLC(this, args)
        mediaPlayer = MediaPlayer(libVLC)
    }
    private fun configurarListenersDeFalla() {
        // Re-routing commands if the guard fixes a camera configuration URL on the interface
        binding.btnReconectarRtsp.setOnClickListener {
            val urlDigitada = binding.txtUrlRtspFix.text.toString().trim()
            if (urlDigitada.isNotEmpty()) {
                viewModel.actualizarUrlPlacasRtsp(urlDigitada)
            }
        }

//        binding.btnCambiarAIP.setOnClickListener {
//            viewModel.cambiarOrigenQrAHardwareIp("rtsp://192.168.1.151:554/live")
//            binding.layoutFixCamaraQr.visibility = View.GONE
//            binding.cameraXQrPreview.visibility = View.GONE
//            binding.vlcQrSurfaceFallback.visibility = View.VISIBLE
//        }
    }
    private fun observarCicloDeVidaDeCamaras() {
        // 1. Reactive loop managing the playback state of the external IP camera
        lifecycleScope.launch {
            viewModel.vlcStreamActive.collectLatest { activo ->
                if (activo) {
                    encenderStreamVideoRtspPlacas()
                } else {
                    apagarStreamVideoRtspPlacas()
                }
            }
        }

        // 2. Reactive flow controlling the display of the Reconfiguration Layout Overlays
        lifecycleScope.launch {
            viewModel.camaraPlacaFalla.collectLatest { fallando ->
                binding.layoutFixCamaraPlaca.visibility = if (fallando) View.VISIBLE else View.GONE
            }
        }

//        lifecycleScope.launch {
//            viewModel.camaraQrFalla.collectLatest { fallando ->
//                binding.layoutFixCamaraQr.visibility = if (fallando) View.VISIBLE else View.GONE
//            }
//        }
    }
    private fun encenderStreamVideoRtspPlacas() {
        try {
            // Ensure any existing memory pointers are fully cleared out cleanly before attaching frames
            ocrJob?.cancel()
            ocrJob = null

            currentPlacaMedia?.release()
            currentPlacaMedia = null

            // Bind the LibVLC media player directly to the XML view texture surface layout
            mediaPlayer?.attachViews(binding.vlcPlacaSurface, null, true, false)

            mediaPlayer?.setEventListener { evento ->
                when (evento.type) {
                    MediaPlayer.Event.EncounteredError -> {
                        Log.e("VlcNetwork", "Error de red asíncrono detectado en el stream RTSP (IP inválida o caída).")
                        lifecycleScope.launch(Dispatchers.Main) {
                            binding.txtUrlRtspFix.setText(viewModel.urlCamaraPlacasRtsp)
                            viewModel.reportarFallaConexionPlacas()
                        }
                    }
                    MediaPlayer.Event.Buffering -> {
                        Log.d("VlcNetwork", "Buffering del stream: ${evento.buffering}%")
                    }
                    MediaPlayer.Event.Playing -> {
                        Log.d("VlcNetwork", "¡Stream RTSP conectado y reproduciendo con éxito!")
                    }
                }
            }

            // Explicitly track allocated reference via currentPlacaMedia to clear later
            val mediaInstance = Media(libVLC, android.net.Uri.parse(viewModel.urlCamaraPlacasRtsp)).apply {
                setHWDecoderEnabled(true, false) // Enable native hardware acceleration
            }

            currentPlacaMedia = mediaInstance
            mediaPlayer?.media = currentPlacaMedia
            mediaPlayer?.play()

            // SOFTWARE PANIC CONNECTIONS TIMEOUT BUFFER
            lifecycleScope.launch(Dispatchers.Main) {
                delay(5000) // 5 seconds maximum tolerance frame
                // Ensure context is still alive and didn't change before execution updates
                if (mediaPlayer?.isPlaying == false && viewModel.vlcStreamActive.value) {
                    Log.w("VlcNetwork", "Timeout de 5 segundos alcanzado sin respuesta de la cámara IP.")
                    Toast.makeText(applicationContext, "Timeout alcanzado sin respuesta de la cámara PLACAS", Toast.LENGTH_LONG).show()
                    apagarStreamVideoRtspPlacas()
                    binding.txtUrlRtspFix.setText(viewModel.urlCamaraPlacasRtsp)
                    viewModel.reportarFallaConexionPlacas()
                }
            }

            // Start the asynchronous snapshot extraction engine loop for local OCR parsing
            iniciarBucleProcesamientoOcrPlacas()

        } catch (e: Exception) {
            Log.e("VlcStream", "Fallo al inicializar texturas de video: ${e.message}")
            viewModel.reportarFallaConexionPlacas()
        }
    }
    private fun apagarStreamVideoRtspPlacas() {
        try {
            ocrJob?.cancel() // Stop the OCR extractor loop immediately
            ocrJob = null

            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    player.stop()
                }
                player.detachViews()
                player.media = null
            }

            // CRITICAL SAVINGS FIX: Force native C++ memory reclamation block instantly
            currentPlacaMedia?.let { media ->
                if (!media.isReleased) {
                    media.release()
                }
            }
            currentPlacaMedia = null

            Log.d("VlcHardware", "Stream RTSP cerrado e hilos nativos liberados correctamente.")
        } catch (e: Exception) {
            Log.e("VlcHardware", "Error handling structural release sequence on player stop", e)
        }
    }
    /**
     * 👁️ CONTINUOUS EXTRACTOR LOOP: Pulls video bitmaps from the LibVLC texture,
     * feeds them locally to ML Kit, and stops as soon as a text match occurs.
     */
    private fun iniciarBucleProcesamientoOcrPlacas() {
        ocrJob?.cancel()
        ocrJob = lifecycleScope.launch(Dispatchers.Default) {
            var surfaceInternaVLC: SurfaceView? = null

            withContext(Dispatchers.Main) {
                for (i in 0 until binding.vlcPlacaSurface.childCount) {
                    val child = binding.vlcPlacaSurface.getChildAt(i)
                    if (child is SurfaceView) {
                        surfaceInternaVLC = child
                        break
                    }
                }
            }

            // Loop continues safely while streaming process parameters are active
            while (mediaPlayer?.isPlaying == true) {
                delay(1000) // Process one frame every second to protect CPU profiles

                val surfaceTarget = surfaceInternaVLC
                // Safety boundary: Ensure context surface state is completely solid before copy triggers
                if (surfaceTarget != null && surfaceTarget.holder.surface.isValid && mediaPlayer?.isPlaying == true) {

                    //val bitmapSnapshot = Bitmap.createBitmap(
                    lastBitmapReadedPlacas= Bitmap.createBitmap(
                        surfaceTarget.width,
                        surfaceTarget.height,
                        Bitmap.Config.ARGB_8888
                    )

                    withContext(Dispatchers.Main) {
                        // Double check validation before launching pixel frame cloning transactions
                        if (surfaceTarget.holder.surface.isValid) {
                            PixelCopy.request(
                                surfaceTarget,
                                //bitmapSnapshot,
                                lastBitmapReadedPlacas,
                                { resultado ->
                                    if (resultado == PixelCopy.SUCCESS) {
                                        procesarOcrEnHiloDeFondo(lastBitmapReadedPlacas)//bitmapSnapshot)
                                    } else {
                                        //bitmapSnapshot.recycle() // Avoid dirty leaking allocations
                                        lastBitmapReadedPlacas.recycle()
                                    }
                                },
                                Handler(Looper.getMainLooper())
                            )
                        } else {
                            //bitmapSnapshot.recycle()
                            lastBitmapReadedPlacas.recycle()
                        }
                    }
                }
            }
        }
    }
    private fun procesarOcrEnHiloDeFondo(bitmap: Bitmap) {
        val image = InputImage.fromBitmap(bitmap, 0)

        textRecognizer.process(image)
            .addOnSuccessListener { visionText ->
                // Recycle processed image structure memory rapidly
                bitmap.recycle()
                for (block in visionText.textBlocks) {
                    val textoEncontrado = block.text.trim()

                    // Match criteria regex filter tracking patterns
                    if (textoEncontrado.matches(Regex("^[a-zA-Z0-9\\s-]{3,8}$"))) {
                        viewModel.registrarPlacaDetectadaPorOcr(textoEncontrado)
                        break
                    }
                }
            }
            .addOnFailureListener {
                bitmap.recycle() // Clean leak tracks on structural engine failures
            }
    }
    private fun obtenerUltimoFramePixelCopyPlacas(): Bitmap {
        if (lastBitmapReadedPlacas == null || lastBitmapReadedPlacas.isRecycled) {
            return getBitmapFailCapture()
        }else {
            return lastBitmapReadedPlacas
        }
        ///////////
//        return runBlocking(Dispatchers.IO) {
//            try {
//                pixelCopyMutex.withLock {
//                    var surfaceInternaVLC: SurfaceView? = null
//
//                    withContext(Dispatchers.Main) {
//                        for (i in 0 until binding.vlcPlacaSurface.childCount) {
//                            val child = binding.vlcPlacaSurface.getChildAt(i)
//                            if (child is SurfaceView) {
//                                surfaceInternaVLC = child
//                                break
//                            }
//                        }
//                    }
//
//                    val targetSurface = surfaceInternaVLC
//                    // Boundary safety evaluation check routines
//                    if (targetSurface == null || !targetSurface.holder.surface.isValid || mediaPlayer?.isPlaying != true) {
//                        Log.e(
//                            "PixelCopyFrame",
//                            "VLC Internal SurfaceView is invalid or player is stopped. Dispatched fallback."
//                        )
//                        return@withLock fallbackBitmap
//                    }
//                    val width = targetSurface.width
//                    val height = targetSurface.height
//                    if (width <= 0 || height <= 0) {
//                        return@withLock fallbackBitmap
//                    }
//
//                    val bitmapSnapshot = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
//
//                    // Execute quick texture copy under strict time monitoring boundaries
//                    val copyResult = withTimeoutOrNull(800) {
//                        suspendCancellableCoroutine { continuation ->
//                            PixelCopy.request(
//                                targetSurface,
//                                bitmapSnapshot,
//                                { resultado ->
//                                    if (resultado == PixelCopy.SUCCESS) {
//                                        if (continuation.isActive) continuation.resume(true){}
//                                    } else {
//                                        Log.e(
//                                            "PixelCopyFrame",
//                                            "Hardware GPU PixelCopy failed. Code: $resultado"
//                                        )
//                                        if (continuation.isActive) continuation.resume(false){}
//                                    }
//                                },
//                                Handler(Looper.getMainLooper())
//                            )
//                        }
//                    }
//
//                    if (copyResult == true) {
//                        fallbackBitmap.recycle()
//                        return@withLock bitmapSnapshot
//                    } else {
//                        Log.w(
//                            "PixelCopyFrame",
//                            "PixelCopy operation timed out or rejected. Returning placeholder canvas."
//                        )
//                        bitmapSnapshot.recycle()
//                        return@withLock fallbackBitmap
//                    }
//                }
//            } catch (e: Exception) {
//                Log.e("PixelCopyFrame", "Exception caught mapping texture stream frame elements", e)
//                return@runBlocking fallbackBitmap
//            }
//        }
    }
    private fun getBitmapFailCapture(): Bitmap{
        val fallbackBitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(fallbackBitmap)
        canvas.drawColor(Color.DKGRAY)
        val paint = Paint().apply {
            color = Color.WHITE
            textSize = 32f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("EVIDENCIA AUTOMATICA - NO RTSP FEED", 320f, 240f, paint)
        return fallbackBitmap
    }


    // --- QR
    private fun configurarEIniciarCameraXFrontal() {
        // 1. Obtener el proveedor de la cámara de forma explícita en el hilo principal
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                // Asegurar que la Activity siga viva antes de tocar el hardware
                if (isFinishing || isDestroyed) return@addListener

                cameraProvider = cameraProviderFuture.get()

                // 2. Desvincular de forma agresiva cualquier caso de uso previo o huérfano
                cameraProvider?.unbindAll()

                // 3. Invocar la conexión secuencial blindada de texturas
                conectarCasosDeUsoDeCamaraX()

            } catch (e: Exception) {
                Log.e("CameraXHardware", "Fallo crítico al inicializar el proveedor: ${e.message}")
                viewModel.reportarFallaConexionQr()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun conectarCasosDeUsoDeCamaraX() {
        val provider = cameraProvider ?: return

        // 🚀 MEJORA 1: Forzar al Preview a usar una estrategia de escalado compatible con GPU de emuladores y físicos
        val previewUseCase = Preview.Builder()
            .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_4_3) // 4:3 es el más compatible universalmente
            .build()

        // 🚀 MEJORA 2: Configurar el análisis de imagen aislando el hilo con el Executor dedicado
        val analysisUseCase = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        // Inyectar el analizador QR pasándole el pool de hilos secundario (cameraExecutor)
        analysisUseCase.setAnalyzer(cameraExecutor ?: java.util.concurrent.Executors.newSingleThreadExecutor()) { imageProxy ->
            procesarFotogramaDeCamaraXConMlKit(imageProxy)
        }

        // 4. Seleccionar rigurosamente la cámara FRONTAL
        val cameraSelector = try {
            CameraSelector.Builder()
                .requireLensFacing(lensFacingSeleccionado)
                .build()
        } catch (e: Exception) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        }

        try {
            // Desvincular de forma preventiva para que no se queden bloqueados flujos de datos anteriores
            provider.unbindAll()

            val camera = provider.bindToLifecycle(
                this as androidx.lifecycle.LifecycleOwner,
                cameraSelector,
                previewUseCase
                ,analysisUseCase
            )

            binding.cameraXQrPreview.post {
                try {
                    previewUseCase.setSurfaceProvider(binding.cameraXQrPreview.surfaceProvider)
                    Log.d("DiagnosticoCamara", "¡Superficie de CameraX inyectada en caliente exitosamente!")
                } catch (e: Exception) {
                    Log.e("DiagnosticoCamara", "Error al inyectar superficie: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("CameraXHardware", "Error fatal al enlazar la cámara frontal: ${e.message}")
            viewModel.reportarFallaConexionQr()
        }
    }
    @SuppressLint("UnsafeOptInUsageError")
    private fun procesarFotogramaDeCamaraXConMlKit(imageProxy: androidx.camera.core.ImageProxy) {
        val mediaImage = imageProxy.image

        // Detener procesamiento preventivo si el paso actual ya está en modo de autorización
        if (mediaImage != null && viewModel.uiState.value.currentStep != CaptureStep.PROCESANDO_AUTORIZACION) {

            // Envolver la textura de Android en un objeto compatible con el SDK de Google
            val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            qrScannerClient.process(inputImage)
                .addOnSuccessListener { barcodes ->
                    // Si se localiza un código de barras o QR en la matriz
                    val qrDetectado = barcodes.firstOrNull()
                    val textoPlanoQr = qrDetectado?.rawValue

                    if (textoPlanoQr != null) {
                        binding.qrGuideFrame.setBackgroundResource(R.drawable.qr_frame_border_detected)
                        // Regresar al hilo principal para inyectar la validación del prefijo "ginn"
                        lifecycleScope.launch(Dispatchers.Main) {
                            viewModel.procesarContenidoQrDetectado(textoPlanoQr)
                            binding.qrGuideFrame.setBackgroundResource(R.drawable.qr_frame_border)
                        }
                    }
                }
                .addOnFailureListener {
                    // Ignorar fallas menores de lectura por movimiento o desenfoque
                }
                .addOnCompleteListener {
                    // ⚠️ OBLIGATORIO: Liberar el frame para indicarle a CameraX que puede enviar el siguiente cuadro
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }


    // --- WHatsapp
    private fun registrarObservadorWhatsapp() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.whatsappStatus.collect { status ->
                    when (status) {
                        is WhatsappAuthStatus.Idle -> {
                            // Si regresa a Idle, nos aseguramos de limpiar la pantalla
                            whatsappDialog?.dismiss()
                            whatsappDialog = null
                        }

                        is WhatsappAuthStatus.Solicitando -> {
                            // Si el diálogo no está inflado en la pantalla del guardia, lo creamos
                            if (whatsappDialog == null) {
                                mostrarDialogoEstructuralWhatsapp(status)
                            } else {
                                // Si ya existe, actualizamos únicamente los widgets en caliente (Evita ANR)
                                val tvContador = whatsappDialog?.findViewById<TextView>(R.id.tvDialogContador)
                                tvContador?.text = "segundos transcurridos ${status.segundos} seg"
                            }
                        }

                        is WhatsappAuthStatus.Autorizado -> {
                            if (whatsappDialog == null) {
                                mostrarDialogoEstructuralWhatsapp()
                            }
                            actualizarEstadoEstiloDialogo(
                                titulo = "¡AUTORIZADO!",
                                colorHex = "#2E7D32", // Verde Material
                                mostrarBoton = false
                            )
                        }

                        is WhatsappAuthStatus.Denegado -> {
                            if (whatsappDialog == null) {
                                mostrarDialogoEstructuralWhatsapp()
                            }
                            actualizarEstadoEstiloDialogo(
                                titulo = "DENEGADO EL ACCESO",
                                colorHex = "#C62828", // Rojo Material
                                mostrarBoton = false
                            )
                        }

                        is WhatsappAuthStatus.Timeout -> {
                            if (whatsappDialog == null) {
                                mostrarDialogoEstructuralWhatsapp()
                            }
                            actualizarEstadoEstiloDialogo(
                                titulo = "TIMEOUT SIN RESPUESTA",
                                colorHex = "#EF6C00", // Naranja de Advertencia
                                mostrarBoton = false
                            )
                        }

                        is WhatsappAuthStatus.Info -> {
                            if (whatsappDialog == null) {
                                mostrarDialogoEstructuralWhatsapp()
                            }
                            actualizarEstadoEstiloDialogo(
                                titulo = "Info",
                                mensajePersonalizado = status.msg,
                                colorHex = "#27CCF5",  //Azul
                                mostrarBoton = true // Permite cerrar el diálogo si la API de Render cae
                            )
                        }

                        is WhatsappAuthStatus.Alerta -> {
                            if (whatsappDialog == null) {
                                mostrarDialogoEstructuralWhatsapp()
                            }
                            actualizarEstadoEstiloDialogo(
                                titulo = "Alerta",
                                mensajePersonalizado = status.msg,
                                colorHex = "#FBC02D",  //Amarillo
                                mostrarBoton = false // Permite cerrar el diálogo si la API de Render cae
                            )
                        }

                        is WhatsappAuthStatus.Error -> {
                            if (whatsappDialog == null) {
                                mostrarDialogoEstructuralWhatsapp()
                            }
                            actualizarEstadoEstiloDialogo(
                                titulo = "ERROR",
                                mensajePersonalizado = status.msg,
                                colorHex = "#C62828",
                                mostrarBoton = true // Permite cerrar el diálogo si la API de Render cae
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Infla la vista XML y acopla el listener de cancelación manual.
     */
    private fun mostrarDialogoEstructuralWhatsapp(status: WhatsappAuthStatus.Solicitando? = null) {
        if (isFinishing || isDestroyed) return

        val inflater = layoutInflater
        val dialogView = inflater.inflate(R.layout.dialog_whatsapp_auth, null)

        val tvMensaje = dialogView.findViewById<TextView>(R.id.tvDialogMensaje)
        val btnCancelar = dialogView.findViewById<Button>(R.id.btnDialogCancelar)

        // Formateo de texto enriquecido Bold (Equivalente al tag_config highlight de tu Python)
        if (status != null) {
            val textoFormateado = android.text.Html.fromHtml(
                "Solicitando la autorización para el ingreso de <b><font color='#0288D1'>${status.nombre}</font></b> " +
                        "al domicilio <b><font color='#0288D1'>${status.calle} ${status.numero}</font></b>",
                android.text.Html.FROM_HTML_MODE_LEGACY
            )
            tvMensaje.text = textoFormateado
        }

        // --- AQUÍ SE USA EL MÉTODO DE CANCELACIÓN MANUAL ---
        btnCancelar.setOnClickListener {
            // 1. Apaga las corrutinas de red e hilos de consulta de inmediato
            viewModel.cancelarSolicitudManual()
            // 2. Destruye la ventana flotante
            whatsappDialog?.dismiss()
            whatsappDialog = null
        }

        whatsappDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false) // Forzar al guardia a usar el botón físico para control de auditoría
            .create()

        whatsappDialog?.show()
    }

    /**
     * Modifica la UI del diálogo actual para mostrar los estados finales de éxito/falla sin parpadear la pantalla.
     */
    private fun actualizarEstadoEstiloDialogo(titulo: String, mensajePersonalizado: String? = null, colorHex: String, mostrarBoton: Boolean) {
        whatsappDialog?.let { dialog ->
            val tvTitulo = dialog.findViewById<TextView>(R.id.tvDialogTitulo)
            val tvContador = dialog.findViewById<TextView>(R.id.tvDialogContador)
            val tvMensaje = dialog.findViewById<TextView>(R.id.tvDialogMensaje)
            val btnCancelar = dialog.findViewById<Button>(R.id.btnDialogCancelar)

            tvTitulo?.text = titulo
            tvTitulo?.setTextColor(android.graphics.Color.parseColor(colorHex))

            // Ocultamos el segundero en pantallas de desenlace
            tvContador?.visibility = android.view.View.GONE

            if (mensajePersonalizado != null) {
                tvMensaje?.text = mensajePersonalizado
            }

            btnCancelar?.visibility = if (mostrarBoton) android.view.View.VISIBLE else android.view.View.GONE
        }
    }


    private fun ocultarTecladoVirtual() {
        val viewFocus = this.currentFocus
        if (viewFocus != null) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(viewFocus.windowToken, 0)
        }
    }

    // --- Request PERMISOS
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
        speechRecognizer?.destroy()
        apagarStreamVideoRtspPlacas()
        mediaPlayer?.release()
        libVLC?.release()
        cameraProvider?.unbindAll()
        textRecognizer.close()
        cameraExecutor?.shutdown()
        qrScannerClient.close()
        whatsappDialog?.dismiss()
        whatsappDialog = null
        super.onDestroy()
    }
}