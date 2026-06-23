package com.larangel.rondyaccesos.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.color
import androidx.lifecycle.lifecycleScope
import com.larangel.rondyaccesos.R
import com.larangel.rondyaccesos.RondyApplication
import com.larangel.rondyaccesos.databinding.ActivityVigilanteConfigBinding
import com.larangel.rondyaccesos.models.MySettings
import com.larangel.rondyaccesos.models.SateliteMode
import com.larangel.rondyaccesos.caseta.CasetaCentralActivity
import com.larangel.rondyaccesos.salida.SalidaVehicularActivity
import com.larangel.rondyaccesos.salida.SalidaPeatonalActivity
import com.larangel.rondyaccesos.vehicular.IngresoVehicularActivity
import com.larangel.rondyaccesos.peatonal.IngresoPeatonalActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.collections.component1
import kotlin.collections.component2
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest

class VigilanteConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVigilanteConfigBinding
    private lateinit var mySettings: MySettings
    private val networkManager by lazy { (application as RondyApplication).networkManager }
    private var btnRoles: Map<Button, SateliteMode> = emptyMap()
    private val httpClient = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVigilanteConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mySettings = MySettings(this)

        observeLogs()
        setupUI()
        loadSavedConfig()

//        binding.btnConfigCaseta.setOnClickListener { registrarSatéliteYArrancar(SateliteMode.CASETA, Intent(this, CasetaCentralActivity::class.java)) }
//        binding.btnConfigIngresoVehicular.setOnClickListener { registrarSatéliteYArrancar(SateliteMode.INGRESO_VEHICULAR, Intent(this, IngresoVehicularActivity::class.java)) }
//        binding.btnConfigIngresoPeatonal.setOnClickListener { registrarSatéliteYArrancar(SateliteMode.INGRESO_PEATONAL, Intent(this, IngresoPeatonalActivity::class.java)) }
//        binding.btnConfigSalidaVehicular.setOnClickListener { registrarSatéliteYArrancar(SateliteMode.SALIDA_VEHICULAR, Intent(this, SalidaVehicularActivity::class.java)) }
//        binding.btnConfigSalidaPeatonal.setOnClickListener { registrarSatéliteYArrancar(SateliteMode.SALIDA_PEATONAL, Intent(this, SalidaPeatonalActivity::class.java)) }
//
//        binding.btnBuscarAmigosRED.setOnClickListener {
//            Toast.makeText(this, "Iniciando escaneo...", Toast.LENGTH_SHORT).show()
//
//            // Si realizarHandshakeInicial es una función suspend, usa launch:
//            lifecycleScope.launch {
//                networkManager.realizarHandshakeInicial(force = true)
//            }
//        }
    }

    private fun setupUI() {
        // --- SELECCIÓN DE ROL ---
        btnRoles = mapOf(
            binding.btnConfigCaseta to SateliteMode.CASETA,
            binding.btnConfigIngresoVehicular to SateliteMode.INGRESO_VEHICULAR,
            binding.btnConfigIngresoPeatonal to SateliteMode.INGRESO_PEATONAL,
            binding.btnConfigSalidaVehicular to SateliteMode.SALIDA_VEHICULAR,
            binding.btnConfigSalidaPeatonal to SateliteMode.SALIDA_PEATONAL
        )

        btnRoles.forEach { (btn, modo) ->
            btn.setOnClickListener {
                marcarBotonSeleccionado(btn)
                registrarSatélite(modo)
                // Aquí podrías decidir si arrancar de inmediato o esperar a que el usuario termine la config
            }
        }

        // --- SELECTOR DE TIPO DE PUERTA ---
        binding.rgDoorType.setOnCheckedChangeListener { _, checkedId ->
            binding.layoutHttpFields.visibility = if (checkedId == R.id.rbHttp) View.VISIBLE else View.GONE
            binding.layoutDahuaFields.visibility = if (checkedId == R.id.rbDahua) View.VISIBLE else View.GONE
            mySettings.saveString("DOOR_CONTROL_TYPE", if (checkedId == R.id.rbHttp) "HTTP" else "DAHUA")
        }

        // --- LÓGICA DAHUA ---
        binding.btnDahuaConnect.setOnClickListener {
            val ip = binding.etDahuaIp.text.toString()
            val user = binding.etDahuaUser.text.toString()
            val pass = binding.etDahuaPass.text.toString()

            // Guardar credenciales
            mySettings.saveString("DAHUA_IP", ip)
            mySettings.saveString("DAHUA_USER", user)
            mySettings.saveString("DAHUA_PASS", pass)

            conectarYLeerPuertasDahua(ip, user, pass)
        }

        binding.btnTestHttp.setOnClickListener {
            probarPulsadorHttp(binding.etUrlOpen.text.toString())
        }
        // --- LÓGICA DE TEST DAHUA ---
        binding.btnTestDahua.setOnClickListener {
            val selectedItem = binding.spDahuaDoors.selectedItem?.toString() ?: ""
            if (selectedItem.isEmpty()) return@setOnClickListener

            // Extraer el número de canal (Ej: "Puerta Canal 1" -> "1")
            val canalId = selectedItem.substringAfterLast(" ")

            val ip = binding.etDahuaIp.text.toString()
            val user = binding.etDahuaUser.text.toString()
            val pass = binding.etDahuaPass.text.toString()

            abrirPuertaDahua(ip, user, pass, canalId, selectedItem)
        }

        binding.btnBuscarAmigosRED.setOnClickListener {
            lifecycleScope.launch { networkManager.realizarHandshakeInicial(force = true) }
        }
    }

    private fun observeLogs() {
        lifecycleScope.launch {
            networkManager.consoleLogs.collectLatest { logs ->
                // Unir los logs con saltos de línea
                binding.txtConsole.text = logs.joinToString("\n")

                // Auto-scroll hacia abajo
                binding.scrollConsole.post {
                    binding.scrollConsole.fullScroll(View.FOCUS_DOWN)
                }
            }
        }
    }

    private fun loadSavedConfig() {
        // Cargar Modo de Satélite actual y pintar el botón
        val currentMode = mySettings.getString("SATELITE_NODE_MODE", "")
        if (currentMode.isNotEmpty()) {
            // Lógica para encontrar el botón y marcarlo (puedes usar un TAG en el XML)
            btnRoles.forEach { (btn, modo) ->
                if (modo.name == currentMode) {
                    marcarBotonSeleccionado(btn)
                    return@forEach
                }
            }
        }

        // Cargar Tipo de Control
        val doorType = mySettings.getString("DOOR_CONTROL_TYPE", "HTTP")
        if (doorType == "HTTP") {
            binding.rbHttp.isChecked = true
            binding.etUrlOpen.setText(mySettings.getString("URL_OPEN", ""))
        } else {
            binding.rbDahua.isChecked = true
            binding.etDahuaIp.setText(mySettings.getString("DAHUA_IP", ""))
            binding.etDahuaUser.setText(mySettings.getString("DAHUA_USER", ""))
        }
    }
    private fun marcarBotonSeleccionado(boton: Button) {
        // Resetear otros botones (esto es mejor hacerlo con un List de botones)
        val listaBotones = listOf(binding.btnConfigCaseta, binding.btnConfigIngresoVehicular, binding.btnConfigIngresoPeatonal, binding.btnConfigSalidaVehicular, binding.btnConfigSalidaPeatonal)
        listaBotones.forEach {
            it.setBackgroundColor(getColor(android.R.color.transparent))
            it.setTextColor(getColor(R.color.black)) // Tu color primario
        }
        // Resaltar seleccionado
        boton.setBackgroundColor(getColor(R.color.black))
        boton.setTextColor(getColor(android.R.color.white))
    }
    private fun conectarYLeerPuertasDahua(ip: String, user: String, pass: String) {
        val url = "http://$ip/cgi-bin/accessControl.cgi?action=getDoorList"

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. PRIMER INTENTO (Esperamos un 401)
                val request = Request.Builder().url(url).build()
                httpClient.newCall(request).execute().use { response ->

                    if (response.code == 401) {
                        val authHeader = response.header("WWW-Authenticate")
                        if (authHeader != null && authHeader.contains("Digest")) {

                            // 2. REINTENTO: Calculamos el Digest y volvemos a enviar
                            val digestHeader = DahuaDigestHelper.calculateDigest(
                                authHeader, user, pass, "GET", "/cgi-bin/accessControl.cgi?action=getDoorList"
                            )

                            val authenticatedRequest = Request.Builder()
                                .url(url)
                                .addHeader("Authorization", digestHeader)
                                .build()

                            httpClient.newCall(authenticatedRequest).execute().use { authResponse ->
                                if (authResponse.isSuccessful) {
                                    val body = authResponse.body?.string() ?: ""
                                    procesarRespuestaPuertasDahua(body)
                                } else {
                                    showToast("Error tras autenticar: ${authResponse.code}")
                                }
                            }
                        }
                    } else if (response.isSuccessful) {
                        // Por si acaso no tuviera seguridad activada
                        procesarRespuestaPuertasDahua(response.body?.string() ?: "")
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(this@VigilanteConfigActivity, "Error Dahua: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }
    private suspend fun procesarRespuestaPuertasDahua(responseBody: String) {
        // Dahua suele responder en texto plano tipo: list.Channels[0]=1 \n list.Channels[1]=2
        // Vamos a extraer los números de canal
        val canales = mutableListOf<String>()
        val lines = responseBody.split("\n")
        lines.forEach { line ->
            if (line.contains("Channels[")) {
                val canal = line.substringAfter("=").trim()
                if (canal.isNotEmpty()) canales.add("Puerta Canal $canal")
            }
        }

        withContext(Dispatchers.Main) {
            if (canales.isEmpty()) {
                showToast("No se encontraron puertas en el controlador")
            } else {
                val adapter = ArrayAdapter(this@VigilanteConfigActivity, android.R.layout.simple_spinner_dropdown_item, canales)
                binding.spDahuaDoors.adapter = adapter
                binding.spDahuaDoors.visibility = View.VISIBLE
                binding.btnTestDahua.visibility = View.VISIBLE
                showToast("Puertas detectadas: ${canales.size}")
            }
        }
    }
    private fun abrirPuertaDahua(ip: String, user: String, pass: String, canal: String, nombrePuerta: String) {
        // La acción para abrir es openDoor y requiere el parámetro channel
        val path = "/cgi-bin/accessControl.cgi?action=openDoor&channel=$canal"
        val url = "http://$ip$path"

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. PRIMER INTENTO (Sin credenciales)
                val request = Request.Builder().url(url).build()
                httpClient.newCall(request).execute().use { response ->

                    if (response.code == 401) {
                        val authHeader = response.header("WWW-Authenticate")
                        if (authHeader != null && authHeader.contains("Digest")) {

                            // 2. SEGUNDO INTENTO (Con Digest Auth)
                            val digestHeader = DahuaDigestHelper.calculateDigest(
                                authHeader, user, pass, "GET", path
                            )

                            val authenticatedRequest = Request.Builder()
                                .url(url)
                                .addHeader("Authorization", digestHeader)
                                .build()

                            httpClient.newCall(authenticatedRequest).execute().use { authResponse ->
                                if (authResponse.isSuccessful) {
                                    // 3. GUARDAR EN CACHE SI ES EXITOSO
                                    mySettings.saveString("DAHUA_SELECTED_DOOR_NAME", nombrePuerta)
                                    mySettings.saveString("DAHUA_SELECTED_DOOR_CHAN", canal)

                                    showToast("✅ Apertura exitosa: $nombrePuerta")
                                } else {
                                    showToast("❌ Error Dahua: ${authResponse.code}")
                                }
                            }
                        }
                    } else if (response.isSuccessful) {
                        showToast("✅ Apertura exitosa (Sin Auth)")
                    }
                }
            } catch (e: Exception) {
                showToast("❌ Error de red: ${e.message}")
            }
        }
    }

    private suspend fun showToast(message: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(this@VigilanteConfigActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun probarPulsadorHttp(url: String) {
        if (url.isEmpty()) {
            Toast.makeText(this, "La URL está vacía", Toast.LENGTH_SHORT).show()
            return
        }

        // Asegurar que la URL tenga protocolo
        val finalUrl = if (!url.startsWith("http")) "http://$url" else url
        mySettings.saveString("URL_OPEN", finalUrl)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Usando Request.Builder como solicitaste
                val request = Request.Builder()
                    .url(finalUrl)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val isSuccessful = response.isSuccessful
                    withContext(Dispatchers.Main) {
                        if (isSuccessful) {
                            Toast.makeText(this@VigilanteConfigActivity, "✅ Comando enviado con éxito", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@VigilanteConfigActivity, "⚠️ Error del servidor: ${response.code}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@VigilanteConfigActivity, "❌ Error de conexión: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun registrarSatélite(modo: SateliteMode) {
        mySettings.saveString("SATELITE_NODE_MODE", modo.name)
        Toast.makeText(this, "Modo ${modo.name} guardado", Toast.LENGTH_SHORT).show()

        when (modo) {
            SateliteMode.CASETA -> {
                startActivity(Intent(this, CasetaCentralActivity::class.java))
                finish()
            }
            SateliteMode.INGRESO_VEHICULAR -> {
                startActivity(Intent(this, IngresoVehicularActivity::class.java))
                finish()
            }
            SateliteMode.INGRESO_PEATONAL -> {
                startActivity(Intent(this, IngresoPeatonalActivity::class.java))
                finish()
            }
            SateliteMode.SALIDA_VEHICULAR -> {
                startActivity(Intent(this, SalidaVehicularActivity::class.java))
                finish()
            }
            SateliteMode.SALIDA_PEATONAL -> {
                startActivity(Intent(this, SalidaPeatonalActivity::class.java))
                finish()
            }
            else -> {
                Toast.makeText(this, "Modo no soportado", Toast.LENGTH_SHORT).show()
            }
        }
    }

}




object DahuaDigestHelper {
    fun calculateDigest(authHeader: String, user: String, pass: String, method: String, uri: String): String {
        val params = authHeader.replace("Digest ", "").split(",").associate {
            val pair = it.trim().split("=")
            pair[0] to pair[1].replace("\"", "")
        }

        val realm = params["realm"] ?: ""
        val nonce = params["nonce"] ?: ""
        val qop = params["qop"] ?: "auth"
        val nc = "00000001"
        val cnonce = "0a4f113b" // Valor aleatorio cualquiera

        val ha1 = md5("$user:$realm:$pass")
        val ha2 = md5("$method:$uri")
        val response = md5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")

        return "Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", qop=$qop, nc=$nc, cnonce=\"$cnonce\", response=\"$response\""
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}