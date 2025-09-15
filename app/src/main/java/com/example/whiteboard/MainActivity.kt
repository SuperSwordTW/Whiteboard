package com.example.whiteboard

import android.graphics.Color
import android.graphics.RectF
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.toColorInt
import com.example.whiteboard.IInkApplication.engine
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.myscript.iink.ContentPackage
import com.myscript.iink.ContentPart
import com.myscript.iink.uireferenceimplementation.EditorBinding
import com.myscript.iink.uireferenceimplementation.EditorData
import com.myscript.iink.uireferenceimplementation.EditorView
import com.myscript.iink.uireferenceimplementation.FontUtils
import com.myscript.iink.uireferenceimplementation.*
import java.io.File
import kotlin.math.max
import android.util.Log
import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.GestureDetector
import com.example.whiteboard.MathInputNormalizer
import com.example.whiteboard.WolframCloudConverter
import androidx.cardview.widget.CardView
import android.net.Uri
import android.util.JsonReader
import android.util.JsonWriter
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.max
import java.util.zip.ZipException
import android.graphics.Paint
import android.graphics.Path
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference



class MainActivity : AppCompatActivity() {


    private lateinit var drawingView: DrawingView

    private var pages = mutableListOf<MutableList<Stroke>>() // each page is a list of strokes
    private var currentPageIndex = 0


    private var currentFileName: String? = null

    private lateinit var desmosWebView: WebView

    private lateinit var desmosCard: CardView
    private var isDesmosVisible = false

    private var editorData: EditorData? = null
    private var editorView: EditorView? = null
    private var contentPackage: ContentPackage? = null
    private var contentPart: ContentPart? = null

    private var editorBinding: EditorBinding? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        autoSaveHandler.postDelayed(autoSaveRunnable, autoSaveInterval)


        // Setting up myscript

        val engine = engine!!

        // Configure engine
        val conf = engine.configuration
        val confDir = "zip://${packageCodePath}!/assets/conf"
        conf.setStringArray("configuration-manager.search-path", arrayOf(confDir))
        val tempDir = filesDir.path + File.separator + "不要開"
        conf.setString("content-package.temp-folder", tempDir)

        // Create or open package
        val packageName = "Whiteboard.iink"
        val file = File(filesDir, packageName)

        contentPackage = if (file.exists()) {
            engine.openPackage(file)   // open existing one
        } else {
            engine.createPackage(file) // create new
        }

        // Create Math part if none exists
        contentPart = if (contentPackage!!.partCount == 0) {
            contentPackage!!.createPart("Math")
        } else {
            contentPackage!!.getPart(0)
        }

        // Load fonts
        val typefaceMap = FontUtils.loadFontsFromAssets(assets)

        // Initialize editor binding
        editorBinding = EditorBinding(engine, typefaceMap)

        // Create EditorView
        editorView = EditorView(this).apply { visibility = View.GONE }
        findViewById<FrameLayout>(R.id.drawing_container).addView(editorView)

        // Open editor
        editorData = editorBinding?.openEditor(editorView)
        Log.d("Mathmode", "openEditor returned editorData=${editorData}, editor=${editorData?.editor}")

        // Wait for layout before configuration
        editorView?.post {
            editorData?.let { data ->
                val editor = data.editor ?: return@post

                // Input controller
                data.inputController?.apply {
                    inputMode = InputController.INPUT_MODE_FORCE_PEN
                    setViewListener(editorView)
                }

                // Set view size + font metrics
                editor.setViewSize(editorView!!.width, editorView!!.height)
                editor.setFontMetricsProvider(FontMetricsProvider(resources.displayMetrics, typefaceMap))

                // Configure Math
                editor.configuration.apply {
                    setBoolean("math.solver.enable", true)
                    setString("math.configuration.bundle", "math")
                    setString("math.configuration.name", "standard")
                }

                // ✅ Assign the Math part
                editor.part = contentPart

                Log.d("Mathmode", "Editor initialized, part: ${editor.part?.type}")
            }
        }



        Log.d("Mathmode", "editorData.editor = ${editorData?.editor}")
        Log.d("Mathmode", "editor part = ${editorData?.editor?.part}")


        // Setting up desmos
        desmosCard = findViewById(R.id.desmos_card)
        desmosWebView = findViewById(R.id.desmos_webview)
        val webSettings: WebSettings = desmosWebView.settings
        webSettings.javaScriptEnabled = true
        desmosWebView.webViewClient = WebViewClient()
        desmosCard.visibility = View.GONE

        desmosWebView.loadUrl("https://www.desmos.com/calculator")

        val desmosHtml = """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8">
              <script src="https://www.desmos.com/api/v1.8/calculator.js?apiKey=dcb31709b452b1cf9dc26972add0fda6"></script>
              <style>
                html, body, #calculator {
                  width: 100%;
                  height: 100%;
                  margin: 0;
                }
              </style>
            </head>
            <body>
              <div id="calculator"></div>
              <script>
                var elt = document.getElementById('calculator');
                window.calculator = Desmos.GraphingCalculator(elt, {
                  expressions: true,
                  expressionsCollapsed: true
                });
                // Optional: add a default expression
                // calculator.setExpression({ id: 'default', latex: 'y=x^2' });
              </script>
            </body>
            </html>
            """.trimIndent()



        desmosWebView.loadDataWithBaseURL(
            "https://www.desmos.com",
            desmosHtml,
            "text/html",
            "utf-8",
            null
        )



        // Setup toolbar
        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        // Add DrawingView dynamically into the container
        drawingView = DrawingView(this)
        val container: FrameLayout = findViewById(R.id.drawing_container)
        container.addView(drawingView)

