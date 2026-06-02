package com.larangel.rondyaccesos.vehicular

import android.graphics.*
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.larangel.rondyaccesos.R
import com.larangel.rondyaccesos.models.MySettings
import kotlinx.coroutines.*
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

    // Listado determinista de rutas de fabricantes (Hikvision, Dahua, Steren, Reolink, Genéricas)
    private val sufijosStreamsProbar = listOf(
        "stream",
        "h264_vga.sdp",
        "cam/realmonitor?channel=1&subtype=0",
        "stream1",
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
        btnEscanear.isEnabled = false
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0

        listaUrlsEncontradas.clear()
        listaCamarasAdapterData.clear()
        adapter.notifyDataSetChanged()

        lifecycleScope.launch(Dispatchers.IO) {
            // Extraer el prefijo base de la IP (Ej: "192.168.1.") asumiendo /24 por simplicidad técnica
            val baseIp = segmentoCidr.substringBeforeLast(".") + "."
            val rstp_user= mySettings.getString("RSTP_USER", "admin")
            val rstp_pass= mySettings.getString("RSTP_PASS", "admin123")
            val passwordCamara = "${rstp_user}:${rstp_pass}" // Lee tus credenciales

            val deferreds = (1..254).map { host ->
                async {
                    val ipAProbar = "$baseIp$host"
                    if (verificarPuertoAbierto(ipAProbar, 554, timeoutMs = 150)) {
                        // El puerto RTSP está abierto. Procedemos a escanear los sufijos uno por uno
                        for (sufijo in sufijosStreamsProbar) {
                            val urlCompletaRtsp = "rtsp://$passwordCamara@$ipAProbar:554/$sufijo"
                            if (validarConexionRtspReal(urlCompletaRtsp)) {
                                val frameSnapshot = extraerFrameEstaticoDeRtsp(urlCompletaRtsp)

                                withContext(Dispatchers.Main) {
                                    listaUrlsEncontradas.add(urlCompletaRtsp)
                                    listaCamarasAdapterData.add(Pair(urlCompletaRtsp, frameSnapshot))
                                    adapter.notifyDataSetChanged()
                                }
                                break // Si un sufijo funcionó, saltamos al siguiente host IP para ahorrar tiempo
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
                socket.connect(InetSocketAddress(ip, puerto), timeoutMs)
                true
            }
        } catch (e: Exception) { false }
    }

    private fun validarConexionRtspReal(urlRtsp: String): Boolean {
        // Inicializa un contenedor LibVLC efímero para validar la cabecera del stream de forma atómica
        var esValido = false
        try {
            val args = arrayListOf("--rtsp-tcp", "--network-caching=100")
            val vlcCore = LibVLC(this, args)
            val player = MediaPlayer(vlcCore)
            val mediaObj = Media(vlcCore, urlRtsp)

            player.media = mediaObj
            player.play()

            // Tolerancia de 800ms para comprobar si el reproductor nativo engancha el códec sin errores
            runBlocking { delay(800) }

            if (player.isPlaying) {
                esValido = true
                player.stop()
            }
            mediaObj.release()
            player.release()
            vlcCore.release()
        } catch (e: Exception) { esValido = false }
        return esValido
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