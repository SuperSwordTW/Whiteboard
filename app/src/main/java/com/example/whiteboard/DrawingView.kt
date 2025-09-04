// DrawingView.kt
package com.example.whiteboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import android.graphics.RectF
import android.graphics.Matrix
import android.graphics.Region
import android.graphics.PathMeasure
import androidx.core.graphics.toColorInt
import kotlin.math.min
import kotlin.math.max
import kotlin.math.hypot
import android.graphics.DashPathEffect
import android.graphics.Color
import android.graphics.PointF
import android.graphics.*
import android.util.AttributeSet
import kotlin.math.*
import com.myscript.iink.uireferenceimplementation.EditorBinding
import com.myscript.iink.uireferenceimplementation.EditorData
import com.myscript.iink.uireferenceimplementation.EditorView
import com.myscript.iink.uireferenceimplementation.FontUtils
import android.util.Log


class DrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val strokes = mutableListOf<Stroke>()
    private var currentPath: Path? = null

    private val undoStack = mutableListOf<List<Stroke>>()
    private val redoStack = mutableListOf<List<Stroke>>()
    private val MAX_HISTORY = 50
    private val currentPaint = Paint().apply {
        color = 0xFFFFFFFF.toInt()
        isAntiAlias = true
        strokeWidth = 10f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    fun setPenColor(color: Int) {
        // If user has something selected, recolor it too
        if (selectedStrokes.isNotEmpty()) {
            for (stroke in selectedStrokes) {
                stroke.paint.color = color
            }
            invalidate()
        } else {
            currentPaint.color = color
            invalidate()
        }
    }

    // Selection mode flag
    private var isSelecting = false

    private var isMathing = false

    private var isSending = false

    // Selection rectangle
    private var selectionPath: Path? = null

    // Selected strokes
    private val selectedStrokes = mutableListOf<Stroke>()

    private fun copyStrokes(strokes: List<Stroke>): List<Stroke> {
        return strokes.map { stroke ->
            Stroke(Path(stroke.path), Paint(stroke.paint))
        }
    }


    private fun handleShapeTouch(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                shapeStartX = event.x
                shapeStartY = event.y
                previewPath = Path() // for live preview
            }
            MotionEvent.ACTION_MOVE -> {
                // Update preview path
                previewPath = Path()
                when (shapeMode) {
                    ShapeType.RECTANGLE -> {
                        previewPath?.addRect(
                            min(shapeStartX, event.x),
                            min(shapeStartY, event.y),
                            max(shapeStartX, event.x),
                            max(shapeStartY, event.y),
                            Path.Direction.CW
                        )
                    }
                    ShapeType.CIRCLE -> {
                        val radius = hypot((event.x - shapeStartX).toDouble(), (event.y - shapeStartY).toDouble()).toFloat()
                        previewPath?.addCircle(shapeStartX, shapeStartY, radius, Path.Direction.CW)
                    }
                    ShapeType.SPHERE -> {
                        previewPath?.reset()
                        val radius = hypot(
                            (event.x - shapeStartX).toDouble(),
                            (event.y - shapeStartY).toDouble()
                        ).toFloat()

                        previewPath?.addCircle(shapeStartX, shapeStartY, radius, Path.Direction.CW)

                        val r = radius * (1.toFloat() / (2))
                        val rect = RectF(
                            shapeStartX - radius,
                            shapeStartY - r,
                            shapeStartX + radius,
                            shapeStartY + r
                        )
                        previewPath?.addOval(rect, Path.Direction.CW)
                    }
                    ShapeType.TRIANGLE -> {
                        previewPath?.moveTo((shapeStartX + event.x) / 2, shapeStartY)
                        previewPath?.lineTo(shapeStartX, event.y)
                        previewPath?.lineTo(event.x, event.y)
                        previewPath?.close()
                    }
                    ShapeType.CUBE -> {
                        previewPath?.reset()

                        // Front face corners
                        val left = shapeStartX
                        val top = shapeStartY
                        val right = event.x
                        val bottom = event.y

                        // Cube depth for perspective
                        val depth = min(abs(right - left), abs(bottom - top)) / 4f

                        // Define 8 cube points
                        val frontTopLeft = PointF(left, top)
                        val frontTopRight = PointF(right, top)
                        val frontBottomRight = PointF(right, bottom)
                        val frontBottomLeft = PointF(left, bottom)

                        val backTopLeft = PointF(left - depth, top - depth)
                        val backTopRight = PointF(right - depth, top - depth)
                        val backBottomRight = PointF(right - depth, bottom - depth)
                        val backBottomLeft = PointF(left - depth, bottom - depth)

                        previewPath?.apply {
                            // Draw front face
                            moveTo(frontTopLeft.x, frontTopLeft.y)
                            lineTo(frontTopRight.x, frontTopRight.y)
                            lineTo(frontBottomRight.x, frontBottomRight.y)
                            lineTo(frontBottomLeft.x, frontBottomLeft.y)
                            close()

                            // Draw back face
                            moveTo(backTopLeft.x, backTopLeft.y)
                            lineTo(backTopRight.x, backTopRight.y)
                            lineTo(backBottomRight.x, backBottomRight.y)
                            lineTo(backBottomLeft.x, backBottomLeft.y)
                            close()

                            // Connect front and back faces
                            moveTo(frontTopLeft.x, frontTopLeft.y)
                            lineTo(backTopLeft.x, backTopLeft.y)

                            moveTo(frontTopRight.x, frontTopRight.y)
                            lineTo(backTopRight.x, backTopRight.y)

                            moveTo(frontBottomRight.x, frontBottomRight.y)
                            lineTo(backBottomRight.x, backBottomRight.y)

                            moveTo(frontBottomLeft.x, frontBottomLeft.y)
                            lineTo(backBottomLeft.x, backBottomLeft.y)
                        }
                    }
                    ShapeType.AXIS -> {
                        previewPath?.reset()
                        // Origin point
                        val ox = shapeStartX
                        val oy = shapeStartY

                        // Length of each axis
                        val axisLength = min(abs(event.x - ox), abs(event.y - oy))

                        // X axis (red, right-up)
                        previewPath?.apply {
                            moveTo(ox, oy)
                            lineTo(ox + axisLength * 4, oy - axisLength / 18) // slight upward tilt
                        }

                        // Y axis (green, vertical-left)
                        previewPath?.apply {
                            moveTo(ox, oy)
                            lineTo(ox - axisLength / 16, oy - axisLength * 3) // slight left tilt
                        }

                        // Z axis (blue, down-right)
                        previewPath?.apply {
                            moveTo(ox, oy)
                            lineTo(ox - axisLength * 2, oy + axisLength * 2) // down-right
                        }
                    }
                    ShapeType.SEGMENT -> {
                        previewPath?.reset()
                        val ox = shapeStartX
                        val oy = shapeStartY
                        previewPath?.apply {
                            moveTo(ox, oy)
                            lineTo(event.x, event.y)
                        }
                    }
                    ShapeType.RAY -> {
                        previewPath?.reset()

                        val startX = shapeStartX
                        val startY = shapeStartY
                        val endX = event.x
                        val endY = event.y

                        previewPath?.apply {
                            // Draw main line
                            moveTo(startX, startY)
                            lineTo(endX, endY)

                            // Draw arrowhead
                            val arrowLength = 30f  // length of arrowhead
                            val arrowAngle = Math.toRadians(30.0) // angle of arrowhead in degrees

                            // Direction vector
                            val dx = endX - startX
                            val dy = endY - startY
                            val lineAngle = atan2(dy, dx)

                            // Left side of arrow
                            val x1 = endX - arrowLength * cos(lineAngle - arrowAngle).toFloat()
                            val y1 = endY - arrowLength * sin(lineAngle - arrowAngle).toFloat()

                            // Right side of arrow
                            val x2 = endX - arrowLength * cos(lineAngle + arrowAngle).toFloat()
                            val y2 = endY - arrowLength * sin(lineAngle + arrowAngle).toFloat()

                            // Draw the two sides
                            moveTo(endX, endY)
                            lineTo(x1, y1)

                            moveTo(endX, endY)
                            lineTo(x2, y2)
                        }
                    }
                    null -> {}
                }
            }
            MotionEvent.ACTION_UP -> {
                undoStack.add(copyStrokes(strokes))
                redoStack.clear() // Clear redo history
                if (undoStack.size > MAX_HISTORY) undoStack.removeAt(0)
                // Commit shape as stroke
                previewPath?.let {
                    strokes.add(Stroke(it, currentPaint))
                }
                previewPath = null
            }
        }
    }


    override fun onTouchEvent(event: MotionEvent): Boolean {
        when {
            isSelecting -> {
                handleSelectionTouch(event)
            }
            shapeMode != null -> {   // ✅ new: shape mode
                handleShapeTouch(event)
            }
            else -> {
                handleDrawingTouch(event)
            }
        }

        if (event.action == MotionEvent.ACTION_UP) performClick()
        invalidate()
        return true
    }


    private val selectionPaint = Paint().apply {
        color = 0x6600FF00 // semi-transparent green
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    fun getStrokes(): List<Stroke> = strokes

    fun setStrokes(strokeList: List<Stroke>) {
        strokes.clear()
        strokes.addAll(strokeList)
        invalidate()
    }

    private val highlightPaint = Paint().apply {
        color = 0x66FF0000 // semi-transparent red
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val tempBounds = RectF()

    private var previewPath: Path? = null
    private val previewPaint = Paint().apply {
        color = Color.GRAY
        strokeWidth = 3f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }

    private val selectionBounds = RectF()
    private val handlePoints = mutableListOf<PointF>()
    init {
        repeat(5) { handlePoints.add(PointF()) }
    }
    private val handleRadius = 10f
    private val handlePaint = Paint().apply {
        color = Color.BLUE
        style = Paint.Style.FILL
    }

    private fun computeSelectionBounds() {
        if (selectedStrokes.isEmpty()) return

        // Reset selectionBounds
        selectionBounds.setEmpty()

        // Union all stroke bounds
        val tmpRect = RectF()
        for (stroke in selectedStrokes) {
            stroke.path.computeBounds(tmpRect, true)
            selectionBounds.union(tmpRect)
        }

        // Ensure handlePoints list has 5 points (4 corners + rotation handle)
        while (handlePoints.size < 5) {
            handlePoints.add(PointF())
        }

        // Corner handles
        handlePoints[0].set(selectionBounds.left, selectionBounds.top)      // top-left
        handlePoints[1].set(selectionBounds.right, selectionBounds.top)     // top-right
        handlePoints[2].set(selectionBounds.right, selectionBounds.bottom)  // bottom-right
        handlePoints[3].set(selectionBounds.left, selectionBounds.bottom)   // bottom-left

        // Rotation handle (top-center above the box)
        val handleOffset = 60f // distance above the selection box
        handlePoints[4].set(
            selectionBounds.centerX(),
            selectionBounds.top - handleOffset
        )
    }


    private var activeHandleIndex: Int = -1 // -1 = none, 0..3 = which corner

    // Reuse temp values to avoid allocations
    private val touchPoint = PointF()
    private val tmpVec = PointF()

    private enum class TransformMode {
        NONE, SCALE, ROTATE, DRAG
    }
    private var transformMode = TransformMode.NONE
    private var activeHandle: PointF? = null

    private fun getHandleAt(x: Float, y: Float): Int {
        for (i in handlePoints.indices) {
            val dx = x - handlePoints[i].x
            val dy = y - handlePoints[i].y
            if (dx * dx + dy * dy <= handleRadius * handleRadius) {
                return i // found a handle
            }
        }
        return -1
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw all saved strokes
        for (stroke in strokes) {
            canvas.drawPath(stroke.path, stroke.paint)
        }

        if (selectedStrokes.isNotEmpty()) {
            computeSelectionBounds()

            // Draw bounding box
            val dashPaint = Paint().apply {
                color = Color.RED
                style = Paint.Style.STROKE
                pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
            }
            canvas.drawRect(selectionBounds, dashPaint)

            // Draw handles
            for ((index, point) in handlePoints.withIndex()) {
                if (index == handlePoints.size - 1) {
                    // Last handle = rotation handle (square)
                    val halfSize = handleRadius
                    canvas.drawRect(
                        point.x - halfSize,
                        point.y - halfSize,
                        point.x + halfSize,
                        point.y + halfSize,
                        handlePaint
                    )
                } else {
                    // Normal circular handles
                    canvas.drawCircle(point.x, point.y, handleRadius, handlePaint)
                }
            }
        }

        // Draw current path (while drawing)
        currentPath?.let {
            canvas.drawPath(it, currentPaint)
        }

        // Draw selection path (line drawn for selection)
        selectionPath?.let { path ->
            canvas.drawPath(path, selectionPaint)
        }

        // Draw preview path (optional dashed/gray path)
        previewPath?.let {
            canvas.drawPath(it, previewPaint)
        }

        // Highlight selected strokes
        for (stroke in selectedStrokes) {
            stroke.path.computeBounds(tempBounds, true)
            tempBounds.inset(-stroke.paint.strokeWidth / 2, -stroke.paint.strokeWidth / 2)
            canvas.drawRect(tempBounds, highlightPaint)
        }
    }


    private fun isPointInPath(path: Path, x: Float, y: Float): Boolean {
        val bounds = RectF()
        path.computeBounds(bounds, true)
        val region = Region()
        region.setPath(path, Region(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt()))
        return region.contains(x.toInt(), y.toInt())
    }

    private fun isPointNearPath(path: Path, x: Float, y: Float, threshold: Float): Boolean {
        val pathMeasure = PathMeasure(path, false)
        val pos = FloatArray(2)
        var distance = 0f
        val step = 5f
        while (distance < pathMeasure.length) {
            pathMeasure.getPosTan(distance, pos, null)
            val dx = x - pos[0]
            val dy = y - pos[1]
            if (dx * dx + dy * dy <= threshold * threshold) {
                return true
            }
            distance += step
        }
        return false
    }

    private fun pathIntersects(selection: Path, stroke: Stroke): Boolean {
        val strokeMeasure = PathMeasure(stroke.path, false)
        val strokeStep = 5f
        val strokePos = FloatArray(2)

        var distance = 0f
        while (distance < strokeMeasure.length) {
            strokeMeasure.getPosTan(distance, strokePos, null)

            // 1️⃣ Check if the point is close to the selection path
            if (isPointNearPath(selection, strokePos[0], strokePos[1], stroke.paint.strokeWidth / 2)) {
                return true
            }

            // 2️⃣ Check if the point is fully inside the selection path (lasso)
            if (isPointInPath(selection, strokePos[0], strokePos[1])) {
                return true
            }

            distance += strokeStep
        }

        return false
    }

    private var shapeMode: ShapeType? = null
    private var shapeStartX = 0f
    private var shapeStartY = 0f

    fun setDrawingMode() {
        shapeMode = null
        setSelectionMode(false)
        setMathingMode(false)
    }

    fun setShapeMode(shape: ShapeType) {
        shapeMode = shape
        setSelectionMode(false)
        setMathingMode(false)
    }

    private fun handleDrawingTouch(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                currentPath = Path().apply { moveTo(event.x, event.y) }
            }
            MotionEvent.ACTION_MOVE -> {
                currentPath?.lineTo(event.x, event.y)
            }
            MotionEvent.ACTION_UP -> {
                undoStack.add(copyStrokes(strokes))
                redoStack.clear() // Clear redo history
                if (undoStack.size > MAX_HISTORY) undoStack.removeAt(0)
                currentPath?.let {
                    strokes.add(Stroke(it, currentPaint))
                }
                currentPath = null
            }
        }
        invalidate()
    }


    // For dragging selected strokes
    private var isDraggingSelection = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private val tmpRect = RectF()
    private val tmpMatrix = Matrix()

    interface OnRecognizeStrokesListener {
        fun onRecognizeStrokes(strokes: List<Stroke>)
        fun onSendRecognizeStrokes(strokes: List<Stroke>)
    }

    var recognizeListener: OnRecognizeStrokesListener? = null

    private fun handleSelectionTouch(event: MotionEvent) {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (selectedStrokes.isNotEmpty()) {
                    // First check if user touched a handle
                    activeHandleIndex = getHandleAt(x, y)
                    if (activeHandleIndex != -1) {
                        transformMode = if (activeHandleIndex == handlePoints.size - 1) {
                            // Last handle = rotation handle
                            TransformMode.ROTATE
                        } else {
                            // Other handles = scale
                            TransformMode.SCALE
                        }
                        lastTouchX = x
                        lastTouchY = y
                        return
                    }

                    // Otherwise: check if tap was on stroke
                    val tappedStroke = selectedStrokes.any { stroke ->
                        stroke.path.computeBounds(tmpRect, true)
                        tmpRect.inset(-stroke.paint.strokeWidth / 2, -stroke.paint.strokeWidth / 2)
                        tmpRect.contains(x, y)
                    }

                    if (!tappedStroke) {
                        // Clear selection
                        selectedStrokes.clear()
                        selectionPath = null
                        isDraggingSelection = false
                        transformMode = TransformMode.NONE
                        invalidate()
                    } else {
                        // Start dragging selection
                        isDraggingSelection = true
                        transformMode = TransformMode.DRAG
                        lastTouchX = x
                        lastTouchY = y
                    }
                } else {
                    // Start a new selection line
                    selectionPath = Path().apply { moveTo(x, y) }
                    isDraggingSelection = false
                    transformMode = TransformMode.NONE
                }
            }

            MotionEvent.ACTION_MOVE -> {
                when (transformMode) {
                    TransformMode.DRAG -> {
                        val dx = x - lastTouchX
                        val dy = y - lastTouchY
                        moveSelected(dx, dy)
                        lastTouchX = x
                        lastTouchY = y
                    }
                    TransformMode.SCALE -> {
                        // Scale relative to selection center
                        computeSelectionBounds()
                        val centerX = selectionBounds.centerX()
                        val centerY = selectionBounds.centerY()

                        val prevDx = lastTouchX - centerX
                        val prevDy = lastTouchY - centerY
                        val newDx = x - centerX
                        val newDy = y - centerY

                        var scaleX = 1f
                        var scaleY = 1f

                        if (prevDx != 0f) {
                            scaleX = newDx / prevDx
                        }
                        if (prevDy != 0f) {
                            scaleY = newDy / prevDy
                        }

                        tmpMatrix.reset()
                        tmpMatrix.postScale(scaleX, scaleY, centerX, centerY)

                        // Compute stroke scaling factor
                        val strokeScale = sqrt(scaleX * scaleY)

                        for (stroke in selectedStrokes) {
                            // Scale the path
                            stroke.path.transform(tmpMatrix)

                            // Scale the stroke width
                            stroke.paint.strokeWidth *= strokeScale
                        }

                        computeSelectionBounds()

                        lastTouchX = x
                        lastTouchY = y
                    }
                    TransformMode.ROTATE -> {
                        computeSelectionBounds()
                        val centerX = selectionBounds.centerX()
                        val centerY = selectionBounds.centerY()

                        // Calculate angle difference in radians
                        val anglePrev = atan2(lastTouchY - centerY, lastTouchX - centerX)
                        val angleNow = atan2(y - centerY, x - centerX)
                        val deltaAngle = Math.toDegrees((angleNow - anglePrev).toDouble()).toFloat()

                        // Apply rotation
                        tmpMatrix.reset()
                        tmpMatrix.postRotate(deltaAngle, centerX, centerY)
                        for (stroke in selectedStrokes) {
                            stroke.path.transform(tmpMatrix)
                        }

                        // Update last touch for next move
                        lastTouchX = x
                        lastTouchY = y
                    }
                    else -> {
                        // Continue drawing selection line
                        selectionPath?.lineTo(x, y)
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {

                if (transformMode == TransformMode.NONE && !isDraggingSelection && selectionPath != null) {
                    undoStack.add(copyStrokes(strokes))
                    redoStack.clear() // Clear redo history
                    if (undoStack.size > MAX_HISTORY) undoStack.removeAt(0)

                    // Select strokes that intersect the selection line
                    selectedStrokes.clear()
                    for (stroke in strokes) {
                        if (pathIntersects(selectionPath!!, stroke)) {
                            selectedStrokes.add(stroke)
                        }
                    }

                    if (isSelecting && isMathing && selectedStrokes.isNotEmpty() && !isSending) {
                        recognizeListener?.onRecognizeStrokes(selectedStrokes)
                        Log.d("Mathmode", "Mathmode detected")
                    }
                    if (isSelecting && isMathing && selectedStrokes.isNotEmpty() && isSending) {
                        recognizeListener?.onSendRecognizeStrokes(selectedStrokes)
                        Log.d("Mathmode", "Sendingmode detected")
                    }

                }
                isDraggingSelection = false
                activeHandleIndex = -1
                transformMode = TransformMode.NONE
            }
        }
    }

    fun undo() {
        if (undoStack.isNotEmpty()) {
            redoStack.add(copyStrokes(strokes)) // Save current state to redo
            val previous = undoStack.removeAt(undoStack.lastIndex)
            strokes.clear()
            strokes.addAll(copyStrokes(previous))
            selectedStrokes.clear()
            selectionPath = null
            invalidate()
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            undoStack.add(copyStrokes(strokes)) // Save current state to undo
            val next = redoStack.removeAt(redoStack.lastIndex)
            strokes.clear()
            strokes.addAll(copyStrokes(next))
            selectedStrokes.clear()
            selectionPath = null
            invalidate()
        }
    }






    // --- Public API ---

    /** Switch between draw mode and selection mode */

    fun setStrokeWidth(width: Float) {
        currentPaint.strokeWidth = width
        // Also update shapes preview if any
        previewPaint.strokeWidth = width
        selectionPaint.strokeWidth = width
        highlightPaint.strokeWidth = width
        invalidate()
    }

    fun setSelectionMode(selecting: Boolean) {
        isSelecting = selecting
        isMathing = false
        selectionPath = null
        selectedStrokes.clear()
        invalidate()
    }

    fun setMathingMode(selecting: Boolean) {
        if (selecting == true) {
            shapeMode = null
        }
        setSelectionMode(selecting)
        isMathing = selecting
        isSending = false
        invalidate()
    }

    fun setSendMathingMode() {
        isSending = true
        invalidate()
    }

    /** Move selected strokes by dx, dy */
    fun moveSelected(dx: Float, dy: Float) {
        val matrix = Matrix()
        matrix.setTranslate(dx, dy)
        for (stroke in selectedStrokes) {
            stroke.path.transform(matrix)
        }
        invalidate()
    }

    /** Delete selected strokes */
    fun deleteSelected() {
        strokes.removeAll(selectedStrokes)
        selectedStrokes.clear()
        selectionPath = null
        invalidate()
    }

    /** Copy selected strokes */
    fun copySelected() {
        val newStrokes = selectedStrokes.map { stroke ->
            val newPath = Path(stroke.path) // copy
            Stroke(newPath, Paint(stroke.paint))
        }
        strokes.addAll(newStrokes)
        invalidate()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }


}