        drawingView.recognizeListener = object : DrawingView.OnRecognizeStrokesListener {
            override fun onRecognizeStrokes(strokes: List<Stroke>) {
                Log.d("Mathmode", "onRecognizeStrokes fired")
                editorData?.editor?.let {

                    Log.d("Mathmode", "editor.part = ${it.part}, editor.isIdle = ${it.isIdle}")

                    val latex = recognizeSelectedStrokes(it, strokes)



//                    onRecognizedMath(latex, strokes)

                    showRecognizedMath(latex, strokes)

                }
                drawingView.setMathingMode(false)
            }
            override fun onSendRecognizeStrokes(strokes: List<Stroke>) {
                Log.d("Mathmode", "onSendRecognizeStrokes fired")
                editorData?.editor?.let {
                    Log.d("Mathmode", "editor.part = ${it.part}, editor.isIdle = ${it.isIdle}")
                    val latex = recognizeSelectedStrokes(it, strokes)
                    sendLatexToDesmos(latex)
                }
                drawingView.setMathingMode(false)
            }
        }

        if (pages.isEmpty()) {
            pages.add(mutableListOf()) // first page
        }
        drawingView.setStrokes(pages[currentPageIndex])


        // --- Handle toolbar button clicks ---
        val drawButton = findViewById<ImageButton>(R.id.btn_draw)
        val strokewidthButton = findViewById<ImageButton>(R.id.btn_stroke_width)
        val selectButton = findViewById<ImageButton>(R.id.btn_select)
        val colorButton = findViewById<ImageButton>(R.id.btn_color)
        val deleteButton = findViewById<ImageButton>(R.id.btn_delete)
        val prevPageButton = findViewById<ImageButton>(R.id.btn_prev_page)
        val nextPageButton = findViewById<ImageButton>(R.id.btn_next_page)
        val desmosButton = findViewById<ImageButton>(R.id.btn_desmos)
        val shapeButton = findViewById<ImageButton>(R.id.btn_shape)
        val undoButton = findViewById<ImageButton>(R.id.btn_undo)
        val redoButton = findViewById<ImageButton>(R.id.btn_redo)
        val mathButton = findViewById<ImageButton>(R.id.btn_math)
        val sendButton = findViewById<ImageButton>(R.id.btn_send)

        undoButton.setOnClickListener { drawingView.undo() }
        redoButton.setOnClickListener { drawingView.redo() }

        strokewidthButton.setOnClickListener {
            val popupView = layoutInflater.inflate(R.layout.stroke_witdth_palette, null)

            val popupWindow = PopupWindow(
                popupView,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                true
            )
            popupWindow.showAsDropDown(shapeButton, -280, -shapeButton.height - 16)

            popupView.findViewById<View>(R.id.width_small).setOnClickListener {
                drawingView.setStrokeWidth(3f)
                popupWindow.dismiss()
            }
            popupView.findViewById<View>(R.id.width_medium).setOnClickListener {
                drawingView.setStrokeWidth(10f)
                popupWindow.dismiss()
            }
            popupView.findViewById<View>(R.id.width_large).setOnClickListener {
                drawingView.setStrokeWidth(20f)
                popupWindow.dismiss()
            }
        }

        sendButton.setOnClickListener {
            drawingView.commitAllActivePaths()
            drawingView.setSelectionMode(true)
            drawingView.setMathingMode(true)
            drawingView.setSendMathingMode()
        }

        shapeButton.setOnClickListener {
            drawingView.commitAllActivePaths()
            val popupView = layoutInflater.inflate(R.layout.shape_palette, null)
            val popupWindow = PopupWindow(
                popupView,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                true
            )
            popupWindow.showAsDropDown(shapeButton, 0, -shapeButton.height - 16)

            popupView.findViewById<View>(R.id.shape_rectangle).setOnClickListener {
                drawingView.setShapeMode(ShapeType.RECTANGLE)
                popupWindow.dismiss()
            }
            popupView.findViewById<View>(R.id.shape_circle).setOnClickListener {
                drawingView.setShapeMode(ShapeType.CIRCLE)
                popupWindow.dismiss()
            }
            popupView.findViewById<View>(R.id.shape_triangle).setOnClickListener {
                drawingView.setShapeMode(ShapeType.TRIANGLE)
                popupWindow.dismiss()
            }
            popupView.findViewById<View>(R.id.shape_cube).setOnClickListener {
                drawingView.setShapeMode(ShapeType.CUBE)
                popupWindow.dismiss()
            }
            popupView.findViewById<View>(R.id.shape_axis).setOnClickListener {
                drawingView.setShapeMode(ShapeType.AXIS)
                popupWindow.dismiss()
            }
            popupView.findViewById<View>(R.id.shape_segment).setOnClickListener {
                drawingView.setShapeMode(ShapeType.SEGMENT)
                popupWindow.dismiss()
            }
            popupView.findViewById<View>(R.id.shape_ray).setOnClickListener {
                drawingView.setShapeMode(ShapeType.RAY)
                popupWindow.dismiss()
            }
            popupView.findViewById<View>(R.id.shape_sphere).setOnClickListener {
                drawingView.setShapeMode(ShapeType.SPHERE)
                popupWindow.dismiss()
            }
            popupView.findViewById<View>(R.id.shape_parabola).setOnClickListener {
                drawingView.setShapeMode(ShapeType.PARABOLA)
                popupWindow.dismiss()
            }
        }

        desmosButton.setOnClickListener {
            isDesmosVisible = !isDesmosVisible
            isDesmosVisible = desmosCard.visibility != View.VISIBLE
            desmosCard.visibility = if (isDesmosVisible) View.VISIBLE else View.GONE
            desmosWebView.visibility = if (isDesmosVisible) View.VISIBLE else View.GONE
        }

        drawButton.setOnClickListener {
            drawingView.setDrawingMode()
        }

        selectButton.setOnClickListener {
            drawingView.commitAllActivePaths()
            drawingView.setSelectionMode(true)
        }

