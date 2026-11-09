/*
 * Adapted from VLC for Android AccessibleSeekBar.kt.
 * Copyright © VideoLAN and VLC authors.
 * Licensed under GNU GPL version 2 or (at your option) any later version.
 * Original: https://github.com/videolan/vlc-android
 * Adaptation: standalone accessibility behavior for MPM Player.
 */
package com.mpcmovil

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.accessibility.AccessibilityEvent
import androidx.appcompat.widget.AppCompatSeekBar
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat

class VlcAccessibleSeekBar : AppCompatSeekBar {
    private val delegate = object : AccessibilityDelegateCompat() {
        var force = false
        var disabled = true
        override fun sendAccessibilityEventUnchecked(host: View, event: AccessibilityEvent) {
            if (disabled) {
                super.sendAccessibilityEventUnchecked(host, event)
                return
            }
            this@VlcAccessibleSeekBar.contentDescription = "Posición ${format(progress)} de ${format(max)}"
            if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                event.eventType != AccessibilityEvent.TYPE_VIEW_SELECTED) {
                super.sendAccessibilityEventUnchecked(host, event)
            } else if (force) {
                super.sendAccessibilityEventUnchecked(host, event)
                force = false
            }
        }
        private fun format(value: Int): String {
            val seconds = value / 1000
            return String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
        }
    }
    constructor(context: Context) : super(context) { initialize() }
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) { initialize() }
    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(context, attrs, defStyle) { initialize() }
    private fun initialize() { ViewCompat.setAccessibilityDelegate(this, delegate) }
    fun forceAccessibilityUpdate() { delegate.force = true }
    fun disableAccessibilityEvents() { delegate.disabled = true }
    fun enableAccessibilityEvents() { delegate.disabled = false }
}
