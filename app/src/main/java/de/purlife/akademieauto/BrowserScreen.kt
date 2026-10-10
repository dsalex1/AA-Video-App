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

    override fun onGetTemplate(): Template {
        val actionStrip = ActionStrip.Builder()
            .addAction(iconAction(R.drawable.ic_back) { renderer.goBack() })
            .addAction(iconAction(R.drawable.ic_home) { renderer.loadHome() })
            .addAction(iconAction(R.drawable.ic_forward) { renderer.toggleSkipSilenceSettings() })
            .addAction(
                // Labelled with the site it switches to.
                Action.Builder()
                    .setTitle(renderer.site.other.label)
                    .setOnClickListener {
                        renderer.switchSite()
                        invalidate()
                    }
                    .build()
            )
            .build()
        val mapActionStrip = ActionStrip.Builder()
            .addAction(Action.PAN)
            .addAction(iconAction(R.drawable.ic_up) { renderer.scrollPage(-1) })
            .addAction(iconAction(R.drawable.ic_down) { renderer.scrollPage(1) })
            .addAction(iconAction(R.drawable.ic_fullscreen) { renderer.toggleFullscreen() })
            .build()
        return NavigationTemplate.Builder()
            .setActionStrip(actionStrip)
            .setMapActionStrip(mapActionStrip)
            .build()
    }

    private fun iconAction(@DrawableRes icon: Int, onClick: () -> Unit): Action =
        Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, icon)).build())
            .setOnClickListener(onClick)
            .build()
}
