package com.alzahra.taqweem

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import java.io.File

/** Shares part of a screen as a picture (the day card), with the app's name underneath. */
object ShareImage {
    /**
     * Draws [views] (children of the same parent, top to bottom) into one image and opens the share sheet.
     * @param background the card's background colour.
     */
    fun share(activity: Activity, views: List<View>, background: Int, caption: String, chooserTitle: String) {
        runCatching {
            val shown = views.filter { it.visibility == View.VISIBLE && it.width > 0 }
            if (shown.isEmpty()) return
            val density = activity.resources.displayMetrics.density
            val pad = (16 * density).toInt()
            val footer = (44 * density).toInt()
            val width = shown.maxOf { it.width } + pad * 2
            val top = shown.minOf { it.top }
            val bottom = shown.maxOf { it.bottom }
            val bmp = Bitmap.createBitmap(width, bottom - top + pad * 2 + footer, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(background)
            shown.forEach { v ->
                c.save(); c.translate((pad + v.left - (v.parent as ViewGroup).paddingLeft).toFloat(), (pad + v.top - top).toFloat()); v.draw(c); c.restore()
            }
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Colors.MUTED; textSize = 14 * density; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
            }
            c.drawText(caption, width / 2f, (bmp.height - footer / 2 + 5 * density), p)

            val dir = File(activity.cacheDir, ShareProvider.DIR).apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "taqweem_${System.currentTimeMillis()}.png")
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            val uri = Uri.parse("content://${activity.packageName}.share/${file.name}")
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure { Toast.makeText(activity, "تعذر إنشاء الصورة", Toast.LENGTH_SHORT).show() }
    }
}

/** Hands the shared picture (in the app's cache) to the app the user picked. Read-only, nothing else exposed. */
class ShareProvider : ContentProvider() {
    companion object { const val DIR = "share" }

    private fun fileFor(uri: Uri): File? {
        val name = uri.lastPathSegment ?: return null
        if (name.contains('/') || name.contains("..")) return null
        val f = File(File(context!!.cacheDir, DIR), name)
        return if (f.isFile) f else null
    }

    override fun onCreate() = true
    override fun getType(uri: Uri) = "image/png"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = fileFor(uri) ?: throw java.io.FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val f = fileFor(uri) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols, 1).apply {
            addRow(cols.map { if (it == OpenableColumns.SIZE) f.length() else if (it == OpenableColumns.DISPLAY_NAME) f.name else null })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
