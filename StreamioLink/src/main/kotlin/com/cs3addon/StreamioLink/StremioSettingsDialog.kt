package com.cs3addon.StreamioLink

import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.lagradost.api.Log
import com.lagradost.cloudstream3.CommonActivity.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

/**
 * Programmatic settings UI (no XML resources needed).
 * One or more catalog addons (the first is primary) + a list of source addons.
 */
object StremioSettingsDialog {

    private const val TAG = "StreamioLink"

    fun show(context: Context) {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }

        container.addView(label(context, "Catalog addons (first = primary home, search & metadata)"))
        val catalogsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val catalogEntries = StremioSettings.catalogEntries().toMutableList()

        fun rerenderCatalogs() {
            catalogsContainer.removeAllViews()
            catalogEntries.toList().forEachIndexed { index, entry ->
                val block = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(4), 0, dp(4))
                }
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                row.addView(
                    TextView(context).apply {
                        text = if (index == 0) "${entry.url}\n(primary)" else entry.url
                        textSize = 14f
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.MIDDLE
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                )
                row.addView(
                    Button(context).apply {
                        text = "Remove"
                        isEnabled = catalogEntries.size > 1
                        setOnClickListener {
                            catalogEntries.removeAll { it.url == entry.url }
                            rerenderCatalogs()
                        }
                    }
                )
                block.addView(row)
                block.addView(
                    CheckBox(context).apply {
                        text = "Also use as a stream source"
                        isChecked = entry.useAsSource
                        isFocusable = true
                        setOnCheckedChangeListener { _, checked ->
                            val current = catalogEntries.indexOfFirst { it.url == entry.url }
                            if (current >= 0) {
                                catalogEntries[current] = catalogEntries[current].copy(useAsSource = checked)
                            }
                        }
                    }
                )
                catalogsContainer.addView(block)
            }
        }
        rerenderCatalogs()
        container.addView(catalogsContainer)

        container.addView(
            Button(context).apply {
                text = "+ Add catalog addon"
                setOnClickListener {
                    showAddSourceDialog(context, title = "Add catalog addon") { url ->
                        if (catalogEntries.none { it.url == url }) {
                            catalogEntries.add(StremioSettings.CatalogEntry(url, useAsSource = true))
                            rerenderCatalogs()
                        } else {
                            showToast("Addon is already configured")
                        }
                    }
                }
            }
        )

        container.addView(
            TextView(context).apply {
                text = "Extra catalog addons append their rows after the primary addon's rows."
                textSize = 12f
                setPadding(0, dp(4), 0, dp(4))
            }
        )

        container.addView(label(context, "Metadata fallback addon (used when the catalog addon has no meta)"))
        val metaInput = editText(context, StremioSettings.metaUrl())
        container.addView(metaInput)

        container.addView(label(context, "Source addons (streams & subtitles)"))
        val sourcesContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val sourceUrls = StremioSettings.sourceUrls().toMutableList()

        fun rerenderSources() {
            sourcesContainer.removeAllViews()
            sourceUrls.forEach { url ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                row.addView(
                    TextView(context).apply {
                        text = url
                        textSize = 14f
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.MIDDLE
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                )
                row.addView(
                    Button(context).apply {
                        text = "Remove"
                        setOnClickListener {
                            sourceUrls.remove(url)
                            rerenderSources()
                        }
                    }
                )
                sourcesContainer.addView(row)
            }
            if (sourceUrls.isEmpty()) {
                sourcesContainer.addView(
                    TextView(context).apply {
                        text = "No source addons yet."
                        textSize = 13f
                    }
                )
            }
        }
        rerenderSources()
        container.addView(sourcesContainer)

        container.addView(
            Button(context).apply {
                text = "+ Add source addon"
                setOnClickListener {
                    showAddSourceDialog(context) { url ->
                        if (!sourceUrls.contains(url)) {
                            sourceUrls.add(url)
                            rerenderSources()
                        }
                    }
                }
            }
        )

        container.addView(
            TextView(context).apply {
                text = "Paste the base URL or a manifest.json link of any Stremio addon. " +
                    "Debrid-configured links (e.g. Torrentio with a Real-Debrid key in the URL) " +
                    "return playable streams; plain torrent addons return magnets which Cloudstream cannot play."
                textSize = 12f
                setPadding(0, dp(8), 0, dp(8))
            }
        )

        val scrollView = ScrollView(context).apply { addView(container) }

        val dialog = AlertDialog.Builder(context)
            .setTitle("StreamioLink settings")
            .setView(scrollView)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val catalog = catalogEntries.filter { StremioMapping.normalizeAddonUrl(it.url) != null }
                if (catalog.isEmpty()) {
                    showToast("Add at least one catalog addon")
                    return@setOnClickListener
                }
                val meta = metaInput.text.toString()
                StremioSettings.save(
                    catalogs = catalog,
                    metaUrl = meta,
                    sourceUrls = sourceUrls,
                )
                dialog.dismiss()
                showToast("StreamioLink settings saved")
                warmUpAddons()
            }
        }

        dialog.show()
    }

    private fun showAddSourceDialog(
        context: Context,
        title: String = "Add source addon",
        onAdded: (String) -> Unit,
    ) {
        val input = EditText(context).apply {
            hint = "https://addon.example/manifest.json"
            val clipboard = (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                ?.primaryClip?.getItemAt(0)?.text?.toString()
            if (!clipboard.isNullOrBlank() && clipboard.startsWith("http", ignoreCase = true)) {
                setText(clipboard)
            }
        }

        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val normalized = StremioMapping.normalizeAddonUrl(input.text.toString())
                if (normalized == null) {
                    showToast("Not a valid addon URL")
                } else {
                    onAdded(normalized)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Best effort pre-fetch of all manifests so the first requests are instant. */
    private fun warmUpAddons() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val bases = (StremioSettings.streamAddonUrls() + StremioSettings.catalogUrls() + StremioSettings.metaUrl())
                    .mapNotNull { StremioMapping.normalizeAddonUrl(it) }
                    .distinct()
                val names = bases.map { base ->
                    async {
                        base to StremioApi.getManifest(base)?.name
                    }
                }.awaitAll().mapNotNull { (_, name) -> name?.takeIf { it.isNotBlank() } }

                if (names.isNotEmpty()) {
                    Handler(Looper.getMainLooper()).post {
                        showToast("StreamioLink loaded: ${names.joinToString(", ")}")
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Manifest warm up failed: ${t.message}")
            }
        }
    }

    private fun label(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 14f
        setPadding(0, (12 * context.resources.displayMetrics.density + 0.5f).toInt(), 0, 0)
    }

    private fun editText(context: Context, value: String): EditText = EditText(context).apply {
        setText(value)
        textSize = 14f
        isSingleLine = true
        isFocusable = true
        isFocusableInTouchMode = true
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }
}
