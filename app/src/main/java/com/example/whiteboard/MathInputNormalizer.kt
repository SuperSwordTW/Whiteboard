package com.example.whiteboard

/**
 * MathInputNormalizer
 * -------------------
 * Lightweight utilities to convert between:
 *  - LaTeX-like math strings (as produced by handwriting recognizers) and
 *  - Plain math / Wolfram-friendly query strings.
 *
 * This is a pragmatic, dependency-free replacement for the Python example that used SymPy.
 * It covers the most common constructs you’ll meet on a whiteboard:
 *
 *  LaTeX ➜ Plain (Wolfram-friendly)
 *   - Strip math fences: $$…$$, \[ … \], \( … \)
 *   - Remove \left / \right
 *   - \frac{a}{b}                 -> (a)/(b)     (iterative for simple-nested fracs)
 *   - \sqrt{x}                    -> sqrt(x)
 *   - \sqrt[n]{x}                 -> (x)^(1/n)
 *   - ^{…} / _{…}                 -> ^(...) / _(...)   (kept as ASCII caret/underscore)
 *   - \cdot, \times               -> *
 *   - \div                        -> /
 *   - Matrices (pmatrix/bmatrix)  -> {{row1},{row2},...}
 *   - \sin,\cos,\tan,\log,\ln,…   -> sin,cos,tan,log,ln (lowercase function names)
 *   - Greek letters               -> alpha, beta, pi, …
 *   - Whitespace normalization
 *
 *  Plain ➜ LaTeX (basic pretty-print)
 *   - sqrt(x)                     -> \sqrt{x}
 *   - (x)^(1/n)                   -> \sqrt[n]{x}
 *   - Standard functions (sin,cos,tan,log,ln,exp) -> \sin,\cos,\tan,\log,\ln,e^{}
 *   - Matrices like {{1,2},{3,4}} -> \begin{bmatrix} 1 & 2 \\ 3 & 4 \end{bmatrix}
 *   - * and / kept as-is, ^ mapped to superscript form a^{b} when safe
 *
 * Notes:
 *  - This is not a full parser. It’s deliberately conservative and aims to produce
 *    queries that Wolfram|Alpha typically understands, without external libraries.
 *  - If you need broader LaTeX coverage, consider a server-side service using SymPy
 *    and call it from the app, or embed a full CAS (heavy).
 */
object MathInputNormalizer {

    // ---------------------------
    // Public API
    // ---------------------------

    /** Convert LaTeX-ish input to a Wolfram-friendly plain math string. */
    fun latexToWolframQuery(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return s

        // 1) Strip math fences like $$…$$, \[ … \], \( … \)
        s = stripMathFences(s)

        // 2) Remove sizing wrappers \left \right
        s = s.replace("""\\left""".toRegex(), "")
            .replace("""\\right""".toRegex(), "")

        // 3) Normalize common operators and spacing
        s = normalizeOperators(s)

        // 4) Handle fractions (simple nested)
        s = expandFractions(s)

        // 5) Handle sqrt and root forms
        s = normalizeSqrt(s)

        // 6) Normalize superscripts/subscripts (keep ASCII markers)
        s = normalizeScripts(s)

        s = convertAlignedBlocks(s)

        // 7) Convert matrices/environments to {{…},{…}}
        s = convertLatexMatricesToLists(s)

        // 8) Map functions \sin -> sin, \log -> log, etc.
        s = normalizeFunctionNames(s)

        // 9) Map greek names \alpha -> alpha, \pi -> pi, etc.
        s = mapGreekLetters(s)

        // 10) Clean up braces and whitespace
        s = cleanupWhitespace(s)

        return s
    }

