package dev.mantikafasi.manticord.emojireplace

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import com.discord.models.domain.emoji.Emoji
import com.discord.models.domain.emoji.ModelEmojiCustom
import com.discord.models.domain.emoji.ModelEmojiUnicode
import com.discord.widgets.chat.input.emoji.OnEmojiSelectedListener
import com.discord.widgets.chat.input.emoji.WidgetEmojiAdapter
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PickerIntegrationTest {
    @Test
    fun `detects Microcord's adapter and renders packaged BlobMoji on its standard emoji cell`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val activity = controller.get()
        val app = activity.application
        val catalog = EmojiPackCatalog(app.assets)
        val images = EmojiImageLoader(EmojiPack.BLOBMOJI, catalog)
        val unicode = ModelEmojiUnicode(names = listOf("grinning"), surrogates = "😀")
        val renderer = EmojiPickerRenderer(app, catalog, images, EmojiPack.BLOBMOJI, mapOf("grinning" to unicode))
        val adapter = WidgetEmojiAdapter(object : OnEmojiSelectedListener {
            override fun onEmojiSelected(emoji: Emoji) = Unit
        }).apply {
            setItems(listOf(
                WidgetEmojiAdapter.Item.EmojiItem(null, unicode, "grinning", false),
                WidgetEmojiAdapter.Item.EmojiItem("Guild", ModelEmojiCustom(), "grinning", false),
            ))
        }
        val grid = TestGrid(activity).apply { setAdapter(adapter) }
        val standard = testImage(activity).apply { contentDescription = "grinning" }
        val custom = testImage(activity).apply { contentDescription = "grinning, Guild" }
        val standardOriginal = standard.drawable
        val customOriginal = custom.drawable
        activity.setContentView(grid)
        grid.layout(0, 0, 208, 104)
        grid.addView(standard, RecyclerView.LayoutParams(52, 52))
        grid.addView(custom, RecyclerView.LayoutParams(52, 52))

        try {
            renderer.start(activity)
            awaitImage(grid, standard)
            assertTrue(renderer.diagnostics(), standard.drawable is BitmapDrawable)
            assertTrue(images.diagnostics(), images.diagnostics().contains("Pack images decoded offline: 1"))
            assertSame(customOriginal, custom.drawable)

            images.select(EmojiPack.DEFAULT)
            renderer.select(EmojiPack.DEFAULT)
            assertSame(standardOriginal, standard.drawable)

            grid.layoutInProgress = true
            images.select(EmojiPack.APPLE)
            renderer.select(EmojiPack.APPLE)
            awaitImage(grid, standard)
            assertTrue(renderer.diagnostics(), standard.drawable is BitmapDrawable)
            assertSame(customOriginal, custom.drawable)
        } finally {
            renderer.close()
            images.close()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `all packs contain the previously missed faces and artwork from other categories`() {
        val assets = RuntimeEnvironment.getApplication().assets
        val catalog = EmojiPackCatalog(assets)
        for (pack in EmojiPack.entries.filter { it != EmojiPack.DEFAULT }) {
            for (name in listOf("joy", "rolling_eyes", "weary", "pizza", "rocket")) {
                val artwork = requireNotNull(catalog.artwork(pack, name, emptyList())) { "${pack.id}: $name" }
                val bitmap = catalog.open(artwork).use { asset ->
                    asset.createInputStream().use { BitmapFactory.decodeStream(it) }
                }
                assertNotNull("${pack.id}: $name", bitmap)
            }
        }
    }

    private fun testImage(context: Context) = ImageView(context).apply {
        setImageDrawable(ColorDrawable(Color.YELLOW))
        layout(0, 0, 52, 52)
    }

    private fun awaitImage(grid: RecyclerView, view: ImageView) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (view.drawable !is BitmapDrawable && System.nanoTime() < deadline) {
            grid.viewTreeObserver.dispatchOnPreDraw()
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    // The stock adapter supplies the real emoji items. The fixture supplies cells in place of the
    // host application's compiled layouts, which are only present in the installed Microcord APK.
    private class TestGrid(context: Context) : RecyclerView(context) {
        var layoutInProgress = false
        override fun getChildAdapterPosition(child: View) = if (layoutInProgress) NO_POSITION else indexOfChild(child)
        override fun getChildLayoutPosition(child: View) = NO_POSITION
        override fun checkLayoutParams(params: ViewGroup.LayoutParams) = params is RecyclerView.LayoutParams
    }
}
