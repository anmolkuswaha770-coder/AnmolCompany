package com.anmolcompany.mesh

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.StringBuilder
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private lateinit var manager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel
    private var receiverRegistered = false

    private lateinit var startButton: Button
    private lateinit var logText: TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newCachedThreadPool()

    // Active sockets maintained by server (if group owner)
    private val clientSockets = ConcurrentHashMap<String, Socket>() // key: address
    private var serverThread: ServerThread? = null
    private var clientThread: ClientThread? = null
    private val running = AtomicBoolean(false)

    // BroadcastReceiver listens to Wi-Fi P2P events
    private val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    private val permissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            val granted = perms.entries.all { it.value == true }
            if (granted) {
                log("Permissions granted.")
                startDiscovery()
            } else {
                log("Permissions required for peer discovery. Please grant them and retry.")
                AlertDialog.Builder(this)
                    .setTitle("Permissions required")
                    .setMessage("Location permission is required for Wi‑Fi Direct discovery. Grant in Settings if previously denied.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Build UI programmatically so this file is self-contained (no XML required).
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 36, 24, 24)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        startButton = Button(this).apply {
            text = "Start Mesh"
            isAllCaps = false
            setOnClickListener {
                if (!running.get()) {
                    initializeMesh()
                } else {
                    stopMesh()
                }
            }
        }

        logText = TextView(this).apply {
            textSize = 14f
            movementMethod = ScrollingMovementMethod()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply { topMargin = 16 }
        }

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            addView(logText, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        root.addView(startButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 8 })

        root.addView(scroll)

        setContentView(root)

        // Initialize Wi-Fi P2P manager
        manager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager.initialize(this, mainLooper, null)

        log("Ready. Tap 'Start Mesh' to initialize peer-to-peer mesh.")
    }

    private fun log(message: String) {
        mainHandler.post {
            val sb = StringBuilder(logText.text.toString())
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append("[${System.currentTimeMillis() / 1000}] ").append(message)
            logText.text = sb.toString()
            // auto-scroll
            (logText.parent as? ScrollView)?.post {
                (logText.parent as ScrollView).fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }

    private fun initializeMesh() {
        running.set(true)
        startButton.text = "Stop Mesh"
        log("Initializing mesh...")

        // Check runtime permissions needed for WiFi P2P discovery
        val perms = mutableListOf<String>()
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        // For Android 12+ some additional permissions may be required depending on your target SDK and APIs,
        // but ACCESS_FINE_LOCATION is the minimum for discovery on many devices.
        if (perms.isNotEmpty()) {
            permissionsLauncher.launch(perms.toTypedArray())
            return
        }

        registerReceiversAndStart()
    }

    private fun registerReceiversAndStart() {
        if (!receiverRegistered) {
            registerReceiver(p2pReceiver, intentFilter)
            receiverRegistered = true
            log("Wi‑Fi P2P broadcast receiver registered.")
        }
        startDiscovery()
    }

    private fun startDiscovery() {
        log("Starting peer discovery...")
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                log("Discovery started successfully.")
            }

            override fun onFailure(reason: Int) {
                log("Discovery failed to start. Reason: $reason")
            }
        })
    }

    private fun stopMesh() {
        running.set(false)
        startButton.text = "Start Mesh"
        log("Stopping mesh: closing sockets, stopping discovery.")
        try {
            serverThread?.shutdown()
            serverThread = null
        } catch (t: Throwable) {
            // ignore
        }
        try {
            clientThread?.shutdown()
            clientThread = null
        } catch (t: Throwable) {
        }
        // close client sockets map
        clientSockets.forEach { (_, socket) ->
            try { socket.close() } catch (_: Throwable) {}
        }
        clientSockets.clear()

        if (receiverRegistered) {
            try {
                unregisterReceiver(p2pReceiver)
            } catch (_: Throwable) {}
            receiverRegistered = false
            log("Receiver unregistered.")
        }

        try {
            manager.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    log("Discovery stopped.")
                }

                override fun onFailure(reason: Int) {
                    log("Failed to stop discovery: $reason")
                }
            })
        } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMesh()
        executor.shutdownNow()
    }

    // BroadcastReceiver handling P2P events
    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        log("Wi‑Fi P2P is enabled.")
                    } else {
                        log("Wi‑Fi P2P is NOT enabled.")
                    }
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    // request current peers
                    manager.requestPeers(channel) { peers: WifiP2pDeviceList? ->
                        val list = peers?.deviceList ?: emptySet()
                        log("Peers changed: ${list.size} found.")
                        if (list.isNotEmpty()) {
                            // Try to connect to all discovered peers opportunistically.
                            // To keep it simple and robust: attempt to connect to each peer once.
                            for (device in list) {
                                attemptConnect(device)
                            }
                        }
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = intent.getParcelableExtra<android.net.NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    if (networkInfo != null && networkInfo.isConnected) {
                        // We are connected with at least one peer, request connection info to determine group owner and group owner address
                        manager.requestConnectionInfo(channel) { info: WifiP2pInfo? ->
                            if (info == null) {
                                log("Connection info unavailable.")
                                return@requestConnectionInfo
                            }
                            handleConnectionInfo(info)
                        }
                    } else {
                        log("P2P disconnected.")
                        // close streams and threads
                        serverThread?.shutdown()
                        serverThread = null
                        clientThread?.shutdown()
                        clientThread = null
                        clientSockets.forEach { (_, s) -> try { s.close() } catch (_: Throwable) {} }
                        clientSockets.clear()
                    }
                }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val device = intent.getParcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    log("This device changed: ${device?.deviceName ?: "unknown"}")
                }
            }
        }
    }

    private fun attemptConnect(device: WifiP2pDevice) {
        // Build config and attempt connect
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WifiP2pConfig.WpsInfo.PBC
        }
        log("Attempting connect to ${device.deviceName} (${device.deviceAddress})")
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                log("Connection initiated to ${device.deviceName}.")
            }

            override fun onFailure(reason: Int) {
                log("Connection initiation failed to ${device.deviceName}. Reason: $reason")
            }
        })
    }

    private fun handleConnectionInfo(info: WifiP2pInfo) {
        if (!running.get()) {
            log("Received connection info but mesh not running.")
            return
        }
        val isGroupOwner = info.isGroupOwner
        val ownerAddress = info.groupOwnerAddress?.hostAddress
        log("Connected. Group owner: $ownerAddress. IsGroupOwner: $isGroupOwner")

        if (isGroupOwner) {
            // Start server to accept multiple client socket connections
            if (serverThread == null) {
                serverThread = ServerThread(8888) { peer, msg -> onMessageReceivedFromPeer(peer, msg) }
                executor.execute(serverThread)
                log("Server thread started on port 8888 (group owner).")
            } else {
                log("Server thread already running.")
            }
        } else {
            // Start client that connects to group owner
            if (ownerAddress != null) {
                clientThread?.shutdown()
                clientThread = ClientThread(ownerAddress, 8888) { peer, msg -> onMessageReceivedFromPeer(peer, msg) }
                executor.execute(clientThread)
                log("Client thread connecting to $ownerAddress:8888")
            } else {
                log("Group owner address unknown; cannot start client socket.")
            }
        }
    }

    private fun onMessageReceivedFromPeer(peer: String, message: String) {
        log("Message from $peer: $message")
        // If this device is the group owner (server), consider relaying the message to other connected peers to create an overlay mesh
        if (serverThread != null) {
            relayMessageFromPeer(peer, message)
        }
    }

    private fun relayMessageFromPeer(originPeer: String, message: String) {
        val prefixed = "RELAY from $originPeer: $message"
        // Broadcast to all clients except origin
        val sockets = clientSockets.entries.toList()
        for ((addr, socket) in sockets) {
            if (addr == originPeer) continue
            executor.execute {
                try {
                    val out = BufferedOutputStream(socket.getOutputStream())
                    val data = prefixed.toByteArray(Charsets.UTF_8)
                    // Write length prefix (4 bytes) then payload
                    val len = data.size
                    out.write(byteArrayOf(
                        (len shr 24).toByte(),
                        (len shr 16).toByte(),
                        (len shr 8).toByte(),
                        len.toByte()
                    ))
                    out.write(data)
                    out.flush()
                } catch (e: Throwable) {
                    log("Failed to relay to $addr: ${e.message}")
                    try { socket.close() } catch (_: Throwable) {}
                    clientSockets.remove(addr)
                }
            }
        }
    }

    // ServerThread: listens on port and accepts incoming clients; reads framed messages and dispatches callback
    private inner class ServerThread(private val port: Int, private val onMessage: (peerAddr: String, msg: String) -> Unit) : Runnable {
        @Volatile
        private var running = true
        private var serverSocket: ServerSocket? = null

        fun shutdown() {
            running = false
            try {
                serverSocket?.close()
            } catch (_: Throwable) {}
            clientSockets.forEach { (_, s) -> try { s.close() } catch (_: Throwable) {} }
            clientSockets.clear()
        }

        override fun run() {
            try {
                serverSocket = ServerSocket()
                serverSocket?.reuseAddress = true
                serverSocket?.bind(InetSocketAddress(port))
                log("Server listening on port $port")
                while (running) {
                    val client = serverSocket?.accept() ?: break
                    val addr = client.inetAddress.hostAddress
                    log("Accepted connection from $addr")
                    clientSockets[addr] = client
                    // spawn reader thread for this client
                    executor.execute {
                        readLoopForSocket(client, addr)
                    }
                }
            } catch (e: IOException) {
                if (running) log("Server socket error: ${e.message}")
            } finally {
                try { serverSocket?.close() } catch (_: Throwable) {}
            }
        }

        private fun readLoopForSocket(socket: Socket, addr: String) {
            try {
                val bis = BufferedInputStream(socket.getInputStream())
                while (running && !socket.isClosed) {
                    // read 4-byte length prefix
                    val lenPrefix = ByteArray(4)
                    var read = 0
                    while (read < 4) {
                        val r = bis.read(lenPrefix, read, 4 - read)
                        if (r == -1) throw IOException("Stream closed")
                        read += r
                    }
                    val len = ((lenPrefix[0].toInt() and 0xFF) shl 24) or
                            ((lenPrefix[1].toInt() and 0xFF) shl 16) or
                            ((lenPrefix[2].toInt() and 0xFF) shl 8) or
                            (lenPrefix[3].toInt() and 0xFF)
                    if (len <= 0 || len > 10_000_000) throw IOException("Invalid length $len")
                    val data = ByteArray(len)
                    var got = 0
                    while (got < len) {
                        val r = bis.read(data, got, len - got)
                        if (r == -1) throw IOException("Stream closed mid-message")
                        got += r
                    }
                    val msg = String(data, Charsets.UTF_8)
                    onMessage(addr, msg)
                }
            } catch (t: Throwable) {
                log("Client $addr disconnected: ${t.message}")
            } finally {
                try { socket.close() } catch (_: Throwable) {}
                clientSockets.remove(addr)
            }
        }
    }

    // ClientThread: connects to server (group owner) and exchanges messages; reads framed messages and dispatches callback
    private inner class ClientThread(
        private val host: String,
        private val port: Int,
        private val onMessage: (peerAddr: String, msg: String) -> Unit
    ) : Runnable {
        @Volatile
        private var running = true
        private var socket: Socket? = null

        fun shutdown() {
            running = false
            try { socket?.close() } catch (_: Throwable) {}
        }

        override fun run() {
            try {
                socket = Socket()
                socket?.connect(InetSocketAddress(host, port), 10_000)
                log("Client connected to $host:$port")
                // send an initial HELLO
                try {
                    val out = BufferedOutputStream(socket!!.getOutputStream())
                    val hello = "HELLO from ${android.os.Build.MODEL}"
                    val bytes = hello.toByteArray(Charsets.UTF_8)
                    val len = bytes.size
                    out.write(byteArrayOf(
                        (len shr 24).toByte(),
                        (len shr 16).toByte(),
                        (len shr 8).toByte(),
                        len.toByte()
                    ))
                    out.write(bytes)
                    out.flush()
                } catch (e: Throwable) {
                    log("Failed to send HELLO: ${e.message}")
                }

                // start read loop
                val bis = BufferedInputStream(socket!!.getInputStream())
                while (running && socket != null && !socket!!.isClosed) {
                    val lenPrefix = ByteArray(4)
                    var read = 0
                    while (read < 4) {
                        val r = bis.read(lenPrefix, read, 4 - read)
                        if (r == -1) throw IOException("Stream closed")
                        read += r
                    }
                    val len = ((lenPrefix[0].toInt() and 0xFF) shl 24) or
                            ((lenPrefix[1].toInt() and 0xFF) shl 16) or
                            ((lenPrefix[2].toInt() and 0xFF) shl 8) or
                            (lenPrefix[3].toInt() and 0xFF)
                    if (len <= 0 || len > 10_000_000) throw IOException("Invalid length $len")
                    val data = ByteArray(len)
                    var got = 0
                    while (got < len) {
                        val r = bis.read(data, got, len - got)
                        if (r == -1) throw IOException("Stream closed mid-message")
                        got += r
                    }
                    val msg = String(data, Charsets.UTF_8)
                    onMessage(host, msg)
                }
            } catch (t: Throwable) {
                log("Client error: ${t.message}")
            } finally {
                try { socket?.close() } catch (_: Throwable) {}
            }
        }
    }
}
