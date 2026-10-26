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
 * One or more catalog addons (the first enabled is primary) + a list of source addons.
 * Every addon row carries an enable toggle, an edit button and a delete button.
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

        container.addView(label(context, "Catalog addons (first enabled = primary home, search & metadata)"))
        val catalogsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val catalogEntries = StremioSettings.catalogEntries().toMutableList()

        fun rerenderCatalogs() {
            catalogsContainer.removeAllViews()
            val enabledCount = catalogEntries.count { it.enabled }
            val firstEnabledIndex = catalogEntries.indexOfFirst { it.enabled }
            catalogEntries.toList().forEachIndexed { index, entry ->
                val block = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(4), 0, dp(4))
                }
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val status = when {
                    !entry.enabled -> "(disabled)"
                    index == firstEnabledIndex -> "(primary)"
                    else -> null
                }
                row.addView(
                    TextView(context).apply {
                        text = status?.let { "${entry.url}\n$it" } ?: entry.url
                        textSize = 14f
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.MIDDLE
                        alpha = if (entry.enabled) 1f else 0.5f
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                )
                row.addView(
                    CheckBox(context).apply {
                        isChecked = entry.enabled
                        contentDescription = "Enabled"
                        // The last enabled catalog must stay on: there is always a primary addon.
                        isEnabled = !entry.enabled || enabledCount > 1
                        isFocusable = true
                        setOnCheckedChangeListener { _, checked ->
                            val current = catalogEntries.indexOfFirst { it.url == entry.url }
                            if (current >= 0) {
                                catalogEntries[current] = catalogEntries[current].copy(enabled = checked)
                                rerenderCatalogs()
                            }
                        }
                    }
                )
                row.addView(
                    iconButton(context, "✏️", "Edit addon") {
                        showAddSourceDialog(
                            context,
                            title = "Edit catalog addon",
                            initialUrl = entry.url,
                        ) { url ->
                            val current = catalogEntries.indexOfFirst { it.url == entry.url }
                            if (current < 0) return@showAddSourceDialog
                            if (url != entry.url && catalogEntries.any { it.url == url }) {
                                showToast("Addon is already configured")
                            } else {
                                catalogEntries[current] = catalogEntries[current].copy(url = url)
                                rerenderCatalogs()
                            }
                        }
                    }
                )
                row.addView(
                    iconButton(context, "🗑️", "Delete addon") {
                        catalogEntries.removeAll { it.url == entry.url }
                        rerenderCatalogs()
                    }.apply { isEnabled = catalogEntries.size > 1 }
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
                text = "Uncheck an addon to disable it without removing it. Extra catalog addons " +
                    "append their rows after the primary addon's rows."
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
        val sourceEntries = StremioSettings.sourceEntries().toMutableList()

        fun rerenderSources() {
            sourcesContainer.removeAllViews()
            sourceEntries.toList().forEach { entry ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                row.addView(
                    TextView(context).apply {
                        text = if (entry.enabled) entry.url else "${entry.url}\n(disabled)"
                        textSize = 14f
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.MIDDLE
                        alpha = if (entry.enabled) 1f else 0.5f
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                )
                row.addView(
                    CheckBox(context).apply {
                        isChecked = entry.enabled
                        contentDescription = "Enabled"
                        isFocusable = true
                        setOnCheckedChangeListener { _, checked ->
                            val current = sourceEntries.indexOfFirst { it.url == entry.url }
                            if (current >= 0) {
                                sourceEntries[current] = sourceEntries[current].copy(enabled = checked)
                                rerenderSources()
                            }
                        }
                    }
                )
                row.addView(
                    iconButton(context, "✏️", "Edit addon") {
                        showAddSourceDialog(
                            context,
                            title = "Edit source addon",
                            initialUrl = entry.url,
                        ) { url ->
                            val current = sourceEntries.indexOfFirst { it.url == entry.url }
                            if (current < 0) return@showAddSourceDialog
                            if (url != entry.url && sourceEntries.any { it.url == url }) {
                                showToast("Addon is already configured")
                            } else {
                                sourceEntries[current] = sourceEntries[current].copy(url = url)
                                rerenderSources()
                            }
                        }
                    }
                )
                row.addView(
                    iconButton(context, "🗑️", "Delete addon") {
                        sourceEntries.removeAll { it.url == entry.url }
                        rerenderSources()
                    }
                )
                sourcesContainer.addView(row)
            }
            if (sourceEntries.isEmpty()) {
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
                        if (sourceEntries.none { it.url == url }) {
                            sourceEntries.add(StremioSettings.SourceEntry(url))
                            rerenderSources()
                        } else {
                            showToast("Addon is already configured")
                        }
                    }
                }
            }
        )

        container.addView(
            TextView(context).apply {
                text = "Paste the base URL or a manifest.json link of any Stremio addon. " +
                    "Debrid-configured links (e.g. Torrentio with a Real-Debrid key in the URL) " +
                    "return playable streams."
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
                val catalog = catalogEntries
                    .mapNotNull { entry ->
                        StremioMapping.normalizeAddonUrl(entry.url)?.let { entry.copy(url = it) }
                    }
                    .distinctBy { it.url }
                if (catalog.isEmpty()) {
                    showToast("Add at least one catalog addon")
                    return@setOnClickListener
                }
                if (catalog.none { it.enabled }) {
                    showToast("Keep at least one catalog addon enabled")
                    return@setOnClickListener
                }
                val meta = metaInput.text.toString()
                StremioSettings.save(
                    catalogs = catalog,
                    metaUrl = meta,
                    sources = sourceEntries,
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
        initialUrl: String? = null,
        onAdded: (String) -> Unit,
    ) {
        val input = EditText(context).apply {
            hint = "https://addon.example/manifest.json"
            if (initialUrl != null) {
                setText(initialUrl)
            } else {
                val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClip
                val clipboard = if (clip != null && clip.itemCount > 0) clip.getItemAt(0)?.text?.toString() else null
                if (!clipboard.isNullOrBlank() && (clipboard.startsWith("http", ignoreCase = true) || clipboard.startsWith("stremio:", ignoreCase = true))) {
                    setText(clipboard)
                }
            }
        }

        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(if (initialUrl == null) "Add" else "Save") { _, _ ->
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

    /** Compact symbol button so a row fits URL + toggle + edit + delete on one line. */
    private fun iconButton(
        context: Context,
        symbol: String,
        description: String,
        onClick: () -> Unit,
    ): Button {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        return Button(context).apply {
            text = symbol
            textSize = 18f
            contentDescription = description
            isFocusable = true
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { onClick() }
        }
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
