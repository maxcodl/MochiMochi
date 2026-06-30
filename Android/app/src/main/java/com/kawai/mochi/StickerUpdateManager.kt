package com.kawai.mochi

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object StickerUpdateManager {
    private val _updateEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val updateEvent = _updateEvent.asSharedFlow()

    @JvmStatic
    fun triggerUpdate() {
        _updateEvent.tryEmit(Unit)
    }

    @JvmStatic
    fun notifyStickersChanged() {
        triggerUpdate()
    }
}