package com.pingsim

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.telephony.SmsMessage
import androidx.core.content.ContextCompat
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

/**
 * PingManager: gửi 1 tin SMS có yêu cầu "báo cáo gửi" (delivery report).
 *
 * Nguyên lý xác định bật/tắt máy:
 *  - Gửi SMS kèm deliveryIntent. SMSC của nhà mạng sẽ gửi lại bản tin
 *    SMS-STATUS-REPORT (TP-Status) khi máy đích thực sự nhận được tin.
 *  - Nếu nhận được TP-Status = "đã giao" trong thời gian chờ  -> MÁY ĐANG MỞ.
 *  - Nếu hết thời gian chờ mà KHÔNG có báo cáo giao             -> MÁY TẮT / NGOÀI VÙNG.
 *  - Nếu TP-Status báo lỗi vĩnh viễn                            -> SỐ LỖI (sai/chặn/không tồn tại).
 *
 * Các dải TP-Status theo 3GPP TS 23.040:
 *  0x00..0x1F : đã giao tới máy đích   (COMPLETE)
 *  0x20..0x3F : tạm thời, SMSC đang thử lại (PENDING -> chờ tiếp)
 *  0x40..0x7F : lỗi vĩnh viễn / SMSC bỏ cuộc (FAILED)
 */
class PingManager(private val context: Context) {

    enum class Outcome { ON, OFF, FAILED }

    interface Callback {
        fun onSending()
        fun onSent(code: String)
        fun onResult(outcome: Outcome, detail: String, code: String, elapsedMs: Long)
    }

    companion object {
        private val counter = AtomicInteger(1000)
        private val rnd = SecureRandom()
        // Bỏ các ký tự dễ nhầm (0,O,1,I) cho mã kiểm tra dễ đọc.
        private const val CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        fun newCode(): String {
            val sb = StringBuilder(5)
            repeat(5) { sb.append(CHARS[rnd.nextInt(CHARS.length)]) }
            return sb.toString()
        }

        private const val ACTION_SENT = "com.pingsim.SMS_SENT"
        private const val ACTION_DELIVERED = "com.pingsim.SMS_DELIVERED"
    }

    fun ping(
        phone: String,
        content: String,
        timeoutSec: Int,
        subId: Int,
        callback: Callback
    ) {
        Session(phone, content, timeoutSec, subId, callback).start()
    }

