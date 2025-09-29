package com.example.whiteboard

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import android.util.Base64
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.cardview.widget.CardView
import androidx.core.content.FileProvider
import androidx.core.graphics.toColorInt
import com.example.whiteboard.IInkApplication.engine
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.myscript.iink.ContentPackage
import com.myscript.iink.ContentPart
import com.myscript.iink.uireferenceimplementation.*
import java.io.*
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max


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

    private class ThemeAdapter(
        private val context: android.content.Context,
        private val names: Array<String>,
        private val bgColors: IntArray,
    ) : android.widget.BaseAdapter() {

        override fun getCount(): Int = names.size
        override fun getItem(position: Int): Any = names[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
            val view = convertView ?: android.view.LayoutInflater.from(context)
                .inflate(R.layout.item_theme_preview, parent, false)

            val bgPreview = view.findViewById<View>(R.id.bg_preview)
            val nameView = view.findViewById<TextView>(R.id.theme_name)

            bgPreview.setBackgroundColor(bgColors[position])
            nameView.text = names[position]

            return view
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        autoSaveHandler.postDelayed(autoSaveRunnable, autoSaveInterval)
        installCrashEmergencySaver()


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
            popupView.findViewById<View>(R.id.shape_dotted).setOnClickListener {
                drawingView.setShapeMode(ShapeType.DOTTED)
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
                    font-size: 30px;
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
            override fun onDoubleTap(e: MotionEvent): Boolean {
                container.removeView(webView)
                return true
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

    // INSERT inside MainActivity (e.g., below concatLatex)
    private fun normalizeLatexForDesmos(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var s = raw.trim()
        // Desmos understands \frac, not \dfrac (which it sees as a symbol name "dfrac")
        s = s.replace(Regex("""\\dfrac\b"""), """\\frac""")

        return s
    }


    private fun sendLatexToDesmos(latex: String?) {
        val normalized_latex = normalizeLatexForDesmos(latex)
        Log.d("desmos", "sendLatexToDesmos: $normalized_latex")
        val jsLatex = org.json.JSONObject.quote(normalized_latex) // ensures proper escaping
        desmosExprCounter ++
        val exprId = "expr$desmosExprCounter"
        desmosWebView.evaluateJavascript(
            "calculator.setExpression({ id: '$exprId', latex: $jsLatex });",
            null
        )
    }



    fun nextPage() {
        // Always clear selection/transform state BEFORE snapshotting or page swap,
        // so no selected object from this page can be acted on after we leave it.
        drawingView.clearSelectionState()

        // Persist current page strokes
        pages[currentPageIndex] = drawingView.getStrokes().toMutableList()

        // Advance or create a new page
        if (currentPageIndex < pages.size - 1) {
            currentPageIndex++
        } else {
            pages.add(mutableListOf())
            currentPageIndex = pages.size - 1
        }

        // Load target page strokes; setStrokes() already hard-resets selection + indices
        drawingView.setStrokes(pages[currentPageIndex])
        drawingView.clearUndoOps()
        updatePageNumber()
    }

    fun previousPage() {
        drawingView.clearSelectionState()


        // Save current strokes
        pages[currentPageIndex] = drawingView.getStrokes().toMutableList()

        if (currentPageIndex > 0) {
            currentPageIndex--
            drawingView.setStrokes(pages[currentPageIndex])
        }
        drawingView.clearUndoOps()
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

    var container_color = "#3A506B"

    fun Int.darken(factor: Float = 0.8f): Int {
        val a = Color.alpha(this)
        val r = (Color.red(this) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(this) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(this) * factor).toInt().coerceIn(0, 255)
        return Color.argb(a, r, g, b)
    }

    var card_color = "#2c3d52"

    var toolbar_color = "#E0F0F6"

    fun Int.toHexColorString(includeAlpha: Boolean = false): String {
        return if (includeAlpha) {
            String.format("#%08X", this)  // #AARRGGBB
        } else {
            String.format("#%06X", 0xFFFFFF and this)  // #RRGGBB
        }
    }

    private fun applyThemeColors(bgColor: Int, toolbarColor: Int) {

        container_color = bgColor.toHexColorString()
        toolbar_color = toolbarColor.toHexColorString()
        card_color = bgColor.darken(0.8f).toHexColorString()
        // Surfaces
        val workspace = findViewById<androidx.constraintlayout.widget.ConstraintLayout>(R.id.workspace_row)
        val container = findViewById<FrameLayout>(R.id.drawing_container)
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)

        workspace.setBackgroundColor(bgColor)
        container.setBackgroundColor(bgColor)
        toolbar.setBackgroundColor(toolbarColor)

        // Desmos card + its WebView
        val hex = bgColor.toHexColorString()
        desmosCard.setCardBackgroundColor(bgColor)
        desmosWebView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }


    @OptIn(ExperimentalStdlibApi::class)
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
            R.id.action_share_qr -> {
                showQrCodedialog()
                true
            }
            R.id.action_theme -> {
                val names = resources.getStringArray(R.array.theme_names)
                val bgColors = resources.obtainTypedArray(R.array.theme_bg_colors)
                val tbColors = resources.obtainTypedArray(R.array.theme_tb_colors)

                val bgArray = IntArray(names.size) { i -> bgColors.getColor(i, android.graphics.Color.WHITE) }
                val tbArray = IntArray(names.size) { i -> tbColors.getColor(i, android.graphics.Color.LTGRAY) }

                val adapter = object : android.widget.BaseAdapter() {
                    override fun getCount() = names.size
                    override fun getItem(position: Int) = names[position]
                    override fun getItemId(position: Int) = position.toLong()
                    override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                        val v = convertView ?: layoutInflater.inflate(R.layout.item_theme_preview, parent, false)
                        v.findViewById<View>(R.id.bg_preview).setBackgroundColor(bgArray[position])
                        v.findViewById<TextView>(R.id.theme_name).text = names[position]
                        return v
                    }
                }

                AlertDialog.Builder(this)
                    .setTitle("Choose Theme")
                    .setAdapter(adapter) { _, which ->
                        applyThemeColors(bgArray[which], tbArray[which])
                    }
                    .setOnDismissListener {
                        bgColors.recycle()
                        tbColors.recycle()
                    }
                    .show()

                true
            }
            R.id.action_open_new_file -> {
                openNewFile()
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

    private fun openNewFile() {
        // Clear current state
        pages.clear()
        pages.add(mutableListOf()) // fresh blank page
        currentPageIndex = 0
        currentFileName = null

        // Reset drawing view
        drawingView.setStrokes(pages[currentPageIndex])
        updatePageNumber()

        Toast.makeText(this, "Opened new blank file", Toast.LENGTH_SHORT).show()
    }


    private fun showDeleteFileDialog() {
        // List files from the same public location where we save
        val names: List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = contentResolver
            val projection = arrayOf(
                MediaColumns.DISPLAY_NAME,
                MediaColumns.RELATIVE_PATH,
                MediaColumns.MIME_TYPE
            )
            val selection = "${MediaColumns.MIME_TYPE}=?"
            val args = arrayOf("application/json")

            val out = mutableListOf<String>()
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                "${MediaStore.Downloads.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndexOrThrow(MediaColumns.DISPLAY_NAME)
                val relIdx  = cursor.getColumnIndexOrThrow(MediaColumns.RELATIVE_PATH)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx) ?: continue
                    val rel  = cursor.getString(relIdx) ?: ""
                    if (rel.contains("Documents/Whiteboard", ignoreCase = true)
                        && name.endsWith(".json", ignoreCase = true)
                    ) {
                        out.add(name)
                    }
                }
            }
            out
        } else {
            // Pre-Q: list from public Documents/Whiteboard folder on disk
            val dir = legacyPublicDir()
            dir.listFiles { f -> f.isFile && f.extension.equals("json", ignoreCase = true) }
                ?.sortedByDescending { it.lastModified() }
                ?.map { it.name }
                ?: emptyList()
        }

        if (names.isEmpty()) {
            Toast.makeText(this, "No saved files to delete", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Delete Saved File")
            .setItems(names.toTypedArray()) { _, which ->
                val targetName = names[which]

                AlertDialog.Builder(this)
                    .setTitle("Confirm Delete")
                    .setMessage("Are you sure you want to delete $targetName?")
                    .setPositiveButton("Delete") { _, _ ->
                        var deleted = false

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            // Find the matching row in Downloads scoped to Documents/Whiteboard and delete it
                            val resolver = contentResolver
                            val projection = arrayOf(
                                MediaColumns._ID,
                                MediaColumns.DISPLAY_NAME,
                                MediaColumns.RELATIVE_PATH
                            )
                            val selection = "${MediaColumns.DISPLAY_NAME}=?"
                            val args = arrayOf(targetName)

                            resolver.query(
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                projection,
                                selection,
                                args,
                                null
                            )?.use { cursor ->
                                val idIdx = cursor.getColumnIndexOrThrow(MediaColumns._ID)
                                val nameIdx = cursor.getColumnIndexOrThrow(MediaColumns.DISPLAY_NAME)
                                val relIdx  = cursor.getColumnIndexOrThrow(MediaColumns.RELATIVE_PATH)
                                while (cursor.moveToNext()) {
                                    val name = cursor.getString(nameIdx) ?: continue
                                    val rel  = cursor.getString(relIdx) ?: ""
                                    if (name == targetName && rel.contains("Documents/Whiteboard", ignoreCase = true)) {
                                        val id = cursor.getLong(idIdx)
                                        val uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
                                        deleted = (resolver.delete(uri, null, null) > 0)
                                        if (deleted) break
                                    }
                                }
                            }
                        } else {
                            // Pre-Q: delete the physical file
                            val f = File(legacyPublicDir(), targetName)
                            deleted = f.exists() && f.delete()
                        }

                        if (deleted) {
                            Toast.makeText(this, "Deleted $targetName", Toast.LENGTH_SHORT).show()
                            if (currentFileName == targetName) currentFileName = null
                        } else {
                            Toast.makeText(this, "Failed to delete $targetName", Toast.LENGTH_SHORT).show()
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

    private val saveExecutor = Executors.newSingleThreadExecutor()
    private val isSaving = AtomicBoolean(false)

    private val autoSaveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val autoSaveInterval = 5 * 60 * 1000L // 5 minutes
//    private val autoSaveInterval = 3 * 60 * 1000L // 3 minutes
//    private val autoSaveInterval = 10 * 1000L // 10 seconds

    private val autoSaveRunnable = object : Runnable {
        override fun run() {
            val name = currentFileName
            if (name.isNullOrBlank()) {
                autoSaveHandler.postDelayed(this, autoSaveInterval)
                return
            }

            val snapshot = snapshotAllPagesForSave()

            if (isSaving.compareAndSet(false, true)) {
                saveExecutor.execute {
                    var ok = false
                    var err: String? = null
                    try {
                        try {
                            saveToPublicSnapshot(name, snapshot)
                            ok = true
                        } catch (e: Exception) {
                            err = e.message
                            ok = false
                        }
                    } catch (e: Exception) {
                        err = e.message
                    } finally {
                        isSaving.set(false)
                    }

                    // Send text to screen
                    runOnUiThread {
                        if (ok) {
                            Toast.makeText(this@MainActivity, "Auto-saved $name", Toast.LENGTH_SHORT).show()
                        } else {
                            val msg = err ?: "Unknown error"
                            Toast.makeText(this@MainActivity, "Auto-save failed: $msg", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }

            // Schedule next auto-save
            autoSaveHandler.postDelayed(this, autoSaveInterval)
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
        galleryServer?.stop()
        galleryServer = null
        super.onDestroy()
    }

    // INSERT: helper to get public Documents/Whiteboard directory on pre-Q devices
    private fun legacyPublicDir(): File {
        val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val dir = File(base, "Whiteboard")
        if (!dir.exists()) dir.mkdirs()
        return dir
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
    // REPLACE: showLoadDialog() to enumerate files from public Documents/Whiteboard
    private fun showLoadDialog() {
        val names = listPublicJsonFiles()
        if (names.isEmpty()) {
            Toast.makeText(this, "No saved files", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Load Whiteboard")
            .setItems(names.toTypedArray()) { _, which ->
                val name = names[which]
                loadFromFile(name)          // this will call openFromPublic(...) first
                currentFileName = name
            }
            .show()
    }
    // INSERT: generate a timestamped recovery file name, e.g., _recovery_2025-09-16_14-05-22.json
    private fun makeRecoveryFileName(base: String? = currentFileName): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        val ts = sdf.format(Date())
        val safe = resolveFinalSaveName(base ?: "whiteboard")
        val bare = safe.removeSuffix(".json")
        return "${bare}_recovery_$ts.json"
    }

    /**
     * Synchronously save a best-effort recovery snapshot to public storage.
     * - Avoids UI (no toasts).
     * - Catches *all* throwables so it never throws from a crash path.
     */
    private fun saveEmergencyRecoverySync() {
        try {
            // 1) Ensure current page is reflected in pages[]
            pages[currentPageIndex] = drawingView.getStrokes().toMutableList()

            // 2) Build snapshot (List<List<StrokeData>>) right now
            val snapshot = pages.map { page -> page.map { it.toStrokeData() } }

            // 3) Use a distinctive, timestamped recovery name
            val recoveryName = makeRecoveryFileName()

            // 4) Write directly using the same public saver you already have
            //    (This is synchronous; do NOT use background executors here.)
            saveToPublicSnapshot(recoveryName, snapshot)

            // Optionally remember it as the "current file" if you want to continue on next launch:
            // currentFileName = recoveryName
        } catch (_: Throwable) {
            // swallow — never throw during crash handling
        }
    }

    /**
     * Install a default UncaughtExceptionHandler that writes a recovery file
     * to Documents/Whiteboard *before* letting the app die.
     */
    private fun installCrashEmergencySaver() {
        val prior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            // Best effort: try to persist current work
            saveEmergencyRecoverySync()

            // Delegate to the previous handler so normal crash flow (logs/ANR dialogs) continues
            try {
                prior?.uncaughtException(thread, ex)
            } catch (_: Throwable) {
                // If prior handler itself fails, rethrow to kill the process
                throw ex
            }
        }
    }
    // INSERT: below your existing helper methods (e.g., under readPagesStreaming)

    private val PUBLIC_SUBDIR = Environment.DIRECTORY_DOCUMENTS + "/Whiteboard"

    /**
     * Save the given snapshot to public shared storage (Documents/Whiteboard) via MediaStore.
     * Returns the Uri on success, or null on failure.
     */
    // REPLACE the entire method
    // REPLACE the entire method
    private fun saveToPublicSnapshot(fileName: String, snapshot: List<List<StrokeData>>): Uri {
        val finalName = resolveFinalSaveName(fileName)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ : Use MediaStore with RELATIVE_PATH (no DATA required)
            val resolver = contentResolver
            val collectionUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val values = ContentValues().apply {
                put(MediaColumns.DISPLAY_NAME, finalName)
                put(MediaColumns.MIME_TYPE, "application/json")
                put(MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/Whiteboard")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }

            val uri = resolver.insert(collectionUri, values)
                ?: throw IllegalStateException("MediaStore insert() returned null")

            try {
                resolver.openOutputStream(uri, "w")?.use { os ->
                    OutputStreamWriter(BufferedOutputStream(os), Charsets.UTF_8).use { osw ->
                        com.google.gson.stream.JsonWriter(osw).use { writer ->
                            writer.isLenient = false
                            writer.beginArray()
                            snapshot.forEach { page ->
                                writer.beginArray()
                                page.forEach { sd -> gson.toJson(sd, StrokeData::class.java, writer) }
                                writer.endArray()
                            }
                            writer.endArray()
                            writer.flush()
                        }
                    }
                } ?: throw IllegalStateException("openOutputStream() returned null")

                val ready = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                resolver.update(uri, ready, null, null)
                return uri
            } catch (t: Throwable) {
                try { resolver.delete(uri, null, null) } catch (_: Throwable) {}
                throw t
            }
        } else {
            // Android 9 and below: write directly into public Documents/Whiteboard with File APIs
            // (Requires WRITE_EXTERNAL_STORAGE permission on API < 29)
            val outFile = File(legacyPublicDir(), finalName)

            FileOutputStream(outFile).use { fos ->
                OutputStreamWriter(BufferedOutputStream(fos), Charsets.UTF_8).use { osw ->
                    com.google.gson.stream.JsonWriter(osw).use { writer ->
                        writer.isLenient = false
                        writer.beginArray()
                        snapshot.forEach { page ->
                            writer.beginArray()
                            page.forEach { sd -> gson.toJson(sd, StrokeData::class.java, writer) }
                            writer.endArray()
                        }
                        writer.endArray()
                        writer.flush()
                    }
                }
            }

            // Make it visible to file explorers immediately
            MediaScannerConnection.scanFile(
                this,
                arrayOf(outFile.absolutePath),
                arrayOf("application/json"),
                null
            )

            return Uri.fromFile(outFile)
        }
    }

    /**
     * Try to open a file saved to public shared storage (Documents/Whiteboard).
     * Returns an InputStream or null if not found.
     */
    // REPLACE the entire method
    // REPLACE the entire method
    private fun openFromPublic(fileName: String): InputStream? {
        val finalName = resolveFinalSaveName(fileName)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Query MediaStore (Downloads) on Android 10+
            val resolver = contentResolver
            val projection = arrayOf(
                MediaColumns._ID,
                MediaColumns.DISPLAY_NAME,
                MediaColumns.RELATIVE_PATH
            )
            val selection = "${MediaColumns.DISPLAY_NAME}=?"
            val args = arrayOf(finalName)

            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                "${MediaStore.Downloads.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaColumns._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(MediaColumns.DISPLAY_NAME)
                val relIdx = cursor.getColumnIndexOrThrow(MediaColumns.RELATIVE_PATH)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx) ?: continue
                    val rel = cursor.getString(relIdx) ?: ""
                    if (name == finalName && rel.contains("Documents/Whiteboard")) {
                        val id = cursor.getLong(idIdx)
                        val uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
                        return contentResolver.openInputStream(uri)
                    }
                }
                null
            }
        } else {
            // Direct file path on pre-Q
            val f = File(legacyPublicDir(), finalName)
            if (f.exists() && f.isFile) BufferedInputStream(f.inputStream()) else null
        }
    }

    // INSERT: lists all *.json saved files in public Documents/Whiteboard
    private fun listPublicJsonFiles(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = contentResolver
            val projection = arrayOf(
                MediaColumns.DISPLAY_NAME,
                MediaColumns.RELATIVE_PATH,
                MediaColumns.MIME_TYPE
            )
            // Filter by MIME to keep the cursor small; we still check path & extension below.
            val selection = "${MediaColumns.MIME_TYPE}=?"
            val args = arrayOf("application/json")

            val out = mutableListOf<String>()
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                "${MediaStore.Downloads.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndexOrThrow(MediaColumns.DISPLAY_NAME)
                val relIdx  = cursor.getColumnIndexOrThrow(MediaColumns.RELATIVE_PATH)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx) ?: continue
                    val rel  = cursor.getString(relIdx) ?: ""
                    if (rel.contains("Documents/Whiteboard", ignoreCase = true)
                        && name.endsWith(".json", ignoreCase = true)
                    ) {
                        out.add(name)
                    }
                }
            }
            out
        } else {
            // Pre-Q: directly read from the public directory on disk
            val dir = legacyPublicDir()
            dir.listFiles { f -> f.isFile && f.extension.equals("json", ignoreCase = true) }
                ?.sortedByDescending { it.lastModified() }
                ?.map { it.name }
                ?: emptyList()
        }
    }


    private fun saveToFile(fileName: String) {
        Toast.makeText(this, "Saving file", Toast.LENGTH_SHORT).show()
        val name = fileName.ifBlank { return }
        val snapshot = snapshotAllPagesForSave()

        if (isSaving.compareAndSet(false, true)) {
            saveExecutor.execute {
                var ok = false
                var err: String? = null
                try {
                    try {
                        saveToPublicSnapshot(name, snapshot)
                        ok = true
                    } catch (e: Exception) {
                        err = e.message
                        ok = false
                    }
                } catch (e: Exception) {
                    err = e.message
                } finally {
                    isSaving.set(false)
                }
                runOnUiThread {
                    if (ok) {
                        Toast.makeText(
                            this,
                            "Saved to Documents/Whiteboard/${resolveFinalSaveName(name)}",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        val msg = err ?: "Unknown error"
                        Toast.makeText(this, "Save failed: $msg", Toast.LENGTH_LONG).show()
                    }
                }
            }
        } else {
            Toast.makeText(this, "A save is already in progress…", Toast.LENGTH_SHORT).show()
        }
    }
    // REPLACE the entire loadFromFile(fileName) with this version
    private fun loadFromFile(fileName: String) {
        val name = resolveFinalSaveName(fileName)

        saveExecutor.execute {
            var loadedPages: MutableList<MutableList<Stroke>>? = null
            var err: String? = null

            try {
                // 1) Try public shared storage first
                val publicIn = openFromPublic(name)
                if (publicIn != null) {
                    publicIn.use { input ->
                        JsonReader(BufferedReader(InputStreamReader(input, Charsets.UTF_8))).use { reader ->
                            loadedPages = readPagesStreaming(reader)
                        }
                    }
                } else {
                    // 2) Fallback to your existing internal files dir (legacy saves)
                    val resolved = resolveLoadName(name)
                    openFileInput(resolved).use { baseIn ->
                        JsonReader(BufferedReader(InputStreamReader(baseIn, Charsets.UTF_8))).use { reader ->
                            loadedPages = readPagesStreaming(reader)
                        }
                    }
                }
            } catch (e: Exception) {
                err = e.message
            }

            runOnUiThread {
                if (loadedPages != null) {
                    pages.clear()
                    pages.addAll(loadedPages!!)
                    currentPageIndex = 0
                    drawingView.setStrokes(pages[currentPageIndex].toMutableList())
                    updatePageNumber()
                    Toast.makeText(this, "Loaded $name", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Load failed: ${err ?: "Unknown error"}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun snapshotAllPagesForSave(): List<List<StrokeData>> {
        // Ensure current page's latest strokes are stored
        pages[currentPageIndex] = drawingView.getStrokes().toMutableList()

        // Convert to StrokeData on the main thread to avoid concurrent mutation issues
        return pages.map { page -> page.map { it.toStrokeData() } }
    }

    // REPLACE: write plain JSON via JsonWriter (streaming) to keep memory low; atomic with .tmp swap.
    private fun saveToFileSnapshot(fileName: String, snapshot: List<List<StrokeData>>): Boolean {
        val finalName = resolveFinalSaveName(fileName) // ensures ".json"
        val tmpName = "$finalName.tmp"

        // 1) Write to temp file (plain JSON, streaming)
        openFileOutput(tmpName, MODE_PRIVATE).use { fos ->
            OutputStreamWriter(BufferedOutputStream(fos), Charsets.UTF_8).use { osw ->
                com.google.gson.stream.JsonWriter(osw).use { writer ->
                    writer.isLenient = false
                    writer.beginArray() // pages [

                    snapshot.forEach { page ->
                        writer.beginArray() // strokes in page [
                        page.forEach { sd ->
                            // Stream each StrokeData object directly
                            gson.toJson(sd, StrokeData::class.java, writer)
                        }
                        writer.endArray()   // ]
                    }

                    writer.endArray()       // ]
                    writer.flush()
                }
            }
        }

        // 2) Replace old file atomically
        val tmpFile = java.io.File(filesDir, tmpName)
        val finalFile = java.io.File(filesDir, finalName)
        if (finalFile.exists()) finalFile.delete()
        if (!tmpFile.renameTo(finalFile)) {
            // Fallback copy if rename fails on some devices
            tmpFile.copyTo(finalFile, overwrite = true)
            tmpFile.delete()
        }
        return true
    }

    /** Always save compressed to reduce memory and file size. */
    // REPLACE: ensure we save plain JSON (no gzip), so the loader can read it directly.
    private fun resolveFinalSaveName(name: String): String {
        return when {
            name.endsWith(".json", ignoreCase = true) -> name
            else -> "$name.json"
        }
    }

    /** Pick an existing file to load; prefer compressed if present. */
    private fun resolveLoadName(name: String): String {
        val f1 = when {
            name.endsWith(".json.gz", ignoreCase = true) -> name
            name.endsWith(".json", ignoreCase = true) -> name
            else -> "$name.json.gz"
        }
        val gz = if (f1.endsWith(".gz", true)) f1 else "$f1.gz"
        return when {
            File(filesDir, gz).exists() -> gz
            File(filesDir, f1).exists() -> f1
            else -> name // last attempt; will throw if missing
        }
    }

    /** Detect gzip magic by reopening stream (we can't reset openFileInput). */
    private fun isGzipStream(base: InputStream): Boolean {
        // We cannot mark/reset the Android FileInputStream reliably;
        // Use magic number detection by separately reopening via helper.
        return try {
            val fis = base as? FileInputStream
            if (fis == null) false else {
                val fd = fis.fd
                // Can't seek FileInputStream directly; we simply return false here.
                // We'll instead detect by filename path using resolveLoadName().
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Reopen a fresh InputStream for the given file name. */
    private fun baseInReset(fileName: String): InputStream {
        return BufferedInputStream(openFileInput(fileName))
    }

    /** Streaming reader for pages -> strokes */
    private fun readPagesStreaming(reader: JsonReader): MutableList<MutableList<Stroke>> {
        val result = mutableListOf<MutableList<Stroke>>()
        reader.beginArray()
        while (reader.hasNext()) {
            // Each page: array of StrokeData
            val page = mutableListOf<Stroke>()
            reader.beginArray()
            while (reader.hasNext()) {
                val sd = gson.fromJson<StrokeData>(reader, StrokeData::class.java)
                page.add(sd.toStroke())
            }
            reader.endArray()
            result.add(page)
        }
        reader.endArray()
        return result
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

    private var galleryServer: TinyHttpServer? = null

    // REPLACE the whole function body of showQrCodedialog() in MainActivity.kt
    private fun showQrCodedialog() {
        // Build the gallery HTML once
        galleryServer?.stop()
        galleryServer = null
        val html = buildGalleryHtmlString()

        // Start a tiny HTTP server to host it
        val server = TinyHttpServer { html }
        server.start(preferredPort = 8765)
        galleryServer = server

        // Compute the local IP address (same Wi-Fi/LAN)
        val host = getLocalIpv4()
        if (host == null) {
            Toast.makeText(this, "No local network IP. Connect to Wi-Fi.", Toast.LENGTH_LONG).show()
            server.stop()
            return
        }
        val link = "http://$host:${server.port}/"

        // Build the dialog UI
        val padding = (16 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        val qrView = ImageView(this).apply {
            // Generate a smaller QR (360 px instead of 720)
            setImageBitmap(generateQrBitmap(link, size = 360))

            // Constrain the size in the dialog
            val qrSize = (200 * resources.displayMetrics.density).toInt() // ~200dp square
            layoutParams = LinearLayout.LayoutParams(qrSize, qrSize).apply {
                gravity = Gravity.CENTER
            }

            adjustViewBounds = true
        }

        val linkView = TextView(this).apply {
            text = link
            setTextIsSelectable(true)
            textSize = 16f
            setTextColor(Color.BLACK) // High-contrast; change to WHITE if your dialog is dark
            setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
        }

        val hintView = TextView(this).apply {
            text = "Make sure both devices are on the same Wi-Fi. Skibidi. Remember to take screenshots. This link stops working when you close the application."
            textSize = 12f
            setTextColor(0xFF666666.toInt())
            setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
        }

        container.addView(qrView)
        container.addView(linkView)
        container.addView(hintView)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Whiteboard Gallery Link")
            .setView(container)
            .setNegativeButton("Close", null)
            .create()

        dialog.show()
    }

    private class TinyHttpServer(private val htmlSupplier: () -> String) {
        private var serverThread: Thread? = null
        private var serverSocket: ServerSocket? = null
        private val running = AtomicBoolean(false)
        var port: Int = 8765
            private set

        fun start(preferredPort: Int = 8765) {
            if (running.get()) return
            running.set(true)
            serverThread = Thread {
                try {
                    // Try preferredPort..preferredPort+20 to avoid collisions
                    var ss: ServerSocket? = null
                    var p = preferredPort
                    while (p < preferredPort + 20 && ss == null) {
                        try {
                            ss = ServerSocket(p)
                        } catch (_: Exception) { p++ }
                    }
                    if (ss == null) throw RuntimeException("No free port")
                    serverSocket = ss
                    port = p

                    // Accept loop
                    while (running.get()) {
                        val client = try { ss.accept() } catch (_: Exception) { null } ?: continue
                        handleClient(client)
                    }
                } catch (_: Exception) {
                    // Silent fail; dialog will be closed by caller if needed
                } finally {
                    try { serverSocket?.close() } catch (_: Exception) {}
                }
            }.apply { isDaemon = true; start() }
        }

        private fun handleClient(client: Socket) {
            client.use { sock ->
                sock.getInputStream().bufferedReader().use { reader ->
                    // Read the first request line only; discard rest
                    val requestLine = reader.readLine() ?: return
                    // Simple GET only; always return the gallery
                    val body = htmlSupplier()
                    val out = sock.getOutputStream().buffered()
                    val headers = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("Content-Type: text/html; charset=utf-8\r\n")
                        append("Content-Length: ${body.toByteArray().size}\r\n")
                        append("Cache-Control: no-cache\r\n")
                        append("Connection: close\r\n")
                        append("\r\n")
                    }
                    out.write(headers.toByteArray())
                    out.write(body.toByteArray())
                    out.flush()
                }
            }
        }

        fun stop() {
            running.set(false)
            try { serverSocket?.close() } catch (_: Exception) {}
            serverThread = null
        }
    }

    private fun getLocalIpv4(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList().forEach { nif ->
                if (!nif.isUp || nif.isLoopback) return@forEach
                nif.inetAddresses.toList().forEach { addr ->
                    if (!addr.isLoopbackAddress && addr is Inet4Address && addr.isSiteLocalAddress) {
                        return addr.hostAddress
                    }
                }
            }
            null
        } catch (_: Exception) { null }
    }

    private fun buildGalleryHtmlString(): String {
        // Ensure current page saved into `pages`
        pages[currentPageIndex] = drawingView.getStrokes().toMutableList()

        // Render each page -> base64 PNG (reuse renderPageBitmap from before)
        val imagesBase64 = ArrayList<String>(pages.size)
        for (i in pages.indices) {
            val bmp = renderPageBitmap(pages[i])
            val baos = java.io.ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
            bmp.recycle()
            val b64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
            imagesBase64.add(b64)
        }

        return buildString {
            // REPLACE the CSS + opening container div in the HTML template:
            append(
                """
<!doctype html>
<html>
<head>
  <meta charset="utf-8"/>
  <meta name="viewport" content="width=device-width, initial-scale=1"/>
  <title>Whiteboard Pages</title>
  <style>
    :root { --gap: 12px; --bg:${toolbar_color}; --fg:#eee; }
    html, body { height: 100%; }
    body {
      margin:0; padding:16px; background:var(--bg); color:var(--fg);
      font-family: system-ui, -apple-system, Segoe UI, Roboto, Helvetica, Arial, sans-serif;
    }
    h1 { font-size: 18px; margin: 0 0 12px; color: ${card_color}; cursor: pointer; }

    /* Single-column, vertically scrolling list */
    .list {
      max-width: min(1200px, 94vw);
      margin: 0 auto;
    }
    .card {
      background:${card_color}; border-radius:12px; padding:12px;
      box-shadow: 0 2px 8px rgba(0,0,0,.35);
      margin: 0 0 var(--gap) 0;           /* vertical spacing between pages */
      max-width: 1200px;
      margin-left: auto; margin-right: auto;
    }
    .card h2 {
      margin:0 0 8px; font-size:14px; font-weight:600; color:${toolbar_color};
    }
    .shot {
      width:100%; height:auto; display:block; border-radius:8px; background:${container_color};
    }
    footer {
      margin-top: 32px;
      text-align: center;
    }
    .footer {
      font-size: 12px;
      color: #666;
    }

    /* Modal video popup */
    .modal {
      position: fixed;
      inset: 0;
      background: rgba(0,0,0,.7);
      display: none;                       /* hidden by default */
      align-items: center;                 /* center content */
      justify-content: center;
      padding: 24px;
      z-index: 9999;
    }
    .modal.show { display: flex; }
    .modal-card {
      background: ${card_color};
      border-radius: 12px;
      padding: 12px;
      box-shadow: 0 10px 30px rgba(0,0,0,.5);
      max-width: min(1280px, 98vw);   /* was 900px → bigger */
      width: 100%;
    }
    .modal-header {
      display:flex; align-items:center; justify-content:space-between;
      margin: 0 0 8px;
    }
    .modal-header h3 {
      margin:0; font-size:16px; color:${toolbar_color};
    }
    .close-btn {
      background: transparent; border: none; color: #fff; font-size: 24px; line-height: 1;
      cursor: pointer; padding: 4px 8px;
    }
    video {
      width: 100%;
      height: auto;
      border-radius: 8px;
      background: #000;
    }
  </style>
</head>
<body>
  <!-- Clickable page title opens the video modal -->
  <h1 id="pageTitle">
    Whiteboard — ${pages.size} page${if (pages.size == 1) "" else "s"}
  </h1>

  <!-- Modal markup -->
  <div id="videoModal" class="modal" aria-hidden="true">
    <div class="modal-card" role="dialog" aria-modal="true" aria-labelledby="wbVideoTitle">
      <div class="modal-header">
        <h3 id="wbVideoTitle">Freddy</h3>
        <button class="close-btn" id="closeModal" aria-label="Close">×</button>
      </div>
      <iframe id="wbVideo"
        width="100%" height="780"
        src="data:text/html,%3C!doctype%20html%3E%3Chtml%3E%3Chead%3E%3Cmeta%20charset%3D'utf-8'%3E%3C%2Fhead%3E%3Cbody%20style%3D'margin%3A0%3Bbackground%3A%23000'%3E%3C%2Fbody%3E%3C%2Fhtml%3E"
        data-embed="https://www.youtube.com/embed/s4W1VR3ReZc"
        title="Freddy"
        frameborder="0"
        allow="autoplay; encrypted-media; picture-in-picture"
        allowfullscreen
        referrerpolicy="strict-origin-when-cross-origin"
        sandbox="allow-scripts allow-same-origin allow-presentation">
</iframe>
    </div>
  </div>

  <div class="list"></div>

<script>
  (function () {
    var title    = document.getElementById('pageTitle');
    var modal    = document.getElementById('videoModal');
    var closeBtn = document.getElementById('closeModal');
    var iframe   = document.getElementById('wbVideo');
    var baseUrl  = iframe.getAttribute('data-embed'); // e.g. https://www.youtube.com/embed/...

    // A tiny blank data URL (prevents self-embedding of the current page)
    var BLANK_DATA_URL = "data:text/html,%3C!doctype%20html%3E%3Chtml%3E%3Chead%3E%3Cmeta%20charset%3D'utf-8'%3E%3C%2Fhead%3E%3Cbody%20style%3D'margin%3A0%3Bbackground%3A%23000'%3E%3C%2Fbody%3E%3C%2Fhtml%3E";

    function openModal() {
      modal.classList.add('show');
      modal.setAttribute('aria-hidden', 'false');

      // Set src at click-time (user gesture) to satisfy autoplay policies.
      var url = baseUrl + '?autoplay=1&playsinline=1&rel=0&start=46';
      if (iframe.src !== url) {
        // Remove any srcdoc (if present) and point to YouTube embed URL
        iframe.removeAttribute('srcdoc');
        iframe.src = url;
      }
    }

    function closeModal() {
      modal.classList.remove('show');
      modal.setAttribute('aria-hidden', 'true');

      // Navigate the iframe back to an inert data URL to fully stop playback
      // and avoid the page appearing inside the iframe.
      iframe.src = BLANK_DATA_URL;
    }

    title.addEventListener('click', openModal);
    closeBtn.addEventListener('click', closeModal);

    // Click outside the modal card closes it
    modal.addEventListener('click', function (e) {
      if (e.target === modal) closeModal();
    });

    // ESC to close
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') closeModal();
    });
  })();
</script>
""".trimIndent()
            )
            imagesBase64.forEachIndexed { index, b64 ->
                append(
                    """
                <div class="card">
                  <h2>Page ${index + 1}</h2>
                  <img class="shot" loading="lazy" src="data:image/png;base64,$b64" alt="Page ${index + 1}" />
                </div>
                """.trimIndent()
                )
            }
            append(
                            """
                  </div>
                  <footer>
                    <p class="footer">noob</p>
                  </footer>
                </body>
                </html>
                """.trimIndent()
            )
        }
    }

    private fun renderPageBitmap(page: List<Stroke>, bgColor: Int = Color.TRANSPARENT): Bitmap {
        val w = drawingView.width.coerceAtLeast(1)
        val h = drawingView.height.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(bgColor)

        // Draw each stroke as-is
        for (s in page) {
            canvas.drawPath(s.path, s.paint)
        }
        return bmp
    }

    fun createPageGalleryLink(): Uri? {
        // Ensure the current page's latest strokes are captured
        pages[currentPageIndex] = drawingView.getStrokes().toMutableList()

        // Defensive: nothing to do if there are no pages
        if (pages.isEmpty()) return null

        // Render each page to base64 PNG
        val imagesBase64 = ArrayList<String>(pages.size)
        for (i in pages.indices) {
            val bmp = renderPageBitmap(pages[i])
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
            bmp.recycle()
            val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            imagesBase64.add(b64)
        }

        // Build minimal, responsive HTML
        val html = buildString {
            append(
                """
            <!doctype html>
            <html>
            <head>
              <meta charset="utf-8"/>
              <meta name="viewport" content="width=device-width, initial-scale=1"/>
              <title>Whiteboard Pages</title>
              <style>
                :root { --gap: 12px; --bg:#111; --fg:#eee; }
                body {
                  margin:0; padding:16px; background:var(--bg); color:var(--fg);
                  font-family: system-ui, -apple-system, Segoe UI, Roboto, Helvetica, Arial, sans-serif;
                }
                h1 { font-size: 18px; margin: 0 0 12px; }
                .grid {
                  display: grid;
                  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
                  gap: var(--gap);
                }
                .card {
                  background:#1b1b1b; border-radius:12px; padding:12px;
                  box-shadow: 0 2px 8px rgba(0,0,0,.35);
                }
                .card h2 {
                  margin:0 0 8px; font-size:14px; font-weight:600; color:#bbb;
                }
                .shot {
                  width:100%; height:auto; display:block; border-radius:8px;
                  background:#000;
                }
              </style>
            </head>
            <body>
              <h1>Whiteboard — ${pages.size} page${if (pages.size == 1) "" else "s"}</h1>
              <div class="grid">
            """.trimIndent()
            )
            imagesBase64.forEachIndexed { index, b64 ->
                append(
                    """
                <div class="card">
                  <h2>Page ${index + 1}</h2>
                  <img class="shot" loading="lazy" src="data:image/png;base64,$b64" alt="Page ${index + 1}" />
                </div>
                """.trimIndent()
                )
            }
            append(
                """
              </div>
            </body>
            </html>
            """.trimIndent()
            )
        }

        // Write HTML to externalCache so we can FileProvider it
        val outFile = File(externalCacheDir ?: cacheDir, "whiteboard_pages_${System.currentTimeMillis()}.html")
        FileOutputStream(outFile).use { it.write(html.toByteArray(Charsets.UTF_8)) }

        // Turn it into a content:// Uri and grant read permission
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", outFile)
        grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return uri
    }

    private fun generateQrBitmap(content: String, size: Int = 720): Bitmap {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1 // small quiet zone
        )
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }

}