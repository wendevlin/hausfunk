package dev.wendelin.hausfunk.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Parent side sound: plays the baby as media, holds audio focus while watching, and mutes the
 * output for the noise gate. Used on the session's thread only.
 */
class ParentAudio(private val context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    // A baby monitor never pauses itself, so focus changes are ignored.
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { }
        .build()
    private var focused = false
    private var muted = false

    /** The module of the current PeerConnectionFactory; replaced when the factory is. */
    var module: JavaAudioDeviceModule? = null
        private set

    fun createModule(): JavaAudioDeviceModule =
        JavaAudioDeviceModule.builder(context)
            .setAudioAttributes(attributes)
            .createAudioDeviceModule()
            .also {
                it.setSpeakerMute(muted)
                module = it
            }

    fun releaseModule() {
        module?.release()
        module = null
    }

    /** Silences the output itself, which is certain to be silent (the remote track's volume was not). */
    fun setMuted(mute: Boolean) {
        muted = mute
        module?.setSpeakerMute(mute)
    }

    fun requestFocus() {
        if (focused) return
        focused = true
        audioManager.requestAudioFocus(focusRequest)
    }

    fun abandonFocus() {
        if (!focused) return
        focused = false
        audioManager.abandonAudioFocusRequest(focusRequest)
    }
}
