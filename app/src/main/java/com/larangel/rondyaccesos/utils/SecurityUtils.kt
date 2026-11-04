package com.larangel.rondyaccesos.utils

import android.app.AlertDialog
import android.content.Context
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.larangel.rondyaccesos.models.MySettings

object SecurityUtils {
    fun mostrarDialogoValidacionPin(context: Context, onValid: () -> Unit) {
        val mySettings = MySettings(context)
        val targetHKey = mySettings.getString("CODIGO_ACTIVACION", "")

        val input = EditText(context).apply {
            hint = "Código de Activación"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.GRAY)
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 20, 50, 10)
            addView(input)
        }

        AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Código de Seguridad Requerido")
            .setMessage("Ingrese el Código de Activación para proceder:")
            .setView(container)
            .setPositiveButton("Aceptar") { _, _ ->
                val tecleado = input.text.toString().trim()
                if (targetHKey.isEmpty() || tecleado == targetHKey) {
                    onValid()
                } else {
                    Toast.makeText(context, "❌ Código incorrecto. Acceso denegado.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
