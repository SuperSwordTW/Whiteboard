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
import java.util.concurrent.ConcurrentHashMap

class DrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private class SpatialHash(private val cellSize: Float = 128f) {
        private val grid = HashMap<Long, MutableSet<Stroke>>()

        private fun key(ix: Int, iy: Int): Long {
            return (ix.toLong() shl 32) xor (iy.toLong() and 0xffffffffL)
        }

        private fun cellsFor(bounds: RectF): Sequence<Pair<Int, Int>> {
            val minX = floor(bounds.left / cellSize).toInt()
            val minY = floor(bounds.top / cellSize).toInt()
            val maxX = floor(bounds.right / cellSize).toInt()
            val maxY = floor(bounds.bottom / cellSize).toInt()
            return sequence {
                for (ix in minX..maxX) {
                    for (iy in minY..maxY) {
                        yield(ix to iy)
                    }
                }
            }
        }

        // INSERT inside SpatialHash
        fun purge(stroke: Stroke) {
            val it = grid.entries.iterator()
            while (it.hasNext()) {
                val entry = it.next()
                val cell = entry.value
                if (cell.remove(stroke) && cell.isEmpty()) {
                    it.remove()
                }
            }
        }

        fun insert(stroke: Stroke, bounds: RectF) {
            cellsFor(bounds).forEach { (ix, iy) ->
                val k = key(ix, iy)
                val cell = grid.getOrPut(k) { mutableSetOf() }
                cell.add(stroke)
            }
        }

        fun remove(stroke: Stroke, bounds: RectF) {
            cellsFor(bounds).forEach { (ix, iy) ->
                val k = key(ix, iy)
                grid[k]?.let { cell ->
                    cell.remove(stroke)
                    if (cell.isEmpty()) grid.remove(k)
                }
            }
        }

        fun update(stroke: Stroke, oldBounds: RectF?, newBounds: RectF) {
            if (oldBounds != null) remove(stroke, oldBounds)
            insert(stroke, newBounds)
        }

        fun query(aabb: RectF): Set<Stroke> {
            val out = LinkedHashSet<Stroke>()
            cellsFor(aabb).forEach { (ix, iy) ->
                grid[key(ix, iy)]?.let(out::addAll)
            }
            return out
        }

