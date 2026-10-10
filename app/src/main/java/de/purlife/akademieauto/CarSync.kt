package de.purlife.akademieauto

/**
 * Makes the car's web view (Akademie Web) follow the phone: the same page, and a video the phone shows full screen
 * continues full screen in the car while the phone pauses. One way only; the car's own taps don't go back.
 */
object CarSync {
    interface Car {
        fun load(url: String)
        fun showVideo(key: String, positionS: Double)
        fun exitVideo()
    }

    /** The car's renderer while Akademie Web is on screen. */
    var car: Car? = null
        set(value) {
            field = value
            phoneUrl?.let { value?.load(it) }
        }

    private var phoneUrl: String? = null

    fun phoneNavigated(url: String) {
        phoneUrl = url
        car?.load(url)
    }

    fun phoneFullscreen(on: Boolean) {
        val car = car ?: return
        val media = MediaHub.current
        DiagLog.add("phone fullscreen=$on, playing: ${media?.key} at ${media?.positionMs} ms")
        if (!on) return car.exitVideo()
        if (media == null || media.key.isEmpty()) return
        val playing = media.playing
        car.showVideo(media.key, media.positionMs / 1000.0)
        if (playing) MediaHub.command("pause") // the car continues it; no double audio
    }
}
