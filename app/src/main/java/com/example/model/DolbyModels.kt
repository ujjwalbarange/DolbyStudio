package com.example.model

enum class AppScreen {
    HERO_SPLASH,
    MAIN_SETTINGS,
    PRESET_EDITOR,
    CUSTOM_EDITOR
}

enum class AudioMode(
    val title: String,
    val subtitle: String,
    val isSpatial: Boolean = false,
    val hasEditor: Boolean = false
) {
    SPATIAL_AUDIO(
        title = "Spatial audio",
        subtitle = "Audio from compatible media becomes more immersive",
        isSpatial = true,
        hasEditor = false
    ),
    SMART_AUDIO(
        title = "Smart audio",
        subtitle = "Smart audio identifies the content and performs automatic adjustments to deliver the best sound",
        isSpatial = false,
        hasEditor = false
    ),
    MUSIC(
        title = "Music",
        subtitle = "Rich, detailed audio that delivers every note and beat with power you can feel",
        isSpatial = false,
        hasEditor = true
    ),
    FILM(
        title = "Film",
        subtitle = "Authentic surround sound and clearer dialogue for the full impact of films and TV shows",
        isSpatial = false,
        hasEditor = true
    ),
    GAME(
        title = "Game",
        subtitle = "Surround sound and clearer dialogue for an immersive game experience",
        isSpatial = false,
        hasEditor = true
    ),
    PODCAST(
        title = "Podcast",
        subtitle = "Boost dialogue for better speech clarity",
        isSpatial = false,
        hasEditor = true
    ),
    CUSTOM(
        title = "Custom",
        subtitle = "Advanced settings for your audio",
        isSpatial = false,
        hasEditor = true
    )
}

enum class EqualiserPreset(
    val displayName: String,
    val targetBandGainsDb: FloatArray
) {
    MANUAL(
        displayName = "Manual",
        targetBandGainsDb = floatArrayOf(5.8f, 5.2f, 1.5f, 0.4f, -1.2f, -1.8f, 1.4f, 1.8f, 6.2f, 8.8f)
    ),
    BRILLIANT_TREBLE(
        displayName = "Brilliant treble",
        targetBandGainsDb = floatArrayOf(0.0f, 0.0f, 0.2f, 0.6f, 1.4f, 2.6f, 4.2f, 6.0f, 7.5f, 8.5f)
    ),
    BASS_BOOST(
        displayName = "Bass boost",
        targetBandGainsDb = floatArrayOf(8.5f, 7.6f, 5.8f, 3.6f, 1.5f, 0.4f, 0.0f, 0.0f, 0.2f, 0.5f)
    ),
    VOCAL_BOOST(
        displayName = "Vocal boost",
        targetBandGainsDb = floatArrayOf(-1.5f, -1.0f, 0.5f, 2.8f, 5.0f, 6.5f, 6.2f, 4.0f, 1.2f, -0.5f)
    ),
    FLAT(
        displayName = "Flat",
        targetBandGainsDb = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
    )
}

data class ModeProfileSettings(
    val mode: AudioMode,
    val equaliserPreset: EqualiserPreset,
    val manualBandGainsDb: List<Float> = EqualiserPreset.MANUAL.targetBandGainsDb.toList(),
    val macroBassTiltDb: Float = 0f,
    val macroTrebleTiltDb: Float = 0f,
    val surroundVirtualiser: Boolean = true,
    val volumeLeveller: Boolean = true
)

/**
 * Represents a real physical audio output endpoint reported by Android AudioManager.
 * Latency fields are used strictly by the silent background delay synchronization engine
 * and are never displayed in the UI.
 */
data class HardwareAudioEndpoint(
    val deviceId: Int,
    val title: String,
    val subtitle: String,
    val androidDeviceType: Int,
    val isHeadphoneBinaural: Boolean,
    val isSelected: Boolean,
    internal val internalHardwareLatencyMs: Float = 0f,
    internal val internalCompensatingDelayMs: Float = 0f
)

object DolbyConstants {
    val ISO_10_BAND_FREQUENCIES = floatArrayOf(
        31.5f, 63f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f
    )
    val ISO_10_BAND_LABELS = listOf(
        "31.5", "63", "125", "250", "500", "1k", "2k", "4k", "8k", "16k"
    )
    const val SAMPLE_RATE = 48000.0
}
