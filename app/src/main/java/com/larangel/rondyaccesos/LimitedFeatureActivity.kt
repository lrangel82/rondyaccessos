package com.larangel.rondyaccesos.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.larangel.rondyaccesos.SplashActivity
import com.larangel.rondyaccesos.databinding.ActivityLimitedFeatureBinding

class LimitedFeatureActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLimitedFeatureBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLimitedFeatureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configurarBotonesLimitados()
    }

    private fun configurarBotonesLimitados() {
        binding.btnAltaDomiciliosLimitado.setOnClickListener {
            Toast.makeText(this, "Abriendo Catálogo Base (Calle, Núm, Extensión)", Toast.LENGTH_SHORT).show()
            // TODO: Invocar tu formulario local para dar de alta domicilios en la tabla local
            // Considerar que en este modo no se guarda el número de celular para Whatsapp.
        }

        binding.btnVerBitacoraLimitado.setOnClickListener {
            Toast.makeText(this, "Cargando Bitácora Histórica Local", Toast.LENGTH_SHORT).show()
            // TODO: Cargar un RecyclerView simple conectado al caché de SheetTable.BITACORA_ACCESOS
        }

        binding.btnReintentarLicencia.setOnClickListener {
            Toast.makeText(this, "Reiniciando validación de S3...", Toast.LENGTH_SHORT).show()

            // Regresar al Splash rompiendo la pila para volver a validar la red
            val intentSplash = Intent(this, SplashActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intentSplash)
            finish()
        }
    }
}