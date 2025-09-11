package com.example.whiteboard

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.UnsupportedEncodingException
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import org.json.JSONObject

/**
 * Lightweight Wolfram|Alpha client using HttpURLConnection (no extra deps).
 * - Short Answers API: quick text result
 * - Full Results (JSON): optional, basic pod extraction
 */
class WolframAlphaClient(
    private val appId: String,         // <- Put your app id here when constructing
    private val timeoutMs: Int = 12_000              // dial as you like
) {

    data class ShortAnswer(
        val ok: Boolean,
        val text: String?,
        val errorMessage: String? = null
    )

    data class FullResult(
        val ok: Boolean,
        val primaryText: String?,
        val podsPreview: String?,
        val errorMessage: String? = null
    )

    /**
     * Fastest: https://api.wolframalpha.com/v1/result?i=<query>&appid=<id>
     * Returns plain text, or 501/400 on unsupported/invalid.
     */
    fun queryShortAnswer(input: String): ShortAnswer {
        val encoded = urlEncode(input)
        val urlStr = "https://api.wolframalpha.com/v1/result?i=$encoded&appid=$appId"
        return try {
            val (code, body) = httpGet(urlStr)
            when (code) {
                200 -> ShortAnswer(true, body?.trim())
                501 -> ShortAnswer(false, null, "Wolfram|Alpha has no short answer for this query.")
                400 -> ShortAnswer(false, null, "Invalid query.")
                else -> ShortAnswer(false, null, "HTTP $code from Wolfram|Alpha.")
            }
        } catch (e: Exception) {
            ShortAnswer(false, null, e.message ?: "Network error")
        }
    }

    /**
     * Richer: https://api.wolframalpha.com/v2/query?input=...&appid=...&output=JSON
     * We extract the primary pod text and build a small preview.
     */
    fun queryFullResult(input: String): FullResult {
        val encoded = urlEncode(input)
        val urlStr =
            "https://api.wolframalpha.com/v2/query?input=$encoded&appid=$appId&output=JSON&scantimeout=6&podtimeout=6&formattimeout=6"

        return try {
            val (code, body) = httpGet(urlStr)
            if (code != 200 || body.isNullOrBlank()) {
                return FullResult(false, null, null, "HTTP $code")
            }

            val json = JSONObject(body)
            val queryResult = json.optJSONObject("queryresult")
            val success = queryResult?.optBoolean("success") ?: false
            if (!success) {
                return FullResult(false, null, null, "W|A did not succeed on this query.")
            }

            val pods = queryResult.optJSONArray("pods")

            // We will build:
            // 1) primaryText: JOIN of ALL plaintexts in the primary pod (so multiple answers show up)
            // 2) podsPreview: one line per pod (first plaintext if present)
            var primaryText: String? = null
            val podsPreviewLines = mutableListOf<String>()

            // Helper to collect every non-blank plaintext from a pod's subpods
            fun collectSubpodPlaintexts(podObj: JSONObject): List<String> {
                val subpods = podObj.optJSONArray("subpods") ?: return emptyList()
                val out = ArrayList<String>(subpods.length())
                for (j in 0 until subpods.length()) {
                    val sp = subpods.optJSONObject(j) ?: continue
                    val plain = sp.optString("plaintext").takeIf { !it.isNullOrBlank() } ?: continue
                    out += plain
                }
                return out
            }

            if (pods != null) {
                // First pass: previews and find a primary pod
                var primaryPodCollected = false
                for (i in 0 until pods.length()) {
                    val pod = pods.optJSONObject(i) ?: continue
                    val title = pod.optString("title")
                    val plains = collectSubpodPlaintexts(pod)

                    // Build preview line (use first plaintext if available)
                    if (plains.isNotEmpty()) {
                        podsPreviewLines += "• $title: ${plains.first()}"
                    } else if (title.isNotBlank()) {
                        podsPreviewLines += "• $title"
                    }

                    // If this is the primary pod, join ALL non-blank plaintexts
                    if (!primaryPodCollected && pod.optBoolean("primary")) {
                        if (plains.isNotEmpty()) {
                            // Join with newlines so multiple answers are visible to the caller
                            primaryText = plains.joinToString(separator = ",")
                        } else if (title.isNotBlank()) {
                            primaryText = title
                        }
                        primaryPodCollected = true
                    }
                }

                // Fallback: if no "primary" pod, try a reasonable default like "Result" / "Results"
                if (primaryText.isNullOrBlank()) {
                    // Look again for a pod titled "Result(s)"
                    for (i in 0 until pods.length()) {
                        val pod = pods.optJSONObject(i) ?: continue
                        val title = pod.optString("title")
                        if (title.equals("Result", ignoreCase = true) ||
                            title.equals("Results", ignoreCase = true)
                        ) {
                            val plains = collectSubpodPlaintexts(pod)
                            if (plains.isNotEmpty()) {
                                primaryText = plains.joinToString(separator = ",")
                                break
                            } else if (title.isNotBlank()) {
                                primaryText = title
                                break
                            }
                        }
                    }
                }

                // Final fallback: if still null/blank, try first pod with any plaintexts
                if (primaryText.isNullOrBlank()) {
                    for (i in 0 until pods.length()) {
                        val pod = pods.optJSONObject(i) ?: continue
                        val plains = collectSubpodPlaintexts(pod)
                        if (plains.isNotEmpty()) {
                            primaryText = plains.joinToString(separator = ",")
                            break
                        }
                    }
                }
            }

            FullResult(
                ok = true,
                primaryText = primaryText,
                podsPreview = podsPreviewLines.joinToString("\n")
            )
        } catch (e: Exception) {
            FullResult(false, null, null, e.message ?: "Network error")
        }
    }

    // ---- internals ----

    private fun urlEncode(s: String): String = try {
        URLEncoder.encode(s, "UTF-8")
    } catch (_: UnsupportedEncodingException) {
        s
    }

    private fun httpGet(urlStr: String): Pair<Int, String?> {
        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { BufferedReader(InputStreamReader(it)).readText() }
            code to body
        } finally {
            conn.disconnect()
        }
    }
}
