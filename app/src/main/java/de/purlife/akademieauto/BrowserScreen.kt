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

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
                PlaybackService.start(carContext)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
                renderer.release()
            }
        })
    }

    /**
     * The template insists on a content card (top left); [CarPresentation] starts the page below it. The buttons are
     * drawn by [CarPresentation] too, so Android Auto adds no button strip.
     */
    override fun onGetTemplate(): Template =
        MapWithContentTemplate.Builder()
            // An invisible character: the card keeps its one-line size, without text (a title could wrap to two lines).
            .setContentTemplate(MessageTemplate.Builder("\u200B").build())
            .setMapController(
                // Drag to scroll; no visible button.
                MapController.Builder().setMapActionStrip(ActionStrip.Builder().addAction(Action.PAN).build()).build()
            )
            .build()
}