    private inner class Session(
        val phone: String,
        content: String,
        val timeoutSec: Int,
        val subId: Int,
        val callback: Callback
    ) {
        val id = counter.incrementAndGet()
        val code = newCode()
        // Chèn mã kiểm tra (đổi mỗi lần gửi) vào cuối nội dung.
        val message: String = if (content.isBlank()) code else "$content $code"
        val start = System.currentTimeMillis()

        val sentAction = "$ACTION_SENT.$id"
        val deliveredAction = "$ACTION_DELIVERED.$id"

        val handler = Handler(Looper.getMainLooper())
        var resolved = false
        var sentNotified = false

        lateinit var sentReceiver: BroadcastReceiver
        lateinit var deliveredReceiver: BroadcastReceiver

        val timeoutRunnable = Runnable {
            resolve(
                Outcome.OFF,
                "Không nhận được báo cáo giao sau ${timeoutSec}s → máy TẮT hoặc NGOÀI VÙNG PHỦ SÓNG " +
                    "(không có mạng / hết pin / tắt nguồn nên SMSC chưa giao được)."
            )
        }

        fun start() {
            callback.onSending()
            registerReceivers()
            try {
                sendSms()
            } catch (e: Exception) {
                resolve(Outcome.FAILED, "Không gửi được: ${e.message ?: "lỗi không xác định"}")
                return
            }
            handler.postDelayed(timeoutRunnable, timeoutSec * 1000L)
        }

        private fun registerReceivers() {
            sentReceiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) = handleSent(resultCode)
            }
            deliveredReceiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) = handleDelivered(intent, resultCode)
            }
            ContextCompat.registerReceiver(
                context, sentReceiver, IntentFilter(sentAction),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            ContextCompat.registerReceiver(
                context, deliveredReceiver, IntentFilter(deliveredAction),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }

        // sentIntent chỉ cần đọc resultCode -> để IMMUTABLE.
        private fun flagsImmutable(): Int =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        // deliveryIntent cần nhận PDU (TP-Status) do hệ thống nhồi vào -> phải MUTABLE.
        private fun flagsMutable(): Int {
            var f = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                f = f or PendingIntent.FLAG_MUTABLE
            }
            return f
        }

        private fun sendSms() {
            val sms = getSmsManager(subId)
            val parts = sms.divideMessage(message)

            val sentPI = PendingIntent.getBroadcast(
                context, id,
                Intent(sentAction).setPackage(context.packageName), flagsImmutable()
            )
            val delivPI = PendingIntent.getBroadcast(
                context, id + 500000,
                Intent(deliveredAction).setPackage(context.packageName), flagsMutable()
            )

            val sentList = ArrayList<PendingIntent>(parts.size)
            val delivList = ArrayList<PendingIntent>(parts.size)
            for (i in parts.indices) {
                sentList.add(sentPI)
                delivList.add(delivPI)
            }
            sms.sendMultipartTextMessage(phone, null, parts, sentList, delivList)
        }

        private fun handleSent(resultCode: Int) {
            when (resultCode) {
                Activity.RESULT_OK -> {
                    if (!sentNotified) {
                        sentNotified = true
                        callback.onSent(code)
                    }
                }
                SmsManager.RESULT_ERROR_NO_SERVICE ->
                    resolve(Outcome.FAILED, "Máy đang gửi KHÔNG có sóng/dịch vụ (NO_SERVICE).")
                SmsManager.RESULT_ERROR_RADIO_OFF ->
                    resolve(Outcome.FAILED, "Radio đang tắt — kiểm tra chế độ máy bay (RADIO_OFF).")
                SmsManager.RESULT_ERROR_NULL_PDU ->
                    resolve(Outcome.FAILED, "Lỗi nội bộ NULL_PDU.")
                SmsManager.RESULT_ERROR_GENERIC_FAILURE ->
                    resolve(Outcome.FAILED, "Gửi thất bại (GENERIC_FAILURE) — có thể hết tiền/ bị nhà mạng chặn.")
                else ->
                    resolve(Outcome.FAILED, "Gửi thất bại, mã lỗi = $resultCode.")
            }
        }

        private fun handleDelivered(intent: Intent, resultCode: Int) {
            val status = parseStatus(intent, resultCode)
            when {
                status in 0x00..0x1F -> resolve(
                    Outcome.ON,
                    "Đã nhận báo cáo GIAO THÀNH CÔNG → MÁY ĐANG MỞ và trong vùng phủ sóng. (TP-Status=$status)"
                )
                status in 0x20..0x3F -> {
                    // Tạm thời: SMSC đang thử giao, tiếp tục chờ đến hết timeout.
                    callback.onSent(code) // giữ trạng thái "đang chờ"
                }
                status in 0x40..0x7F -> resolve(
                    Outcome.FAILED,
                    "Nhà mạng báo LỖI VĨNH VIỄN (TP-Status=$status): số có thể sai, không tồn tại, hoặc bị chặn."
                )
                else -> { /* không xác định -> bỏ qua, chờ tiếp */ }
            }
        }

        private fun parseStatus(intent: Intent, resultCode: Int): Int {
            val pdu = intent.getByteArrayExtra("pdu")
            val format = intent.getStringExtra("format")
            if (pdu != null) {
                try {
                    val sms = if (format != null) SmsMessage.createFromPdu(pdu, format)
                    else @Suppress("DEPRECATION") SmsMessage.createFromPdu(pdu)
                    if (sms != null) return sms.status
                } catch (_: Exception) { }
            }
            // Dự phòng nếu không đọc được PDU.
            return if (resultCode == Activity.RESULT_OK) 0 else 0x40
        }

        private fun resolve(outcome: Outcome, detail: String) {
            if (resolved) return
            resolved = true
            handler.removeCallbacks(timeoutRunnable)
            safeUnregister(sentReceiver)
            safeUnregister(deliveredReceiver)
            val elapsed = System.currentTimeMillis() - start
            callback.onResult(outcome, detail, code, elapsed)
        }

        private fun safeUnregister(r: BroadcastReceiver?) {
            if (r == null) return
            try {
                context.unregisterReceiver(r)
            } catch (_: Exception) { }
        }
    }

    private fun getSmsManager(subId: Int): SmsManager {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val base = context.getSystemService(SmsManager::class.java)
            if (subId >= 0) base.createForSubscriptionId(subId) else base
        } else {
            @Suppress("DEPRECATION")
            if (subId >= 0) SmsManager.getSmsManagerForSubscriptionId(subId)
            else SmsManager.getDefault()
        }
    }
}
