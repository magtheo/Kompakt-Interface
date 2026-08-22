package dev.magnor.kompakt.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.magnor.kompakt.data.EnrollmentManager
import dev.magnor.kompakt.domain.OfflineException
import dev.magnor.kompakt.domain.RepositoryException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Enrollment UX state (Phase 4, dev plan §6). E-ink friendly: discrete
 * states, no spinners — in-flight work only disables the button and
 * swaps a static label.
 */
class EnrollmentViewModel(
    private val enrollment: EnrollmentManager,
) : ViewModel() {

    data class UiState(
        val serverUrl: String = "",
        val deviceName: String = "",
        val busy: Boolean = false,
        val message: String? = null,
    ) {
        val canRequest: Boolean
            get() = !busy && serverUrl.isNotBlank() && deviceName.isNotBlank()
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    val enrollmentState: StateFlow<EnrollmentManager.State> = enrollment.state

    fun onServerUrlChange(value: String) {
        _ui.value = _ui.value.copy(serverUrl = value, message = null)
    }

    fun onDeviceNameChange(value: String) {
        _ui.value = _ui.value.copy(deviceName = value, message = null)
    }

    fun requestEnrollment() {
        val s = _ui.value
        if (!s.canRequest) return
        _ui.value = s.copy(busy = true, message = null)
        viewModelScope.launch {
            try {
                enrollment.requestEnrollment(
                    baseUrl = s.serverUrl.trim().let { if (it.endsWith("/")) it.dropLast(1) else it },
                    name = s.deviceName.trim(),
                )
                _ui.value = _ui.value.copy(busy = false, message = null)
            } catch (e: OfflineException) {
                _ui.value = _ui.value.copy(busy = false, message = "Could not reach server — check address and network")
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(busy = false, message = e.message ?: "enrollment failed")
            }
        }
    }

    fun poll() {
        if (_ui.value.busy) return
        _ui.value = _ui.value.copy(busy = true, message = null)
        viewModelScope.launch {
            try {
                when (enrollment.poll()) {
                    is EnrollmentManager.State.Active ->
                        _ui.value = _ui.value.copy(busy = false, message = "Enrolled — live data active")
                    is EnrollmentManager.State.AwaitingApproval ->
                        _ui.value = _ui.value.copy(busy = false, message = "Still waiting for approval")
                    EnrollmentManager.State.NotEnrolled ->
                        _ui.value = _ui.value.copy(busy = false, message = "No enrollment on server — enroll again")
                }
            } catch (e: OfflineException) {
                _ui.value = _ui.value.copy(busy = false, message = "Could not reach server")
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(busy = false, message = e.message ?: "check failed")
            }
        }
    }

    fun forget() {
        enrollment.forget()
        _ui.value = UiState(message = "Device forgotten")
    }
}
