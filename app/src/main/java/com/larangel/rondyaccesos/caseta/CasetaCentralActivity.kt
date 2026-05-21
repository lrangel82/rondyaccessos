package com.larangel.rondyaccesos.caseta

import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.larangel.rondyaccesos.databinding.ActivityCasetaCentralBinding
import com.larangel.rondyaccesos.databinding.ItemSateliteMonitorBinding
import com.larangel.rondyaccesos.models.SateliteMode
import com.larangel.rondyaccesos.models.com.larangel.rondyaccesos.caseta.CasetaCentralViewModel
import com.larangel.rondyaccesos.models.com.larangel.rondyaccesos.caseta.SateliteNodoState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class CasetaCentralActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCasetaCentralBinding
    private val viewModel: CasetaCentralViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCasetaCentralBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupClickListenersDeMosaicos()
        observarFlujoDeEstadoCentral()
    }

    private fun setupClickListenersDeMosaicos() {
        // Enlazar clics para maximizar el satélite correspondiente en pantalla completa
        binding.cardIngresoVehicular.setOnClickListener {
            viewModel.uiState.value.nodosSatelites.find { it.role == SateliteMode.INGRESO_VEHICULAR }?.let {
                viewModel.seleccionarSateliteParaDetalle(it)
            }
        }
        binding.cardIngresoPeatonal.setOnClickListener {
            viewModel.uiState.value.nodosSatelites.find { it.role == SateliteMode.INGRESO_PEATONAL }?.let {
                viewModel.seleccionarSateliteParaDetalle(it)
            }
        }
    }

    private fun observarFlujoDeEstadoCentral() {
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                binding.lblServerStatus.text = state.statusServidorTxt
                binding.lblTotalIngresos.text = "Ingresos Hoy: ${state.totalIngresosHoy}"
                binding.lblTotalSalidas.text = "Salidas Hoy: ${state.totalSalidasHoy}"

                // 1. Actualizar de forma reactiva cada celda del mosaico
                state.nodosSatelites.forEach { nodo ->
                    when (nodo.role) {
                        SateliteMode.INGRESO_VEHICULAR -> vincularDatosFilaSatelite(ItemSateliteMonitorBinding.bind(binding.viewIngresoVehicular.root), nodo)
                        SateliteMode.INGRESO_PEATONAL -> vincularDatosFilaSatelite(ItemSateliteMonitorBinding.bind(binding.viewIngresoPeatonal.root), nodo)
                        SateliteMode.SALIDA_VEHICULAR -> vincularDatosFilaSatelite(ItemSateliteMonitorBinding.bind(binding.viewSalidaVehicular.root), nodo)
                        SateliteMode.SALIDA_PEATONAL -> vincularDatosFilaSatelite(ItemSateliteMonitorBinding.bind(binding.viewSalidaPeatonal.root), nodo)
                        else -> {}
                    }
                }

                // 2. Controlar la visibilidad del Panel Modal de Auditoría
                if (state.sateliteSeleccionado != null) {
                    binding.panelDetalleAuditoria.visibility = View.VISIBLE
                    // Pinta datos estructurados como teléfono del responsable, morosidad, permisos activos, etc.
                    actualizarCamposPanelAuditoriaIncrustado(state.sateliteSeleccionado)
                } else {
                    binding.panelDetalleAuditoria.visibility = View.GONE
                }
            }
        }
    }

    private fun vincularDatosFilaSatelite(subBinding: ItemSateliteMonitorBinding, nodo: SateliteNodoState) {
        subBinding.txtNombreSatelite.text = nodo.role.name.replace("_", " ")
        subBinding.txtIpSatelite.text = "IP Node: ${nodo.ipAddress}"

        if (nodo.estaEnLinea) {
            subBinding.txtEstatusLinea.text = "• EN LINEA"
            subBinding.txtEstatusLinea.setTextColor(Color.GREEN)
        } else {
            subBinding.txtEstatusLinea.text = "• DESCONECTADO"
            subBinding.txtEstatusLinea.setTextColor(Color.RED)
        }

        // Si el satélite gatilló una alerta de bloqueo (Morosidad/Lista negra), hace parpadear la celda en rojo
        if (nodo.requiereAsistencia) {
            subBinding.root.setBackgroundColor(Color.parseColor("#420A0A"))
            subBinding.txtUltimaPlaca.text = "⚠️ ATENCIÓN: ALERTA DE ACCESO BLOQUEADO"
            subBinding.txtUltimaPlaca.setTextColor(Color.RED)
        } else {
            subBinding.root.setBackgroundColor(Color.parseColor("#212121"))
            nodo.ultimoRegistroRecibido?.let {
                subBinding.txtUltimaPlaca.text = "Último vehículo: ${it.placa} -> ${it.statusStr}"
                subBinding.txtUltimaPlaca.setTextColor(Color.LTGRAY)
            }
        }
    }

    private fun actualizarCamposPanelAuditoriaIncrustado(satelite: SateliteNodoState) {
        val detalleBinding = binding.viewDetalleIncrustado
        // Configurar textos dinámicos basados en el satélite que gatilló el clic
        satelite.ultimoRegistroRecibido?.let { registro ->
            detalleBinding.lblAuditoriaDireccion.text = "Domicilio: ${registro.calle} #${registro.numero}"
            detalleBinding.lblAuditoriaDetalleVisita.text = "Pasajero: ${registro.conductor}\nMotivo: ${registro.descripcion}"

            if (satelite.requiereAsistencia) {
                detalleBinding.lblAuditoriaEstatusCasa.text = "Estatus: ALERTA DE BLOQUEO ACTIVA ⚠️"
                detalleBinding.lblAuditoriaEstatusCasa.setTextColor(Color.RED)
            } else {
                detalleBinding.lblAuditoriaEstatusCasa.text = "Estatus: Sin anomalías dadas de alta"
                detalleBinding.lblAuditoriaEstatusCasa.setTextColor(Color.GREEN)
            }
        }

        // Acción para anular remotamente el bloqueo y mandarle el JSON de apertura al satélite hijo
        detalleBinding.btnForzarAperturaRemota.setOnClickListener {
            viewModel.forzarAperturaRemotaDesdeCaseta(satelite.role)
        }

        // Ocultar el panel modal y regresar a la rejilla de monitoreo de mosaicos
        detalleBinding.btnCerrarAuditoria.setOnClickListener {
            viewModel.cerrarDetalle()
        }
    }
}