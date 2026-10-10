package de.purlife.akademieauto

import androidx.annotation.DrawableRes
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Uses the navigation template only for its full-screen drawing surface; the web page is rendered onto it.
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
            actions.addAction(
                // Labelled with the site it switches to.
                Action.Builder()
                    .setTitle(renderer.site.other.label)
                    .setOnClickListener {
                        renderer.switchSite()
                        invalidate()
                    }
                    .build()
            )
        }
        return NavigationTemplate.Builder()
            .setActionStrip(actions.build())
            .setMapActionStrip(ActionStrip.Builder().addAction(Action.PAN).build()) // drag to scroll; no visible button
            .build()
    }

    private fun iconAction(@DrawableRes icon: Int, onClick: () -> Unit): Action =
        Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, icon)).build())
            .setOnClickListener(onClick)
            .build()
}