        mathButton.setOnClickListener {
            drawingView.commitAllActivePaths()
            drawingView.setSelectionMode(true)
            drawingView.setMathingMode(true)
        }

        prevPageButton.setOnClickListener { previousPage() }
        nextPageButton.setOnClickListener { nextPage() }

        colorButton.setOnClickListener {
            val popupView = layoutInflater.inflate(R.layout.color_palette, null)

            val popupWindow = PopupWindow(
                popupView,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                true
            )

            // Show above the button
            popupWindow.showAsDropDown(colorButton, 0, -colorButton.height - 16)

            // Set click listeners for each color
            popupView.findViewById<View>(R.id.color_red).setOnClickListener {
                drawingView.setPenColor("#FF5252".toColorInt())
                popupWindow.dismiss()
            }
            popupView.findViewById<View>(R.id.color_violet).setOnClickListener {
                drawingView.setPenColor("#DF8EF2".toColorInt())
                popupWindow.dismiss()
            }
            popupView.findViewById<View>(R.id.color_green).setOnClickListener {
                drawingView.setPenColor("#B2FF59".toColorInt())
                popupWindow.dismiss()
            }
            popupView.findViewById<View>(R.id.color_blue).setOnClickListener {
                drawingView.setPenColor("#40C4FF".toColorInt())
                popupWindow.dismiss()
            }
            popupView.findViewById<View>(R.id.color_orange).setOnClickListener {
                drawingView.setPenColor("#FFAB40".toColorInt())
                popupWindow.dismiss()
            }
            popupView.findViewById<View>(R.id.color_yellow).setOnClickListener {
                drawingView.setPenColor("#FFFF00".toColorInt())
                popupWindow.dismiss()
            }
            popupView.findViewById<View>(R.id.color_white).setOnClickListener {
                drawingView.setPenColor(Color.WHITE)
                popupWindow.dismiss()
            }
        }
        deleteButton.setOnClickListener {
            drawingView.setDeleteMode(true)
//            drawingView.deleteSelected()
        }
    }

    private val tmpRect = RectF()

//    private val wolfram by lazy {
//        WolframAlphaClient(appId = "8GAVHGL5LL")
//    }
//
//    private val wolframCloud by lazy {
//        WolframCloudConverter(texToWlUrl = "https://www.wolframcloud.com/obj/linjustin0209/tex-to-wl", wlToTexUrl = "https://www.wolframcloud.com/obj/linjustin0209/wl-to-tex")
//    }

    // DEPRECATED
