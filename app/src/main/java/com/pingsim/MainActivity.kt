package com.pingsim

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import com.pingsim.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var pingManager: PingManager
    private lateinit var adapter: HistoryAdapter

    private val history = ArrayList<PingRecord>()
    private val subIds = ArrayList<Int>()   // song song với spinner; -1 = SIM mặc định
    private var countDown: CountDownTimer? = null

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.READ_PHONE_STATE] == true) loadSims()
        if (result[Manifest.permission.SEND_SMS] == true) {
            toast(getString(R.string.perm_ok))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        pingManager = PingManager(this)

        adapter = HistoryAdapter(history)
        binding.recyclerHistory.layoutManager = LinearLayoutManager(this)
        binding.recyclerHistory.adapter = adapter

        binding.slider.addOnChangeListener { _, value, _ ->
            binding.txtTimeout.text = getString(R.string.timeout_label, value.toInt())
        }
        binding.txtTimeout.text = getString(R.string.timeout_label, binding.slider.value.toInt())

        binding.btnPing.setOnClickListener { onPingClicked() }

        requestNeededPermissions()
    }

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

    private fun onPingClicked() {
        val phone = binding.editPhone.text?.toString()?.trim().orEmpty()
        if (phone.isEmpty()) {
            binding.editPhone.error = getString(R.string.err_phone)
            return
        }
        if (!granted(Manifest.permission.SEND_SMS)) {
            permLauncher.launch(
                arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE)
            )
            toast(getString(R.string.need_perm))
            return
        }

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

                val (label, colorRes) = when (outcome) {
                    PingManager.Outcome.ON -> getString(R.string.res_on) to R.color.green
                    PingManager.Outcome.OFF -> getString(R.string.res_off) to R.color.red
                    PingManager.Outcome.FAILED -> getString(R.string.res_failed) to R.color.orange
                }
                binding.txtStatus.text = "$label\n$detail"
                binding.txtStatus.setTextColor(ContextCompat.getColor(this@MainActivity, colorRes))

                history.add(
                    0,
                    PingRecord(phone, code, outcome, detail, now(), elapsedMs)
                )
                adapter.notifyItemInserted(0)
                binding.recyclerHistory.scrollToPosition(0)
            }
        })
    }

    private fun startCountdown(sec: Int) {
        stopCountdown()
        binding.txtCountdown.visibility = View.VISIBLE
        countDown = object : CountDownTimer(sec * 1000L, 1000) {
            override fun onTick(ms: Long) {
                binding.txtCountdown.text =
                    getString(R.string.countdown, (ms / 1000).toInt() + 1)
            }
            override fun onFinish() {
                binding.txtCountdown.text = ""
            }
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
        binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.btnPing.text =
            getString(if (busy) R.string.btn_pinging else R.string.btn_ping)
    }

    private fun now(): String =
        SimpleDateFormat("HH:mm:ss dd/MM", Locale.getDefault()).format(Date())

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