        fun clear() = grid.clear()
    }

    private val selectionBoxPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }

    private val dirtyTransformStrokes = LinkedHashSet<Stroke>()

    private var indexDirty = false

    // Cached clip rects (reused each frame)
    private val clipRect = Rect()
    private val clipRectF = RectF()

    // Selection bounds recompute flag
    private var selectionBoundsDirty = true

    private val strokes = mutableListOf<Stroke>()
    private var currentPath: Path? = null

    // INSERT after existing field declarations
    private val spatial = SpatialHash(128f)
    private val strokeAabbs = ConcurrentHashMap<Stroke, RectF>()
    private val tmpStrokeBounds = RectF()

    private fun aabbOf(stroke: Stroke): RectF {
        // Lazily compute and cache expanded AABB
        return strokeAabbs[stroke] ?: run {
            val r = RectF()
            stroke.path.computeBounds(r, true)
            val pad = stroke.paint.strokeWidth / 2f
            r.inset(-pad, -pad)
            strokeAabbs[stroke] = r
            r
        }
    }

    private fun markDirty(stroke: Stroke) {
        // Drop cached AABB; it will be recomputed lazily on next draw
        strokeAabbs.remove(stroke)
    }

    private fun markSelectionBoundsDirty() {
        selectionBoundsDirty = true
    }

    private fun computeStrokeAabb(stroke: Stroke, out: RectF = RectF()): RectF {
        stroke.path.computeBounds(out, true)
        val pad = stroke.paint.strokeWidth / 2f
        out.inset(-pad, -pad)
        return out
    }

    private fun indexStroke(stroke: Stroke) {
        val aabb = computeStrokeAabb(stroke)
        strokeAabbs[stroke] = RectF(aabb)
        spatial.insert(stroke, aabb)
    }

    private fun unindexStroke(stroke: Stroke) {
        strokeAabbs.remove(stroke)?.let { spatial.remove(stroke, it) }
        val now = RectF()
        stroke.path.computeBounds(now, true)
        val pad = stroke.paint.strokeWidth * 0.5f
        now.inset(-pad, -pad)
        spatial.remove(stroke, now)

        // Final guarantee: purge from any cell it might still be in
        spatial.purge(stroke)
    }

    private fun reindexStroke(stroke: Stroke) {
        val old = strokeAabbs[stroke]
        val now = computeStrokeAabb(stroke)
        strokeAabbs[stroke] = RectF(now)
        spatial.update(stroke, old, now)
    }

    private fun rebuildSpatialIndex() {
        spatial.clear()
        strokeAabbs.clear()
        for (s in strokes) indexStroke(s)
    }

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
                    ShapeType.PARABOLA -> {
                        previewPath?.reset()

                        // Span/scale based on drag distance (similar to how SPHERE used radius)
                        val dx = event.x - shapeStartX
                        val dy = event.y - shapeStartY
                        val span = hypot(dx.toDouble(), dy.toDouble()).toFloat()

                        // Horizontal half-width and vertical offset (mirror the SPHERE's radius/half-radius idea)
                        val halfWidth = span
                        val m = span * 1.5f

                        val leftX = shapeStartX - halfWidth
                        val rightX = shapeStartX + halfWidth

                        // Drag below the start → opens downward; drag above → opens upward
                        val opensDown = event.y <= shapeStartY
                        val endY = if (opensDown) shapeStartY + m else shapeStartY - m
                        val ctrlY = if (opensDown) shapeStartY - m else shapeStartY + m

                        // A quadratic Bézier is a parabola segment; control at (h, k ± m) keeps vertex at (h, k)
                        previewPath?.moveTo(leftX, endY)
                        previewPath?.quadTo(shapeStartX, ctrlY, rightX, endY)
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
                    val newStroke = Stroke(it, currentPaint)
                    strokes.add(newStroke)
                    indexStroke(newStroke)
                    aabbOf(newStroke)
                    indexDirty = true
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
//                handleDrawingTouch(event) Switched to multi-point touch detection.
                handleMultiFingerDrawingTouch(event)
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
        strokeAabbs.clear()
        // Precompute AABBs for fast first frame
        for (s in strokes) aabbOf(s)
        selectedStrokes.clear()
        selectionPath = null
        markSelectionBoundsDirty()
        rebuildSpatialIndex()
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
        repeat(6) { handlePoints.add(PointF()) }
    }
    private val handleRadius = 15f
    private val handlePaint = Paint().apply {
        color = Color.BLUE
        style = Paint.Style.FILL
    }

    private val copyhandlePaint = Paint().apply {
        color = "#8F00FF".toColorInt()
        style = Paint.Style.FILL
    }

    private fun computeSelectionBounds() {
        if (!selectionBoundsDirty) return

        if (selectedStrokes.isEmpty()) {
            selectionBounds.setEmpty()
            selectionBoundsDirty = false
            if (handlePoints.isEmpty()) repeat(6) { handlePoints.add(PointF()) }
            for (p in handlePoints) p.set(0f, 0f)
            return
        }

        selectionBounds.setEmpty()

        // Use cached stroke AABBs to union quickly
        val tmpRect = RectF()
        for (stroke in selectedStrokes) {
            tmpRect.set(aabbOf(stroke))
            selectionBounds.union(tmpRect)
        }

        // Ensure handlePoints list has 5 points (4 corners + rotation)
        while (handlePoints.size < 5) handlePoints.add(PointF())

        // Corner handles
        handlePoints[0].set(selectionBounds.left, selectionBounds.top)
        handlePoints[1].set(selectionBounds.right, selectionBounds.top)
        handlePoints[2].set(selectionBounds.right, selectionBounds.bottom)
        handlePoints[3].set(selectionBounds.left, selectionBounds.bottom)

        // Rotation handle (top-center)
        val handleOffset = 60f
        handlePoints[4].set(selectionBounds.centerX(), selectionBounds.top - handleOffset)
        // Copy handle
        handlePoints[5].set(selectionBounds.centerX()+60f, selectionBounds.top - handleOffset)

        selectionBoundsDirty = false
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

        // Current clip (screen) area
        canvas.getClipBounds(clipRect)
        clipRectF.set(clipRect)

        // Draw only strokes that intersect the clip rect (using cached AABBs)
        for (stroke in strokes) {
            val r = aabbOf(stroke)
            if (!RectF.intersects(r, clipRectF)) continue
            canvas.drawPath(stroke.path, stroke.paint)
        }

        for ((_, path) in activePaths) {
            canvas.drawPath(path, currentPaint)
        }

        // Current in-progress path
        currentPath?.let {
            canvas.drawPath(it, currentPaint)
        }

        // Selection lasso/path
        selectionPath?.let { path ->
            canvas.drawPath(path, selectionPaint)
        }

        // Selection box + handles and highlights (only if selection intersects screen)
        if (selectedStrokes.isNotEmpty()) {
            computeSelectionBounds()
            if (!selectionBounds.isEmpty && RectF.intersects(selectionBounds, clipRectF)) {
                // Bounding box
                canvas.drawRect(selectionBounds, selectionBoxPaint)

                // Handles
                for ((index, point) in handlePoints.withIndex()) {
                    if (index == handlePoints.size - 2) {
                        val halfSize = handleRadius
                        canvas.drawRect(
                            point.x - halfSize, point.y - halfSize,
                            point.x + halfSize, point.y + halfSize,
                            handlePaint
                        )
                    }
                    else if (index == handlePoints.size - 1){
                        val halfSize = handleRadius
                        canvas.drawRect(
                            point.x - halfSize, point.y - halfSize,
                            point.x + halfSize, point.y + halfSize,
                            copyhandlePaint
                        )
                    }
                    else {
                        canvas.drawCircle(point.x, point.y, handleRadius, handlePaint)
                    }
                }
            }

            // Highlight only visible selected strokes
            for (stroke in selectedStrokes) {
                val rSel = aabbOf(stroke)
                if (!RectF.intersects(rSel, clipRectF)) continue
                canvas.drawRect(rSel, highlightPaint)
            }
        }

        // Preview path (shapes)
        previewPath?.let {
            canvas.drawPath(it, previewPaint)
        }
    }

    private val regionClip = Region()
    private val regionTemp = Region()

    private fun isPointInPath(path: Path, x: Float, y: Float): Boolean {
        val bounds = RectF()
        path.computeBounds(bounds, true)

        // Use roundOut to avoid losing edge coverage due to truncation
        val clip = Rect()
        bounds.roundOut(clip)

        regionClip.set(clip)
        regionTemp.setPath(path, regionClip)
        return regionTemp.contains(x.toInt(), y.toInt())
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

        // Handle zero-length paths (dots) by using the path's bounds
        if (strokeMeasure.length == 0f) {
            val bounds = RectF()
            stroke.path.computeBounds(bounds, true)
            val cx = bounds.centerX()
            val cy = bounds.centerY()
            val threshold = stroke.paint.strokeWidth
            if (isPointNearPath(selection, cx, cy, threshold)) return true
            if (isPointInPath(selection, cx, cy)) return true
            return false
        }

        // Otherwise, iterate along the path as before
        val strokeStep = 5f
        val strokePos = FloatArray(2)
        var distance = 0f
        while (distance < strokeMeasure.length) {
            strokeMeasure.getPosTan(distance, strokePos, null)
            if (isPointNearPath(selection, strokePos[0], strokePos[1], stroke.paint.strokeWidth / 2)) return true
            if (isPointInPath(selection, strokePos[0], strokePos[1])) return true
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

    private val activePaths = mutableMapOf<Int, Path>()

    private fun handleMultiFingerDrawingTouch(event: MotionEvent) {
        val pointerIndex = event.actionIndex
        val pointerId = event.getPointerId(pointerIndex)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                // New finger starts drawing
                val path = Path().apply {
                    moveTo(event.getX(pointerIndex), event.getY(pointerIndex))
                }
                activePaths[pointerId] = path
            }

            MotionEvent.ACTION_MOVE -> {
                // Update all active fingers
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    activePaths[id]?.lineTo(event.getX(i), event.getY(i))
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> {
                undoStack.add(copyStrokes(strokes))
                redoStack.clear() // Clear redo history
                // Finger finished — finalize stroke
                activePaths[pointerId]?.let { path ->
                    val newStroke = Stroke(path, currentPaint)
                    strokes.add(newStroke)
                    indexStroke(newStroke)
                    aabbOf(newStroke)
                    indexDirty = true

                }
                activePaths.remove(pointerId)
            }
        }
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
                    val newStroke = Stroke(it, currentPaint)
                    strokes.add(newStroke)
                    indexStroke(newStroke)
                    aabbOf(newStroke)
                    indexDirty = true
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

    // --- Add inside DrawingView class (e.g., below other private helpers) ---
    private fun getSelectedBounds(expandForStrokeWidth: Boolean = true): RectF? {
        if (selectedStrokes.isEmpty()) return null

        val out = RectF()
        val tmp = RectF()
        var first = true

        for (s in selectedStrokes) {
            s.path.computeBounds(tmp, true)
            if (expandForStrokeWidth) {
                val inset = -s.paint.strokeWidth / 2f
                tmp.inset(inset, inset)
            }
            if (first) {
                out.set(tmp)
                first = false
            } else {
                out.union(tmp)
            }
        }
        return if (first) null else out
    }


    private fun handleSelectionTouch(event: MotionEvent) {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (selectedStrokes.isNotEmpty()) {
                    // First check if user touched a handle
                    activeHandleIndex = getHandleAt(x, y)
                    if (activeHandleIndex != -1) {
                        if (activeHandleIndex == handlePoints.size - 1){
                            copySelected()
                            return
                        }
                        transformMode = if (activeHandleIndex == handlePoints.size - 2) {
                            // Last handle = rotation handle
                            TransformMode.ROTATE
                        }
                        else {
                            // Other handles = scale
                            TransformMode.SCALE
                        }
                        lastTouchX = x
                        lastTouchY = y
                        return
                    }

                    val selectionBounds = getSelectedBounds(expandForStrokeWidth = true)
                    val tappedWithinSelection = selectionBounds?.contains(x, y) == true

                    if (!tappedWithinSelection) {
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
                        dirtyTransformStrokes.addAll(selectedStrokes)
                        indexDirty = true
                        markSelectionBoundsDirty()
                    }
                    TransformMode.SCALE -> {
                        // Scale relative to selection center
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
                        val strokeScale = sqrt(abs(scaleX * scaleY))

                        for (stroke in selectedStrokes) {
                            // Scale the path
                            stroke.path.transform(tmpMatrix)

                            // Scale the stroke width
                            stroke.paint.strokeWidth *= strokeScale
                            markDirty(stroke)
                        }
                        markSelectionBoundsDirty()

                        lastTouchX = x
                        lastTouchY = y
                        dirtyTransformStrokes.addAll(selectedStrokes)
                        indexDirty = true
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
                            markDirty(stroke)
                        }
                        markSelectionBoundsDirty()

                        // Update last touch for next move
                        lastTouchX = x
                        lastTouchY = y
                        dirtyTransformStrokes.addAll(selectedStrokes)
                        indexDirty = true
                    }
                    else -> {
                        // Continue drawing selection line
                        selectionPath?.lineTo(x, y)
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (transformMode == TransformMode.NONE && !isDraggingSelection && selectionPath != null) {
                    if (indexDirty) {
                        // If you implemented a spatial hash earlier, make sure to update it here.
                        // Example with AABB cache or spatial structure:
                        // Super Important
                        if (dirtyTransformStrokes.isNotEmpty()) {
                            // Reindex only those strokes whose geometry changed
                            for (s in dirtyTransformStrokes) {
                                reindexStroke(s)
                            }
                            dirtyTransformStrokes.clear()
                        } else {
                            // Added/removed/undo/redo or unknown mutations → safest is full rebuild
//                            rebuildSpatialIndex()
                        }
                        indexDirty = false
                    }
                    undoStack.add(copyStrokes(strokes))
                    redoStack.clear() // Clear redo history
                    if (undoStack.size > MAX_HISTORY) undoStack.removeAt(0)

                    // Coarse: AABB query first, then precise path check
                    selectedStrokes.clear()
                    val aabb = RectF()
                    selectionPath!!.computeBounds(aabb, true)
                    // Small expansion helps catch near-line hits
                    aabb.inset(-24f, -24f)

                    val candidates = spatial.query(aabb)
                    for (stroke in candidates) {
                        // Fine test
                        if (pathIntersects(selectionPath!!, stroke)) {
                            selectedStrokes.add(stroke)
                        }
                    }
                    markSelectionBoundsDirty()

                    if (isSelecting && isMathing && selectedStrokes.isNotEmpty() && !isSending) {
                        recognizeListener?.onRecognizeStrokes(selectedStrokes)
                        Log.d("Mathmode", "Mathmode detected")
                    }
                    if (isSelecting && isMathing && selectedStrokes.isNotEmpty() && isSending) {
                        recognizeListener?.onSendRecognizeStrokes(selectedStrokes)
                        Log.d("Mathmode", "Sendingmode detected")
                    }
                    if (dirtyTransformStrokes.isNotEmpty()) {
                        for (s in dirtyTransformStrokes) {
                            reindexStroke(s)
                        }
                        dirtyTransformStrokes.clear()
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
            rebuildSpatialIndex()
            strokeAabbs.clear()
            for (s in strokes) aabbOf(s)
            markSelectionBoundsDirty()
            selectedStrokes.clear()
            indexDirty = true
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
            rebuildSpatialIndex()
            strokeAabbs.clear()
            for (s in strokes) aabbOf(s)
            markSelectionBoundsDirty()
            selectedStrokes.clear()
            indexDirty = true
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
            markDirty(stroke)
            dirtyTransformStrokes.add(stroke)
        }
        markSelectionBoundsDirty()
        invalidate()
    }

    /** Delete selected strokes */
    fun deleteSelected() {
        dirtyTransformStrokes.removeAll(selectedStrokes)
        for (s in selectedStrokes) {
            unindexStroke(s)
            strokeAabbs.remove(s)
        }
        strokes.removeAll(selectedStrokes)
        selectedStrokes.clear()
        indexDirty = true
        selectionPath = null
        markSelectionBoundsDirty()
        invalidate()
    }

    /** Copy selected strokes */
    /** Copy selected strokes with an offset */
    fun copySelected() {
        if (selectedStrokes.isEmpty()) return

        // Offset amount for the pasted copies
        val offsetX = 20f
        val offsetY = 20f

        // Duplicate + translate each selected stroke
        val newStrokes = selectedStrokes.map { src ->
            val newPath = Path(src.path)
            val m = Matrix().apply { setTranslate(offsetX, offsetY) }
            newPath.transform(m)
            Stroke(newPath, Paint(src.paint))
        }

        // Add to model
        strokes.addAll(newStrokes)

        // If you maintain AABB cache and/or spatial hash, index each new stroke now
        // (so no need to set indexDirty here)
        newStrokes.forEach { s ->
            // If you have these helpers, keep them:
             aabbOf(s)                 // populate AABB cache
             indexStroke(s)            // add to spatial hash
            // If you only have one of them, call the one you use.
        }

        // Make ONLY the copies selected
        selectedStrokes.clear()
        selectedStrokes.addAll(newStrokes)

        // Reset any selection gesture path and refresh selection box
        selectionPath = null
        // If you use a dirty flag system:
         selectionBoundsDirty = true
        // else recompute immediately:
//        computeSelectionBounds()

        invalidate()
    }


    override fun performClick(): Boolean {
        super.performClick()
        return true
    }


}
