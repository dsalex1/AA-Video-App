package de.purlife.akademieauto

import android.content.Context
import android.view.View
import android.webkit.WebView

/**
 * A WebView that never learns its window was hidden. Otherwise it pauses media itself when the app goes to the
 * background or the screen turns off, whatever the page thinks.
 */
class BackgroundWebView(context: Context) : WebView(context) {
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(View.VISIBLE)
    }
}
