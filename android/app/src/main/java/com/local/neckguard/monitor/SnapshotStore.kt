package com.local.neckguard.monitor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import com.local.neckguard.pose.PostureMeasurement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 快照落盘：在原图上画耳肩髋连线与角度，存 JPEG，只保留最近 MAX_FILES 张。 */
class SnapshotStore(context: Context) {

    private val dir = File(context.filesDir, "snapshots").apply { mkdirs() }

    fun encodeJpeg(bitmap: Bitmap, quality: Int = 80): ByteArray {
        val out = ByteArrayOutputStream(64 * 1024)
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    /**
     * @param jpeg 分析帧的 JPEG 字节（与 measurement 的像素坐标同尺寸）
     * @return 落盘文件，失败返回 null
     */
    suspend fun save(
        jpeg: ByteArray,
        measurement: PostureMeasurement?,
        neckDeg: Float?,
        thresholdDeg: Float?,
        timestampMillis: Long,
    ): File? = withContext(Dispatchers.IO) {
        try {
            val base = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return@withContext null
            val annotated = drawOverlay(base, measurement, neckDeg, thresholdDeg)
            val name = "snap_" + FILE_TS.format(Date(timestampMillis)) + ".jpg"
            val file = File(dir, name)
            file.outputStream().use { annotated.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            if (annotated !== base) annotated.recycle()
            base.recycle()
            prune()
            file
        } catch (e: Exception) {
            Log.e(TAG, "save snapshot failed", e)
            null
        }
    }

    fun drawOverlay(src: Bitmap, m: PostureMeasurement?, neckDeg: Float?, thresholdDeg: Float?): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true) ?: return src
        val canvas = Canvas(out)
        val stroke = (out.width / 120f).coerceAtLeast(3f)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 200, 0)
            strokeWidth = stroke
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 220, 255)
            style = Paint.Style.FILL
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = (out.width / 18f).coerceAtLeast(24f)
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }
        if (m != null) {
            m.hip?.let { canvas.drawLine(it.x, it.y, m.shoulder.x, m.shoulder.y, line) }
            canvas.drawLine(m.shoulder.x, m.shoulder.y, m.ear.x, m.ear.y, line)
            // 竖直参考线
            val ref = Paint(line).apply { color = Color.argb(160, 255, 255, 255); strokeWidth = stroke / 2 }
            canvas.drawLine(m.shoulder.x, m.shoulder.y, m.shoulder.x, m.ear.y - stroke * 4, ref)
            canvas.drawCircle(m.ear.x, m.ear.y, stroke * 2, dot)
            canvas.drawCircle(m.shoulder.x, m.shoulder.y, stroke * 2, dot)
            m.hip?.let { canvas.drawCircle(it.x, it.y, stroke * 2, dot) }
        }
        val label = buildString {
            append("颈部 ")
            append(neckDeg?.let { String.format(Locale.US, "%.1f", it) } ?: "--")
            append("°")
            thresholdDeg?.let { append("  阈值 ").append(String.format(Locale.US, "%.0f", it)).append("°") }
        }
        canvas.drawText(label, text.textSize * 0.5f, text.textSize * 1.4f, text)
        canvas.drawText(DISPLAY_TS.format(Date()), text.textSize * 0.5f, text.textSize * 2.8f, text)
        return out
    }

    fun listFiles(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") }?.sortedByDescending { it.name } ?: emptyList()

    private fun prune() {
        val files = listFiles()
        if (files.size <= MAX_FILES) return
        files.drop(MAX_FILES).forEach { runCatching { it.delete() } }
    }

    companion object {
        private const val TAG = "SnapshotStore"
        private const val MAX_FILES = 50
        private val FILE_TS = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
        private val DISPLAY_TS = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    }
}
