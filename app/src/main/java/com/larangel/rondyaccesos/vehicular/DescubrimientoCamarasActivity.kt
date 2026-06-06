package com.larangel.rondyaccesos.vehicular

import android.graphics.*
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.larangel.rondyaccesos.R
import com.larangel.rondyaccesos.RondyApplication
import com.larangel.rondyaccesos.models.MySettings
import com.larangel.rondyaccesos.models.sockets.RondyNetworkManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withPermit
import java.net.InetSocketAddress
import java.net.Socket
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

class DescubrimientoCamarasActivity : AppCompatActivity() {

    private lateinit var txtSubred: EditText
    private lateinit var btnEscanear: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var gridView: GridView

    private val listaUrlsEncontradas = mutableListOf<String>()
    private val listaCamarasAdapterData = mutableListOf<Pair<String, Bitmap>>()
    private lateinit var adapter: CamarasGridAdapter
    private lateinit var mySettings: MySettings // Asegura inyectar o leer tu clase de persistencia

    private var miIp: String = ""

    // Listado determinista de rutas de fabricantes (Hikvision, Dahua, Steren, Reolink, Genéricas)
    private val sufijosStreamsProbar = listOf(
        "stream",
        "h264_vga.sdp",
        "cam/realmonitor?channel=1&subtype=0",
        "stream1",
        "stream2",
        "Preview_01_main"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_descubrimiento_camaras)

        mySettings = MySettings(this) // Inicialización de tu utilería de caché

        txtSubred = findViewById(R.id.txtSubredEscaneo)
        btnEscanear = findViewById(R.id.btnIniciarEscaneoLan)
        progressBar = findViewById(R.id.progressEscaneo)
        gridView = findViewById(R.id.gridViewCamarasLocalizadas)

        adapter = CamarasGridAdapter()
        gridView.adapter = adapter

        // 🌟 REGLA: Si ya existían cámaras guardadas en el caché previamente, las cargamos de inmediato
        cargarCamarasDesdeCacheExistente()

        btnEscanear.setOnClickListener {
            ejecutarEscaneoDeRedCompleto(txtSubred.text.toString().trim())
        }

        gridView.setOnItemClickListener { _, _, position, _ ->
            val urlSeleccionada = listaUrlsEncontradas[position]
            // Guardamos de forma persistente la URL ganadora para la cámara de placas por defecto
            mySettings.saveString("URL_CAMARA_PLACAS_PREFERIDA", urlSeleccionada)
            Toast.makeText(this, "Cámara configurada por defecto correctamente.", Toast.LENGTH_LONG).show()
            finish() // Regresa a la pantalla principal
        }

