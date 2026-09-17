package com.powercess.mbrain.gateway

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ActivationState(val checking: Boolean = false, val error: String? = null)

/** Lives with the application, so an authorization dialog survives Activity recreation. */
class CapabilityActivation(
    private val scope: CoroutineScope,
    private val authorize: suspend () -> Unit,
    private val onEnabled: (Boolean) -> Unit,
) {
    private val mutableState = MutableStateFlow(ActivationState())
    val state = mutableState.asStateFlow()
    private var pending: Job? = null

    fun setEnabled(enabled: Boolean) {
        if (!enabled) {
            pending?.cancel()
            mutableState.value = ActivationState()
            onEnabled(false)
            return
        }
        if (state.value.checking) return
        mutableState.value = ActivationState(checking = true)
        pending = scope.launch {
            try {
                authorize()
                ensureActive()
                mutableState.value = ActivationState()
                onEnabled(true)
            } catch (timeout: TimeoutCancellationException) {
                onEnabled(false)
                mutableState.value = ActivationState(error = "权限检查超时，请重试")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                onEnabled(false)
                mutableState.value = ActivationState(error = error.message ?: "授权未完成，请重试")
            }
        }
    }
}
