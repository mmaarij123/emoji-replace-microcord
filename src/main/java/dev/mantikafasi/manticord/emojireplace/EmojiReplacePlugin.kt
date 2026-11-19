package dev.mantikafasi.manticord.emojireplace

import android.app.Application
import android.app.Service
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.content.res.AssetManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Spannable
import android.widget.TextView
import com.discord.models.domain.emoji.ModelEmojiUnicode
import com.discord.stores.StoreStream
import com.discord.utilities.textprocessing.spans.SpoilerSpan
import com.facebook.widget.text.span.BetterImageSpan
import dev.mantikafasi.manticord.plugins.api.ManticordPlugin
import dev.mantikafasi.manticord.plugins.api.PluginSettings
import dev.mantikafasi.manticord.plugins.api.PluginScope
import dev.mantikafasi.manticord.plugins.api.pluginMessageDecorator
import dev.mantikafasi.manticord.plugins.api.pluginSettings
import java.util.WeakHashMap
import rx.Completable

class EmojiReplacePlugin : ManticordPlugin {
    private var cleanup: (() -> Unit)? = null
    @Volatile private var showDiagnostics: (() -> Unit)? = null

    override val settingsSchema = pluginSettings {
        choice(
            PACK_SETTING,
            "Emoji pack",
            default = EmojiPack.APPLE.id,
            options = EmojiPack.entries.map { it.id to it.label },
        )
        action("check_rendering", "Check emoji replacement") { showDiagnostics?.invoke() }
    }

    override fun start(scope: PluginScope): Completable = Completable.fromAction {
        val assets = requireNotNull(scope.resources.context).assets
        val catalog = EmojiPackCatalog(assets)
        val aliases = StoreStream.getEmojis().unicodeEmojisNamesMap
        val initialPack = scope.settings.selectedPack()
        val images = EmojiImageLoader(initialPack, catalog)
        val renderer = ChatEmojiRenderer(catalog, aliases, images, initialPack)
        val application = scope.applicationContext as Application
        val picker = EmojiPickerRenderer(application, catalog, images, initialPack, aliases)
        val resources = EmojiResources.create(application, catalog, aliases, scope.storage.cacheDir) {
            scope.ui.refreshMessages()
            picker.refresh()
        }
        fun applySelection() {
            val pack = scope.settings.selectedPack()
            images.select(pack)
            renderer.select(pack)
            picker.select(pack)
            resources?.select(pack)
        }
        val registration = scope.ui.registerMessageDecorator(pluginMessageDecorator("emoji_pack", decorate = { views, _ ->
            renderer.bind(views.content)
        }))
        val settings = scope.settings.observeChanges().filter { it == PACK_SETTING }.subscribe {
            scope.runOnMainThread { applySelection() }
        }
        scope.runOnMainThread { context ->
            resources?.start(context)
            picker.start(context)
            applySelection()
            scope.ui.refreshMessages()
        }
        showDiagnostics = {
            scope.dialogs.alert(
                "Emoji Replace",
                listOfNotNull(
                    "Pack: ${scope.settings.selectedPack().label}",
                    picker.diagnostics(),
                    renderer.diagnostics(),
                    resources?.diagnostics(),
                    images.diagnostics(),
                ).joinToString("\n"),
            )
        }
        cleanup = {
            showDiagnostics = null
            settings.unsubscribe()
            registration.unregister()
            images.close()
            Handler(Looper.getMainLooper()).post {
                renderer.close()
                picker.close()
                resources?.close()
            }
        }
    }

    override fun stop(): Completable = Completable.fromAction {
        cleanup?.invoke()
        cleanup = null
    }

    private fun PluginSettings.selectedPack() = EmojiPack.fromId(getString(PACK_SETTING, EmojiPack.APPLE.id))

    private companion object {
        const val PACK_SETTING = "emoji_pack"
    }
}

