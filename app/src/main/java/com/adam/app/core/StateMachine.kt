package com.adam.app.core

enum class AdamState {
    IDLE,
    NOTIFY_ANNOUNCE,
    NOTIFY_READ,
    NOTIFY_OPTIONS,
    LISTENING,
    PROCESSING,
    CONFIRMING,
    EXECUTING,
    CONVERSING
}

enum class ButtonEvent {
    VOLUME_UP,
    VOLUME_DOWN,
    LONG_PRESS_VOLUME_UP,
    LONG_PRESS_VOLUME_DOWN
}

class StateMachine(private val listener: StateListener) {

    var currentState: AdamState = AdamState.IDLE
        private set

    interface StateListener {
        fun onStateChanged(oldState: AdamState, newState: AdamState)
    }

    fun transition(newState: AdamState) {
        val old = currentState
        currentState = newState
        listener.onStateChanged(old, newState)
    }

    fun handleButtonEvent(event: ButtonEvent) {
        when (currentState) {
            AdamState.IDLE -> when (event) {
                ButtonEvent.LONG_PRESS_VOLUME_DOWN -> transition(AdamState.LISTENING)
                else -> { /* ignore in idle */ }
            }

            AdamState.NOTIFY_ANNOUNCE -> when (event) {
                ButtonEvent.VOLUME_UP -> transition(AdamState.NOTIFY_READ)
                ButtonEvent.VOLUME_DOWN -> transition(AdamState.IDLE)
                ButtonEvent.LONG_PRESS_VOLUME_DOWN -> transition(AdamState.LISTENING)
                else -> {}
            }

            AdamState.NOTIFY_READ -> when (event) {
                ButtonEvent.LONG_PRESS_VOLUME_DOWN -> transition(AdamState.LISTENING)
                else -> { /* TTS is speaking, wait for completion */ }
            }

            AdamState.NOTIFY_OPTIONS -> when (event) {
                ButtonEvent.VOLUME_UP -> transition(AdamState.NOTIFY_READ) // repeat
                ButtonEvent.VOLUME_DOWN -> transition(AdamState.IDLE) // dismiss
                ButtonEvent.LONG_PRESS_VOLUME_DOWN -> transition(AdamState.LISTENING) // voice command
                else -> {}
            }

            AdamState.LISTENING -> when (event) {
                ButtonEvent.VOLUME_DOWN -> transition(AdamState.IDLE) // cancel
                else -> {}
            }

            AdamState.PROCESSING -> {
                // No button actions during processing
            }

            AdamState.CONFIRMING -> when (event) {
                ButtonEvent.VOLUME_UP -> transition(AdamState.EXECUTING) // confirm
                ButtonEvent.VOLUME_DOWN -> transition(AdamState.IDLE) // cancel
                else -> {}
            }

            AdamState.EXECUTING -> {
                // No button actions during execution
            }

            AdamState.CONVERSING -> when (event) {
                ButtonEvent.VOLUME_DOWN -> transition(AdamState.IDLE) // end conversation
                else -> {}
            }
        }
    }
}
