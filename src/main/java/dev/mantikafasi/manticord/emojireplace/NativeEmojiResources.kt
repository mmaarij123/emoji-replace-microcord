package dev.mantikafasi.manticord.emojireplace

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.content.res.Resources
import android.content.res.loader.AssetsProvider
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import com.discord.models.domain.emoji.ModelEmojiUnicode
import com.discord.utilities.theme.ArscWriter
import java.io.File
import java.io.IOException
import java.util.WeakHashMap
import java.util.concurrent.Executors

internal interface EmojiResources {
    fun start(context: Context)
    fun select(pack: EmojiPack)
    fun diagnostics(): String
    fun close()

    companion object {
        fun create(
            application: Application,
            catalog: EmojiPackCatalog,
            emojis: Map<String, ModelEmojiUnicode>,
            cache: File,
            onApplied: () -> Unit,
        ): EmojiResources? = if (Build.VERSION.SDK_INT >= 30) {
            NativeEmojiResources(application, catalog, emojis, cache, onApplied)
        } else null
    }
}

/** Uses the same Android resource-table mechanism as Microcord's built-in theme engine. */
@RequiresApi(30)
private class NativeEmojiResources(
    private val application: Application,
    private val catalog: EmojiPackCatalog,
    private val emojis: Map<String, ModelEmojiUnicode>,
    private val cache: File,
    private val onApplied: () -> Unit,
) : EmojiResources, Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "Emoji Replace resource table").apply { isDaemon = true } }
    private val loader = ResourcesLoader()
    private val attached = WeakHashMap<Resources, Unit>()
    private var provider: ResourcesProvider? = null
    private var selection: EmojiPack? = null
    private var active = true
    private var count = 0
    private var error: String? = null

    override fun start(context: Context) {
        application.registerActivityLifecycleCallbacks(this)
        attach(application.resources)
        attach(context.resources)
    }

    override fun select(pack: EmojiPack) {
        if (!active || pack == selection) return
        selection = pack
        count = 0
        error = null
        if (pack == EmojiPack.DEFAULT) {
            install(null)
            return
        }
        worker.execute {
            val result = runCatching { prepare(pack) }
            main.post {
                val next = result.getOrNull()
                if (!active || pack != selection) {
                    next?.provider?.close()
                } else {
                    error = result.exceptionOrNull()?.message
                    install(next)
                }
            }
        }
    }

    @Suppress("DiscouragedApi")
    private fun prepare(pack: EmojiPack): Prepared? {
        val resources = application.resources
        val names = application.packageName
        val paths = mutableMapOf<String, EmojiArtwork>()
        val entries = emojis.values.distinctBy(ModelEmojiUnicode::codePoints).mapNotNull { emoji ->
            val artwork = catalog.artwork(pack, emoji.firstName, emoji.names) ?: return@mapNotNull null
            val key = "emoji_${emoji.codePoints}"
            val id = resources.getIdentifier(key, "raw", names)
            if (id == 0) return@mapNotNull null
            val path = "res/emoji_replace/$key.webp"
            paths[path] = artwork
            ArscWriter.Entry(id, "raw", key, ArscWriter.Value.File(path))
        }.distinctBy { it.id }
        if (entries.isEmpty()) return null

        val table = File.createTempFile("emoji-", ".arsc", cache)
        try {
            table.writeBytes(ArscWriter.write(names, entries))
            val next = ParcelFileDescriptor.open(table, ParcelFileDescriptor.MODE_READ_ONLY).use {
                ResourcesProvider.loadFromTable(it, object : AssetsProvider {
                    override fun loadAssetFd(path: String, accessMode: Int): AssetFileDescriptor? {
                        val artwork = paths[path] ?: return null
                        return try { catalog.open(artwork) } catch (_: IOException) { null }
                    }
                })
            }
            return Prepared(next, entries.size)
        } finally {
            table.delete()
        }
    }

    private fun install(next: Prepared?) {
        val previous = provider
        loader.setProviders(listOfNotNull(next?.provider))
        provider = next?.provider
        previous?.close()
        count = next?.count ?: 0
        onApplied()
    }

    private fun attach(resources: Resources) {
        if (attached.containsKey(resources)) return
        runCatching { resources.addLoaders(loader) }
            .onSuccess { attached[resources] = Unit }
            .onFailure { error = it.message }
    }

    override fun diagnostics(): String = "Native emoji resources: $count" +
        (error?.let { "\nNative resource error: $it" } ?: "")

    override fun close() {
        active = false
        worker.shutdownNow()
        application.unregisterActivityLifecycleCallbacks(this)
        loader.clearProviders()
        provider?.close()
        provider = null
        attached.keys.toList().forEach { it.removeLoaders(loader) }
        attached.clear()
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) = attach(activity.resources)
    override fun onActivityStarted(activity: Activity) = attach(activity.resources)
    override fun onActivityResumed(activity: Activity) = attach(activity.resources)
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    private class Prepared(val provider: ResourcesProvider, val count: Int)
}
