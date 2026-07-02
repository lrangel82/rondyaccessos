package com.larangel.rondyaccesos.peatonal

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.larangel.rondyaccesos.ui.VigilanteConfigActivity
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
//import androidx.compose.ui.test.left
//import androidx.compose.ui.test.right
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.chip.Chip
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.tensorflow.lite.Interpreter
import com.larangel.rondyaccesos.RondyApplication
import com.larangel.rondyaccesos.databinding.ActivityIngresoPeatonalBinding
import com.larangel.rondyaccesos.models.CaptureStep
import com.larangel.rondyaccesos.peatonal.IngresoPeatonalViewModel
import com.larangel.rondyaccesos.utils.CloudStorageManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.larangel.rondyaccesos.R
import com.larangel.rondyaccesos.models.network.WhatsappAuthStatus
import com.larangel.rondyaccesos.utils.euclideanDistance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class IngresoPeatonalActivity : AppCompatActivity() {

    private lateinit var binding: ActivityIngresoPeatonalBinding
    private val viewModel: IngresoPeatonalViewModel by viewModels {
        IngresoPeatonalViewModelFactory(application as RondyApplication)
    }

    private var whatsappDialog: AlertDialog? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private var speechIntent: Intent? = null
    private val RECORD_AUDIO_REQUEST_CODE = 101

    private var reintentandoEscucha = false
    private var ultimoPasoProcesado: CaptureStep? = null
    private var microfonoHabilitadoPorPaso = true

    // QR -- Parámetros de control de hilos para CameraX Frontal
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraExecutor: ExecutorService? = Executors.newSingleThreadExecutor()
    private val qrScannerClient = BarcodeScanning.getClient()
    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build()
    )
    private lateinit var faceNetInterpreter: Interpreter
    private var lastTimeDetectarFace: Long = 0
    private var lastTimeQRDetected: Long = 0

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

    private var imageCapture: ImageCapture? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIngresoPeatonalBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()
        try {
            faceNetInterpreter = Interpreter(loadModelFile("facenet_512.tflite"))
        } catch (e: Exception) {
            e.printStackTrace()
        }

        configurarAccionesGlobales()

        //----Permisos-----
        verificarPermisoDeCamaraAutomatizado() //Camara
        verificarConexionYFijarAjustes()       //Network
        verificarPermisosYConfigurarEscucha()  //Microfono

        //---Observadores de ciclo de vida---
        observarCicloDelAsistente()
        registrarObservadorWhatsapp()


    }
    override fun onStart() {
        super.onStart()
        val app = application as RondyApplication

        // Register the functional extractor to serve the current view frames
        app.imagenesCallBackActivo = { tipoCamara ->
            when (tipoCamara) {
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

            viewModel.reiniciarAsistentePeatonal()
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
                    viewModel.guardarNombreYPasarAAutorizar(valorManual)
                    binding.txtInputManual.setText("")
                    ocultarTecladoVirtual()
                }
                else -> {}
            }
        }
        binding.btnMenuConfiguracion.setOnClickListener {
            // 1. Detener por completo los hilos de procesamiento y cerrar streams
            viewModel.detenerAllJobs()
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


        binding.layoutSplash.setOnClickListener {
            viewModel.despertarAsistente()
        }
    }

    // --- OBSERVADORES    RENDERIZACIÓN REACTIVA CONTROLADA
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
                if (state.currentFaceEmbedding !=null && state.currentFaceEmbedding.isNotEmpty()) {
                    state.currentFaceBitmap?.let { bitmap ->
                        binding.ivFacePreview.setImageBitmap(bitmap)
                        binding.ivFacePreview.visibility = View.VISIBLE
                    }
                }else{
                    binding.ivFacePreview.visibility = View.GONE
                }


                // 🛑 CORRECCIÓN ANR CRÍTICA: Solo regenerar los Chips si el paso cambió físicamente
                if (ultimoPasoProcesado != state.currentStep) {
                    ultimoPasoProcesado = state.currentStep

                    binding.containerGridBotones.removeAllViews() // Vaciar de forma controlada

                    when (state.currentStep) {
                        CaptureStep.SELECCION_MOTIVO -> {
                            controlarEstadoMicrofono(habilitar = true)
                            binding.lblInstruccionSeccion.text = "1. Indique el Motivo de Ingreso:"
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

                        CaptureStep.CAPTURA_ROSTRO -> {
                            ocultarTecladoVirtual()
                            controlarEstadoMicrofono(habilitar = false)
                            binding.lblInstruccionSeccion.text = "5. Capturar Rostro:"
                            binding.ScrollViewGridBotones.visibility = View.GONE
                            binding.txtInputManual.visibility = View.GONE
                            binding.btnSiguientePasoManual.visibility = View.GONE
                            binding.btnSiguientePasoManual.text = "nunca debe ver esto"
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
    private fun inyectarBotonEnMalla(texto: String, alPresionar: () -> Unit) {
        val chip = Chip(this).apply {
            text = texto
            isClickable = true
            setChipBackgroundColorResource(android.R.color.holo_green_dark)
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


    // --- Dialogo Whatsapp
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


    // --- QR y ROSTRO
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

        ///CODIGI PARA LOS ROSTROS
//        val imageAnalysis = ImageAnalysis.Builder()
//            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
//            .build()
//
//// Inicializar el intérprete cargando tu archivo mobile_facenet.tflite desde assets
//        val faceNetInterpreter = Interpreter(loadModelFileFromAssets("facenet.tflite"))
//
//        imageAnalysis.setAnalyzer(cameraExecutor, MultiTaskImageAnalyzer(faceNetInterpreter))
//
//// Enlazar al ciclo de vida junto con tu Preview
//        cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)

        ///////////////////////////////////////////////




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

            //Escanear QRs
            qrScannerClient.process(inputImage)
                .addOnSuccessListener { barcodes ->
                    if (System.currentTimeMillis() - lastTimeQRDetected > 2000) {
                        // Si se localiza un código de barras o QR en la matriz
                        lastTimeQRDetected = System.currentTimeMillis()
                        val qrDetectado = barcodes.firstOrNull()
                        val textoPlanoQr = qrDetectado?.rawValue

                        if (textoPlanoQr != null) {
                            binding.qrGuideFrame.setBackgroundResource(R.drawable.qr_frame_border_detected)
                            // Regresar al hilo principal para inyectar la validación del prefijo "ginn"
                            lifecycleScope.launch(Dispatchers.Main) {
                                delay(500)
                                binding.qrGuideFrame.setBackgroundResource(R.drawable.qr_frame_border)
                                viewModel.procesarContenidoQrDetectado(textoPlanoQr)
                            }
                        }
                    }
                }
                .addOnFailureListener {
                    // Ignorar fallas menores de lectura por movimiento o desenfoque
                    imageProxy.close()
                }
                .addOnCompleteListener {
                    //Escanear Rostro
                    if (System.currentTimeMillis() - lastTimeDetectarFace > 2000) {
                        faceDetector.process(inputImage)
                            .addOnSuccessListener { faces ->
                                lastTimeDetectarFace = System.currentTimeMillis()
                                val mainFace =
                                    faces.maxByOrNull { face -> face.boundingBox.width() * face.boundingBox.height() }
                                if (mainFace == null) {
                                    viewModel.cleanRostro()
                                    return@addOnSuccessListener
                                }

                                val sourceBitmap =
                                    imageProxy.toBitmap() ?: return@addOnSuccessListener
                                val rotationDegress = imageProxy.imageInfo.rotationDegrees.toFloat()//180.0f //mainFace.headEulerAngleY

                                val faceBitmap = cropAndRotateFace(
                                    sourceBitmap,
                                    mainFace.boundingBox,
                                    rotationDegress
                                )
                                if (faceBitmap != null) {
                                    // Generar el código matemático (Embedding)
                                    val faceEmbedding = extractFaceEmbedding(faceBitmap)
                                    val similitudFromCurrent = if (viewModel.uiState.value.currentFaceEmbedding==null) 0.0f else faceEmbedding.euclideanDistance(viewModel.uiState.value.currentFaceEmbedding!!)
                                    Log.d(
                                        "ValidacionRostro",
                                        "SE ENCONTRO ROSTRO: ${mainFace.boundingBox} similitud con Anterior: $similitudFromCurrent"
                                    )
                                    //Distancia ecuclidiana cercanas a 0 son el mismo rostro, mayores es un rostro distinto
                                    if (viewModel.uiState.value.currentFaceEmbedding == null || similitudFromCurrent > 1.5f)
                                        // Guardar faceEmbedding (FloatArray)
                                        viewModel.registrarRostro(faceBitmap, faceEmbedding)
                                }
                                imageProxy.close()
                            }
                            .addOnFailureListener {
                                imageProxy.close()
                            }
                            .addOnCompleteListener {
                                // ⚠️ OBLIGATORIO: Liberar el frame para indicarle a CameraX que puede enviar el siguiente cuadro
                                imageProxy.close()
                            }
                    }else{
                        imageProxy.close()
                    }
                }



        } else {
            imageProxy.close()
        }
    }
    fun cropAndRotateFace(source: Bitmap, bounds: Rect, rotationDegrees: Float): Bitmap? {
        try {
            // 2. CORRECCIÓN CRÍTICA: Si es frontal, invertimos el eje X
            // Añadir un margen del 10% para asegurar que se incluya todo el rostro
            val padding = (bounds.width() * 0.15f).toInt()
            var rectCrop: Rect
            if (lensFacingSeleccionado == CameraSelector.LENS_FACING_FRONT){
                // En la cámara frontal, el 'left' del detector es el 'right' en el bitmap espejeado
                val nuevoLeft = source.width - bounds.right
                val nuevoRight = source.width - bounds.left
                rectCrop = Rect(
                    maxOf(0, nuevoLeft - padding),
                    maxOf(0, bounds.top - padding),
                    minOf(source.width, nuevoRight + padding),
                    minOf(source.height, bounds.bottom + padding)
                )
            }else {
                 rectCrop = Rect(
                    maxOf(0, bounds.left - padding),
                    maxOf(0, bounds.top - padding),
                    minOf(source.width, bounds.right + padding),
                    minOf(source.height, bounds.bottom + padding)
                )
            }

//            // Asegurar que el cuadro delimitador esté estrictamente dentro de los límites del Bitmap
//            val left = bounds.left.coerceIn(0, source.width)
//            val top = bounds.top.coerceIn(0, source.height)
//            val width = bounds.width().coerceIn(0, source.width - left)
//            val height = bounds.height().coerceIn(0, source.height - top)

            // Validar que el área de recorte sea válida
            if (rectCrop.width() <= 0 || rectCrop.height() <= 0 ) return null

            // Si la imagen requiere rotación, configuramos la matriz
            val matrix = Matrix()
            if (rotationDegrees > 0.0 || rotationDegrees < 0.0) {
                matrix.postRotate(rotationDegrees)
            }

            // Corta y rota en una sola operación eficiente de memoria
            return Bitmap.createBitmap(source, rectCrop.left, rectCrop.top, rectCrop.width(), rectCrop.height(), matrix, true)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
    private fun convertBitmapToByteBuffer(bitmap: android.graphics.Bitmap): ByteBuffer {
        // 160 x 160 píxeles x 3 canales (RGB) x 4 bytes (Float tamaño)
        val inputSize = 160
        val byteBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(inputSize * inputSize)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        // Recorrer los píxeles y extraer canales R, G, B
        for (pixelValue in intValues) {
            val r = (pixelValue shr 16 and 0xFF)
            val g = (pixelValue shr 8 and 0xFF)
            val b = (pixelValue and 0xFF)

            // Normalización estándar (0 a 1). usa: (r  / 255.0f )
            // Si tu modelo pide rango (-1 a 1), usa: (r - 127.5f) / 127.5f
            byteBuffer.putFloat((r - 127.5f) / 128.0f)
            byteBuffer.putFloat((g - 127.5f) / 128.0f)
            byteBuffer.putFloat((b - 127.5f) / 128.0f)
        }
        return byteBuffer
    }
    private fun loadModelFile(modelName: String): ByteBuffer {
        // Función auxiliar para cargar el archivo .tflite de la carpeta Assets
        val fileDescriptor = assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }
    private fun normalizarEmbedding(embedding: FloatArray): FloatArray {
        var sum = 0.0f
        for (v in embedding) sum += v * v
        val norm = Math.sqrt(sum.toDouble()).toFloat()
        for (i in embedding.indices) embedding[i] = embedding[i] / norm
        return embedding
    }
    private fun extractFaceEmbedding(faceBitmap: android.graphics.Bitmap): FloatArray {
        // Redimensionar el bitmap al tamaño que requiera tu modelo FaceNet (ej. 160x160 o 112x112)
        val resizedBitmap = android.graphics.Bitmap.createScaledBitmap(faceBitmap, 160, 160, true)
        val inputBuffer = convertBitmapToByteBuffer(resizedBitmap)

        // El output del modelo suele ser un arreglo de 1 fila por 512 columnas (características)
        val outputArray = Array(1) { FloatArray(512) }

        // Ejecución de la inferencia en TensorFlow Lite
        faceNetInterpreter.run(inputBuffer, outputArray)

        return normalizarEmbedding(outputArray[0]) // Este es el "código matemático" único del rostro
    }



    private fun actualizarInterfazError(msg: String) {
       // binding.panelColorContenedor.setBackgroundColor(Color.parseColor("#440000")) // Rojo oscuro Cyber
        binding.tvEstatusGrande.text = msg
        binding.layoutVisualEstatusDerecho.visibility = View.VISIBLE
    }
    private fun ocultarTecladoVirtual() {
        val viewFocus = this.currentFocus
        if (viewFocus != null) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(viewFocus.windowToken, 0)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor?.shutdown()
    }
}