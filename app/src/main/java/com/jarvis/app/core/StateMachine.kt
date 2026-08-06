package com.jarvis.app.core

enum class JarvisState {
    IDLE,
    NOTIFY_ANNOUNCE,
    NOTIFY_READ,
    NOTIFY_OPTIONS,
    LISTENING,
    PROCESSING,
    CONFIRMING,
    EXECUTING
}

enum class ButtonEvent {
    VOLUME_UP,
    VOLUME_DOWN,
    LONG_PRESS_VOLUME_UP,
    LONG_PRESS_VOLUME_DOWN
}

class StateMachine(private val listener: StateListener) {

    var currentState: JarvisState = JarvisState.IDLE
        private set

    interface StateListener {
        fun onStateChanged(oldState: JarvisState, newState: JarvisState)
    }

    fun transition(newState: JarvisState) {
        val old = currentState
        currentState = newState
        listener.onStateChanged(old, newState)
    }

    fun handleButtonEvent(event: ButtonEvent) {
        when (currentState) {
            JarvisState.IDLE -> when (event) {
                ButtonEvent.LONG_PRESS_VOLUME_DOWN -> transition(JarvisState.LISTENING)
                else -> { /* ignore in idle */ }
            }

            JarvisState.NOTIFY_ANNOUNCE -> when (event) {
                ButtonEvent.VOLUME_UP -> transition(JarvisState.NOTIFY_READ)
                ButtonEvent.VOLUME_DOWN -> transition(JarvisState.IDLE)
                ButtonEvent.LONG_PRESS_VOLUME_DOWN -> transition(JarvisState.LISTENING)
                else -> {}
            }

            JarvisState.NOTIFY_READ -> when (event) {
                ButtonEvent.LONG_PRESS_VOLUME_DOWN -> transition(JarvisState.LISTENING)
                else -> { /* TTS is speaking, wait for completion */ }
            }

            JarvisState.NOTIFY_OPTIONS -> when (event) {
                ButtonEvent.VOLUME_UP -> transition(JarvisState.NOTIFY_READ) // repeat
                ButtonEvent.VOLUME_DOWN -> transition(JarvisState.IDLE) // dismiss
                ButtonEvent.LONG_PRESS_VOLUME_DOWN -> transition(JarvisState.LISTENING) // voice command
                else -> {}
            }

            JarvisState.LISTENING -> when (event) {
                ButtonEvent.VOLUME_DOWN -> transition(JarvisState.IDLE) // cancel
                else -> {}
            }

            JarvisState.PROCESSING -> {
                // No button actions during processing
            }

            JarvisState.CONFIRMING -> when (event) {
                ButtonEvent.VOLUME_UP -> transition(JarvisState.EXECUTING) // confirm
                ButtonEvent.VOLUME_DOWN -> transition(JarvisState.IDLE) // cancel
                else -> {}
            }

            JarvisState.EXECUTING -> {
                // No button actions during execution
            }
        }
    }
}
