package de.purlife.akademieauto

import androidx.annotation.DrawableRes
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MapController
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * A POI app, not navigation: Android Auto allows only one active navigation app, so Google Maps keeps navigating.
 * MapWithContentTemplate gives POI apps their own drawing surface ("map"); the web page is rendered onto it.
 */
class BrowserScreen(carContext: CarContext) : Screen(carContext) {
    private val renderer = WebSurfaceRenderer(carContext)

    init {
        renderer.onFullscreenChanged = { invalidate() }
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
     * Skip Silence and the YouTube/Akademie switch, top right; while a video is full screen only Skip Silence
     * (the template needs at least one button). Pages and fullscreen follow the phone (see [CarSync]).
     */
    override fun onGetTemplate(): Template {
        val actions = ActionStrip.Builder()
            .addAction(iconAction(R.drawable.ic_forward) { renderer.toggleSkipSilenceSettings() })
        if (!renderer.fullscreen) {
            // Shows the site it switches to, like the phone's button.
            val target = if (renderer.site.other == Site.YOUTUBE) R.drawable.ic_site_youtube else R.drawable.ic_site_akademie
            actions.addAction(iconAction(target) {
                renderer.switchSite()
                invalidate()
            })
        }
        return MapWithContentTemplate.Builder()
            .setContentTemplate(MessageTemplate.Builder(carContext.getString(R.string.app_name)).build())
            .setActionStrip(actions.build())
            .setMapController(
                // Drag to scroll; no visible button.
                MapController.Builder().setMapActionStrip(ActionStrip.Builder().addAction(Action.PAN).build()).build()
            )
            .build()
    }

    private fun iconAction(@DrawableRes icon: Int, onClick: () -> Unit): Action =
        Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, icon)).build())
            .setOnClickListener(onClick)
            .build()
}
