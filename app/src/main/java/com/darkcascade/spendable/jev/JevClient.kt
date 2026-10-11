package com.darkcascade.spendable.jev

import com.darkcascade.spendable.engine.Candidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

class JevException(message: String) : IOException(message)

/**
 * Minimal client for TypeSafe's Jev classifier (POST https://api.typesafe.ai/v1/systemone).
 *
 * Each recurring-expense candidate becomes a *state* (merchant, descriptors, the monthly charges
 * the heuristic engine matched) plus one *noul* question: "is this a true recurring bill?".
 * The noul (0..1 probability of yes) is blended with the heuristic score by the caller.
 */
class JevClient(
    private val apiKey: String,
    private val model: String = "jev-latest",
    private val endpoint: String = "https://api.typesafe.ai/v1/systemone",
) {

    /** Returns one noul per candidate (null where that call failed). Throws only if *every* call failed. */
    suspend fun scoreRecurring(candidates: List<Candidate>, parallelism: Int = 4): List<Double?> = coroutineScope {
        val gate = Semaphore(parallelism)
        var lastError: Exception? = null
        val results = candidates.map { c ->
            async {
                gate.withPermit {
                    try {
                        noul(stateFor(c))
                    } catch (e: Exception) {
                        lastError = e
                        null
                    }
                }
            }
        }.awaitAll()
        if (candidates.isNotEmpty() && results.all { it == null }) throw JevException(lastError?.message ?: "Jev call failed")
        results
    }

    /** Cheap connectivity/auth check used by the settings screen. */
    suspend fun ping(): Double = noul(JSONObject().put("merchant", "NETFLIX.COM").put("charges", JSONArray()))

    private fun stateFor(c: Candidate): JSONObject = JSONObject()
        .put("merchant", c.displayName)
        .put("bank_descriptors", JSONArray(c.charges.map { it.rawDescription }.distinct().take(3)))
        .put("bank_category", c.charges.groupingBy { it.category }.eachCount().maxByOrNull { it.value }?.key ?: "")
        .put("pattern", "Charged on or near day ${c.dayOfMonth} of the month in ${c.hits} of the last ${c.expectedMonths} months")
        .put(
            "monthly_charges",
            JSONArray(c.charges.map { JSONObject().put("date", it.date.toString()).put("amount_usd", abs(it.amountCents) / 100.0) }),
        )
        .put("other_similar_charges_from_merchant_in_same_period", c.similarChargesInSpan - c.hits)

    private suspend fun noul(state: Any): Double {
        val body = JSONObject()
            .put("model", model)
            .put("state", state)
            .put(
                "questions",
                JSONObject().put(
                    "is_recurring_bill",
                    JSONObject()
                        .put("type", "noul")
                        .put(
                            "instructions",
                            "These charges are a recurring bill or subscription that will automatically charge again " +
                                "next month on about the same day for about the same amount.",
                        )
                        .put(
                            "criteria",
                            JSONObject()
                                .put("true", "Subscription, membership, insurance premium, loan or car payment, rent, utility, phone or internet bill, or a scheduled autopay.")
                                .put("false", "Separate everyday purchases (groceries, gas, restaurants, shopping, rides, toll top-ups, transfers) that just happen to land on similar dates."),
                        ),
                ),
            )
        val json = post(body)
        return json.getJSONObject("answers").getJSONObject("is_recurring_bill").getDouble("noul")
    }

    private suspend fun post(body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        var attempt = 0
        while (true) {
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 30_000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
            }
            try {
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = conn.responseCode
                if (code in 200..299) return@withContext JSONObject(conn.inputStream.bufferedReader().readText())
                val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                if ((code == 429 || code == 529 || code >= 500) && attempt < 3) {
                    delay(1000L shl attempt)
                    attempt++
                    continue
                }
                throw JevException(
                    when (code) {
                        401, 403 -> "Jev rejected the API key ($code)."
                        422 -> "Jev rejected the request (422): ${err.take(200)}"
                        else -> "Jev error $code: ${err.take(200)}"
                    },
                )
            } finally {
                conn.disconnect()
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }
}
