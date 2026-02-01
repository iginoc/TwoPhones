package com.igino.twophones

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.system.exitProcess

class MainActivity : AppCompatActivity() {

    private val MY_UUID: UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")
    private val NAME = "TwoPhones"

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var deviceList: ListView
    private lateinit var redBall: View
    private lateinit var btnExit: Button
    private var connectedThread: ConnectedThread? = null
    private var serverThread: AcceptThread? = null

    private var screenWidth = 0
    private var screenHeight = 0

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            setupBluetooth()
        } else {
            Toast.makeText(this, "Permissions required", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        deviceList = findViewById(R.id.deviceList)
        redBall = findViewById(R.id.redBall)
        btnExit = findViewById(R.id.btnExit)

        btnExit.setOnClickListener {
            finishAndRemoveTask()
            exitProcess(0)
        }

        setupGameButtons()

        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        requestPermissionLauncher.launch(permissions.toTypedArray())
    }

    private fun setupGameButtons() {
        val gameButtons = listOf(
            R.id.btnGame1 to "Gioco 1",
            R.id.btnGame2 to "Gioco 2",
            R.id.btnGame3 to "Gioco 3",
            R.id.btnGame4 to "Gioco 4",
            R.id.btnGame5 to "Gioco 5"
        )

        gameButtons.forEach { (id, name) ->
            findViewById<Button>(id).setOnClickListener {
                connectedThread?.write("START_GAME:$name".toByteArray())
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupBluetooth() {
        val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter.bondedDevices
        val deviceNames = mutableListOf<String>()
        val devices = mutableListOf<BluetoothDevice>()

        pairedDevices?.forEach { device ->
            deviceNames.add(device.name ?: "Unknown")
            devices.add(device)
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, deviceNames)
        deviceList.adapter = adapter

        deviceList.setOnItemClickListener { _, _, position, _ ->
            connectToDevice(devices[position])
        }

        serverThread = AcceptThread()
        serverThread?.start()
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        ConnectThread(device).start()
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        event?.let {
            val xRatio = it.x / screenWidth
            val yRatio = it.y / screenHeight
            when (it.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    connectedThread?.write("MOVE:$xRatio,$yRatio".toByteArray())
                }
                MotionEvent.ACTION_UP -> {
                    connectedThread?.write("UP".toByteArray())
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun manageConnectedSocket(socket: BluetoothSocket) {
        runOnUiThread {
            deviceList.visibility = View.GONE
            Toast.makeText(this, "Connesso!", Toast.LENGTH_SHORT).show()
        }
        connectedThread = ConnectedThread(socket)
        connectedThread?.start()
    }

    @SuppressLint("MissingPermission")
    private inner class AcceptThread : Thread() {
        private val mmServerSocket: BluetoothServerSocket? by lazy(LazyThreadSafetyMode.NONE) {
            bluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord(NAME, MY_UUID)
        }

        override fun run() {
            var shouldLoop = true
            while (shouldLoop) {
                val socket: BluetoothSocket? = try {
                    mmServerSocket?.accept()
                } catch (e: IOException) {
                    shouldLoop = false
                    null
                }
                socket?.also {
                    manageConnectedSocket(it)
                    mmServerSocket?.close()
                    shouldLoop = false
                }
            }
        }

        fun cancel() {
            try {
                mmServerSocket?.close()
            } catch (e: IOException) { }
        }
    }

    @SuppressLint("MissingPermission")
    private inner class ConnectThread(device: BluetoothDevice) : Thread() {
        private val mmSocket: BluetoothSocket? by lazy(LazyThreadSafetyMode.NONE) {
            device.createRfcommSocketToServiceRecord(MY_UUID)
        }

        override fun run() {
            bluetoothAdapter.cancelDiscovery()
            mmSocket?.let { socket ->
                try {
                    socket.connect()
                    manageConnectedSocket(socket)
                } catch (e: IOException) {
                    try {
                        socket.close()
                    } catch (e2: IOException) { }
                }
            }
        }

        fun cancel() {
            try {
                mmSocket?.close()
            } catch (e: IOException) { }
        }
    }

    private inner class ConnectedThread(private val mmSocket: BluetoothSocket) : Thread() {
        private val mmInStream: InputStream = mmSocket.inputStream
        private val mmOutStream: OutputStream = mmSocket.outputStream
        private val mmBuffer: ByteArray = ByteArray(1024)

        override fun run() {
            var numBytes: Int
            while (true) {
                numBytes = try {
                    mmInStream.read(mmBuffer)
                } catch (e: IOException) {
                    break
                }
                val message = String(mmBuffer, 0, numBytes)
                handleMessage(message)
            }
        }

        private fun handleMessage(message: String) {
            runOnUiThread {
                if (message.startsWith("MOVE:")) {
                    try {
                        val coords = message.substring(5).split(",")
                        val xRatio = coords[0].toFloat()
                        val yRatio = coords[1].toFloat()
                        
                        redBall.x = xRatio * screenWidth - (redBall.width / 2)
                        redBall.y = yRatio * screenHeight - (redBall.height / 2)
                        redBall.visibility = View.VISIBLE
                    } catch (e: Exception) { }
                } else if (message == "UP") {
                    redBall.visibility = View.GONE
                } else if (message.startsWith("START_GAME:")) {
                    val gameName = message.substring(11)
                    showGameRequest(gameName)
                }
            }
        }

        private fun showGameRequest(gameName: String) {
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Richiesta Gioco")
                .setMessage("L'altro dispositivo vuole avviare: $gameName. Vuoi giocare?")
                .setPositiveButton("Sì") { _, _ ->
                    Toast.makeText(this@MainActivity, "Avvio di $gameName...", Toast.LENGTH_SHORT).show()
                    // Qui potresti implementare la logica per avviare il gioco specifico
                }
                .setNegativeButton("No", null)
                .show()
        }

        fun write(bytes: ByteArray) {
            try {
                mmOutStream.write(bytes)
            } catch (e: IOException) { }
        }

        fun cancel() {
            try {
                mmSocket.close()
            } catch (e: IOException) { }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serverThread?.cancel()
        connectedThread?.cancel()
    }
}
