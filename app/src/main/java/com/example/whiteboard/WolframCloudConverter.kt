package com.example.whiteboard

import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * WolframCloudConverter
 * ---------------------
 * Calls your Wolfram Cloud APIFunction that does TeX -> Wolfram Language parsing.
 * This client post-processes the response to remove:
 *   1) Association echo like: <|"tex" -> HoldForm[ ... ]|>
 *   2) The outer HoldForm[...] wrapper
 * and returns just the inner expression, e.g.:
 *   Limit[Sin[x]/x, x -> 0]
 */
class WolframCloudConverter(
    private val apiUrl: String,
    private val timeoutMs: Int = 12_000
) {

    /**
     * Convert LaTeX into Wolfram Language InputForm string.
     * @return Pair(success, resultOrErrorMessageOrExpression)
     */
    fun toWolframLanguageFromTeX(texLatex: String?): Pair<Boolean, String> {
        val latex = texLatex?.trim().orEmpty()
        if (latex.isEmpty()) return false to "Empty LaTeX input."

        return try {
            val url = URL(apiUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            }

            val body = "tex=" + URLEncoder.encode(latex, "UTF-8")
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.use { BufferedReader(InputStreamReader(it)).readText() }?.trim().orEmpty()
            conn.disconnect()

            if (code !in 200..299 || raw.isEmpty()) {
                return false to "HTTP $code: ${if (raw.isEmpty()) "No response body" else raw}"
            }

            val cleaned = stripAssociationAndHoldForm(raw).trim()
            if (cleaned.isEmpty()) {
                false to "Cloud returned an empty expression. Raw: $raw"
            } else {
                true to cleaned
            }
        } catch (e: Exception) {
            false to (e.message ?: "Network error")
        }
    }

    // --- helpers ---

    /**
     * Accepts outputs like:
     *   <|"tex" -> HoldForm[Limit[Sin[x]/x, x -> 0]]|>
     * or just:
     *   HoldForm[Limit[Sin[x]/x, x -> 0]]
     * or already clean:
     *   Limit[Sin[x]/x, x -> 0]
     * and returns:
     *   Limit[Sin[x]/x, x -> 0]
     */
    private fun stripAssociationAndHoldForm(s0: String): String {
        var s = s0.trim()

        // 1) If it's an Association echo, extract the RHS after "->"
        //    Matches: <| "tex" -> SOME_CONTENT |>
        val assocRegex = Regex("""^<\|\s*("?tex"?)\s*->\s*(.*)\|>$""", RegexOption.DOT_MATCHES_ALL)
        val assoc = assocRegex.find(s)
        if (assoc != null && assoc.groupValues.size >= 3) {
            s = assoc.groupValues[2].trim()
        }

        // 2) If it's wrapped in HoldForm[ ... ], strip exactly one outer layer.
        //    We do a balanced strip safely by counting brackets at top level.
        s = stripSingleOuterHoldForm(s)

        return s
    }

    /**
     * Removes exactly one outer HoldForm[ ... ] if present with balanced brackets.
     * Leaves inner content untouched.
     */
    private fun stripSingleOuterHoldForm(s0: String): String {
        val prefix = "HoldForm["
        if (!s0.startsWith(prefix)) return s0
        var i = prefix.length
        var depth = 1
        while (i < s0.length) {
            val ch = s0[i]
            if (ch == '[') depth++
            if (ch == ']') {
                depth--
                if (depth == 0) {
                    // i points to the closing bracket of the outer HoldForm
                    // Extract inside: prefix..i-1
                    return s0.substring(prefix.length, i).trim()
                }
            }
            i++
        }
        // If unbalanced, return original
        return s0
    }
}
