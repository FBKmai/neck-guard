package com.local.neckguard.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import com.local.neckguard.pose.FrameResult
import com.local.neckguard.pose.PostureMeasurement

/**
 * 在预览上画耳肩髋连线。预览用 FIT_CENTER，因此按图像宽高比算出居中矩形再映射。
 * 前置摄像头的预览是镜像的，而分析帧不是，所以 mirrored=true 时 x 取反。
 */
@Composable
fun PoseOverlay(
    result: FrameResult?,
    imageWidth: Int,
    imageHeight: Int,
    mirrored: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val m: PostureMeasurement = result?.measurementOrNull ?: return@Canvas
        if (imageWidth <= 0 || imageHeight <= 0) return@Canvas

        val cw = size.width
        val ch = size.height
        val imageAspect = imageWidth.toFloat() / imageHeight
        val fw: Float
        val fh: Float
        if (cw / ch > imageAspect) {
            fh = ch
            fw = ch * imageAspect
        } else {
            fw = cw
            fh = cw / imageAspect
        }
        val ox = (cw - fw) / 2f
        val oy = (ch - fh) / 2f

        fun map(x: Float, y: Float): Offset {
            val nx = x / imageWidth
            val ny = y / imageHeight
            val sx = if (mirrored) 1f - nx else nx
            return Offset(ox + sx * fw, oy + ny * fh)
        }

        val good = result is FrameResult.Valid
        val lineColor = if (good) Color(0xFFFFC800) else Color(0xFFFF5252)
        val stroke = (fw / 120f).coerceAtLeast(4f)
        val ear = map(m.ear.x, m.ear.y)
        val shoulder = map(m.shoulder.x, m.shoulder.y)
        val hip = m.hip?.let { map(it.x, it.y) }

        // 竖直参考线
        drawLine(
            color = Color.White.copy(alpha = 0.6f),
            start = shoulder,
            end = Offset(shoulder.x, ear.y - stroke * 4),
            strokeWidth = stroke / 2,
        )
        hip?.let { drawLine(lineColor, it, shoulder, stroke, StrokeCap.Round) }
        drawLine(lineColor, shoulder, ear, stroke, StrokeCap.Round)
        drawCircle(Color(0xFF00DCFF), radius = stroke * 1.6f, center = ear)
        drawCircle(Color(0xFF00DCFF), radius = stroke * 1.6f, center = shoulder)
        hip?.let { drawCircle(Color(0xFF00DCFF), radius = stroke * 1.6f, center = it) }
    }
}
