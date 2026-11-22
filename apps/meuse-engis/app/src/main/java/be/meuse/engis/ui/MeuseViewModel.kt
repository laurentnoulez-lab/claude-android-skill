package be.meuse.engis.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import be.meuse.engis.data.MeuseRepository
import be.meuse.engis.domain.MeuseSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

enum class Reference { NORMALE, MOIS_EN_COURS }

data class MeuseUiState(
    val snapshot: MeuseSnapshot? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val reference: Reference = Reference.NORMALE,
)

class MeuseViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = MeuseRepository(app)

    private val _state = MutableStateFlow(MeuseUiState(snapshot = repository.cached()))
    val state: StateFlow<MeuseUiState> = _state.asStateFlow()

    /** Appelé à chaque retour au premier plan : recharge si les données ont plus de 10 minutes. */
    fun refreshIfStale() {
        val last = _state.value.snapshot?.fetchedAt
        if (last == null || Duration.between(last, Instant.now()) > Duration.ofMinutes(10)) refresh()
    }

    fun refresh() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val snapshot = repository.refresh()
                _state.update { it.copy(snapshot = snapshot, loading = false) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        loading = false,
                        error = "Impossible de joindre le serveur du SPW (${e.message ?: e.javaClass.simpleName}).",
                    )
                }
            }
        }
    }

    fun setReference(reference: Reference) = _state.update { it.copy(reference = reference) }
}
