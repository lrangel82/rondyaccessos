package com.larangel.rondyaccesos.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.larangel.rondyaccesos.RondyApplication
import com.larangel.rondyaccesos.databinding.ActivityVigilanteConfigBinding
import com.larangel.rondyaccesos.models.MySettings
import com.larangel.rondyaccesos.models.SateliteMode
import com.larangel.rondyaccesos.caseta.CasetaCentralActivity
import com.larangel.rondyaccesos.salida.SalidaVehicularActivity
import com.larangel.rondyaccesos.salida.SalidaPeatonalActivity
import com.larangel.rondyaccesos.vehicular.IngresoVehicularActivity
import com.larangel.rondyaccesos.peatonal.IngresoPeatonalActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class VigilanteConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVigilanteConfigBinding
    private lateinit var mySettings: MySettings
    private val networkManager by lazy { (application as RondyApplication).networkManager }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVigilanteConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mySettings = MySettings(this)

        observeLogs()

        binding.btnConfigCaseta.setOnClickListener { registrarSatéliteYArrancar(SateliteMode.CASETA, Intent(this, CasetaCentralActivity::class.java)) }
        binding.btnConfigIngresoVehicular.setOnClickListener { registrarSatéliteYArrancar(SateliteMode.INGRESO_VEHICULAR, Intent(this, IngresoVehicularActivity::class.java)) }
        binding.btnConfigIngresoPeatonal.setOnClickListener { registrarSatéliteYArrancar(SateliteMode.INGRESO_PEATONAL, Intent(this, IngresoPeatonalActivity::class.java)) }
        binding.btnConfigSalidaVehicular.setOnClickListener { registrarSatéliteYArrancar(SateliteMode.SALIDA_VEHICULAR, Intent(this, SalidaVehicularActivity::class.java)) }
        binding.btnConfigSalidaPeatonal.setOnClickListener { registrarSatéliteYArrancar(SateliteMode.SALIDA_PEATONAL, Intent(this, SalidaPeatonalActivity::class.java)) }

        binding.btnBuscarAmigosRED.setOnClickListener {
            Toast.makeText(this, "Iniciando escaneo...", Toast.LENGTH_SHORT).show()

            // Si realizarHandshakeInicial es una función suspend, usa launch:
            lifecycleScope.launch {
                networkManager.realizarHandshakeInicial(force = true)
            }
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


    private fun registrarSatéliteYArrancar(modo: SateliteMode, intentDestino: Intent) {
        // Guardamos de forma persistente la configuración de hardware del satélite (Mapeado de Python)
        mySettings.saveString("SATELITE_NODE_MODE", modo.name)

        // Arrancamos la interfaz operativa del satélite
        startActivity(intentDestino)
        finish() // Sacamos la configuración del árbol de navegación para que no regrese al presionar "Atrás"
    }
}