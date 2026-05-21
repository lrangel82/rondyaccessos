package com.larangel.rondyaccesos.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.larangel.rondyaccesos.databinding.ActivityVigilanteConfigBinding
import com.larangel.rondyaccesos.models.MySettings
import com.larangel.rondyaccesos.models.SateliteMode
import com.larangel.rondyaccesos.caseta.CasetaCentralActivity
import com.larangel.rondyaccesos.salida.SalidaVehicularActivity
import com.larangel.rondyaccesos.salida.SalidaPeatonalActivity
import com.larangel.rondyaccesos.vehicular.IngresoVehicularActivity
import com.larangel.rondyaccesos.peatonal.IngresoPeatonalActivity

class VigilanteConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVigilanteConfigBinding
    private lateinit var mySettings: MySettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVigilanteConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mySettings = MySettings(this)

        binding.btnConfigCaseta.setOnClickListener { registrarSatéliteYArrancar(SateliteMode.CASETA, Intent(this, CasetaCentralActivity::class.java)) }
        binding.btnConfigIngresoVehicular.setOnClickListener { registrarSatéliteYArrancar(SateliteMode.INGRESO_VEHICULAR, Intent(this, IngresoVehicularActivity::class.java)) }
        binding.btnConfigIngresoPeatonal.setOnClickListener { registrarSatéliteYArrancar(SateliteMode.INGRESO_PEATONAL, Intent(this, IngresoPeatonalActivity::class.java)) }
        binding.btnConfigSalidaVehicular.setOnClickListener { registrarSatéliteYArrancar(SateliteMode.SALIDA_VEHICULAR, Intent(this, SalidaVehicularActivity::class.java)) }
        binding.btnConfigSalidaPeatonal.setOnClickListener { registrarSatéliteYArrancar(SateliteMode.SALIDA_PEATONAL, Intent(this, SalidaPeatonalActivity::class.java)) }
    }

    private fun registrarSatéliteYArrancar(modo: SateliteMode, intentDestino: Intent) {
        // Guardamos de forma persistente la configuración de hardware del satélite (Mapeado de Python)
        mySettings.saveString("SATELITE_NODE_MODE", modo.name)

        // Arrancamos la interfaz operativa del satélite
        startActivity(intentDestino)
        finish() // Sacamos la configuración del árbol de navegación para que no regrese al presionar "Atrás"
    }
}