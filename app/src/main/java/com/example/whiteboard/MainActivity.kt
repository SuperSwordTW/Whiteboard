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

class MainActivity : AppCompatActivity() {


    private lateinit var drawingView: DrawingView

    private var pages = mutableListOf<MutableList<Stroke>>() // each page is a list of strokes
    private var currentPageIndex = 0

    private var currentFileName: String? = null

    private lateinit var desmosWebView: WebView

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
                    setBoolean("math.solver.enable", false)
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

        desmosWebView = findViewById(R.id.desmos_webview)
        val webSettings: WebSettings = desmosWebView.settings
        webSettings.javaScriptEnabled = true
        desmosWebView.webViewClient = WebViewClient()

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



                    onRecognizedMath(latex, strokes)

//                    showRecognizedMath(result, strokes)

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
            drawingView.setSelectionMode(true)
            drawingView.setMathingMode(true)
            drawingView.setSendMathingMode()
        }

        shapeButton.setOnClickListener {
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
            desmosWebView.visibility = if (isDesmosVisible) View.VISIBLE else View.GONE
        }

        drawButton.setOnClickListener {
            drawingView.setDrawingMode()
        }

        selectButton.setOnClickListener {
            drawingView.setSelectionMode(true)
        }

        mathButton.setOnClickListener {
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
                drawingView.setPenColor(Color.RED)
                popupWindow.dismiss()
            }
            popupView.findViewById<View>(R.id.color_violet).setOnClickListener {
                drawingView.setPenColor("#8F67EB".toColorInt())
                popupWindow.dismiss()
            }
            popupView.findViewById<View>(R.id.color_green).setOnClickListener {
                drawingView.setPenColor(Color.GREEN)
                popupWindow.dismiss()
            }
            popupView.findViewById<View>(R.id.color_blue).setOnClickListener {
                drawingView.setPenColor(Color.BLUE)
                popupWindow.dismiss()
            }
            popupView.findViewById<View>(R.id.color_black).setOnClickListener {
                drawingView.setPenColor(Color.BLACK)
                popupWindow.dismiss()
            }
            popupView.findViewById<View>(R.id.color_white).setOnClickListener {
                drawingView.setPenColor(Color.WHITE)
                popupWindow.dismiss()
            }
        }
        deleteButton.setOnClickListener {
            drawingView.deleteSelected()
        }
    }

    private val tmpRect = RectF()

    private val wolfram by lazy {
        WolframAlphaClient(appId = "8GAVHGL5LL")
    }

    private val wolframCloud by lazy {
        WolframCloudConverter(apiUrl = "https://www.wolframcloud.com/obj/linjustin0209/tex-to-wl")
    }

    // REPLACE the existing method in MainActivity with this version
    fun onRecognizedMath(input: String?, strokes: List<Stroke>) {
        val text = input?.trim()
        if (text.isNullOrEmpty()) return

        val (okTeX, wlInput) = wolframCloud.toWolframLanguageFromTeX(text)
//        val waInput = MathInputNormalizer.latexToWolframQuery(text)
        val waInput = if (okTeX) wlInput else MathInputNormalizer.latexToWolframQuery(text)
        Log.d("Mathmode","converted string: $waInput")

        val full = wolfram.queryFullResult(waInput)
        val displayText: String = if (full.ok && !full.primaryText.isNullOrBlank()) {
            full.primaryText!!
        } else {
            full.errorMessage ?: "No result."
        }

        val latex = MathInputNormalizer.plainToLatex(displayText)
        val input = concatLatex(text, latex)

        Log.d("Mathmode","string ouputted to wolfram: $input")

        showRecognizedMath(input, strokes)
    }

    private fun concatLatex(base: String?, next: String?): String {
        val b = base?.trim().orEmpty()
        val n = next?.trim().orEmpty()
        if (b.isEmpty()) return n
        if (n.isEmpty()) return b

        // Join with a space so things like "x=1" and "y=2" don't collide
        return "$b \\newline $n"
    }


    private fun showWolframResultDialog(query: String, resultText: String) {
        val msg = "Query:\n$query\n\nResult:\n$resultText"
        AlertDialog.Builder(this)
            .setTitle("Wolfram|Alpha")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }


    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun showRecognizedMath(latex: String?, strokes: List<Stroke>) {
        if (latex.isNullOrEmpty() || strokes.isEmpty()) return

        Log.d("Mathmode", "showRecognizedMath called")

        // Compute bounding box
        val bounds = RectF()
        val tmpRect = RectF()
        for (stroke in strokes) {
            stroke.path.computeBounds(tmpRect, true)
            bounds.union(tmpRect)
        }

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

        val cleanLatex = latex.replace("\\\\", "\\")
        val katexExpression = "$$${cleanLatex}$$"

        val html = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
            <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/katex@0.16.8/dist/katex.min.css">
            <script defer src="https://cdn.jsdelivr.net/npm/katex@0.16.8/dist/katex.min.js"></script>
            <script defer src="https://cdn.jsdelivr.net/npm/katex@0.16.8/dist/contrib/auto-render.min.js"
                    onload="renderMathInElement(document.body);"></script>
            <style>
                body {
                    margin:0;
                    padding:0;
                    background: transparent;
                    display:inline-block;
                    overflow:hidden;
                }
                #math-content {
                    display:inline-block;
                    margin:0;
                    padding:0;
                    color: white;
                    font-size:60px;
                }
            </style>
        </head>
        <body>
            <span id="math-content">$katexExpression</span>
        </body>
        </html>
    """.trimIndent()

        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)

        val container: FrameLayout = findViewById(R.id.drawing_container)
        container.addView(webView)

        // Adjust size to actual rendered content
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                webView.evaluateJavascript(
                    """
                (function() {
                    var el = document.getElementById("math-content");
                    return el.offsetWidth + "," + el.offsetHeight;
                })();
                """
                ) { result ->
                    val dims = result.replace("\"", "").split(",")
                    if (dims.size == 2) {
                        val w = dims[0].toIntOrNull() ?: 0
                        val h = dims[1].toIntOrNull() ?: 0
                        if (w > 0 && h > 0) {
                            webView.layoutParams = FrameLayout.LayoutParams(w, h)
                        }
                    }
                }
            }
        }

        // Position WebView
        webView.post {
            webView.x = bounds.left
            webView.y = bounds.top
        }

        // Drag & long-press delete
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



    fun nextPage() {
        // Save current strokes
        pages[currentPageIndex] = drawingView.getStrokes().toMutableList()

        if (currentPageIndex < pages.size - 1) {
            currentPageIndex++
        } else {
            // Add a new blank page
            pages.add(mutableListOf())
            currentPageIndex = pages.size - 1
        }
        drawingView.setStrokes(pages[currentPageIndex])
        updatePageNumber()
    }

    fun previousPage() {
        // Save current strokes
        pages[currentPageIndex] = drawingView.getStrokes().toMutableList()

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
            R.id.action_copy -> {
                drawingView.copySelected()
                true
            }
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


    private val autoSaveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val autoSaveInterval = 5 * 60 * 1000L // 5 minutes
//    private val autoSaveInterval = 10 * 1000L // 10 seconds

    private val autoSaveRunnable = object : Runnable {
        override fun run() {
            currentFileName?.let {
                saveToFile(it)
                Toast.makeText(this@MainActivity, "Auto-saved $it", Toast.LENGTH_SHORT).show()
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
    private fun saveToFile(fileName: String) {
        val safeName = if (fileName.endsWith(".json")) fileName else "$fileName.json"
        pages[currentPageIndex] = drawingView.getStrokes().toMutableList()

        val dataPages = pages.map { page -> page.map { it.toStrokeData() } }
        val json = gson.toJson(dataPages)

        openFileOutput(safeName, MODE_PRIVATE).use {
            it.write(json.toByteArray())
        }

        Toast.makeText(this, "Saved to $safeName", Toast.LENGTH_SHORT).show()
    }

    private fun loadFromFile(fileName: String) {
        val json = openFileInput(fileName).bufferedReader().use { it.readText() }

        val type = object : TypeToken<MutableList<List<StrokeData>>>() {}.type
        val dataPages: MutableList<List<StrokeData>> = gson.fromJson(json, type)

        // Rebuild the pages with real Stroke objects
        pages.clear()
        pages.addAll(dataPages.map { page -> page.map { it.toStroke() }.toMutableList() })

        currentPageIndex = 0
        drawingView.setStrokes(pages[currentPageIndex].toMutableList())
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