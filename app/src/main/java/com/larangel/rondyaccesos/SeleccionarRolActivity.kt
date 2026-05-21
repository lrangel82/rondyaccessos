package com.larangel.rondyaccesos.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.larangel.rondyaccesos.databinding.ActivitySeleccionarRolBinding
import com.larangel.rondyaccesos.models.MySettings
import com.larangel.rondyaccesos.admin.AdminMainActivity

class SeleccionarRolActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySeleccionarRolBinding
    private lateinit var mySettings: MySettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySeleccionarRolBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mySettings = MySettings(this)

        binding.cardRolVigilante.setOnClickListener {
            // Guardamos el modo preferido como "VIGILANTE"
            mySettings.saveString("ROLY_MODE", "VIGILANTE")

            // Enviamos a configurar qué satélite o caseta será este equipo
            startActivity(Intent(this, VigilanteConfigActivity::class.java))
            finish()
        }

        binding.cardRolAdmin.setOnClickListener {
            // Guardamos el modo preferido como "ADMINISTRADOR"
            mySettings.saveString("ROLY_MODE", "ADMINISTRADOR")

            // Enviamos directo al Panel de Administración Central
            startActivity(Intent(this, AdminMainActivity::class.java))
            finish()
        }
    }
}