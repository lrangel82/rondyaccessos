package com.larangel.rondyaccesos.vehicular

import android.Manifest
import android.content.Intent
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
import com.larangel.rondyaccesos.models.WhatsappAuthStatus
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
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import com.google.mlkit.vision.barcode.BarcodeScanning
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIngresoVehicularBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configurarAccionesGlobales()
        observarCicloDelAsistente()
        verificarPermisosYConfigurarEscucha()

        inicializarContenedorVLC()
        configurarListenersDeFalla()
        observarCicloDeVidaDeCamaras()
        //QR
        configurarEIniciarCameraXFrontal()
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
                            binding.ScrollViewGridBotones.visibility = View.VISIBLE
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

                        CaptureStep.CAPTURA_NOMBRE -> {
                            // En pasos de teclado manual, apagamos el micro para liberar el búfer táctil al 100%
                            controlarEstadoMicrofono(habilitar = false)
                            binding.lblInstruccionSeccion.text = "4. Ingrese Nombre del Conductor:"
                            binding.ScrollViewGridBotones.visibility = View.GONE
                            binding.txtInputManual.visibility = View.VISIBLE
                            binding.txtInputManual.hint = "Escriba el nombre aquí..."
                            binding.btnSiguientePasoManual.visibility = View.VISIBLE
                            binding.btnSiguientePasoManual.text = "Continuar ➡️"
                        }

                        CaptureStep.CAPTURA_PLACA -> {
                            controlarEstadoMicrofono(habilitar = false)
                            binding.lblInstruccionSeccion.text = "5. Ingrese Matrícula Vehicular:"
                            binding.ScrollViewGridBotones.visibility = View.GONE
                            binding.txtInputManual.visibility = View.VISIBLE
                            binding.txtInputManual.hint = "Escriba la placa aquí..."
                            binding.btnSiguientePasoManual.visibility = View.VISIBLE
                            binding.btnSiguientePasoManual.text = "Solicitar Autorización 🔐"
                        }

                        CaptureStep.PROCESANDO_AUTORIZACION -> {
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

    // --- CAMARAS
    private fun inicializarContenedorVLC() {
        val args = ArrayList<String>().apply {
            add("--rtsp-tcp") // Force RTSP over TCP transport layer to avoid dropped frames
            add("--no-drop-late-frames")
            add("--no-skip-frames")
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

        binding.btnCambiarAIP.setOnClickListener {
            viewModel.cambiarOrigenQrAHardwareIp("rtsp://192.168.1.151:554/live")
            binding.layoutFixCamaraQr.visibility = View.GONE
            binding.cameraXQrPreview.visibility = View.GONE
            binding.vlcQrSurfaceFallback.visibility = View.VISIBLE
        }
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

        lifecycleScope.launch {
            viewModel.camaraQrFalla.collectLatest { fallando ->
                binding.layoutFixCamaraQr.visibility = if (fallando) View.VISIBLE else View.GONE
            }
        }
    }
    private fun encenderStreamVideoRtspPlacas() {
        try {
            // Bind the LibVLC media player directly to the XML view texture surface layout
            mediaPlayer?.attachViews(binding.vlcPlacaSurface, null, true, false)

            val media = Media(libVLC, viewModel.urlCamaraPlacasRtsp).apply {
                setHWDecoderEnabled(true, false) // Enable native hardware acceleration
            }
            mediaPlayer?.media = media
            mediaPlayer?.play()

            // Start the asynchronous snapshot extraction engine loop for local OCR parsing
            iniciarBucleProcesamientoOcrPlacas()

        } catch (e: Exception) {
            Log.e("VlcStream", "Fallo al inicializar texturas de video: ${e.message}")
            viewModel.reportarFallaConexionPlacas()
        }
    }
    private fun apagarStreamVideoRtspPlacas() {
        ocrJob?.cancel() // Stop the OCR extractor loop immediately
        mediaPlayer?.stop()
        mediaPlayer?.detachViews()
        Log.d("VlcHardware", "Stream RTSP cerrado e hilos liberados correctamente.")
    }
    /**
     * 👁️ CONTINUOUS EXTRACTOR LOOP: Pulls video bitmaps from the LibVLC texture,
     * feeds them locally to ML Kit, and stops as soon as a text match occurs.
     */
    private fun iniciarBucleProcesamientoOcrPlacas() {
        ocrJob?.cancel()
        ocrJob = lifecycleScope.launch(Dispatchers.Default) {

            // Buscamos la superficie de video interna que LibVLC inyectó en el layout
            var surfaceInternaVLC: SurfaceView? = null
            withContext(Dispatchers.Main) {
                // Buscamos de forma recursiva en el contenedor ViewGroup de la vista
                for (i in 0 until binding.vlcPlacaSurface.childCount) {
                    val child = binding.vlcPlacaSurface.getChildAt(i)
                    if (child is SurfaceView) {
                        surfaceInternaVLC = child
                        break
                    }
                }
            }

            // Bucle continuo mientras el reproductor RTSP esté activo
            while (mediaPlayer?.isPlaying == true) {
                delay(1000) // Procesar un fotograma cada segundo para proteger la CPU

                val surfaceTarget = surfaceInternaVLC
                if (surfaceTarget != null && surfaceTarget.holder.surface.isValid) {

                    // Creamos un Bitmap en memoria con las dimensiones exactas de la vista de la cámara
                    val bitmapSnapshot = Bitmap.createBitmap(
                        surfaceTarget.width,
                        surfaceTarget.height,
                        Bitmap.Config.ARGB_8888
                    )

                    // Usamos PixelCopy para clonar la textura de la GPU en el hilo principal de renderizado
                    withContext(Dispatchers.Main) {
                        PixelCopy.request(
                            surfaceTarget,
                            bitmapSnapshot,
                            { resultado ->
                                if (resultado == PixelCopy.SUCCESS) {
                                    // Si la GPU entregó la imagen con éxito, la mandamos al OCR en background
                                    procesarOcrEnHiloDeFondo(bitmapSnapshot)
                                }
                            },
                            Handler(Looper.getMainLooper())
                        )
                    }
                }
            }
        }
    }
    private fun procesarOcrEnHiloDeFondo(bitmap: Bitmap) {
        val image = InputImage.fromBitmap(bitmap, 0)

        textRecognizer.process(image)
            .addOnSuccessListener { visionText ->
                for (block in visionText.textBlocks) {
                    val textoEncontrado = block.text.trim()

                    // Expresión regular para matrículas vehiculares de 3 a 8 caracteres
                    if (textoEncontrado.matches(Regex("^[a-zA-Z0-9\\s-]{3,8}$"))) {
                        viewModel.registrarPlacaDetectadaPorOcr(textoEncontrado)
                        break
                    }
                }
            }
            .addOnFailureListener {
                // Manejo preventivo si falla el procesamiento del frame
            }
    }

    // --- QR
    private fun configurarEIniciarCameraXFrontal() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()

                // Enlazar los casos de uso de la cámara al ciclo de vida de la Activity
                conectarCasosDeUsoDeCamaraX()

            } catch (e: Exception) {
                Log.e("CameraXHardware", "Error obteniendo el proveedor de cámara: ${e.message}")
                viewModel.reportarFallaConexionQr()
            }
        }, ContextCompat.getMainExecutor(this))
    }
    private fun conectarCasosDeUsoDeCamaraX() {
        val provider = cameraProvider ?: return

        // 1. Caso de Uso: Previsualización en tiempo real en la pantalla
        val previewUseCase = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.cameraXQrPreview.surfaceProvider)
        }

        // 2. Caso de Uso: Pipeline de análisis asíncrono para decodificación de fotogramas
        val analysisUseCase = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST) // No acumular retrasos de frames
            .build()

        // Enlazar el búfer de bytes al motor de ML Kit Barcode Scanning
        analysisUseCase.setAnalyzer(cameraExecutor ?: Executors.newSingleThreadExecutor()) { imageProxy ->
            procesarFotogramaDeCamaraXConMlKit(imageProxy)
        }

        // 3. Selección estricta de la CÁMARA FRONTAL (Mapeado de tus requerimientos de Satélite 2)
        val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

        try {
            // Desvincular pases previos para evitar colisiones de hardware
            provider.unbindAll()

            // Ligar los componentes al ciclo de vida controlado por Android Jetpack
            provider.bindToLifecycle(this, cameraSelector, previewUseCase, analysisUseCase)
            Log.d("CameraXHardware", "Cámara frontal y analizador QR iniciados exitosamente.")

        } catch (e: Exception) {
            Log.e("CameraXHardware", "Error al vincular casos de uso a CameraX: ${e.message}")
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
                        // Regresar al hilo principal para inyectar la validación del prefijo "ginn"
                        lifecycleScope.launch(Dispatchers.Main) {
                            viewModel.procesarContenidoQrDetectado(textoPlanoQr)
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
        super.onDestroy()
        speechRecognizer?.destroy()
        apagarStreamVideoRtspPlacas()
        mediaPlayer?.release()
        libVLC?.release()
        textRecognizer.close()
        cameraExecutor?.shutdown()
        qrScannerClient.close()
    }
}