private class ChatEmojiRenderer(
    private val catalog: EmojiPackCatalog,
    private val aliases: Map<String, ModelEmojiUnicode>,
    private val images: EmojiImageLoader,
    initialPack: EmojiPack,
) {
    private val boundViews = WeakHashMap<TextView, Unit>()
    private var pack = initialPack
    private var chatRows = 0
    private var chatSpans = 0

    fun diagnostics(): String = "Chat rows processed: $chatRows\nChat emoji matches: $chatSpans"

    fun bind(view: TextView) {
        chatRows++
        boundViews[view] = Unit
        replaceImages(view)
    }

    fun select(newPack: EmojiPack) {
        if (pack == newPack) return
        pack = newPack
        boundViews.keys.toList().forEach(::replaceImages)
    }

    fun close() {
        select(EmojiPack.DEFAULT)
        boundViews.clear()
    }

    private fun replaceImages(view: TextView) {
        val text = view.text as? Spannable ?: return
        for (span in text.getSpans(0, text.length, BetterImageSpan::class.java)) {
            // The host uses plain BetterImageSpan for bundled Unicode art and a Vito subclass
            // for remote custom emojis. In-place span edits keep the host's text and links intact.
            val original = when {
                span is PackEmojiSpan && span.pack == pack -> continue
                span is PackEmojiSpan -> span.original
                span.javaClass == BetterImageSpan::class.java -> span
                else -> continue
            }
            val start = text.getSpanStart(span)
            val end = text.getSpanEnd(span)
            if (start < 0 || end <= start) continue
            val replacement = replacement(view, text, start, end, original) ?: original
            if (replacement === span) continue
            val flags = text.getSpanFlags(span)
            text.removeSpan(span)
            text.setSpan(replacement, start, end, flags)
        }
    }

    private fun replacement(
        view: TextView,
        text: Spannable,
        start: Int,
        end: Int,
        original: BetterImageSpan,
    ): PackEmojiSpan? {
        if (pack == EmojiPack.DEFAULT || text.getSpans(start, end, SpoilerSpan::class.java).any { !it.isRevealed }) {
            return null
        }
        val name = text.subSequence(start, end).toString().removeSurrounding(":")
        val artwork = catalog.artwork(pack, name, aliases[name]?.names.orEmpty()) ?: return null
        chatSpans++
        val drawable = PackEmojiDrawable(original.drawable, view)
        if (!images.load(pack, artwork, drawable)) return null
        return PackEmojiSpan(pack, original, drawable)
    }
}

private class PackEmojiSpan(
    val pack: EmojiPack,
    val original: BetterImageSpan,
    drawable: PackEmojiDrawable,
) : BetterImageSpan(drawable, original.verticalAlignment, original.margin)

class EmojiReplaceDiscoveryService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}

internal enum class EmojiPack(val id: String, val label: String = id) {
    DEFAULT("default", "Default (Discord)"),
    APPLE("Apple"),
    BLOBMOJI("BlobMoji"),
    FACEBOOK("Facebook"),
    GOOGLE("Google"),
    HUAWEI("Huawei"),
    JOYPIXELS("JoyPixels"),
    MICROSOFT("Microsoft"),
    MICROSOFT_3D("Microsoft-3D"),
    OPENMOJI("OpenMoji"),
    SAMSUNG("Samsung"),
    SAMSUNG_OLD("Samsung-Old"),
    TOSS("Toss"),
    WHATSAPP("WhatsApp"),
    ;

    companion object {
        private val byId = entries.associateBy { it.id }

        fun fromId(id: String): EmojiPack = byId[id] ?: APPLE
    }
}

internal data class EmojiArtwork(val offset: Long, val length: Long)

internal class EmojiPackCatalog(private val assets: AssetManager) {
    private val indexes = EmojiPack.entries.filter { it != EmojiPack.DEFAULT }.associateWith { pack ->
        assets.open("emoji-index/${pack.id}.tsv").bufferedReader().useLines { lines ->
            lines.associate { line ->
                val (name, offset, length) = line.split('\t', limit = 3)
                name to EmojiArtwork(offset.toLong(), length.toLong())
            }
        }
    }

    fun artwork(pack: EmojiPack, name: String, aliases: List<String>): EmojiArtwork? {
        val index = indexes[pack] ?: return null
        return index[name] ?: aliases.firstNotNullOfOrNull(index::get)
    }

    fun open(artwork: EmojiArtwork): AssetFileDescriptor {
        val source = assets.openFd("emoji-art.emojipack")
        if (artwork.offset < 0 || artwork.length <= 0 || artwork.offset > source.length - artwork.length) {
            source.close()
            throw java.io.IOException("Invalid emoji artwork range")
        }
        // Transfer the open descriptor to the slice so Android can decode this asset in place.
        return AssetFileDescriptor(source.parcelFileDescriptor, source.startOffset + artwork.offset, artwork.length)
    }
}
