package com.dictate

import android.content.Context
import android.util.Log
import com.rementia.openwakeword.lib.WakeWordEngine
import com.rementia.openwakeword.lib.model.DetectionMode
import com.rementia.openwakeword.lib.model.WakeWordModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class WakeWordBridge(context: Context, private val listener: Listener) {

    interface Listener {
        fun onWakeWordDetected()
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val engine = WakeWordEngine(
        context = context,
        models = listOf(WakeWordModel("Hey Jarvis", "hey_jarvis_v0.1.onnx", 0.5f)),
        detectionMode = DetectionMode.SINGLE_BEST,
        scope = scope
    )

    init {
        scope.launch {
            engine.detections.collect {
                Log.d("WakeWordBridge", "Flow collected a detection -- calling listener.onWakeWordDetected()")
                listener.onWakeWordDetected()
            }
        }
    }

    fun start() = engine.start()

    fun stop() = engine.stop()

    fun release() = engine.release()
}