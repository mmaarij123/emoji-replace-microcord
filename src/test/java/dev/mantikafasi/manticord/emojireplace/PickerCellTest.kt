package dev.mantikafasi.manticord.emojireplace

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.widget.ImageView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PickerCellTest {
    @Test
    fun `loaded artwork is installed on the native image view and the stock art can be restored`() {
        val view = cellView()
        val stock = view.drawable
        var clicked = false
        view.setOnClickListener { clicked = true }
        val cell = PickerCell(view, "grinning") { target ->
            target.onImageLoaded(makeBitmap())
            true
        }

        cell.render()
        assertEquals(192, view.imageAlpha)
        assertTrue(view.drawable is BitmapDrawable)
        assertTrue(view.performClick())
        assertTrue(clicked)

        cell.restore()
        assertEquals(192, view.imageAlpha)
        assertSame(stock, view.drawable)
    }

    @Test
    fun `load completion after recycling leaves the new binding alone`() {
        val view = cellView()
        lateinit var formerTarget: EmojiImageTarget
        val first = PickerCell(view, "grinning") { target ->
            formerTarget = target
            true
        }
        first.render()
        assertEquals(192, view.imageAlpha)
        first.restore()
        view.setImageDrawable(ColorDrawable(Color.BLUE))
        view.imageAlpha = 227
        val custom = view.drawable

        formerTarget.onImageLoaded(makeBitmap())
        first.render()

        assertEquals(227, view.imageAlpha)
        assertSame(custom, view.drawable)
    }

    @Test
    fun `recycling after the host binds a new drawable leaves that drawable installed`() {
        val view = cellView()
        val cell = PickerCell(view, "grinning") { target ->
            target.onImageLoaded(makeBitmap())
            true
        }
        cell.render()
        val newHostImage = ColorDrawable(Color.BLUE)
        view.setImageDrawable(newHostImage)

        cell.restore()

        assertSame(newHostImage, view.drawable)
    }

    @Test
    fun `failed artwork decode keeps the stock art visible`() {
        val view = cellView()
        var requests = 0
        val cell = PickerCell(view, "grinning") { target ->
            requests++
            target.onImageLoaded(null)
            true
        }

        cell.render()
        cell.render()

        assertEquals(1, requests)
        assertEquals(192, view.imageAlpha)
    }

    private fun cellView() = ImageView(RuntimeEnvironment.getApplication()).apply {
        layout(0, 0, 52, 52)
        setPadding(4, 4, 4, 4)
        setImageDrawable(ColorDrawable(Color.YELLOW))
        imageAlpha = 192
    }

    private fun makeBitmap() = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
}
