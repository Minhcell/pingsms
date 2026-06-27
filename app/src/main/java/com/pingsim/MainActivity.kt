package com.pingsim

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.CountDownTimer
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pingsim.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var pingManager: PingManager
    private val hlrManager = HlrManager()
    private lateinit var adapter: HistoryAdapter

    private val history = ArrayList<PingRecord>()
    private val subIds = ArrayList<Int>()   // song song spinner; -1 = SIM mặc định
    private var countDown: CountDownTimer? = null

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.READ_PHONE_STATE] == true) loadSims()
        if (result[Manifest.permission.SEND_SMS] == true) toast(getString(R.string.perm_ok))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        pingManager = PingManager(this)

        adapter = HistoryAdapter(history) { rec ->
            binding.editPhone.setText(rec.phone)
            binding.editPhone.setSelection(rec.phone.length)
            binding.editPhone.requestFocus()
            binding.scrollTop.smoothScrollTo(0, 0)
            toast(getString(R.string.history_tap, rec.phone))
        }
        binding.recyclerHistory.layoutManager = LinearLayoutManager(this)
        binding.recyclerHistory.adapter = adapter

        binding.slider.addOnChangeListener { _, value, _ ->
            binding.txtTimeout.text = getString(R.string.timeout_label, value.toInt())
        }
        binding.txtTimeout.text = getString(R.string.timeout_label, binding.slider.value.toInt())

        loadCreds()
        binding.btnPing.setOnClickListener { onPingClicked() }
        binding.btnClearHistory.setOnClickListener { confirmClearHistory() }

        requestNeededPermissions()
    }

    // ---------- Quyền & SIM ----------

    private fun requestNeededPermissions() {
        val need = ArrayList<String>()
        if (!granted(Manifest.permission.SEND_SMS)) need.add(Manifest.permission.SEND_SMS)
        if (!granted(Manifest.permission.READ_PHONE_STATE)) need.add(Manifest.permission.READ_PHONE_STATE)
        if (need.isNotEmpty()) permLauncher.launch(need.toTypedArray()) else loadSims()
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun loadSims() {
        val entries = ArrayList<String>()
        subIds.clear()
        if (granted(Manifest.permission.READ_PHONE_STATE)) {
            try {
                val sm = getSystemService(SubscriptionManager::class.java)
                val list: List<SubscriptionInfo>? = sm?.activeSubscriptionInfoList
                list?.forEach { info ->
                    val name = info.displayName?.toString() ?: "SIM"
                    val carrier = info.carrierName?.toString() ?: ""
                    entries.add("SIM ${info.simSlotIndex + 1}: $name $carrier".trim())
                    subIds.add(info.subscriptionId)
                }
            } catch (_: Exception) { }
        }
        if (entries.isEmpty()) {
            entries.add(getString(R.string.default_sim))
            subIds.add(-1)
        }
        val a = ArrayAdapter(this, android.R.layout.simple_spinner_item, entries)
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerSim.adapter = a

        val show = if (subIds.size > 1) View.VISIBLE else View.GONE
        binding.spinnerSim.visibility = show
        binding.labelSim.visibility = show
    }

    // ---------- API key (lưu/đọc) ----------

    private fun prefs() = getSharedPreferences("pingsms", Context.MODE_PRIVATE)

    private fun loadCreds() {
        binding.editUserId.setText(prefs().getString("user_id", ""))
        binding.editApiKey.setText(prefs().getString("api_key", ""))
    }

    private fun saveCreds(userId: String, apiKey: String) {
        prefs().edit().putString("user_id", userId).putString("api_key", apiKey).apply()
    }

    // ---------- Điều phối theo chế độ ----------

    private fun onPingClicked() {
        val phone = binding.editPhone.text?.toString()?.trim().orEmpty()
        if (phone.isEmpty()) {
            binding.editPhone.error = getString(R.string.err_phone)
            return
        }

        when (binding.modeGroup.checkedButtonId) {
            R.id.btnModeHlr -> runHlr(phone, fallbackToSms = false)
            R.id.btnModeBoth -> {
                if (!ensureSmsPermission()) return
                runHlr(phone, fallbackToSms = true)
            }
            else -> {
                if (!ensureSmsPermission()) return
                runSms(phone)
            }
        }
    }

    private fun ensureSmsPermission(): Boolean {
        if (granted(Manifest.permission.SEND_SMS)) return true
        permLauncher.launch(
            arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE)
        )
        toast(getString(R.string.need_perm))
        return false
    }

    // ---------- Chế độ SMS ----------

    private fun runSms(phone: String) {
        val content = binding.editContent.text?.toString()?.trim().orEmpty()
        val timeout = binding.slider.value.toInt()
        val subId = subIds.getOrElse(binding.spinnerSim.selectedItemPosition) { -1 }

        setBusy(true)
        binding.txtStatus.text = getString(R.string.status_sending)
        binding.txtStatus.setTextColor(ContextCompat.getColor(this, R.color.neutral))
        startCountdown(timeout)

        pingManager.ping(phone, content, timeout, subId, object : PingManager.Callback {
            override fun onSending() = runOnUiThread {
                binding.txtStatus.text = getString(R.string.status_sending)
            }

            override fun onSent(code: String) = runOnUiThread {
                binding.txtStatus.text = getString(R.string.status_waiting, code)
            }

            override fun onResult(
                outcome: PingManager.Outcome,
                detail: String,
                code: String,
                elapsedMs: Long
            ) = runOnUiThread {
                stopCountdown()
                setBusy(false)
                showOutcome(outcome, detail)
                addHistory(phone, "SMS", code, outcome, detail, elapsedMs)
            }
        })
    }

    // ---------- Chế độ HLR (im lặng) ----------

    private fun runHlr(phone: String, fallbackToSms: Boolean) {
        val userId = binding.editUserId.text?.toString()?.trim().orEmpty()
        val apiKey = binding.editApiKey.text?.toString()?.trim().orEmpty()
        if (userId.isEmpty() || apiKey.isEmpty()) {
            toast(getString(R.string.need_api))
            binding.editUserId.requestFocus()
            binding.scrollTop.smoothScrollTo(0, 0)
            return
        }
        saveCreds(userId, apiKey)

        setBusy(true)
        binding.txtStatus.text = getString(R.string.status_hlr)
        binding.txtStatus.setTextColor(ContextCompat.getColor(this, R.color.neutral))

        hlrManager.lookup(phone, userId, apiKey, "VN", object : HlrManager.Callback {
            override fun onResult(res: HlrManager.HlrResult) = runOnUiThread {
                val inconclusive = res.status == "unknown" || res.status == "failed" || res.status == "error"
                addHistory(phone, "HLR", "", res.outcome, res.detail, res.elapsedMs)

                if (fallbackToSms && inconclusive && ensureSmsPermission()) {
                    binding.txtStatus.text = getString(R.string.status_hlr_to_sms)
                    runSms(phone)
                } else {
                    setBusy(false)
                    showOutcome(res.outcome, res.detail)
                }
            }
        })
    }

    // ---------- Tiện ích chung ----------

    private fun showOutcome(outcome: PingManager.Outcome, detail: String) {
        val (label, colorRes) = when (outcome) {
            PingManager.Outcome.ON -> getString(R.string.res_on) to R.color.green
            PingManager.Outcome.OFF -> getString(R.string.res_off) to R.color.red
            PingManager.Outcome.FAILED -> getString(R.string.res_failed) to R.color.orange
        }
        binding.txtStatus.text = "$label\n$detail"
        binding.txtStatus.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun addHistory(
        phone: String, method: String, code: String,
        outcome: PingManager.Outcome, detail: String, elapsedMs: Long
    ) {
        history.add(0, PingRecord(phone, method, code, outcome, detail, now(), elapsedMs))
        adapter.notifyItemInserted(0)
        binding.recyclerHistory.scrollToPosition(0)
    }

    private fun confirmClearHistory() {
        if (history.isEmpty()) {
            toast(getString(R.string.history_empty))
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_clear_title)
            .setMessage(R.string.confirm_clear_msg)
            .setNegativeButton(R.string.confirm_clear_cancel, null)
            .setPositiveButton(R.string.confirm_clear_ok) { _, _ ->
                val n = history.size
                history.clear()
                adapter.notifyItemRangeRemoved(0, n)
                toast(getString(R.string.history_cleared))
            }
            .show()
    }

    private fun startCountdown(sec: Int) {
        stopCountdown()
        binding.txtCountdown.visibility = View.VISIBLE
        countDown = object : CountDownTimer(sec * 1000L, 1000) {
            override fun onTick(ms: Long) {
                binding.txtCountdown.text = getString(R.string.countdown, (ms / 1000).toInt() + 1)
            }
            override fun onFinish() { binding.txtCountdown.text = "" }
        }.start()
    }

    private fun stopCountdown() {
        countDown?.cancel()
        countDown = null
        binding.txtCountdown.text = ""
        binding.txtCountdown.visibility = View.GONE
    }

    private fun setBusy(busy: Boolean) {
        binding.btnPing.isEnabled = !busy
        binding.editPhone.isEnabled = !busy
        binding.editContent.isEnabled = !busy
        binding.slider.isEnabled = !busy
        binding.spinnerSim.isEnabled = !busy
        binding.modeGroup.isEnabled = !busy
        binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.btnPing.text = getString(if (busy) R.string.btn_pinging else R.string.btn_ping)
    }

    private fun now(): String =
        SimpleDateFormat("HH:mm:ss dd/MM", Locale.getDefault()).format(Date())

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
