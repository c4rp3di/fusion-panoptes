package com.carpe.panoptes.ui.modules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.carpe.panoptes.PanoptesApp
import com.carpe.panoptes.modules.ModuleCatalog
import com.carpe.panoptes.modules.ModuleCatalogRepository
import com.carpe.panoptes.modules.ModuleDownloader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ModulesUiState(
    val modulos: List<ModuleCatalog.Entry> = emptyList(),
    val instalados: Set<String> = emptySet(),
    // v1.0.22: antes solo se sabia "instalado si/no" (instalados). Con esto,
    // la pantalla puede comparar la version instalada contra la del
    // catalogo y ofrecer "Actualizar" en vez de dejar "Desinstalar" puesto
    // aunque haya una version distinta publicada - exactamente lo que
    // causo la confusion real de "le mande el arreglo pero el informe
    // sigue dando el mismo error, caracter por caracter".
    val versionesInstaladas: Map<String, String> = emptyMap(),
    val progreso: Map<String, ModuleDownloader.Progress> = emptyMap(),
    val cargando: Boolean = true,
    val mensaje: String? = null
)

class ModulesViewModel(private val app: PanoptesApp) : ViewModel() {

    private val repo = ModuleCatalogRepository(app) { app.settingsRepository.catalogoConfig() }
    private val downloader = ModuleDownloader(app)

    private val _state = MutableStateFlow(ModulesUiState())
    val state: StateFlow<ModulesUiState> = _state.asStateFlow()

    init {
        cargarCatalogo()
    }

    private fun snapshotInstalados(): Pair<Set<String>, Map<String, String>> {
        val lista = app.moduleManager.listInstalled()
        return lista.map { it.id }.toSet() to lista.associate { it.id to it.version }
    }

    fun cargarCatalogo() {
        viewModelScope.launch {
            _state.value = _state.value.copy(cargando = true)
            val catalogo = repo.load()
            val (instalados, versiones) = snapshotInstalados()
            _state.value = _state.value.copy(
                modulos = catalogo.modules,
                instalados = instalados,
                versionesInstaladas = versiones,
                cargando = false
            )
        }
    }

    fun instalar(entrada: ModuleCatalog.Entry) {
        if (!entrada.publicado) {
            _state.value = _state.value.copy(mensaje = "Este módulo aún no está publicado en el catálogo")
            return
        }
        viewModelScope.launch {
            try {
                downloader.downloadAndInstall(entrada) { progreso ->
                    _state.value = _state.value.copy(
                        progreso = _state.value.progreso + (entrada.id to progreso)
                    )
                }
                app.moduleManager.scanInstalled()
                val (instalados, versiones) = snapshotInstalados()
                _state.value = _state.value.copy(
                    instalados = instalados,
                    versionesInstaladas = versiones,
                    mensaje = "${entrada.name} instalado (v${entrada.version})"
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(mensaje = "Error instalando ${entrada.id}: ${e.message}")
            }
        }
    }

    fun desinstalar(id: String) {
        app.moduleManager.uninstall(id)
        val (instalados, versiones) = snapshotInstalados()
        _state.value = _state.value.copy(instalados = instalados, versionesInstaladas = versiones)
    }

    fun mensajeMostrado() {
        _state.value = _state.value.copy(mensaje = null)
    }
}
