package com.pockettranspose.settings

/** The three values the user can step from the control sheet or the notification. */
enum class AudioControl { PITCH, SPEED, GAIN }

/** Current values, for showing outside the control sheet (the playback notification). */
data class AudioValues(val pitchSemitone: Int, val playbackRate: Float, val gain: Float)
