package com.example.androidhostapp

import android.app.AlertDialog
import android.content.Context
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView

internal object LicenseDialog {
    private data class Entry(val title: String, val rawId: Int)

    fun show(context: Context) {
        val entries = listOf(
            Entry("gnirehtet — Apache License 2.0", R.raw.license_gnirehtet_apache2),
            Entry("flashbot — BSD 3-Clause License", R.raw.license_flashbot_bsd3),
            Entry("AdbLib — BSD 3-Clause License", R.raw.license_adblib_bsd3)
        )
        AlertDialog.Builder(context)
            .setTitle("オープンソースライセンス")
            .setItems(entries.map { it.title }.toTypedArray()) { _, which ->
                showText(context, entries[which].title, context.resources.openRawResource(entries[which].rawId).bufferedReader().use { it.readText() })
            }
            .setNegativeButton("閉じる", null)
            .show()
    }

    private fun showText(context: Context, title: String, text: String) {
        val scrollView = ScrollView(context)
        val textView = TextView(context).apply {
            setTextIsSelectable(true)
            setPadding(24, 16, 24, 16)
            setTextSize(12f)
            setText(text)
            ellipsize = null
            maxLines = Int.MAX_VALUE
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        scrollView.addView(textView)
        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(scrollView)
            .setPositiveButton("閉じる", null)
            .show()
    }
}
