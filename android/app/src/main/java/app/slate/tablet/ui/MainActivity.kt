package app.slate.tablet.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import app.slate.tablet.R
import app.slate.tablet.link.Discovery
import app.slate.tablet.link.FoundPc
import app.slate.tablet.link.SlateLink
import app.slate.tablet.link.Transport
import app.slate.tablet.link.WifiLink
import app.slate.tablet.protocol.Protocol
import java.net.InetAddress
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/** Setup screen: connection status, Wi-Fi PC list, and drawing-surface options. */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var discovery: Discovery
    private lateinit var statusText: TextView
    private lateinit var pcList: LinearLayout
    private lateinit var pcEmpty: TextView
    private lateinit var openCanvas: Button
    private lateinit var disconnect: Button

    private var lastPhase = SlateLink.status.phase
    private var resumed = false

    /** The Wi-Fi PC we're pairing with, so its code is remembered once the PC accepts it. */
    private var pairing: Pair<String, Int>? = null

    private val onStatus: (SlateLink.Status) -> Unit = { s ->
        statusText.text = when (s.phase) {
            SlateLink.Phase.IDLE -> s.message.ifEmpty { getString(R.string.status_idle) }
            SlateLink.Phase.CONNECTING -> s.message
            SlateLink.Phase.CONNECTED -> if (s.rttMs >= 0) "${s.message} · ${s.rttMs} ms round trip" else s.message
        }
        val connected = s.phase == SlateLink.Phase.CONNECTED
        disconnect.isEnabled = s.phase != SlateLink.Phase.IDLE
        if (connected && lastPhase != SlateLink.Phase.CONNECTED) {
            pairing?.let { (name, code) -> if (s.transport == Transport.WIFI) prefs.savePairCode(name, code) }
            pairing = null
            if (resumed) startActivity(Intent(this, CanvasActivity::class.java))
        }
        lastPhase = s.phase
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        statusText = findViewById(R.id.status)
        pcList = findViewById(R.id.pc_list)
        pcEmpty = findViewById(R.id.pc_empty)
        openCanvas = findViewById(R.id.open_canvas)
        disconnect = findViewById(R.id.disconnect)

        openCanvas.setOnClickListener { startActivity(Intent(this, CanvasActivity::class.java)) }
        disconnect.setOnClickListener { SlateLink.disconnect() }
        findViewById<Button>(R.id.connect_ip).setOnClickListener { askForIp() }

        bindAreaScale()
        findViewById<Button>(R.id.study_history).setOnClickListener {
            startActivity(Intent(this, app.slate.tablet.study.StudyActivity::class.java))
        }
        findViewById<Button>(R.id.study_ask_tablet).setOnClickListener {
            startActivity(Intent(this, app.slate.tablet.study.CaptureActivity::class.java))
        }
        findViewById<Button>(R.id.side_chat_permission).setOnClickListener {
            if (app.slate.tablet.study.SideChat.canShow(this)) Toast.makeText(this, R.string.side_chat_on, Toast.LENGTH_LONG).show()
            else app.slate.tablet.study.SideChat.requestPermission(this)
        }
        val shortcutField = findViewById<EditText>(R.id.shortcuts).apply { setText(prefs.shortcuts) }
        findViewById<Button>(R.id.shortcuts_save).setOnClickListener {
            prefs.shortcuts = shortcutField.text.toString().ifBlank { Prefs.DEFAULT_SHORTCUTS }
            shortcutField.setText(prefs.shortcuts)
            Toast.makeText(this, R.string.shortcuts_saved, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.ime_enable).setOnClickListener {
            startActivity(Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<Button>(R.id.ime_pick).setOnClickListener {
            getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.showInputMethodPicker()
        }
        findViewById<Button>(R.id.ime_switch_button).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.ime_switch_button)
                .setMessage(R.string.ime_switch_help)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        bindSwitch(R.id.add_space, prefs.addSpaceAfterText) { prefs.addSpaceAfterText = it }
        bindSwitch(R.id.ask_api_fallback, prefs.askApiFallback) { prefs.askApiFallback = it }
        bindSwitch(R.id.writing_via_claude_code, prefs.writingViaClaudeCode) { prefs.writingViaClaudeCode = it }
        findViewById<Button>(R.id.termux_check).setOnClickListener { checkTermux() }
        findViewById<Button>(R.id.termux_setup).setOnClickListener { setUpTermux() }
        bindSwitch(R.id.fast_handwriting, prefs.fastHandwriting) { prefs.fastHandwriting = it }
        val keyField = findViewById<EditText>(R.id.api_key).apply { setText(prefs.apiKey) }
        findViewById<Button>(R.id.api_key_save).setOnClickListener {
            prefs.apiKey = keyField.text.toString()
            val msg = if (prefs.apiKey.isEmpty()) R.string.api_key_cleared else R.string.api_key_saved
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
        bindSwitch(R.id.keep_screen_on, prefs.keepScreenOn) { prefs.keepScreenOn = it }
        bindSwitch(R.id.dark_canvas, prefs.darkCanvas) { prefs.darkCanvas = it }
        bindSwitch(R.id.show_outline, prefs.showOutline) { prefs.showOutline = it }
        bindSwitch(R.id.show_status, prefs.showStatus) { prefs.showStatus = it }

        discovery = Discovery(this, ::showPcs)
        SlateLink.start()
    }

    override fun onStart() {
        super.onStart()
        lastPhase = SlateLink.status.phase
        SlateLink.addListener(onStatus)
        discovery.start()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        checkTermux()
        findViewById<TextView>(R.id.spend).text = getString(
            R.string.spend_label, app.slate.tablet.ai.Spend.thisMonthUsd(), app.slate.tablet.ai.Spend.callsThisMonth(),
        )
    }

    /** Shows whether the Termux bridge answers (checked off the main thread). */
    private fun checkTermux(then: ((Boolean) -> Unit)? = null) {
        val status = findViewById<TextView>(R.id.termux_status)
        thread(isDaemon = true) {
            val ok = app.slate.tablet.ai.TermuxClaude.available(this, fresh = true)
            runOnUiThread {
                status.setText(if (ok) R.string.termux_ok else R.string.termux_off)
                then?.invoke(ok)
            }
        }
    }

    /** Copies the one-line setup command for Termux, and watches for the bridge to start. */
    private fun setUpTermux() {
        val command = app.slate.tablet.ai.TermuxClaude.setupCommand(this)
        fun copy() {
            getSystemService(android.content.ClipboardManager::class.java)
                .setPrimaryClip(android.content.ClipData.newPlainText("Slate setup", command))
            Toast.makeText(this, R.string.termux_copied, Toast.LENGTH_SHORT).show()
        }
        copy()
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.termux_setup_title)
            .setMessage(R.string.termux_setup_message)
            .setPositiveButton(R.string.termux_copy, null)
            .setNegativeButton(R.string.close, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { copy() }
        val poll = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                checkTermux { ok ->
                    if (ok) dialog.setTitle(getString(R.string.termux_ok)) else statusText.postDelayed(this, 2000)
                }
            }
        }
        statusText.postDelayed(poll, 2000)
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onStop() {
        discovery.stop()
        SlateLink.removeListener(onStatus)
        super.onStop()
    }

    // Slider 0..75 ↔ area 25..100%.
    private fun bindAreaScale() {
        val label = findViewById<TextView>(R.id.area_label)
        val bar = findViewById<SeekBar>(R.id.area_scale)
        fun show(percent: Int) { label.text = getString(R.string.area_label, percent) }
        val percent = (prefs.areaScale * 100).roundToInt().coerceIn(25, 100)
        bar.progress = percent - 25
        show(percent)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                show(progress + 25)
                if (fromUser) prefs.areaScale = (progress + 25) / 100f
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun bindSwitch(id: Int, value: Boolean, save: (Boolean) -> Unit) {
        findViewById<Switch>(id).apply {
            isChecked = value
            setOnCheckedChangeListener { _, checked -> save(checked) }
        }
    }

    private fun showPcs(pcs: List<FoundPc>) {
        pcList.removeAllViews()
        pcEmpty.visibility = if (pcs.isEmpty()) TextView.VISIBLE else TextView.GONE
        for (pc in pcs) {
            pcList.addView(Button(this).apply {
                text = "${pc.name}  (${pc.address.hostAddress})"
                isAllCaps = false
                setOnClickListener { pair(pc.name, pc.address, pc.port) }
            })
        }
    }

    private fun pair(name: String, address: InetAddress, port: Int) {
        val saved = prefs.pairCode(name)
        val input = codeField().apply { saved?.let { setText("%04d".format(it)) } }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.pair_title, name))
            .setMessage(R.string.pair_message)
            .setView(padded(input))
            .setPositiveButton(R.string.connect) { _, _ ->
                val code = input.text.toString().toIntOrNull()
                if (input.text.length != 4 || code == null) {
                    Toast.makeText(this, R.string.bad_code, Toast.LENGTH_SHORT).show()
                } else {
                    pairing = name to code
                    WifiLink.connect(address, port, code, name)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** For networks that block broadcasts (guest Wi-Fi, some routers). */
    private fun askForIp() {
        val ip = EditText(this).apply {
            hint = getString(R.string.ip_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        val code = codeField()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ip)
            addView(code)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.connect_by_ip)
            .setView(padded(box))
            .setPositiveButton(R.string.connect) { _, _ ->
                val host = ip.text.toString().trim()
                val pairCode = code.text.toString().toIntOrNull()
                if (code.text.length != 4 || pairCode == null) {
                    Toast.makeText(this, R.string.bad_code, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                thread(isDaemon = true) {
                    val addr = if (IP_PATTERN.matches(host)) runCatching { InetAddress.getByName(host) }.getOrNull() else null
                    runOnUiThread {
                        if (addr == null) {
                            Toast.makeText(this, R.string.bad_ip, Toast.LENGTH_SHORT).show()
                        } else {
                            pairing = host to pairCode
                            WifiLink.connect(addr, Protocol.WIFI_PORT, pairCode, host)
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun codeField() = EditText(this).apply {
        hint = getString(R.string.pair_code_hint)
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        filters = arrayOf(InputFilter.LengthFilter(4))
        isSingleLine = true
    }

    private fun padded(v: android.view.View) = LinearLayout(this).apply {
        val pad = (20 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, 0)
        addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private companion object {
        val IP_PATTERN = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    }
}
