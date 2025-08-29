package com.example.whiteboard

import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import com.myscript.iink.Editor
import com.myscript.iink.PointerEvent
import com.myscript.iink.PointerEventType
import com.myscript.iink.PointerType
import com.myscript.iink.MimeType
import android.util.Log
import com.myscript.iink.uireferenceimplementation.*

data class Stroke(
    val path: Path,
    val paintToCopy: Paint
) {
    val paint: Paint = Paint(paintToCopy).apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
}


data class StrokeData(
    val points: List<Pair<Float, Float>>,
    val color: Int,
    val strokeWidth: Float
)

fun Stroke.toStrokeData(): StrokeData {
    val points = mutableListOf<Pair<Float, Float>>()
    val pm = PathMeasure(this.path, false)
    val pos = FloatArray(2)
    var distance = 0f
    val step = 5f
    while (distance < pm.length) {
        pm.getPosTan(distance, pos, null)
        points.add(pos[0] to pos[1])
        distance += step
    }
    return StrokeData(points, paint.color, paint.strokeWidth)
}

fun StrokeData.toStroke(): Stroke {
    val path = Path()
    if (points.isNotEmpty()) {
        val first = points.first()
        path.moveTo(first.first, first.second)
        for (p in points.drop(1)) {
            path.lineTo(p.first, p.second)
        }
    }
    val paint = Paint().apply {
        color = this@toStroke.color
        strokeWidth = this@toStroke.strokeWidth
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }
    return Stroke(path, paint)
}

/**
 * Recognize math from a list of strokes and return LaTeX result.
 * Works with selected strokes, suitable for a selection tool workflow.
 */
fun recognizeSelectedStrokes(editor: Editor, strokes: List<Stroke>, editorView: EditorView? = null): String? {
    Log.d("Mathmode", "Starting recognition with ${strokes.size} strokes")
    if (strokes.isEmpty()) {
        Log.d("Mathmode", "Stroke list empty")
        return null
    }

    if (editor.part == null) {
        Log.e("Mathmode", "No part set on editor -> abort")
        return null
    }

    // If you want to clear prior content, do it and wait for engine to settle
    // Comment out if you want to keep previous content
    editor.clear()
    if (!editor.isIdle) editor.waitForIdle()

    // OPTIONAL: get view on-screen location only if you suspect coordinate mismatch
    val viewOnScreen = IntArray(2)
    editorView?.getLocationOnScreen(viewOnScreen)

    var pointerId = 0
    for (stroke in strokes) {
        val pm = PathMeasure(stroke.path, false)
        if (pm.length <= 0f) {
            Log.d("Mathmode", "Skipping empty path for pointer $pointerId")
            pointerId++
            continue
        }

        val pos = FloatArray(2)
        var distance = 0f
        val step = 2f
        var timestamp = System.currentTimeMillis()
        val timeStep = 10L

        // DOWN
        pm.getPosTan(0f, pos, null)
        val downX = pos[0] - (viewOnScreen.getOrNull(0) ?: 0) // convert if your path is screen coords
        val downY = pos[1] - (viewOnScreen.getOrNull(1) ?: 0)
        Log.v("Mathmode", "pointer $pointerId DOWN at raw(${pos[0]},${pos[1]}) local($downX,$downY)")
        val downEvent = PointerEvent(PointerEventType.DOWN, downX, downY, timestamp, 0.5f, 0f, 0f, PointerType.PEN, pointerId)
        editor.pointerEvents(arrayOf(downEvent), true)

        distance += step
        timestamp += timeStep

        // MOVE
        while (distance < pm.length) {
            pm.getPosTan(distance, pos, null)
            val mx = pos[0] - (viewOnScreen.getOrNull(0) ?: 0)
            val my = pos[1] - (viewOnScreen.getOrNull(1) ?: 0)
            val moveEvent = PointerEvent(PointerEventType.MOVE, mx, my, timestamp, 0.5f, 0f, 0f, PointerType.PEN, pointerId)
            editor.pointerEvents(arrayOf(moveEvent), true)
            distance += step
            timestamp += timeStep
        }

        // UP
        pm.getPosTan(pm.length, pos, null)
        val upX = pos[0] - (viewOnScreen.getOrNull(0) ?: 0)
        val upY = pos[1] - (viewOnScreen.getOrNull(1) ?: 0)
        val upEvent = PointerEvent(PointerEventType.UP, upX, upY, timestamp, 0.5f, 0f, 0f, PointerType.PEN, pointerId)
        editor.pointerEvents(arrayOf(upEvent), true)

        pointerId++
    }

    // Wait for the engine to process the injected pointer events
    if (!editor.isIdle) editor.waitForIdle()
    Log.d("Mathmode", "After injection: isIdle=${editor.isIdle}, isEmpty=${editor.isEmpty(editor.rootBlock)}")

    // Now query conversion states on the root block
    var states = editor.getSupportedTargetConversionStates(editor.rootBlock)
    Log.d("Mathmode","supported BEFORE conversion: ${states.joinToString()}")

    if (states.isNotEmpty()) {
        // Use rootBlock explicitly for convert
        editor.convert(editor.rootBlock, states[0])
        if (!editor.isIdle) editor.waitForIdle()
        Log.d("Mathmode", "Conversion finished")
        val jiix = editor.export_(editor.rootBlock, MimeType.JIIX)
        Log.d("MathDbg", "JIIX length=${jiix?.length ?: 0}")
        Log.d("MathDbg", "JIIX preview:\n" + jiix?.take(200) ?: "null")
    } else {
        Log.d("Mathmode", "No conversion states available")
    }

    states = editor.getSupportedTargetConversionStates(editor.rootBlock)
    Log.d("Mathmode","supported AFTER first conversion: ${states.joinToString()}")

    val publishState = states.find { it.equals("DIGITAL_PUBLISH") }
    if (publishState != null) {
        editor.convert(editor.rootBlock, publishState)
        if (!editor.isIdle) editor.waitForIdle()
        Log.d("Mathmode","Converted to publish state: $publishState")
    } else {
        Log.d("Mathmode","No DIGITAL_PUBLISH found; states after first convert: ${states.joinToString()}")
    }

    val supportedExports = editor.getSupportedExportMimeTypes(editor.rootBlock)
    Log.d("Mathmode","Supported export mime types now: ${supportedExports?.joinToString()}")

    // Export explicitly from rootBlock
    val latex = editor.export_(editor.rootBlock, MimeType.LATEX)
    Log.d("Mathmode", "Exported LaTeX: $latex")
    return latex
}
