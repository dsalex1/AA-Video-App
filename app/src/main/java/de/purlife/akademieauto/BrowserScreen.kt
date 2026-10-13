package de.purlife.akademieauto

import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MapController
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * A POI app, not navigation: Android Auto allows only one active navigation app, so Google Maps keeps navigating.
 * MapWithContentTemplate gives POI apps their own drawing surface ("map"); the web page is rendered onto it.
 */
class BrowserScreen(carContext: CarContext) : Screen(carContext) {
    private val renderer = WebSurfaceRenderer(carContext)
    private var cardText = ""
    private val refresh = {
        if (cardText != cardText()) invalidate()
    }

    init {
        renderer.onPageChanged = refresh
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
                PlaybackService.start(carContext)
                MediaHub.addListener(refresh)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                MediaHub.removeListener(refresh)
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
                renderer.release()
            }
        })
    }

    /** "YouTube · Lektion 3: Anat…": the site and the start of what's playing, short enough for one line. */
    private fun cardText(): String {
        val title = MediaHub.current?.title?.trim().orEmpty()
        val site = renderer.pageSite.label
        if (title.isEmpty()) return site
        val room = CARD_CHARS - site.length - 3
        return site + " · " + if (title.length <= room) title else title.take(room - 1).trimEnd() + "…"
    }

    /**
     * The template insists on a content card (top left); it names the site and video. The buttons are drawn by
     * [CarPresentation], so Android Auto adds no button strip.
     */
    override fun onGetTemplate(): Template {
        cardText = cardText()
        return MapWithContentTemplate.Builder()
            .setContentTemplate(MessageTemplate.Builder(cardText).build())
            .setMapController(
                // Drag to scroll; no visible button.
                MapController.Builder().setMapActionStrip(ActionStrip.Builder().addAction(Action.PAN).build()).build()
            )
            .build()
    }

    private companion object {
        /** What fits on one line of the card; longer text wraps and makes the card taller. */
        const val CARD_CHARS = 28
    }
}
