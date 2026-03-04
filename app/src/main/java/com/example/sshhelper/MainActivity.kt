package com.example.sshhelper

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

class MainActivity : Activity() {
    private lateinit var scannerUI: LinearLayout
    private lateinit var terminalUI: LinearLayout
    private lateinit var consoleOutput: TextView
    private lateinit var commandInput: EditText
    private var lastSelectedIp: String? = null

    private var sshSession: Session? = null
    private var sshChannel: ChannelShell? = null
    private var sshOutputStream: OutputStream? = null

    private var fullTerminalText = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val rootLayout = FrameLayout(this)

        // --- 1. SCANNER UI ---
        scannerUI = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 30, 30, 30)
            val btnScan = Button(context).apply { text = "ΣΑΡΩΣΗ ΓΙΑ ΣΥΣΚΕΥΕΣ" }
            val statusText = TextView(context).apply { text = "Έτοιμο." }
            val resultsLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val scrollResults = ScrollView(context).apply { addView(resultsLayout) }
            addView(btnScan); addView(statusText); addView(scrollResults)
            btnScan.setOnClickListener {
                resultsLayout.removeAllViews()
                val subnet = getHotspotSubnet()
                statusText.text = "Σάρωση στο $subnet.x..."
                scanDevices(subnet, resultsLayout, statusText)
            }
        }

        // --- 2. TERMINAL UI ---
        terminalUI = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)

            consoleOutput = TextView(context).apply {
                setTextColor(Color.parseColor("#00FF00"))
                setBackgroundColor(Color.BLACK)
                typeface = Typeface.MONOSPACE
                textSize = 14f
                setPadding(30, 30, 30, 30)
            }

            val scrollConsole = ScrollView(context).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                addView(consoleOutput)
            }

            // --- SHORTCUT BAR ---
            val shortcutBar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#1A1A1A"))
            }

            val shortcuts = mapOf(
                "X" to "\u0018", "C" to "\u0003", "S" to "\u0013",
                "TAB" to "\t", "▲" to "\u001B[A", "▼" to "\u001B[B", "CLS" to "clear"
            )

            shortcuts.forEach { (label, code) ->
                val btn = Button(context).apply {
                    text = label
                    textSize = 11f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        if (label == "CLS") {
                            fullTerminalText.setLength(0)
                            consoleOutput.text = ""
                            sendSpecial("clear\r\n")
                        } else sendSpecial(code)
                    }
                }
                shortcutBar.addView(btn)
            }

            // --- ΔΙΟΡΘΩΜΕΝΟ INPUT LINE (Black & Green) ---
            val inputLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(10, 20, 10, 20)
                setBackgroundColor(Color.parseColor("#0A0A0A")) // Σχεδόν μαύρο
            }
            commandInput = EditText(context).apply {
                hint = "Εντολή (exitd)..."
                setTextColor(Color.parseColor("#00FF00")) // ΠΡΑΣΙΝΑ ΓΡΑΜΜΑΤΑ
                setHintTextColor(Color.parseColor("#004400")) // Σκούρο πράσινο hint
                setBackgroundColor(Color.BLACK) // ΜΑΥΡΟ ΦΟΝΤΟ
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val btnRun = Button(context).apply {
                text = "RUN"
                setTextColor(Color.GREEN)
                setOnClickListener { processCommand() }
            }
            inputLayout.addView(commandInput); inputLayout.addView(btnRun)

            addView(scrollConsole); addView(shortcutBar); addView(inputLayout)
        }

        rootLayout.addView(scannerUI); rootLayout.addView(terminalUI)
        setContentView(rootLayout)
    }

    private fun updateTerminalText(rawText: String) {
    // 1. Εξαφανίζουμε ΟΛΟΥΣ τους κωδικούς ANSI που προκαλούν τα [D [C [K κτλ.
    // Αυτό το Regex είναι πιο ισχυρό και θα καθαρίσει όλη τη "σαλάτα" από την οθόνη
    val clean = rawText.replace(Regex("\u001B\\[[0-9;?]*[a-zA-Z]|\u001B\\(B"), "")

    // 2. Ελέγχουμε αν το κείμενο που έμεινε περιέχει εντολές διαγραφής
    for (char in clean) {
        when {
            // Διαγραφή (Backspace)
            char.toInt() == 8 || char.toInt() == 127 -> {
                if (fullTerminalText.isNotEmpty()) {
                    fullTerminalText.deleteCharAt(fullTerminalText.length - 1)
                }
            }
            char == '\r' -> continue // Αγνοούμε το carriage return
            else -> {
                // Προσθέτουμε μόνο αν δεν είναι "σκουπίδι" ελέγχου
                if (char.toInt() >= 32 || char == '\n' || char == '\t') {
                    fullTerminalText.append(char)
                }
            }
        }
    }
    consoleOutput.text = fullTerminalText.toString()
}

    private fun sendSpecial(code: String) {
        CoroutineScope(Dispatchers.IO).launch {
            sshOutputStream?.write(code.toByteArray())
            sshOutputStream?.flush()
        }
    }

    private fun processCommand() {
        var cmd = commandInput.text.toString().trim()
        if (cmd.lowercase() == "exitd") {
            sshChannel?.disconnect(); sshSession?.disconnect()
            fullTerminalText.setLength(0)
            terminalUI.visibility = View.GONE; scannerUI.visibility = View.VISIBLE
            return
        }
        if (cmd.startsWith("nano")) cmd = cmd.replaceFirst("nano", "command nano")

        CoroutineScope(Dispatchers.IO).launch {
            sshOutputStream?.write((cmd + "\n").toByteArray())
            sshOutputStream?.flush()
            withContext(Dispatchers.Main) { commandInput.setText("") }
        }
    }

    private fun startPersistentShell(ip: String) {
        val prefs = getEncryptedPrefs()
        val user = prefs.getString("${ip}_u", "pi") ?: "pi"
        val pass = prefs.getString("${ip}_p", "") ?: ""

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val jsch = JSch()
                sshSession = jsch.getSession(user, ip, 22).apply {
                    setPassword(pass)
                    setConfig("StrictHostKeyChecking", "no")
                    connect(10000)
                }
                sshChannel = sshSession?.openChannel("shell") as ChannelShell
                val inputStream = sshChannel?.inputStream
                sshOutputStream = sshChannel?.outputStream

                // "ansi" αντί για "dumb" για καλύτερη συμβατότητα με βελάκια
                sshChannel?.setPtyType("dumb")
                sshChannel?.connect()

                withContext(Dispatchers.Main) {
                    scannerUI.visibility = View.GONE
                    terminalUI.visibility = View.VISIBLE
                    fullTerminalText.setLength(0)
                    consoleOutput.text = ""
                }

                val buffer = ByteArray(4096)
                while (sshChannel?.isConnected == true) {
                    val available = inputStream?.available() ?: 0
                    if (available > 0) {
                        val len = inputStream?.read(buffer) ?: -1
                        if (len > 0) {
                            val rawText = String(buffer, 0, len)
                            withContext(Dispatchers.Main) {
                                updateTerminalText(rawText)
                                val scroll = consoleOutput.parent as ScrollView
                                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                            }
                        }
                    }
                    delay(20)
                }
            } catch (e: Exception) { }
        }
    }

    private fun getHotspotSubnet(): String {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        for (intf in interfaces) {
            val addrs = intf.inetAddresses
            for (addr in addrs) {
                if (!addr.isLoopbackAddress && addr is Inet4Address) {
                    val ip = addr.hostAddress ?: ""
                    // Αν βρει οποιαδήποτε IP (Wi-Fi ή Hotspot) που δεν είναι η εσωτερική 127.0.0.1
                    if (ip.contains(".")) {
                        return ip.substringBeforeLast(".")
                    }
                }
            }
        }
    } catch (e: Exception) {}
    return "192.168.43" // Default fallback
}

    private fun scanDevices(subnet: String, layout: LinearLayout, status: TextView) {
        CoroutineScope(Dispatchers.IO).launch {
            val found = mutableListOf<String>()
            (2..254).map { i -> launch { if (isPortOpen("$subnet.$i", 22)) found.add("$subnet.$i") } }.joinAll()
            withContext(Dispatchers.Main) {
                status.text = "Βρέθηκαν: ${found.size}"
                found.forEach { ip ->
                    val btn = Button(this@MainActivity).apply {
                        text = "IP: $ip"
                        setOnClickListener { lastSelectedIp = ip; showLoginDialog(ip) }
                    }
                    layout.addView(btn)
                }
            }
        }
    }

    private fun isPortOpen(ip: String, port: Int) = try { Socket().apply { connect(InetSocketAddress(ip, port), 250) }.close(); true } catch (e: Exception) { false }

    private fun showLoginDialog(ip: String) {
        val prefs = getEncryptedPrefs()
        val userField = EditText(this).apply { hint = "User"; setText(prefs.getString("${ip}_u", "pi")) }
        val passField = EditText(this).apply { hint = "Pass"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; setText(prefs.getString("${ip}_p", "")) }
        val diag = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(50,0,50,0); addView(userField); addView(passField) }
        AlertDialog.Builder(this).setTitle("Σύνδεση με: $ip").setView(diag)
            .setPositiveButton("Connect") { _, _ ->
                prefs.edit().putString("${ip}_u", userField.text.toString()).putString("${ip}_p", passField.text.toString()).apply()
                startPersistentShell(ip)
            }.show()
    }

    private fun getEncryptedPrefs(): SharedPreferences {
        val key = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create("ssh_prefs", key, this, EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }
}
