package de.purlife.akademieauto

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityTest {
    @Test
    fun launchesWithoutCrashing() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertNotNull(activity.window.decorView)
    }

    @Test
    fun reopeningFromNotificationDoesNotCrash() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val webView = controller.get().window.decorView
        controller.pause().stop()
        // What the notification tap delivers to the singleTask activity
        controller.newIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)).start().resume()
        assertEquals(webView, controller.get().window.decorView)
    }
}