        //Defualt mascara subred
        miIp = (application as RondyApplication).networkManager.getMiIp()
        // Extraer el prefijo (ej: de 192.168.1.50 a 192.168.1.)
        val prefix = miIp.substringBeforeLast(".") + ".0/24"
        txtSubred.setText(prefix)
    }

    private fun cargarCamarasDesdeCacheExistente() {
        val cacheList = mySettings.getSimpleList("CACHE_CAMARAS_LAN_LOCALIZADAS")
        if (cacheList.isNotEmpty()) {
            listaUrlsEncontradas.addAll(cacheList)
            lifecycleScope.launch(Dispatchers.Default) {
                cacheList.forEach { url ->
                    // Genera un bitmap de previsualización estático a partir de la URL guardada
                    val previewFrame = extraerFrameEstaticoDeRtsp(url)
                    withContext(Dispatchers.Main) {
                        listaCamarasAdapterData.add(Pair(url, previewFrame))
                        adapter.notifyDataSetChanged()
                    }
                }
            }
        }
    }

    private fun ejecutarEscaneoDeRedCompleto(segmentoCidr: String) {
        val progressEscaneo = findViewById<ProgressBar>(R.id.progressEscaneo)
        val txtDetalle = findViewById<TextView>(R.id.txtProgresoDetalle)

        txtDetalle.text = "Iniciando escaneo..."
        btnEscanear.isEnabled = false
        progressEscaneo.visibility = View.VISIBLE
        txtDetalle.visibility = View.VISIBLE
        progressEscaneo.progress = 0
        val ipsProcesadas = java.util.concurrent.atomic.AtomicInteger(0)

        listaUrlsEncontradas.clear()
        listaCamarasAdapterData.clear()
        adapter.notifyDataSetChanged()

        lifecycleScope.launch(Dispatchers.IO) {
            // Extraer el prefijo base de la IP (Ej: "192.168.1.") asumiendo /24 por simplicidad técnica
            val baseIp = segmentoCidr.substringBeforeLast(".") + "."
            val rstp_user= mySettings.getString("RSTP_USER", "admin")
            val rstp_pass= mySettings.getString("RSTP_PASS", "admin123")
            val passwordCamara = "${rstp_user}:${rstp_pass}" // Lee tus credenciales
            val semaforo = kotlinx.coroutines.sync.Semaphore(15)

            val deferreds = (1..254).map { host ->
            //val deferreds = (66..68).map { host ->
                async {
                    semaforo.withPermit {
                        val ipAProbar = "$baseIp$host"
                        if (ipAProbar == miIp) return@async // Ignorar mi propia IP)
                        // Actualizar texto de qué IP se está probando actualmente
                        withContext(Dispatchers.Main) {
                            txtDetalle.text = "Probando: $ipAProbar"
                        }
                        if (verificarPuertoAbierto(ipAProbar, 554, timeoutMs = 800)) {
                            // El puerto RTSP está abierto. Procedemos a escanear los sufijos uno por uno
                            for (sufijo in sufijosStreamsProbar) {
                                val urlCompletaRtsp =
                                    "rtsp://$passwordCamara@$ipAProbar:554/$sufijo"
                                // rtsp://luisrangel:mevale14@172.16.1.67:554/stream2
                                val bitmapCapturado = validarYCapturarFrameRtsp(urlCompletaRtsp)

                                if (bitmapCapturado != null) {
                                    withContext(Dispatchers.Main) {
                                        listaUrlsEncontradas.add(urlCompletaRtsp)
                                        listaCamarasAdapterData.add(
                                            Pair(
                                                urlCompletaRtsp,
                                                bitmapCapturado
                                            )
                                        )
                                        adapter.notifyDataSetChanged()
                                    }
                                    break
                                }
                            }
                        }

                        // Actualizar barra de progreso al terminar con esta IP
                        val actual = ipsProcesadas.incrementAndGet()
                        withContext(Dispatchers.Main) {
                            progressEscaneo.progress = actual
                            if (actual >= 254) {
                                txtDetalle.text = "Escaneo completado"
                                // Opcional: ocultar barra tras unos segundos
                            }
                        }
                    }
                }
            }

            deferreds.awaitAll() // Esperamos a que los 254 hilos terminen

            // Guardamos la lista total de URLs válidas descubiertas en el caché del dispositivo
            mySettings.saveSingleList("CACHE_CAMARAS_LAN_LOCALIZADAS", listaUrlsEncontradas)

            withContext(Dispatchers.Main) {
                btnEscanear.isEnabled = true
                progressBar.visibility = View.GONE
                Toast.makeText(applicationContext, "Escaneo finalizado. Se encontraron ${listaUrlsEncontradas.size} cámaras.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun verificarPuertoAbierto(ip: String, puerto: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                Log.d("Escaneo", "Probando $ip:$puerto...")
                socket.connect(InetSocketAddress(ip, puerto), timeoutMs)
                Log.d("Escaneo", "¡ÉXITO en $ip!")
                true
            }
        } catch (e: Exception) {
            // Esto te dirá si es "Timeout", "Host unreachable" o "Permission denied"
            Log.e("Escaneo", "Fallo en $ip: ${e.message}")
            false
        }
    }

    private suspend fun validarYCapturarFrameRtsp(urlRtsp: String): Bitmap? = withContext(Dispatchers.IO) {
        var resultado: Bitmap? = null
        val vlcArgs = arrayListOf(
            "--rtsp-tcp",                // Forzar TCP para evitar pérdida de paquetes en el escaneo
            "--network-caching=500",      // Buffer bajo para velocidad
            "--no-audio",                 // No necesitamos audio para la validación
            "--no-stats",
            "--swscale-mode=0"            // Optimizar escalado
        )

        var vlcCore: LibVLC? = null
        var player: MediaPlayer? = null
        var mediaObj: Media? = null

        try {
            vlcCore = LibVLC(this@DescubrimientoCamarasActivity, vlcArgs)
            player = MediaPlayer(vlcCore)
            mediaObj = Media(vlcCore, urlRtsp)

            // Opciones de media para acelerar la apertura
            mediaObj.addOption(":clock-jitter=0")
            mediaObj.addOption(":clock-synchro=0")

            player.media = mediaObj
            player.play()

            // Bucle de espera inteligente (Timeout de 3 segundos)
            val timeout = 3000L
            val startTime = System.currentTimeMillis()

            while (System.currentTimeMillis() - startTime < timeout) {
                // Verificamos si ya enganchó el stream
                if (player?.isPlaying == true) {
                    val snapshotFile = java.io.File(cacheDir, "snap_${System.currentTimeMillis()}.png")
                    val path = snapshotFile.absolutePath

                    // Usamos Reflexión para invocar el método oculto en el binario nativo
                    val exito = try {
                        val method = player!!.javaClass.getMethod(
                            "takeSnapshot",
                            Int::class.javaPrimitiveType,
                            String::class.java,
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType
                        )
                        method.invoke(player, 0, path, 0, 0) as Boolean
                    } catch (e: Exception) {
                        Log.e("RondyScan", "El método takeSnapshot no está disponible en este binario")
                        false
                    }

                    if (exito) {
                        delay(400) // Tiempo para que el decoder nativo termine de escribir el archivo
                        if (snapshotFile.exists() && snapshotFile.length() > 0) {
                            val options = BitmapFactory.Options().apply { inSampleSize = 2 }
                            resultado = BitmapFactory.decodeFile(path, options)
                            snapshotFile.delete()
                            break
                        }
                    }
                }
                delay(200) // Reintentar cada 200ms
            }

        } catch (e: Exception) {
            Log.e("ScanCam", "Error validando $urlRtsp: ${e.message}")
        } finally {
            // Limpieza rigurosa de memoria nativa
            player?.stop()
            mediaObj?.release()
            player?.release()
            vlcCore?.release()
        }

        return@withContext resultado
    }

    private fun extraerFrameEstaticoDeRtsp(urlRtsp: String): Bitmap {
        // Genera un bitmap de contingencia genérico por si la cámara no permite clonar sus pixeles en frío
        val bitmapFallback = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmapFallback)
        canvas.drawColor(Color.parseColor("#333333"))
        val paint = Paint().apply {
            color = Color.GREEN
            textSize = 24f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("CAM ACTIVA", 150f, 100f, paint)
        return bitmapFallback
    }

    // --- Base GridAdapter Interno para Renderizar las Tarjetas Estáticas ---
    private inner class CamarasGridAdapter : BaseAdapter() {
        override fun getCount(): Int = listaCamarasAdapterData.size
        override fun getItem(position: Int): Any = listaCamarasAdapterData[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val item = listaCamarasAdapterData[position]
            val layout = LayoutInflater.from(this@DescubrimientoCamarasActivity).inflate(android.R.layout.activity_list_item, null)

            val icon = layout.findViewById<ImageView>(android.R.id.icon)
            val text = layout.findViewById<TextView>(android.R.id.text1)

            text.text = item.first.substringAfterLast("@") // Muestra la IP limpia sin credenciales
            text.setTextColor(Color.WHITE)
            icon.setImageBitmap(item.second) // Asigna el frame estático

            return layout
        }
    }
}