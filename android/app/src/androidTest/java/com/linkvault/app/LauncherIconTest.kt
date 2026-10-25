package com.linkvault.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LauncherIconTest {
    @Test
    fun manifestLauncherUsesLatestBookmarkArtworkAndBackground() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val icon = context.packageManager.getApplicationIcon(context.packageName)
        assertTrue("Manifest icon must be adaptive", icon is AdaptiveIconDrawable)
        val adaptive = icon as AdaptiveIconDrawable
        val background = Bitmap.createBitmap(108, 108, Bitmap.Config.ARGB_8888)
        adaptive.background.setBounds(0, 0, 108, 108)
        adaptive.background.draw(Canvas(background))
        assertEquals(Color.rgb(232, 234, 223), background.getPixel(54, 20))

        val foreground = Bitmap.createBitmap(108, 108, Bitmap.Config.ARGB_8888)
        val artwork = checkNotNull(context.getDrawable(R.drawable.ic_linkfilebox))
        artwork.setBounds(0, 0, 108, 108)
        artwork.draw(Canvas(foreground))
        assertEquals("Bookmark box border", Color.rgb(48, 55, 45), foreground.getPixel(31, 55))
        assertEquals("Rear card border", Color.rgb(146, 155, 132), foreground.getPixel(76, 45))
        assertEquals("Box interior remains open", 0, Color.alpha(foreground.getPixel(40, 66)))

        val monochrome = Bitmap.createBitmap(108, 108, Bitmap.Config.ARGB_8888)
        val themedArtwork = checkNotNull(context.getDrawable(R.drawable.ic_linkfilebox_monochrome))
        themedArtwork.setBounds(0, 0, 108, 108)
        themedArtwork.draw(Canvas(monochrome))
        assertEquals(Color.WHITE, monochrome.getPixel(31, 55))
        assertEquals(Color.WHITE, monochrome.getPixel(76, 45))
        assertEquals(0, Color.alpha(monochrome.getPixel(40, 66)))

        val preview = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
        adaptive.setBounds(0, 0, 432, 432)
        adaptive.draw(Canvas(preview))
        File(context.cacheDir, "launcher-icon-preview.png").outputStream().use {
            assertTrue(preview.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        listOf(background, foreground, monochrome, preview).forEach(Bitmap::recycle)
    }
}
