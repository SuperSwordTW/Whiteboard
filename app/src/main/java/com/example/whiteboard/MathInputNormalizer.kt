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



     fun convertAlignedBlocks(s0: String): String {
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
    // REPLACE the old function with this:
    fun plainToLatex(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return s

        // 0) Basic symbol canonicalization first
        s = normalizeAscii(s)

        // 1) Matrices from {{...},{...}}
        s = listsToLatexMatrix(s)

        // 2) Named functions/constants -> LaTeX commands (sin, cos, ln, e^x, greek, ∞, …)
        s = normalizeFunctionsAndConstants(s)

        // 3) Limits (lim x->a f(x), lim_(x->a) f(x), lim(x->a))
        s = applyLimitHeuristics(s)

        // 4) Derivatives
        //    - dy/dx           -> \frac{d y}{d x}
        //    - d/dx f(x)       -> \frac{d}{dx} f(x)
        //    - d^2/dx^2 f(x)   -> \frac{d^{2}}{dx^{2}} f(x)
        //    - ∂/∂x f, ∂^2/∂x^2 f  similarly
        s = applyDerivativeHeuristics(s)

        // 5) Integrals (indefinite + bounded)
        //    - int_(a)^(b) f(x) dx  -> \int_{a}^{b} f(x)\,dx
        //    - int f(x) dx / integrate f(x) dx -> \int f(x)\,dx
        s = applyIntegralHeuristics(s)

        // 6) Roots
        //    sqrt(x)      -> \sqrt{x}
        //    (x)^(1/n)    -> \sqrt[n]{x}
        s = Regex("""\bsqrt\s*\(\s*([^)]+?)\s*\)""").replace(s) { m ->
            """\sqrt{${m.groupValues[1]}}"""
        }
        s = Regex("""\(\s*([^()]+?)\s*\)\s*\^\s*\(\s*1\s*/\s*([^)]+?)\s*\)""")
            .replace(s) { m -> """\sqrt[${m.groupValues[2]}]{${m.groupValues[1]}}""" }

        // 7) Fractions (keep conservative to avoid trashing URLs etc.)
        s = applyFractionHeuristics(s)

        // 8) Superscripts/subscripts – add braces when simple
        s = Regex("""([A-Za-z0-9\)\]\}])\s*\^\s*([A-Za-z0-9]+)""")
            .replace(s) { m -> "${m.groupValues[1]}^{${m.groupValues[2]}}" }
        s = Regex("""([A-Za-z])_([A-Za-z0-9]+)""")
            .replace(s) { m -> "${m.groupValues[1]}_{${m.groupValues[2]}}" }

        // 9) Absolute value: |x| -> \left| x \right|
        s = Regex("""\|\s*([^|]+?)\s*\|""", setOf(RegexOption.DOT_MATCHES_ALL))
            .replace(s) { m -> """\left| ${m.groupValues[1].trim()} \right|""" }

        // 10) Sums/Products/Extrema with bounds: sum_(i=1)^(n) -> \sum_{i=1}^{n}, etc.
        s = Regex("""\b(sum|prod|max|min)\s*_\s*\(\s*([^)]+?)\s*\)\s*\^\s*\(\s*([^)]+?)\s*\)""",
            setOf(RegexOption.IGNORE_CASE))
            .replace(s) { m ->
                val op = when (m.groupValues[1].lowercase()) {
                    "sum" -> """\sum"""
                    "prod" -> """\prod"""
                    "max" -> """\max"""
                    "min" -> """\min"""
                    else -> """\sum"""
                }
                """${op}_{${m.groupValues[2]}}^{${m.groupValues[3]}}"""
            }

        // 11) Final spacing tidy (adds thin spaces before differentials)
        s = tidySpaces(s)
        return s
    }


// ADD these helpers (inside the same object):

    /** Limits: handle lim x->a f(x), lim_(x->a) f(x), lim(x->a), lim_{x->a} f(x). */
    private fun applyLimitHeuristics(input: String): String {
        var s = input

        // lim_(x->a)  or  lim_{x->a}
        s = Regex("""\blim\s*_\s*(\(|\{)\s*([^(){}]+?)\s*(\)|\})""", RegexOption.IGNORE_CASE)
            .replace(s) { m ->
                val sub = m.groupValues[2].replace("->", """\to """).trim()
                """\lim_{${sub}}"""
            }

        // lim(x->a)
        s = Regex("""\blim\s*\(\s*([^()]+?)\s*\)""", RegexOption.IGNORE_CASE)
            .replace(s) { m ->
                val sub = m.groupValues[1].replace("->", """\to """).trim()
                """\lim_{${sub}}"""
            }

        // lim x->a f(x)   (no underscore/parentheses)
        s = Regex("""\blim\s+([A-Za-z][A-Za-z0-9_]*)\s*->\s*([^\s]+)""", RegexOption.IGNORE_CASE)
            .replace(s) { m -> """\lim_{${m.groupValues[1]} \to ${m.groupValues[2]}}""" }

        return s
    }

    /** Derivatives: total and partial; first and higher order; dy/dx and operator forms. */
    private fun applyDerivativeHeuristics(input: String): String {
        var s = input

        // dy/dx  -> \frac{d y}{d x}
        s = Regex("""\bd([A-Za-z])\s*/\s*d([A-Za-z])\b""")
            .replace(s) { m -> """\frac{d ${m.groupValues[1]}}{d ${m.groupValues[2]}}""" }

        // d/dx -> \frac{d}{dx}
        s = Regex("""\bd\s*/\s*d\s*([A-Za-z])\b""")
            .replace(s) { m -> """\frac{d}{d${m.groupValues[1]}}""" }

        // d^n/dx^n -> \frac{d^{n}}{dx^{n}}
        s = Regex("""\bd\^(\d+)\s*/\s*d([A-Za-z])\^(\d+)\b""")
            .replace(s) { m -> """\frac{d^{${m.groupValues[1]}}}{d${m.groupValues[2]}^{${m.groupValues[3]}}}""" }

        // Partial: ∂/∂x, ∂^n/∂x^n (if unicode present)
        s = s.replace(Regex("""∂\s*/\s*∂([A-Za-z])""")) { m -> """\frac{\partial}{\partial ${m.groupValues[1]}}""" }
        s = s.replace(Regex("""∂\^(\d+)\s*/\s*∂([A-Za-z])\^(\d+)""")) { m ->
            """\frac{\partial^{${m.groupValues[1]}}}{\partial ${m.groupValues[2]}^{${m.groupValues[3]}}}"""
        }

        return s
    }

    /** Integrals: bounded and unbounded; also 'integrate ... dx' phrasing. */
    private fun applyIntegralHeuristics(input: String): String {
        var s = input

        // int_(a)^(b) -> \int_{a}^{b}
        s = Regex("""\bint\s*_\s*\(\s*([^)]+?)\s*\)\s*\^\s*\(\s*([^)]+?)\s*\)""",
            RegexOption.IGNORE_CASE)
            .replace(s) { m -> """\int_{${m.groupValues[1]}}^{${m.groupValues[2]}}""" }

        // bare "int" token -> \int
        s = Regex("""\bint\b""", RegexOption.IGNORE_CASE).replace(s, """\int""")

        // integrate f(x) dx  /  integral of f(x) dx  -> \int f(x)\,dx
        s = Regex("""\b(integrate|integral\s+of)\s+(.+?)\s+d([A-Za-z])\b""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .replace(s) { m -> """\int ${m.groupValues[2].trim()} \\, d${m.groupValues[3]}""" }

        return s
    }

// OPTIONAL: you can enhance your existing fraction heuristics by ADDING this rule
// at the END of applyFractionHeuristics(...) just before returning 's':
//    // simple token / simple token
//    s = Regex("""(?<!\\frac)([A-Za-z\\][A-Za-z0-9_]*|\d+(?:\.\d+)?)\s*/\s*([A-Za-z\\][A-Za-z0-9_]*|\d+(?:\.\d+)?)""")
//        .replace(s) { m -> """\frac{${m.groupValues[1]}}{${m.groupValues[2]}}""" }


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

        // ( ... ) / ( ... )
        s = Regex("""(?<!\\frac)\(\s*([^()]+?)\s*\)\s*/\s*\(\s*([^()]+?)\s*\)""")
            .replace(s) { m -> """\frac{${m.groupValues[1]}}{${m.groupValues[2]}}""" }

        // ( ... ) / token
        s = Regex("""(?<!\\frac)\(\s*([^()]+?)\s*\)\s*/\s*([A-Za-z\\][A-Za-z0-9_]*|\d+(?:\.\d+)?)""")
            .replace(s) { m -> """\frac{${m.groupValues[1]}}{${m.groupValues[2]}}""" }

        // token (>=2 chars or function call) / ( ... )
        s = Regex(
            """(?<!\\frac)((?:\\?[A-Za-z]{2,}(?:\([^()]*\))?)|\d+(?:\.\d+)?)\s*/\s*\(\s*([^()]+?)\s*\)"""
        ).replace(s) { m -> """\frac{${m.groupValues[1]}}{${m.groupValues[2]}}""" }

        // NEW: simple token / simple token  (e.g., 1/2, x/y, \alpha/\beta)
        // Guards:
        //  - (?<!\\frac)     : don't re-wrap existing \frac
        //  - (?<!//) (?<!:)  : avoid "http://", "https://"
        //  - \b ... \b       : stick to math-y tokens, not parts of paths
        s = Regex(
            """(?<!\\frac)(?<!//)(?<!:)\b([A-Za-z\\][A-Za-z0-9_]*|\d+(?:\.\d+)?)\s*/\s*([A-Za-z\\][A-Za-z0-9_]*|\d+(?:\.\d+)?)\b"""
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