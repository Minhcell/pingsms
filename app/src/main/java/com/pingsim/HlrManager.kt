package com.pingsim

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * HlrManager: tra cứu trạng thái thuê bao qua HLR Lookup của Neutrino API.
 *
 * Đây là kiểu "ping im lặng" — KHÔNG gửi tin nào tới người nhận,
 * mà hỏi thẳng cơ sở dữ liệu HLR của mạng di động.
 *
 * Endpoint: POST https://neutrinoapi.net/hlr-lookup
 * Auth: header User-ID + API-Key
 * Trả về field hlr-status:
 *   ok        -> đang kết nối mạng (MÁY HOẠT ĐỘNG)
 *   absent    -> đã từng đăng ký nhưng tắt máy / ngoài vùng (MÁY TẮT)
 *   unknown   -> mạng không biết số này
 *   invalid   -> không phải số di động hợp lệ
 *   fixed-line-> số cố định
 *   voip      -> số VoIP
 *   failed    -> tra cứu thất bại
 */
class HlrManager {

    data class HlrResult(
        val outcome: PingManager.Outcome,
        val detail: String,
        val status: String,   // hlr-status thô, hoặc "error"
        val elapsedMs: Long
    )

    interface Callback {
        fun onResult(res: HlrResult)
    }

    private val main = Handler(Looper.getMainLooper())

    fun lookup(
        phone: String,
        userId: String,
        apiKey: String,
        countryCode: String,
        callback: Callback
    ) {
        val start = System.currentTimeMillis()
        Thread {
            val res = try {
                doLookup(phone, userId, apiKey, countryCode)
            } catch (e: Exception) {
                Pair(PingManager.Outcome.FAILED to "error", "Lỗi kết nối: ${e.message ?: "không xác định"}")
            }
            val elapsed = System.currentTimeMillis() - start
            val result = HlrResult(res.first.first, res.second, res.first.second, elapsed)
            main.post { callback.onResult(result) }
        }.start()
    }

    // Trả Pair< (Outcome, statusThô), detail >
    private fun doLookup(
        phone: String,
        userId: String,
        apiKey: String,
        countryCode: String
    ): Pair<Pair<PingManager.Outcome, String>, String> {
        val url = URL("https://neutrinoapi.net/hlr-lookup")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 20000
        conn.readTimeout = 20000
        conn.doOutput = true
        conn.setRequestProperty("User-ID", userId)
        conn.setRequestProperty("API-Key", apiKey)
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

        val body = "number=" + URLEncoder.encode(phone, "UTF-8") +
            "&country-code=" + URLEncoder.encode(countryCode, "UTF-8")
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

        val httpCode = conn.responseCode
        val stream = if (httpCode in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
        conn.disconnect()

        if (text.isBlank()) {
            return (PingManager.Outcome.FAILED to "error") to "Máy chủ không trả dữ liệu (HTTP $httpCode)."
        }

        val json = JSONObject(text)
        val apiError = json.optInt("api-error", 0)
        if (apiError > 0) {
            val msg = json.optString("api-error-msg", "Lỗi API #$apiError")
            return (PingManager.Outcome.FAILED to "error") to "API báo lỗi: $msg (mã $apiError)."
        }

        val status = json.optString("hlr-status", "")
        val network = json.optString("origin-network", "")
        val country = json.optString("country", "")
        val ported = json.optBoolean("is-ported", false)
        val roaming = json.optBoolean("is-roaming", false)

        val extra = buildString {
            if (network.isNotBlank()) append(" • Mạng: $network")
            if (country.isNotBlank()) append(" • $country")
            if (ported) append(" • đã chuyển mạng giữ số")
            if (roaming) append(" • đang roaming")
        }

        val pair = when (status) {
            "ok" -> (PingManager.Outcome.ON to status) to
                "HLR: đang KẾT NỐI mạng → MÁY HOẠT ĐỘNG (im lặng, không gửi tin).$extra"
            "absent" -> (PingManager.Outcome.OFF to status) to
                "HLR: thuê bao VẮNG MẶT → TẮT MÁY hoặc NGOÀI VÙNG PHỦ SÓNG.$extra"
            "unknown" -> (PingManager.Outcome.FAILED to status) to
                "HLR: mạng KHÔNG BIẾT số này (chưa kích hoạt / số không tồn tại).$extra"
            "invalid" -> (PingManager.Outcome.FAILED to status) to
                "HLR: số không hợp lệ (không phải MSISDN di động)."
            "fixed-line" -> (PingManager.Outcome.FAILED to status) to
                "Đây là số CỐ ĐỊNH, không phải di động."
            "voip" -> (PingManager.Outcome.FAILED to status) to
                "Đây là số VoIP, không phải SIM di động."
            "failed" -> (PingManager.Outcome.FAILED to status) to
                "HLR tra cứu THẤT BẠI, chưa xác định được trạng thái.$extra"
            else -> (PingManager.Outcome.FAILED to "failed") to
                "HLR trả trạng thái lạ: '$status'."
        }
        return pair
    }
}