//    fun onRecognizedMath(input: String?, strokes: List<Stroke>) {
//        var text = input?.trim()
//        if (text.isNullOrEmpty()) return
//
//        Log.d("Mathmode","Original string: $text")
//
//        var (okTeX, waInput) = wolframCloud.toWolframLanguageFromTeX(text)
////        var waInput = MathInputNormalizer.convertAlignedBlocks(text)
////        waInput = MathInputNormalizer.convertLatexMatricesToLists(text)
//
//
//        Log.d("Mathmode","converted string: $waInput")
//
//        if (waInput == "Failed") {
//            waInput = text
//        }
//
//
////        val short = wolfram.queryShortAnswer(waInput)
//
//        val full = wolfram.queryFullResult(waInput)
//
////        val displayText: String = if (short.ok && !short.text.isNullOrBlank()) {
////            short.text
////        } else {
////            short.errorMessage?: "No result."
////        }
//
//        val displayText: String = if (full.ok && !full.primaryText.isNullOrBlank()) {
//            full.primaryText!!
//        } else {
//            full.errorMessage ?: "No result."
//        }
//
////        val latex = MathInputNormalizer.plainToLatex(displayText)
//        var (oklatex,latex) = wolframCloud.toTeXFromWolframLanguage(displayText)
//        if (latex == "\\text{\$\\\$\$Failed}") {
//            latex = MathInputNormalizer.plainToLatex(displayText)
//        }
//        val input = concatLatex(text, latex)
//
//        Log.d("Mathmode","string shown: $input")
//
//        showRecognizedMath(input, strokes)
//    }

    private fun concatLatex(base: String?, next: String?): String {
        val b = base?.trim().orEmpty()
        val n = next?.trim().orEmpty()
        if (b.isEmpty()) return n
        if (n.isEmpty()) return b

        // Join with a space so things like "x=1" and "y=2" don't collide
        return "$b \\newline $n"
    }


    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun showRecognizedMath(latex: String?, strokes: List<Stroke>) {
        if (latex.isNullOrEmpty() || strokes.isEmpty()) return

        Log.d("Mathmode", "showRecognizedMath called")

        // 1) Compute the drawing bounds so we can position the WebView near the ink.
        val bounds = RectF()
        val tmpRect = RectF()
        for (stroke in strokes) {
            stroke.path.computeBounds(tmpRect, true)
            bounds.union(tmpRect)
        }

        // 2) Create a transparent WebView that will auto-size to the *rendered KaTeX content*.
        val webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            settings.javaScriptEnabled = true
            setBackgroundColor(Color.TRANSPARENT)
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        }

        // 3) Color / expression
        val cleanLatex = latex.replace("\\\\", "\\")
        val katexExpression = "$$${cleanLatex}$$"
        val pencolor = "#${Integer.toHexString(drawingView.getPenColor()).substring(2)}"

        // 4) Bridge: the page calls Android.onSize(wPx, hPx) *after* KaTeX finishes rendering.
        //    We size the WebView to the actual rendered content in device pixels to avoid clipping.
        webView.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun onSize(wPx: Int, hPx: Int) {
                runOnUiThread {
                    val pad = (8 * resources.displayMetrics.density).toInt() // small breathing room
                    val lp = FrameLayout.LayoutParams(wPx + pad, hPx + pad)
                    webView.layoutParams = lp
                }
            }
        }, "Android")

        // 5) HTML: ensure KaTeX renders, then measure using getBoundingClientRect() and devicePixelRatio.
        //    Also force wrapping (no cutoff) if the expression grows wide.
        val html = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
            <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/katex@0.16.8/dist/katex.min.css">
            <script defer src="https://cdn.jsdelivr.net/npm/katex@0.16.8/dist/katex.min.js"></script>
            <script defer src="https://cdn.jsdelivr.net/npm/katex@0.16.8/dist/contrib/auto-render.min.js"></script>
            <style>
                html, body {
                    margin: 0; padding: 0; background: transparent; overflow: hidden;
                }
                #wrap {
                    display: inline-block;
                    margin: 0; padding: 0;
                    color: ${pencolor};
                    /* Let long math wrap instead of overflowing */
                    max-width: 100%;
                    white-space: normal;
                }
                /* KaTeX default display math prevents wrapping; override to allow long lines */
                .katex, .katex-display {
                    white-space: normal !important;
                }
                #math-content {
                    display: inline-block;
                    font-size: 20px;
                }
            </style>
        </head>
        <body>
            <div id="wrap">
                <span id="math-content">${katexExpression}</span>
            </div>
            <script>
                (function() {
                    function renderAndMeasure() {
                        try {
                            renderMathInElement(document.body, {
                                delimiters: [{left: "$$", right: "$$", display: true}]
                            });
                        } catch (e) {}

                        // Wait a frame for layout to settle, then measure.
                        requestAnimationFrame(function() {
                            var el = document.getElementById("math-content");
                            if (!el) return;
                            var rect = el.getBoundingClientRect();
                            var dpr = window.devicePixelRatio || 1;
                            var w = Math.ceil(rect.width * dpr);
                            var h = Math.ceil(rect.height * dpr);
                            if (window.Android && Android.onSize) {
                                Android.onSize(w, h);
                            }
                        });
                    }

                    if (document.readyState === "complete") {
                        renderAndMeasure();
                    } else {
                        window.addEventListener("load", renderAndMeasure);
                    }

                    // Re-measure if fonts load later or viewport metrics change.
                    window.addEventListener("resize", function(){
                        var el = document.getElementById("math-content");
                        if (!el) return;
                        var rect = el.getBoundingClientRect();
                        var dpr = window.devicePixelRatio || 1;
                        var w = Math.ceil(rect.width * dpr);
                        var h = Math.ceil(rect.height * dpr);
                        if (window.Android && Android.onSize) {
                            Android.onSize(w, h);
                        }
                    });
                })();
            </script>
        </body>
        </html>
    """.trimIndent()

        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)

        val container: FrameLayout = findViewById(R.id.drawing_container)
        container.addView(webView)

        // 6) Position near the ink bounds (size will be set by the JS bridge above).
        webView.post {
            webView.x = bounds.left
            webView.y = bounds.top
        }

        // 7) Drag & long-press delete (unchanged).
        var dX = 0f
        var dY = 0f
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                container.removeView(webView)
            }
        })
        webView.setOnTouchListener { v, event ->
            gestureDetector.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dX = v.x - event.rawX
                    dY = v.y - event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    v.x = event.rawX + dX
                    v.y = event.rawY + dY
                }
            }
            true
        }
    }

    private var desmosExprCounter = 0

    private fun sendLatexToDesmos(latex: String?) {
        val jsLatex = org.json.JSONObject.quote(latex) // ensures proper escaping
        desmosExprCounter ++
        val exprId = "expr$desmosExprCounter"
        desmosWebView.evaluateJavascript(
            "calculator.setExpression({ id: '$exprId', latex: $jsLatex });",
            null
        )
    }

    // ADD this helper inside class MainActivity (near other private utilities)
    private fun deepCopyAndNormalizeStroke(src: Stroke): Stroke {
        // Deep-copy geometry and paint so DrawingView (or other code) cannot overwrite shared instances
        val newPath = Path(src.path)
        val newPaint = Paint(src.paint).apply {
            // Ensure stroke-style paint with your defaults respected
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND

            // Clamp/normalize critical visual props
            alpha = max(0, minOf(255, alpha))
            strokeWidth = if (strokeWidth <= 0f) 1f else strokeWidth
        }
        return Stroke(newPath, newPaint)
    }

    // ADD this helper inside class MainActivity (used by legacy fallback path)
    private fun normalizePage(page: MutableList<Stroke>): MutableList<Stroke> {
        // Rebuild each stroke with its own Paint instance and normalized properties
        return page.map { deepCopyAndNormalizeStroke(it) }.toMutableList()
    }


    // INSERT inside class MainActivity, near other private utilities

    /**
     * How densely to sample points when serializing a Path.
     * Smaller step => more points => larger file, higher fidelity.
     */
    private val SERIALIZE_SAMPLE_STEP_PX = 3f

    /**
     * Minimal per-stroke DTO we write/read. We stream it with JsonWriter/JsonReader (no data classes needed).
     * We serialize each stroke as polyline points (x,y) to guarantee portability and keep memory flat.
     */
    // REPLACE your existing writeStroke(...) in MainActivity.kt with this version.
    private fun writeStroke(writer: JsonWriter, s: Stroke) {
        writer.beginObject()

        // Paint — write FULL ARGB in one field. Do NOT split alpha.
        writer.name("argb").value(s.paint.color)
        writer.name("width").value(s.paint.strokeWidth.toDouble())

        // Geometry (sampled points from Path)
        writer.name("points")
        writer.beginArray()
        val pm = android.graphics.PathMeasure(s.path, false)
        val pos = FloatArray(2)
        var length = pm.length
        while (true) {
            var distance = 0f
            while (distance <= length) {
                pm.getPosTan(distance, pos, null)
                writer.beginArray()
                writer.value(pos[0].toDouble())
                writer.value(pos[1].toDouble())
                writer.endArray()
                distance += SERIALIZE_SAMPLE_STEP_PX
            }
            if (length > 0f) {
                pm.getPosTan(length, pos, null)
                writer.beginArray()
                writer.value(pos[0].toDouble())
                writer.value(pos[1].toDouble())
                writer.endArray()
            }
            if (!pm.nextContour()) break
            length = pm.length
        }
        writer.endArray()

        writer.endObject()
    }

    /**
     * Recreate a Stroke from streamed JSON.
     * We reconstruct a Path from the polyline points and restore paint basics.
     */
    // REPLACE this helper in MainActivity.kt
    // REPLACE your existing readStroke(...) in MainActivity.kt with this version.
    private fun readStroke(reader: JsonReader): Stroke {
        var argb = 0xFF000000.toInt() // default black (will be overridden by file)
        var width = 4f
        val pts = ArrayList<Pair<Float, Float>>(64)

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                // Read the single, authoritative ARGB field
                "argb" -> argb = reader.nextInt()
                // Backward-compat: if older files had "color" and optional "alpha", accept them.
                "color" -> argb = reader.nextInt()
                "alpha" -> {
                    // Ignore separate alpha on load; ARGB already carries the true alpha.
                    // We still consume the value to keep the stream aligned.
                    reader.nextInt()
                }
                "width" -> width = reader.nextDouble().toFloat()
                "points" -> {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        reader.beginArray()
                        val x = reader.nextDouble().toFloat()
                        val y = reader.nextDouble().toFloat()
                        reader.endArray()
                        pts.add(x to y)
                    }
                    reader.endArray()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        // Rebuild Path from polyline
        val path = android.graphics.Path()
        if (pts.isNotEmpty()) {
            path.moveTo(pts[0].first, pts[0].second)
            var i = 1
            while (i < pts.size) {
                path.lineTo(pts[i].first, pts[i].second)
                i++
            }
        }

        // Construct Paint with EXACT ARGB (this preserves pure white #FFFFFFFF, etc.)
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
            color = argb            // <-- full ARGB restored here
            strokeWidth = if (width <= 0f) 1f else width
        }

        return Stroke(path, paint)
    }


    /**
     * VERSIONED, streaming save format:
     *
     * {
     *   "version": 1,
     *   "pageCount": N,
     *   "pages": [
     *     { "strokes": [ {stroke...}, ... ] },
     *     ...
     *   ]
     * }
     *
     * Gzipped for space and faster I/O.
     */
    private fun saveWhiteboardToUri(uri: Uri): Boolean {
        // We DO NOT build giant JSON strings. We stream out page-by-page, stroke-by-stroke.
        val cr = contentResolver
        cr.openOutputStream(uri)?.use { raw ->
            GZIPOutputStream(BufferedOutputStream(raw)).use { gz ->
                JsonWriter(OutputStreamWriter(gz, Charsets.UTF_8)).use { writer ->
                    writer.setIndent("") // no pretty indent to reduce size
                    writer.beginObject()
                    writer.name("version").value(1)
                    writer.name("pageCount").value(pages.size)
                    writer.name("pages")
                    writer.beginArray()

                    for (pageIndex in 0 until pages.size) {
                        val page = pages[pageIndex]
                        writer.beginObject()
                        writer.name("strokes")
                        writer.beginArray()
                        // stream each stroke
                        for (s in page) {
                            writeStroke(writer, s)
                        }
                        writer.endArray()
                        writer.endObject()
                    }

                    writer.endArray()
                    writer.endObject()
                }
            }
        } ?: return false
        return true
    }

    /**
     * Streamed, versioned loader that won't allocate a massive DOM.
     * It reconstructs pages and strokes incrementally.
     */
    private fun loadWhiteboardFromUri(uri: Uri): Boolean {
        val loadedPages = mutableListOf<MutableList<Stroke>>()

        val cr = contentResolver
        cr.openInputStream(uri)?.use { raw ->
            GZIPInputStream(BufferedInputStream(raw)).use { gz ->
                JsonReader(InputStreamReader(gz, Charsets.UTF_8)).use { reader ->
                    var version = 1
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "version" -> version = reader.nextInt() // future-proofing
                            "pageCount" -> {
                                // We don't actually need this up-front with streaming,
                                // but we still read it to keep the stream position correct.
                                reader.nextInt()
                            }
                            "pages" -> {
                                reader.beginArray()
                                while (reader.hasNext()) {
                                    val strokes = mutableListOf<Stroke>()
                                    reader.beginObject()
                                    while (reader.hasNext()) {
                                        when (reader.nextName()) {
                                            "strokes" -> {
                                                reader.beginArray()
                                                while (reader.hasNext()) {
                                                    strokes.add(readStroke(reader))
                                                }
                                                reader.endArray()
                                            }
                                            else -> reader.skipValue()
                                        }
                                    }
                                    reader.endObject()
                                    loadedPages.add(strokes)
                                }
                                reader.endArray()
                            }
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
            }
        } ?: return false

        // Replace current document with the loaded one (single assignment, minimal churn)
        pages.clear()
        pages.addAll(loadedPages)
        currentPageIndex = 0.coerceAtMost(pages.size - 1).coerceAtLeast(0)
        drawingView.setStrokes(pages.getOrNull(currentPageIndex) ?: mutableListOf())
        updatePageNumber()
        return true
    }





    fun nextPage() {
        // Always clear selection/transform state BEFORE snapshotting or page swap,
        // so no selected object from this page can be acted on after we leave it.
        drawingView.clearSelectionState()

        // Persist current page strokes
        pages[currentPageIndex] = drawingView.getStrokes().map { s ->
            val p = android.graphics.Path(s.path)
            val paint = android.graphics.Paint(s.paint) // preserves ARGB + width
            Stroke(p, paint)
        }.toMutableList()

        // Advance or create a new page
        if (currentPageIndex < pages.size - 1) {
            currentPageIndex++
        } else {
            pages.add(mutableListOf())
            currentPageIndex = pages.size - 1
        }

        // Load target page strokes; setStrokes() already hard-resets selection + indices
        drawingView.setStrokes(pages[currentPageIndex])

        updatePageNumber()
    }

    fun previousPage() {
        drawingView.clearSelectionState()
        // Save current strokes
        pages[currentPageIndex] = drawingView.getStrokes().map { s ->
            val p = android.graphics.Path(s.path)
            val paint = android.graphics.Paint(s.paint) // preserves ARGB + width
            Stroke(p, paint)
        }.toMutableList()

        if (currentPageIndex > 0) {
            currentPageIndex--
            drawingView.setStrokes(pages[currentPageIndex])
        }
        updatePageNumber()
    }

    private fun updatePageNumber() {
        val tvPageNumber = findViewById<TextView>(R.id.tv_page_number)
        tvPageNumber.text = getString(R.string.page_number, currentPageIndex + 1, pages.size)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }


    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_save -> {
                if (currentFileName == null) {
                    showSaveDialog()
                } else {
                    saveToFile(currentFileName!!)
                }
                true
            }
            R.id.action_load -> {
                showLoadDialog()
                true
            }
            R.id.action_delete_page -> {
                deleteCurrentPage()
                true
            }
            R.id.action_delete_file -> {
                showDeleteFileDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showDeleteFileDialog() {
        val files = filesDir.list() ?: emptyArray()   // 👈 no filter here
        if (files.isEmpty()) {
            Toast.makeText(this, "No saved files to delete", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Delete Saved File")
            .setItems(files) { _, which ->
                val name = files[which]
                AlertDialog.Builder(this)
                    .setTitle("Confirm Delete")
                    .setMessage("Are you sure you want to delete $name?")
                    .setPositiveButton("Delete") { _, _ ->
                        val file = File(filesDir, name)
                        if (file.exists() && file.delete()) {
                            Toast.makeText(this, "Deleted $name", Toast.LENGTH_SHORT).show()
                            if (currentFileName == name) currentFileName = null
                        } else {
                            Toast.makeText(this, "Failed to delete $name", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .show()
    }

    private fun deleteCurrentPage() {
        if (pages.isEmpty()) return

        AlertDialog.Builder(this)
            .setTitle("Delete Page")
            .setMessage("Are you sure you want to delete this page?")
            .setPositiveButton("Delete") { _, _ ->
                pages.removeAt(currentPageIndex)

                // Adjust currentPageIndex
                if (currentPageIndex >= pages.size) {
                    currentPageIndex = max(0, pages.size - 1)
                }

                // If no pages remain, add a blank one
                if (pages.isEmpty()) {
                    pages.add(mutableListOf())
                    currentPageIndex = 0
                }

                drawingView.setStrokes(pages[currentPageIndex])
                updatePageNumber()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }


    // 2) ADD these fields inside class MainActivity
    private val autoSaveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val autoSaveInterval = 5 * 60 * 1000L // 5 minutes
// private val autoSaveInterval = 10 * 1000L // for testing

    private val saveExecutor = Executors.newSingleThreadExecutor()
    private val isSaving = AtomicBoolean(false)
    private val AUTO_SERIALIZE_SAMPLE_STEP_PX = 6f

    // 3) ADD — snapshot helpers inside class MainActivity
    private fun deepCopyStroke(s: Stroke): Stroke = Stroke(Path(s.path), Paint(s.paint))

    private fun snapshotPageBlocking(index: Int): List<Stroke> {
        val ref = AtomicReference<List<Stroke>>(emptyList())
        val latch = CountDownLatch(1)
        runOnUiThread {
            val src: List<Stroke> = if (index == currentPageIndex) {
                drawingView.getStrokes()
            } else {
                pages.getOrNull(index) ?: emptyList()
            }
            ref.set(src.map { deepCopyStroke(it) })
            latch.countDown()
        }
        latch.await()
        return ref.get()
    }

    private fun getPageCountBlocking(): Int {
        val ref = AtomicReference(0)
        val latch = CountDownLatch(1)
        runOnUiThread {
            ref.set(pages.size)
            latch.countDown()
        }
        latch.await()
        return ref.get()
    }

    /** Streaming write of a single stroke with a custom sampling step (for autosave). */
    private fun writeStroke(writer: JsonWriter, s: Stroke, sampleStep: Float) {
        writer.beginObject()

        // Paint
        writer.name("color").value(s.paint.color)
        writer.name("width").value(s.paint.strokeWidth.toDouble())
        writer.name("alpha").value(s.paint.alpha)

        // Geometry
        writer.name("points")
        writer.beginArray()
        val pm = android.graphics.PathMeasure(s.path, false)
        val pos = FloatArray(2)
        var length = pm.length
        while (true) {
            var distance = 0f
            while (distance <= length) {
                pm.getPosTan(distance, pos, null)
                writer.beginArray()
                writer.value(pos[0].toDouble())
                writer.value(pos[1].toDouble())
                writer.endArray()
                distance += sampleStep
            }
            if (length > 0f) {
                pm.getPosTan(length, pos, null)
                writer.beginArray()
                writer.value(pos[0].toDouble())
                writer.value(pos[1].toDouble())
                writer.endArray()
            }
            if (!pm.nextContour()) break
            length = pm.length
        }
        writer.endArray()

        writer.endObject()
    }

    /** Page-by-page paged save (no giant snapshot). */
    private fun saveToFilePaged(fileName: String, sampleStep: Float = AUTO_SERIALIZE_SAMPLE_STEP_PX): Boolean {
        val safeName = if (fileName.endsWith(".json")) fileName else "$fileName.json"
        val finalFile = java.io.File(filesDir, safeName)
        val tmpFile = java.io.File(filesDir, "$safeName.tmp")

        try {
            tmpFile.outputStream().use { raw ->
                GZIPOutputStream(BufferedOutputStream(raw)).use { gz ->
                    JsonWriter(OutputStreamWriter(gz, Charsets.UTF_8)).use { writer ->
                        writer.setIndent("") // compact
                        writer.beginObject()
                        writer.name("version").value(1)

                        val pageCount = getPageCountBlocking()
                        writer.name("pageCount").value(pageCount)
                        writer.name("pages")
                        writer.beginArray()

                        for (i in 0 until pageCount) {
                            // Clone ONE page on main, write it, then release it — keeps memory flat.
                            val pageCopy: List<Stroke> = snapshotPageBlocking(i)

                            writer.beginObject()
                            writer.name("strokes")
                            writer.beginArray()
                            for (s in pageCopy) writeStroke(writer, s, sampleStep)
                            writer.endArray()
                            writer.endObject()
                        }

                        writer.endArray()
                        writer.endObject()
                    }
                }
            }

            if (!tmpFile.renameTo(finalFile)) {
                tmpFile.copyTo(finalFile, overwrite = true)
                tmpFile.delete()
            }
            return true
        } catch (e: Throwable) {
            // Best effort cleanup on failure
            runCatching { tmpFile.delete() }
            throw e
        }
    }


    private fun snapshotAllPagesForSave(): List<List<Stroke>> {
        // Ensure current page is synced first (deep copy to break shared refs)
        pages[currentPageIndex] = drawingView.getStrokes().map { deepCopyStroke(it) }.toMutableList()

        // Return an immutable snapshot (deep copy of every page)
        return pages.map { page -> page.map { deepCopyStroke(it) } }
    }


    // 4) REPLACE your existing autoSaveRunnable block with this version
    private val autoSaveRunnable = object : Runnable {
        override fun run() {
            val name = currentFileName
            if (name.isNullOrBlank()) {
                autoSaveHandler.postDelayed(this, autoSaveInterval)
                return
            }

            if (isSaving.compareAndSet(false, true)) {
                saveExecutor.execute {
                    var ok = false
                    var err: String? = null
                    try {
                        ok = saveToFilePaged(name) // paged, low-memory, gzipped, streamed
                    } catch (t: Throwable) {
                        err = t.message ?: t.toString()
                    } finally {
                        isSaving.set(false)
                    }

                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            if (ok) {
                                Toast.makeText(this@MainActivity, "Auto-saved $name", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this@MainActivity, "Auto-save failed: ${err ?: "Unknown error"}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
            autoSaveHandler.postDelayed(this, autoSaveInterval)
        }
    }


    // 5) ADD — streaming + gzip writer that saves from a provided snapshot (no UI state access)
    private fun saveToFileSnapshot(fileName: String, pagesSnapshot: List<List<Stroke>>): Boolean {
        val safeName = if (fileName.endsWith(".json")) fileName else "$fileName.json"

        // Write to a temp file and atomically replace the final file to avoid corruption
        val finalFile = File(filesDir, safeName)
        val tmpFile = File(filesDir, "$safeName.tmp")

        try {
            tmpFile.outputStream().use { raw ->
                GZIPOutputStream(BufferedOutputStream(raw)).use { gz ->
                    JsonWriter(OutputStreamWriter(gz, Charsets.UTF_8)).use { writer ->
                        writer.setIndent("") // compact
                        writer.beginObject()
                        writer.name("version").value(1)
                        writer.name("pageCount").value(pagesSnapshot.size)
                        writer.name("pages")
                        writer.beginArray()

                        for (page in pagesSnapshot) {
                            writer.beginObject()
                            writer.name("strokes")
                            writer.beginArray()
                            for (s in page) {
                                writeStroke(writer, s) // uses your ARGB-preserving helper
                            }
                            writer.endArray()
                            writer.endObject()
                        }

                        writer.endArray()
                        writer.endObject()
                    }
                }
            }

            // Atomic replace
            if (tmpFile.renameTo(finalFile)) {
                return true
            } else {
                // If rename fails, try manual copy-overwrite fallback
                tmpFile.copyTo(finalFile, overwrite = true)
                tmpFile.delete()
                return true
            }
        } catch (e: IOException) {
            // Clean up temp file on failure
            tmpFile.delete()
            throw e
        }
    }


    override fun onResume() {
        super.onResume()
        autoSaveHandler.postDelayed(autoSaveRunnable, autoSaveInterval)
    }

    override fun onPause() {
        super.onPause()
        autoSaveHandler.removeCallbacks(autoSaveRunnable)
    }

    override fun onDestroy() {
        autoSaveHandler.removeCallbacks(autoSaveRunnable)
        editorData?.editor?.close()
        contentPart?.close()
        contentPackage?.close()
        autoSaveHandler.removeCallbacksAndMessages(null)
        saveExecutor.shutdown()
        saveExecutor.shutdownNow()
        super.onDestroy()
    }


    private val gson = Gson()
    private val saveFileName = "whiteboard.json"

    private fun showSaveDialog() {
        val input = EditText(this)
        input.hint = "Enter file name"

        AlertDialog.Builder(this)
            .setTitle("Save Whiteboard")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().ifBlank { "whiteboard" }
                currentFileName = name
                saveToFile(name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    private fun showLoadDialog() {
        val files = filesDir.list() ?: emptyArray()
        if (files.isEmpty()) {
            Toast.makeText(this, "No saved files", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Load Whiteboard")
            .setItems(files) { _, which ->
                val name = files[which]
                loadFromFile(name)
                currentFileName = name
            }
            .show()
    }

    // REPLACE your existing saveToFile(...) with this streaming + gzip version.
// It uses the same on-disk "filename.json" but writes compressed, streaming JSON.
    private fun saveToFile(fileName: String) {
        // Normalize filename (keep your .json convention)
        val safeName = if (fileName.endsWith(".json")) fileName else "$fileName.json"

        // Ensure current page is synced back to pages[]
        pages[currentPageIndex] = drawingView.getStrokes().toMutableList()

        // Stream out a VERSIONED document, page-by-page, stroke-by-stroke (low memory).
        openFileOutput(safeName, MODE_PRIVATE).use { raw ->
            GZIPOutputStream(BufferedOutputStream(raw)).use { gz ->
                JsonWriter(OutputStreamWriter(gz, Charsets.UTF_8)).use { writer ->
                    writer.setIndent("") // compact
                    writer.beginObject()
                    writer.name("version").value(1)
                    writer.name("pageCount").value(pages.size)
                    writer.name("pages")
                    writer.beginArray()

                    for (page in pages) {
                        writer.beginObject()
                        writer.name("strokes")
                        writer.beginArray()
                        for (s in page) {
                            // <-- Uses your provided helper
                            writeStroke(writer, s)
                        }
                        writer.endArray()
                        writer.endObject()
                    }

                    writer.endArray()
                    writer.endObject()
                }
            }
        }

        Toast.makeText(this, "Saved to $safeName", Toast.LENGTH_SHORT).show()
    }


    // REPLACE your existing loadFromFile(...) with this streaming + gzip loader.
// It first tries to read the new VERSIONED+GZIP format. If that fails, it falls
// back to your legacy plain-JSON (List<List<StrokeData>>) loader for compatibility.
    // REPLACE your existing loadFromFile(...) with this version
    private fun loadFromFile(fileName: String) {
        val loadedPages = mutableListOf<MutableList<Stroke>>()

        // --- Attempt: new streamed + gzip, versioned format ---
        val versionedOk = try {
            openFileInput(fileName).use { raw ->
                // If the file isn't gzipped (legacy .json), GZIPInputStream will throw.
                val gzStream = try {
                    GZIPInputStream(BufferedInputStream(raw))
                } catch (_: ZipException) {
                    // Not gzipped — likely legacy format; fall back after this block.
                    return@use null
                } ?: return@use null

                JsonReader(InputStreamReader(gzStream, Charsets.UTF_8)).use { reader ->
                    var version = 1
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "version" -> version = reader.nextInt()
                            "pageCount" -> reader.nextInt() // not required for streaming
                            "pages" -> {
                                reader.beginArray()
                                while (reader.hasNext()) {
                                    val strokes = mutableListOf<Stroke>()
                                    reader.beginObject()
                                    while (reader.hasNext()) {
                                        when (reader.nextName()) {
                                            "strokes" -> {
                                                reader.beginArray()
                                                while (reader.hasNext()) {
                                                    // readStroke(reader) already reconstructs color/width/alpha
                                                    // but we deep-copy & normalize to avoid any shared Paint/Path surprises
                                                    val s = readStroke(reader)
                                                    strokes.add(deepCopyAndNormalizeStroke(s))
                                                }
                                                reader.endArray()
                                            }
                                            else -> reader.skipValue()
                                        }
                                    }
                                    reader.endObject()
                                    loadedPages.add(strokes)
                                }
                                reader.endArray()
                            }
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
            }
            true
        } catch (_: Exception) {
            false
        }

        if (!versionedOk) {
            // --- Fallback: legacy plain JSON format (List<List<StrokeData>>) ---
            try {
                val json = openFileInput(fileName).bufferedReader().use { it.readText() }
                val type = object : TypeToken<MutableList<List<StrokeData>>>() {}.type
                val dataPages: MutableList<List<StrokeData>> = gson.fromJson(json, type)

                loadedPages.clear()
                loadedPages.addAll(
                    dataPages.map { pageData ->
                        // Convert each StrokeData to Stroke, then deep-copy & normalize to ensure
                        // color/width/alpha are applied and not later overridden by shared Paint.
                        pageData.map { sd ->
                            val s = sd.toStroke()
                            deepCopyAndNormalizeStroke(s)
                        }.toMutableList()
                    }
                )
            } catch (e: Exception) {
                Toast.makeText(this, "Load failed: ${e.message}", Toast.LENGTH_LONG).show()
                return
            }
        }

        // Swap in the loaded document atomically and refresh UI
        pages.clear()
        pages.addAll(loadedPages.map { normalizePage(it) }) // final safety pass
        currentPageIndex = 0.coerceAtMost(pages.size - 1).coerceAtLeast(0)
        drawingView.setStrokes(pages.getOrNull(currentPageIndex) ?: mutableListOf())
        updatePageNumber()

        Toast.makeText(this, "Loaded $fileName", Toast.LENGTH_SHORT).show()
    }



    private fun saveWhiteboard() {
        pages[currentPageIndex] = drawingView.getStrokes().toMutableList()

        val dataPages = pages.map { page -> page.map { it.toStrokeData() } }
        val json = gson.toJson(dataPages)
        val file = File(filesDir, saveFileName)
        file.writeText(json)
    }

    private fun loadWhiteboard() {
        val file = File(filesDir, saveFileName)
        if (file.exists()) {
            val json = file.readText()
            val type = object : TypeToken<MutableList<List<StrokeData>>>() {}.type
            val dataPages: MutableList<List<StrokeData>> = gson.fromJson(json, type)

            pages.clear()
            pages.addAll(dataPages.map { page -> page.map { it.toStroke() }.toMutableList() })

            currentPageIndex = 0
            drawingView.setStrokes(pages[currentPageIndex].toMutableList())
            updatePageNumber()
        }
    }
}