    private fun convertAlignedBlocks(s0: String): String {
        var s = s0

        // Matches \begin{aligned}...\end{aligned}, \begin{align}...\end{align}, \begin{align*}...\end{align*}
        val env = Regex(
            """\\begin\{(aligned|align\*?|align)\}(.+?)\\end\{\1\}""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )

        while (true) {
            val m = env.find(s) ?: break
            val body = m.groupValues[2]

            // Split lines by unescaped \\ (same splitter used elsewhere)
            val lines = body
                .trim()
                .split(Regex("""(?<!\\)\\\\+"""))
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            // For each line:
            // 1) remove alignment ampersands (&)
            // 2) replace common LaTeX relations with ASCII
            // 3) light cleanup
            val plainLines = lines.map { line0 ->
                var line = line0

                // Remove alignment markers (e.g. "x & = & 1" -> "x  =  1")
                line = line.replace("&", " ")

                // Relations: \leq, \geq, \neq, \approx
                line = line.replace("""\\leq\b""".toRegex(), "<=")
                    .replace("""\\geq\b""".toRegex(), ">=")
                    .replace("""\\neq\b""".toRegex(), "!=")
                    .replace("""\\ne\b""".toRegex(), "!=")
                    .replace("""\\approx\b""".toRegex(), "~")

                // Also handle := and \coloneqq (becomes = for queries)
                line = line.replace("""\\coloneqq\b""".toRegex(), "=")
                    .replace(":=", "=")

                // Collapse spaces
                line = line.replace("""\s+""".toRegex(), " ").trim()

                line
            }

            // If multiple equations, wrap as a set for Wolfram|Alpha
            val replacement = if (plainLines.size == 1) {
                plainLines.first()
            } else {
                "{${plainLines.joinToString(", ")}}"
            }

            s = s.replaceRange(m.range, replacement)
        }
        return s
    }

    /**
     * Convert a plain math/Wolfram-friendly string back to a readable LaTeX string.
     * This is a heuristic pretty-printer; it will not perfectly invert every case,
     * but is handy for showing results inline with KaTeX/MathJax.
     */
    fun plainToLatex(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return s

        // --- Canonicalize obvious symbols first ---
        s = normalizeAscii(s)

        // --- Matrices: {{1,2},{3,4}} -> \begin{bmatrix} 1 & 2 \\ 3 & 4 \end{bmatrix}
        s = listsToLatexMatrix(s)

        // --- Functions & constants ---
        s = normalizeFunctionsAndConstants(s)

        // --- Roots ---
        // sqrt( x ) -> \sqrt{x}
        s = Regex("""\bsqrt\s*\(\s*([^)]+?)\s*\)""").replace(s) { m ->
            """\sqrt{${m.groupValues[1]}}"""
        }
        // (x)^(1/n) -> \sqrt[n]{x}
        s = Regex("""\(\s*([^()]+?)\s*\)\s*\^\s*\(\s*1\s*/\s*([^)]+?)\s*\)""")
            .replace(s) { m -> """\sqrt[${m.groupValues[2]}]{${m.groupValues[1]}}""" }

        // --- Powers & subscripts (add braces when missing) ---
        // x^10 -> x^{10}, (… already parenthesized exponent is kept)
        s = Regex("""([A-Za-z0-9\)\]\}])\s*\^\s*([A-Za-z0-9]+)""")
            .replace(s) { m -> "${m.groupValues[1]}^{${m.groupValues[2]}}" }
        // x_10 -> x_{10}
        s = Regex("""([A-Za-z])_([A-Za-z0-9]+)""")
            .replace(s) { m -> "${m.groupValues[1]}_{${m.groupValues[2]}}" }

        // --- Limit notation ---
        // lim_(x->0)  ➜  \lim_{x \to 0}
        s = Regex("""\blim_\s*(\(|\{)\s*([^(){}]+?)\s*(\)|\})""", setOf(RegexOption.IGNORE_CASE))
            .replace(s) { m -> """\lim_{${m.groupValues[2].replace("->", """\to """).trim()}}""" }
        // lim(x->0)  ➜  \lim_{x \to 0}
        s = Regex("""\blim\s*\(\s*([^()]+?)\s*\)""", setOf(RegexOption.IGNORE_CASE))
            .replace(s) { m -> """\lim_{${m.groupValues[1].replace("->", """\to """).trim()}}""" }

        // --- Fractions (safe heuristics) ---
        s = applyFractionHeuristics(s)

        // --- Absolute value: |x| -> \left|x\right| (single-level)
        s = Regex("""\|\s*([^|]+?)\s*\|""", setOf(RegexOption.DOT_MATCHES_ALL))
            .replace(s) { m -> """\left| ${m.groupValues[1].trim()} \right|""" }

        // --- Sums/Products/Integrals w/ bounds ---
        // sum_(i=1)^(n) -> \sum_{i=1}^{n}
        s = Regex("""\b(sum|prod|max|min)\s*_\s*\(\s*([^)]+?)\s*\)\s*\^\s*\(\s*([^)]+?)\s*\)""",
            setOf(RegexOption.IGNORE_CASE))
            .replace(s) { m ->
                val op = m.groupValues[1].lowercase()
                val latexOp = when (op) {
                    "sum" -> """\sum"""
                    "prod" -> """\prod"""
                    "max" -> """\max"""
                    "min" -> """\min"""
                    else -> """\sum"""
                }
                """${latexOp}_{${m.groupValues[2]}}^{${m.groupValues[3]}}"""
            }
        // int_(a)^(b) -> \int_{a}^{b}
        s = Regex("""\bint\s*_\s*\(\s*([^)]+?)\s*\)\s*\^\s*\(\s*([^)]+?)\s*\)""",
            setOf(RegexOption.IGNORE_CASE))
            .replace(s) { m -> """\int_{${m.groupValues[1]}}^{${m.groupValues[2]}}""" }
        // bare "int" -> \int
        s = Regex("""\bint\b""", setOf(RegexOption.IGNORE_CASE)).replace(s, """\int""")

        // --- Derivatives: d/dx f(x) -> \frac{d}{dx} f(x) ---
        s = Regex("""\bd\s*/\s*d([A-Za-z])\b""").replace(s) { m ->
            """\frac{d}{d${m.groupValues[1]}}"""
        }

        // --- Clean up spaces around LaTeX control sequences ---
        s = tidySpaces(s)

        return s
    }

    private fun normalizeAscii(input: String): String {
        var s = input
        // Common arrow/ops/unicode -> LaTeX equivalents
        s = s.replace("→", """\to """)
            .replace("->", """\to """)
            .replace("=>", """\Rightarrow """)
            .replace("≤", """\le """)
            .replace("<=", """\le """)
            .replace("≥", """\ge """)
            .replace(">=", """\ge """)
            .replace("≠", """\ne """)
            .replace("±", """\pm """)
            .replace("∞", """\infty""")
        // make sure commas/spaces are reasonable
        s = s.replace(Regex("""\s+"""), " ").trim()
        return s
    }

    private fun normalizeFunctionsAndConstants(input: String): String {
        var s = input

        // exp(x) -> e^{x}
        s = Regex("""\bexp\s*\(\s*([^)]+?)\s*\)""", setOf(RegexOption.IGNORE_CASE))
            .replace(s) { m -> """e^{${m.groupValues[1]}}""" }

        // trig/log families
        val toBackslash = listOf(
            // base trig
            "sin" to """\sin""",
            "cos" to """\cos""",
            "tan" to """\tan""",
            "cot" to """\cot""",
            "sec" to """\sec""",
            "csc" to """\csc""",
            // inverse trig (common aliases)
            "asin" to """\arcsin""",
            "acos" to """\arccos""",
            "atan" to """\arctan""",
            // hyperbolic
            "sinh" to """\sinh""",
            "cosh" to """\cosh""",
            "tanh" to """\tanh""",
            // logs
            "ln" to """\ln""",
            "log" to """\log"""
        )
        for ((plain, latex) in toBackslash) {
            s = Regex("""\b$plain\b""", setOf(RegexOption.IGNORE_CASE)).replace(s, latex)
        }

        // Common greek names
        val greek = listOf(
            "alpha","beta","gamma","delta","epsilon","zeta","eta","theta","iota","kappa","lambda",
            "mu","nu","xi","omicron","pi","rho","sigma","tau","upsilon","phi","chi","psi","omega"
        )
        for (g in greek) {
            s = Regex("""\b$g\b""", setOf(RegexOption.IGNORE_CASE)).replace(s, """\\$g""")
        }

        // infinity/infty/inf variants
        s = Regex("""\b(infty|inf)\b""", setOf(RegexOption.IGNORE_CASE)).replace(s, """\infty""")

        return s
    }

    /**
     * Fraction heuristics:
     *  1) (…)/(…)      → \frac{…}{…}
     *  2) (…)/token    → \frac{…}{token}
     *  3) token/(…)    → \frac{token}{…}    (token is at least 2 chars or a function call)
     *
     * NOTE: We keep it conservative to avoid trashing things like URL-like strings.
     */
    private fun applyFractionHeuristics(input: String): String {
        var s = input

        // parenthesized over parenthesized
        s = Regex("""(?<!\\frac)\(\s*([^()]+?)\s*\)\s*/\s*\(\s*([^()]+?)\s*\)""")
            .replace(s) { m -> """\frac{${m.groupValues[1]}}{${m.groupValues[2]}}""" }

        // parenthesized over token
        s = Regex("""(?<!\\frac)\(\s*([^()]+?)\s*\)\s*/\s*([A-Za-z\\][A-Za-z0-9_]*|\d+(?:\.\d+)?)""")
            .replace(s) { m -> """\frac{${m.groupValues[1]}}{${m.groupValues[2]}}""" }

        // token (>=2 chars or function call) over parenthesized
        s = Regex(
            """(?<!\\frac)((?:\\?[A-Za-z]{2,}(?:\([^()]*\))?)|\d+(?:\.\d+)?)\s*/\s*\(\s*([^()]+?)\s*\)"""
        ).replace(s) { m -> """\frac{${m.groupValues[1]}}{${m.groupValues[2]}}""" }

        return s
    }

    private fun tidySpaces(input: String): String {
        var s = input
        // Collapse excessive spaces
        s = s.replace(Regex("""\s+"""), " ").trim()
        // Insert thin spaces before differentials like "dx" after integrands: f(x) dx -> f(x)\,dx
        s = Regex("""\)\s*d([A-Za-z])\b""").replace(s) { m -> """) \\, d${m.groupValues[1]}""" }
        return s
    }

// ---------------------------
    // LaTeX ➜ Plain helpers
    // ---------------------------

    private fun stripMathFences(s0: String): String {
        var out = s0
        val fences = listOf(
            Regex("""^\s*\$\$(.*)\$\$\s*$""", setOf(RegexOption.DOT_MATCHES_ALL)),
            Regex("""^\s*\\\[(.*)\\\]\s*$""", setOf(RegexOption.DOT_MATCHES_ALL)),
            Regex("""^\s*\\\((.*)\\\)\s*$""", setOf(RegexOption.DOT_MATCHES_ALL))
        )
        for (rx in fences) {
            val m = rx.find(out)
            if (m != null) {
                out = m.groupValues[1]
                break
            }
        }
        return out
    }

    private fun normalizeOperators(s0: String): String {
        var s = s0
        s = s.replace("""\\cdot""".toRegex(), " * ")
            .replace("""\\times""".toRegex(), " * ")
            .replace("""\\div""".toRegex(), " / ")
            .replace("""\\;|\\,|\\:|\\!""".toRegex(), " ")
        return s
    }

    /** Expand \frac{a}{b} -> (a)/(b) iteratively for simple (non-nested-brace) cases. */
    private fun expandFractions(s0: String): String {
        var s = s0
        val rx = Regex("""\\frac\{([^{}]+)\}\{([^{}]+)\}""")
        repeat(8) {
            val r = s.replace(rx) { m ->
                val num = m.groupValues[1].trim()
                val den = m.groupValues[2].trim()
                "($num)/($den)"
            }
            if (r == s) return@repeat
            s = r
        }
        return s
    }

    /** \sqrt{x} -> sqrt(x), \sqrt[n]{x} -> (x)^(1/n) */
    private fun normalizeSqrt(s0: String): String {
        var s = s0
        // \sqrt[n]{x}
        s = s.replace(Regex("""\\sqrt\[(.+?)\]\{(.+?)\}""")) { m ->
            val n = m.groupValues[1].trim()
            val x = m.groupValues[2].trim()
            "($x)^(1/$n)"
        }
        // \sqrt{x}
        s = s.replace(Regex("""\\sqrt\{(.+?)\}""")) { m ->
            "sqrt(${m.groupValues[1].trim()})"
        }
        return s
    }

    /** Keep ^ and _ markers but ensure braces become parentheses after removal. */
    private fun normalizeScripts(s0: String): String {
        var s = s0
        // a^{b} -> a^(b)
        s = s.replace(Regex("""\^\{([^{}]+)\}"""), """^($1)""")
        // a^b (leave as-is)
        // Subscripts: _{…} -> _(...)
        s = s.replace(Regex("""_\{([^{}]+)\}"""), """_($1)""")
        return s
    }

    /** Convert \begin{pmatrix}…\end{pmatrix}, \begin{bmatrix}…\end{bmatrix}, \begin{matrix}…\end{matrix} to {{…},{…}}. */
    private fun convertLatexMatricesToLists(s0: String): String {
        var s = s0
        val env = Regex("""\\begin\{(pmatrix|bmatrix|matrix)\}(.+?)\\end\{\1\}""", setOf(RegexOption.DOT_MATCHES_ALL))
        while (true) {
            val m = env.find(s) ?: break
            val body = m.groupValues[2]

            // Split rows on \\ (unescaped)
            val rows = body.trim()
                .split(Regex("""(?<!\\)\\\\+"""))
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val rowLists = rows.map { row ->
                val cols = row.split("&").map { it.trim() }.filter { it.isNotEmpty() }
                "{${cols.joinToString(",")}}"
            }
            val replacement = "{{${rowLists.joinToString(",")}}}"
            s = s.replaceRange(m.range, replacement)
        }
        return s
    }

    /** Map LaTeX function names to plain equivalents: \sin -> sin, \log -> log, etc. */
    private fun normalizeFunctionNames(s0: String): String {
        var s = s0
        val map = listOf(
            "\\\\sin\\b" to "sin",
            "\\\\cos\\b" to "cos",
            "\\\\tan\\b" to "tan",
            "\\\\csc\\b" to "csc",
            "\\\\sec\\b" to "sec",
            "\\\\cot\\b" to "cot",
            "\\\\arcsin\\b" to "asin",
            "\\\\arccos\\b" to "acos",
            "\\\\arctan\\b" to "atan",
            "\\\\ln\\b" to "ln",
            "\\\\log\\b" to "log",
            "\\\\exp\\b" to "exp"
        )
        for ((k, v) in map) s = s.replace(k.toRegex(), v)
        return s
    }

    /** \alpha -> alpha, \pi -> pi, etc. */
    private fun mapGreekLetters(s0: String): String {
        var s = s0
        val greek = listOf(
            "alpha","beta","gamma","delta","epsilon","zeta","eta","theta","iota","kappa","lambda",
            "mu","nu","xi","omicron","pi","rho","sigma","tau","upsilon","phi","chi","psi","omega",
            // uppercase common
            "Gamma","Delta","Theta","Lambda","Xi","Pi","Sigma","Upsilon","Phi","Psi","Omega"
        )
        for (name in greek) {
            s = s.replace("""\\$name\b""".toRegex(), name.lowercase())
        }
        return s
    }

    private fun cleanupWhitespace(s0: String): String {
        var s = s0
        // Remove redundant braces around single tokens where obvious
        s = s.replace(Regex("""\{([A-Za-z0-9+\-*/^._\s]+)\}""")) { m -> m.groupValues[1] }
        // Collapse spaces
        s = s.replace("""\s+""".toRegex(), " ").trim()
        return s
    }

    // ---------------------------
    // Plain ➜ LaTeX helpers
    // ---------------------------

    private fun listsToLatexMatrix(s0: String): String {
        // Replace *standalone* top-level matrix-like lists with bmatrix
        // This is heuristic: only handles one matrix literal per string cleanly.
        val rx = Regex("""\{\{([^{}]|(\{[^{}]*\}))*\}\}""")
        var s = s0
        rx.findAll(s).forEach { match ->
            val content = match.value.removePrefix("{{").removeSuffix("}}")
            val rows = splitTopLevel(content, ',') // rows separated by "},{"
                .map { it.trim().trimStart('{').trimEnd('}') }
            val latexRows = rows.joinToString(" \\\\ ") { row ->
                splitTopLevel(row, ',').joinToString(" & ") { it.trim() }
            }
            val latex = "\\\\begin{bmatrix} $latexRows \\\\end{bmatrix}"
                .replace("\\\\end", "\\end") // keep escaping correct in raw string
                .replace("\\\\begin", "\\begin")
            s = s.replace(match.value, latex)
        }
        return s
    }

    /** Split a top-level comma-separated list ignoring commas inside parentheses/brackets/braces. */
    private fun splitTopLevel(s: String, sep: Char): List<String> {
        val out = mutableListOf<String>()
        val buf = StringBuilder()
        var depthParen = 0
        var depthBrack = 0
        var depthBrace = 0
        for (ch in s) {
            when (ch) {
                '(' -> depthParen++
                ')' -> depthParen = (depthParen - 1).coerceAtLeast(0)
                '[' -> depthBrack++
                ']' -> depthBrack = (depthBrack - 1).coerceAtLeast(0)
                '{' -> depthBrace++
                '}' -> depthBrace = (depthBrace - 1).coerceAtLeast(0)
            }
            if (ch == sep && depthParen == 0 && depthBrack == 0 && depthBrace == 0) {
                out += buf.toString()
                buf.setLength(0)
            } else {
                buf.append(ch)
            }
        }
        if (buf.isNotEmpty()) out += buf.toString()
        return out
    }


   // escape add \\

    fun escapeForWLStringLiteral(tex: String?): String {
        if (tex.isNullOrEmpty()) return ""
        val sb = StringBuilder(tex.length * 2)
        for (ch in tex) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '\"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\t' -> sb.append("\\t")
                '\r' -> {} // drop
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * Convenience wrapper that produces a complete ToExpression[...] call
     * you can paste/run inside Wolfram Language (Mathematica/Wolfram Cloud).
     *
     * Example output:
     *   ToExpression["\\int_{0}^{1} e^{-x^{2}} \\, dx", TeXForm, HoldForm]
     */
    fun wrapAsToExpression(tex: String?, hold: Boolean = true): String {
        val escaped = escapeForWLStringLiteral(tex)
        val post = if (hold) "HoldForm" else "Identity"
        return """ToExpression["$escaped", TeXForm, $post]"""
    }

    /**
     * JSON-escape a string without adding surrounding quotes.
     * If you need the full quoted value, wrap the result with double quotes yourself.
     * (Android’s org.json.JSONObject.quote(...) also works if available.)
     */
    fun escapeForJsonString(s: String?): String {
        if (s == null) return ""
        val sb = StringBuilder(s.length + 16)
        for (ch in s) {
            when (ch) {
                '\"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch < ' ') {
                        sb.append(String.format("\\u%04x", ch.code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        return sb.toString()
    }

    /**
     * Helpful when your input is a mix of ASCII math and TeX:
     * - Ensures common ASCII arrows/relations become proper TeX commands
     * - Adds a thin space before differentials ‘dx’ after a closing parenthesis or brace
     * This is optional pre-clean before escaping for WL string literal.
     */
    fun preNormalizeAsciiToTeX(input: String?): String {
        if (input.isNullOrBlank()) return ""
        var s = input

        // ASCII arrows/relations to TeX
        s = s.replace("->", """\to """)
            .replace("<=", """\le """)
            .replace(">=", """\ge """)
            .replace("!=", """\ne """)

        // Add thin space before differentials: f(x) dx -> f(x)\, dx
        s = Regex("""(\)|\})\s*d([A-Za-z])\b""").replace(s) { m -> "${m.groupValues[1]} \\, d${m.groupValues[2]}" }

        // Collapse whitespace
        s = s.replace(Regex("""\s+"""), " ").trim()
        return s
    }

    fun toExpressionFromTeX(tex: String?, hold: Boolean = true, preNormalizeAscii: Boolean = false): String {
        val prepared = if (preNormalizeAscii) preNormalizeAsciiToTeX(tex) else (tex ?: "")
        return wrapAsToExpression(prepared, hold)
    }

}