package com.igino.twophones

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import java.io.IOException
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sin
import kotlin.system.exitProcess

class MainActivity : AppCompatActivity(), SensorEventListener {

    private val MY_UUID: UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")
    private val NAME = "TwoPhones"

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var deviceList: ListView
    private lateinit var redBall: View
    private var greenBall: View? = null
    private lateinit var mainLayout: ConstraintLayout
    private lateinit var bottomBar: View
    private val connectedThreads = java.util.Collections.synchronizedList(mutableListOf<ConnectedThread>())
    private var serverThread: AcceptThread? = null

    private var screenWidth = 0
    private var screenHeight = 0

    private var currentGame: String? = null
    private val localSnake = mutableListOf<View>()
    private val remoteSnake = mutableListOf<View>()
    private val gridLines = mutableListOf<View>()
    private val drawViews = mutableListOf<View>()
    private val snakeSize = 60
    private val snakeLength = 8 

    private var localPaddle: View? = null
    private var remotePaddle: View? = null
    private var ball: View? = null
    private var ballSpeedX = 15f
    private var ballSpeedY = 15f
    private val paddleWidth = 40
    private val paddleHeight = 250

    private var localPongScore = 0
    private var remotePongScore = 0
    private var pongScoreView: TextView? = null

    // Kart Game State
    private var kartView: KartView? = null
    private var player1X = 0f
    private var player2X = 0f
    private var player1Steer = 0f
    private var player2Steer = 0f
    private var trackPos = 0f
    private var trackCurve = 0f

    private lateinit var sensorManager: SensorManager
    private var gyroscope: Sensor? = null

    private var assignedSide: String? = null 
    private var statusRoleTextView: TextView? = null
    private var isScreenOff = false
    private var lastPaddleUpdate = 0L
    private var smoothedRotationX = 0f
    private val filterAlpha = 0.3f

    private var isServer = false

    private enum class Direction { UP, DOWN, LEFT, RIGHT }
    private var localDirection = Direction.RIGHT
    private val gameHandler = Handler(Looper.getMainLooper())
    private var gameRunnable: Runnable? = null
    private val moveSpeed = 20f

    private var downX = 0f
    private var downY = 0f

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            setupBluetooth()
        } else {
            Toast.makeText(this, "Permissions required", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mainLayout = findViewById(R.id.gameArea)
        bottomBar = findViewById(R.id.bottomBar)
        deviceList = findViewById(R.id.deviceList)
        redBall = findViewById(R.id.redBall)
        findViewById<Button>(R.id.btnExit).setOnClickListener {
            finishAndRemoveTask()
            exitProcess(0)
        }

        setupGameButtons()

        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels

        mainLayout.post {
            screenWidth = mainLayout.width
            screenHeight = mainLayout.height
        }

        mainLayout.setOnTouchListener { _, event ->
            handleTouch(event)
            true
        }

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        requestPermissionLauncher.launch(permissions.toTypedArray())
    }

    private fun setupGameButtons() {
        val gameButtons = listOf(
            R.id.btnGame1 to "Pallina",
            R.id.btnGame2 to "Snake",
            R.id.btnGame3 to "Navi",
            R.id.btnGame4 to "Pong",
            R.id.btnGame5 to "Kart"
        )

        gameButtons.forEach { (id, name) ->
            findViewById<Button>(id).setOnClickListener {
                broadcast("START_GAME:$name;")
                if (isServer) {
                    when (name) { 
                        "Pallina" -> startRedBallGame()
                        "Snake" -> startSnakeGame()
                        "Pong" -> startPongGame()
                        "Kart" -> startKartGame()
                    }
                }
            }
        }
    }

    private fun updateUIForRole() {
        runOnUiThread {
            val gameButtonIds = listOf(R.id.btnGame1, R.id.btnGame2, R.id.btnGame3, R.id.btnGame4, R.id.btnGame5)
            if (!isServer && assignedSide != null) {
                gameButtonIds.forEach { id -> findViewById<Button>(id).visibility = View.GONE }
                if (statusRoleTextView == null) {
                    statusRoleTextView = TextView(this).apply {
                        setTextColor(Color.GRAY); setPadding(30, 0, 30, 0); gravity = Gravity.CENTER_VERTICAL; textSize = 14f
                    }
                    findViewById<LinearLayout>(R.id.buttonContainer).addView(statusRoleTextView, 1)
                }
                statusRoleTextView?.text = "Connesso come controller $assignedSide"
                statusRoleTextView?.visibility = View.VISIBLE
            } else if (isServer) {
                gameButtonIds.forEach { id -> findViewById<Button>(id).visibility = View.VISIBLE }
                statusRoleTextView?.visibility = View.GONE
            }
        }
    }

    private fun setScreenOffMode(off: Boolean) {
        if (isServer || assignedSide == null) return
        isScreenOff = off
        runOnUiThread {
            val params = window.attributes
            if (off) {
                params.screenBrightness = 0.01f
                mainLayout.setBackgroundColor(Color.BLACK)
                bottomBar.visibility = View.GONE
            } else {
                params.screenBrightness = -1f 
                if (currentGame == null) mainLayout.setBackgroundColor(Color.WHITE)
                bottomBar.visibility = View.VISIBLE
            }
            window.attributes = params
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupBluetooth() {
        val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter.bondedDevices
        val deviceNames = mutableListOf<String>()
        val devices = mutableListOf<BluetoothDevice>()
        pairedDevices?.forEach { device -> deviceNames.add(device.name ?: "Unknown"); devices.add(device) }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, deviceNames)
        deviceList.adapter = adapter
        deviceList.setOnItemClickListener { _, _, position, _ -> connectToDevice(devices[position]) }
        serverThread = AcceptThread()
        serverThread?.start()
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) { ConnectThread(device).start() }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == 186 && isServer) {
            bottomBar.visibility = if (bottomBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            mainLayout.post {
                screenWidth = mainLayout.width
                screenHeight = mainLayout.height
            }
            return true
        }
        if (currentGame == "Snake") {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { if (localDirection != Direction.DOWN) localDirection = Direction.UP; return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { if (localDirection != Direction.UP) localDirection = Direction.DOWN; return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (localDirection != Direction.RIGHT) localDirection = Direction.LEFT; return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (localDirection != Direction.LEFT) localDirection = Direction.RIGHT; return true }
            }
        } else if (currentGame == "Pong") {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    localPaddle?.let { it.y = (it.y - 50f).coerceAtLeast(0f); broadcast("PONG_PADDLE:${it.y / screenHeight};") }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    localPaddle?.let { it.y = (it.y + 50f).coerceAtMost((screenHeight - paddleHeight).toFloat()); broadcast("PONG_PADDLE:${it.y / screenHeight};") }
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun handleTouch(event: MotionEvent) {
        if (!isServer && assignedSide != null && isScreenOff) {
            if (event.action == MotionEvent.ACTION_DOWN) setScreenOffMode(false)
            return
        }
        if (currentGame == null) {
            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                val myColor = if (isServer) Color.RED else Color.BLUE
                createDot(event.x, event.y, myColor)
                broadcast("DRAW:${event.x / screenWidth},${event.y / screenHeight};")
            }
        } else if (currentGame == "Pallina") {
            val xRatio = event.x / screenWidth; val yRatio = event.y / screenHeight
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> broadcast("MOVE:$xRatio,$yRatio;")
                MotionEvent.ACTION_UP -> broadcast("UP;")
            }
        }
    }

    private fun createDot(x: Float, y: Float, color: Int) {
        val dot = View(this).apply {
            val size = 20; layoutParams = ViewGroup.LayoutParams(size, size)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
            this.x = x - size / 2; this.y = y - size / 2
        }
        mainLayout.addView(dot); drawViews.add(dot)
    }

    private fun handleSnakeSwipe(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y }
            MotionEvent.ACTION_UP -> {
                val deltaX = event.x - downX; val deltaY = event.y - downY
                if (abs(deltaX) > abs(deltaY)) {
                    if (abs(deltaX) > 100) localDirection = if (deltaX > 0) Direction.RIGHT else Direction.LEFT
                } else {
                    if (abs(deltaY) > 100) localDirection = if (deltaY > 0) Direction.DOWN else Direction.UP
                }
            }
        }
    }

    private fun updateSnakePosition(snake: List<View>, x: Float, y: Float) {
        if (snake.isEmpty()) return
        for (i in snake.size - 1 downTo 1) { snake[i].x = snake[i - 1].x; snake[i].y = snake[i - 1].y; snake[i].visibility = View.VISIBLE }
        snake[0].x = x; snake[0].y = y; snake[0].visibility = View.VISIBLE
    }

    private fun createGrid(color: Int) {
        val rows = 10; val cols = 10
        val cellWidth = screenWidth.toFloat() / cols; val cellHeight = screenHeight.toFloat() / rows
        for (i in 0..cols) {
            val line = View(this).apply { layoutParams = ViewGroup.LayoutParams(2, screenHeight); setBackgroundColor(color); x = i * cellWidth; y = 0f }
            mainLayout.addView(line, 0); gridLines.add(line)
        }
        for (i in 0..rows) {
            val line = View(this).apply { layoutParams = ViewGroup.LayoutParams(screenWidth, 2); setBackgroundColor(color); x = 0f; y = i * cellHeight }
            mainLayout.addView(line, 0); gridLines.add(line)
        }
    }

    private fun clearAllGameViews() {
        mainLayout.setBackgroundColor(Color.WHITE)
        localSnake.forEach { mainLayout.removeView(it) }; localSnake.clear()
        remoteSnake.forEach { mainLayout.removeView(it) }; remoteSnake.clear()
        gridLines.forEach { mainLayout.removeView(it) }; gridLines.clear()
        drawViews.forEach { mainLayout.removeView(it) }; drawViews.clear()
        greenBall?.let { mainLayout.removeView(it) }; greenBall = null
        localPaddle?.let { mainLayout.removeView(it) }; localPaddle = null
        remotePaddle?.let { mainLayout.removeView(it) }; remotePaddle = null
        ball?.let { mainLayout.removeView(it) }; ball = null
        pongScoreView?.let { mainLayout.removeView(it) }; pongScoreView = null
        kartView?.let { mainLayout.removeView(it) }; kartView = null
        redBall.visibility = View.GONE
        sensorManager.unregisterListener(this); setScreenOffMode(false)
        if (isServer) {
            bottomBar.visibility = View.VISIBLE
            mainLayout.post {
                screenWidth = mainLayout.width
                screenHeight = mainLayout.height
            }
        }
    }

    private fun startSnakeGame() {
        runOnUiThread {
            stopGameLoop(); currentGame = "Snake"; clearAllGameViews()
            if (isServer) bottomBar.visibility = View.GONE
            mainLayout.post {
                screenWidth = mainLayout.width
                screenHeight = mainLayout.height
                mainLayout.setBackgroundColor(if (isServer) Color.WHITE else Color.BLACK)
                createGrid(Color.argb(80, 200, 200, 200))
                val centerX = screenWidth / 2f; val centerY = screenHeight / 2f
                localDirection = if (isServer) Direction.LEFT else Direction.RIGHT
                for (i in 0 until snakeLength) { val circle = createSnakeCircle(Color.GREEN); localSnake.add(circle); mainLayout.addView(circle) }
                localSnake[0].x = if (isServer) centerX - 200f else centerX + 200f
                localSnake[0].y = centerY
                for (i in 0 until snakeLength) { val circle = createSnakeCircle(Color.BLUE); remoteSnake.add(circle); mainLayout.addView(circle) }
                remoteSnake[0].x = if (isServer) centerX + 200f else centerX - 200f
                remoteSnake[0].y = centerY
                startGameLoop(); if (!isServer) setScreenOffMode(true)
            }
        }
    }

    private fun createSnakeCircle(color: Int): View {
        return View(this).apply {
            layoutParams = ViewGroup.LayoutParams(snakeSize, snakeSize)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
            visibility = View.GONE
        }
    }

    private fun startPongGame() {
        runOnUiThread {
            stopGameLoop(); currentGame = "Pong"; clearAllGameViews()
            if (isServer) bottomBar.visibility = View.GONE
            mainLayout.post {
                screenWidth = mainLayout.width
                screenHeight = mainLayout.height
                mainLayout.setBackgroundColor(Color.BLACK) 
                if (isServer) {
                    localPongScore = 0; remotePongScore = 0
                    pongScoreView = TextView(this).apply {
                        setTextColor(Color.WHITE); textSize = 40f; gravity = Gravity.CENTER_HORIZONTAL
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        text = "0 - 0"; y = 50f
                    }
                    mainLayout.addView(pongScoreView)
                }
                ballSpeedX = 15f; ballSpeedY = 15f
                localPaddle = View(this).apply {
                    layoutParams = ViewGroup.LayoutParams(paddleWidth, paddleHeight)
                    background = GradientDrawable().apply {
                        setColor(Color.GREEN)
                        cornerRadius = 20f
                    }
                    x = 50f; y = (screenHeight / 2f) - (paddleHeight / 2f)
                    visibility = if (isServer) View.VISIBLE else View.GONE
                }
                mainLayout.addView(localPaddle)
                remotePaddle = View(this).apply {
                    layoutParams = ViewGroup.LayoutParams(paddleWidth, paddleHeight)
                    background = GradientDrawable().apply {
                        setColor(Color.RED)
                        cornerRadius = 20f
                    }
                    x = screenWidth - 50f - paddleWidth; y = (screenHeight / 2f) - (paddleHeight / 2f)
                    visibility = if (isServer) View.VISIBLE else View.GONE
                }
                mainLayout.addView(remotePaddle)
                ball = View(this).apply {
                    val size = 40; layoutParams = ViewGroup.LayoutParams(size, size)
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
                    x = screenWidth / 2f; y = screenHeight / 2f
                    visibility = if (isServer) View.VISIBLE else View.GONE
                }
                mainLayout.addView(ball)
                gyroscope?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
                startGameLoop(); if (!isServer) setScreenOffMode(true)
            }
        }
    }

    private fun startKartGame() {
        runOnUiThread {
            stopGameLoop(); currentGame = "Kart"; clearAllGameViews()
            if (isServer) bottomBar.visibility = View.GONE
            mainLayout.post {
                screenWidth = mainLayout.width
                screenHeight = mainLayout.height
                if (isServer) {
                    kartView = KartView(this).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    }
                    mainLayout.addView(kartView)
                    player1X = 0f; player2X = 0f; trackPos = 0f
                }
                gyroscope?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
                startGameLoop(); if (!isServer) setScreenOffMode(true)
            }
        }
    }

    private fun updateKartGameServer() {
        trackPos += 0.5f
        trackCurve = (sin(trackPos * 0.05f) * 50).toFloat()
        player1X += (player1Steer * 15f)
        player2X += (player2Steer * 15f)
        player1X = player1X.coerceIn(-screenWidth/2f + 100, screenWidth/2f - 100)
        player2X = player2X.coerceIn(-screenWidth/2f + 100, screenWidth/2f - 100)
        runOnUiThread { kartView?.invalidate() }
    }

    private fun updatePongBallServer() {
        ball?.let { b ->
            b.x += ballSpeedX; b.y += ballSpeedY
            if (b.y < 0 || b.y > screenHeight - b.height) ballSpeedY *= -1
            val ballRect = Rect(b.x.toInt(), b.y.toInt(), (b.x + b.width).toInt(), (b.y + b.height).toInt())
            localPaddle?.let { lp ->
                val lpRect = Rect(lp.x.toInt(), lp.y.toInt(), (lp.x + lp.width).toInt(), (lp.y + lp.height).toInt())
                if (Rect.intersects(ballRect, lpRect)) { ballSpeedX = abs(ballSpeedX) * 1.05f; b.x = lp.x + lp.width + 5 }
            }
            remotePaddle?.let { rp ->
                val rpRect = Rect(rp.x.toInt(), rp.y.toInt(), (rp.x + rp.width).toInt(), (rp.y + rp.height).toInt())
                if (Rect.intersects(ballRect, rpRect)) { ballSpeedX = -abs(ballSpeedX) * 1.05f; b.x = rp.x - b.width - 5 }
            }
            if (b.x < -50) { remotePongScore++; resetPongBall(); broadcast("PONG_SCORE:$localPongScore,$remotePongScore;"); updatePongScoreUI() }
            else if (b.x > screenWidth + 50) { localPongScore++; resetPongBall(); broadcast("PONG_SCORE:$localPongScore,$remotePongScore;"); updatePongScoreUI() }
            else { broadcast("PONG_BALL:${b.x / screenWidth},${b.y / screenHeight};") }
        }
    }

    private fun resetPongBall() {
        ball?.let { b -> b.x = screenWidth / 2f; b.y = screenHeight / 2f; ballSpeedX = 15f * (if (Math.random() > 0.5) 1 else -1); ballSpeedY = 15f * (if (Math.random() > 0.5) 1 else -1) }
    }

    private fun updatePongScoreUI() { runOnUiThread { pongScoreView?.text = "$localPongScore - $remotePongScore" } }

    private fun startGameLoop() {
        gameRunnable = object : Runnable {
            override fun run() {
                if (currentGame == "Snake") {
                    var newX = localSnake[0].x; var newY = localSnake[0].y
                    when (localDirection) {
                        Direction.UP -> newY -= moveSpeed; Direction.DOWN -> newY += moveSpeed
                        Direction.LEFT -> newX -= moveSpeed; Direction.RIGHT -> newX += moveSpeed
                    }
                    if (newX < 0 || newX > screenWidth - snakeSize || newY < 0 || newY > screenHeight - snakeSize) {
                        currentGame = null; broadcast("SNAKE_WIN;"); showGameOverSnake(false, "Fuori campo!"); return
                    }
                    val headRect = Rect(newX.toInt(), newY.toInt(), (newX + snakeSize).toInt(), (newY + snakeSize).toInt())
                    for (part in remoteSnake) {
                        if (part.visibility == View.VISIBLE) {
                            val partRect = Rect(part.x.toInt(), part.y.toInt(), (part.x + snakeSize).toInt(), (part.y + snakeSize).toInt())
                            if (Rect.intersects(headRect, partRect)) { currentGame = null; broadcast("SNAKE_WIN;"); showGameOverSnake(false, "Scontro!"); return }
                        }
                    }
                    updateSnakePosition(localSnake, newX, newY); broadcast("G2_MOVE:${newX / screenWidth},${newY / screenHeight};")
                } else if (currentGame == "Pong" && isServer) {
                    updatePongBallServer()
                } else if (currentGame == "Kart" && isServer) {
                    updateKartGameServer()
                }
                if (currentGame != null) gameHandler.postDelayed(this, 30)
            }
        }
        gameHandler.post(gameRunnable!!)
    }

    private fun stopGameLoop() { gameRunnable?.let { gameHandler.removeCallbacks(it) }; gameRunnable = null }

    private fun startRedBallGame() {
        runOnUiThread {
            stopGameLoop(); currentGame = "Pallina"; clearAllGameViews()
            if (isServer) bottomBar.visibility = View.GONE
            mainLayout.post {
                screenWidth = mainLayout.width
                screenHeight = mainLayout.height
                mainLayout.setBackgroundColor(if (isServer) Color.WHITE else Color.BLACK)
                val size = 200
                greenBall = View(this).apply {
                    layoutParams = ViewGroup.LayoutParams(size, size); background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.GREEN) }
                    x = (screenWidth / 2f) - (size / 2f); y = (screenHeight / 2f) - (size / 2f); visibility = if (isServer) View.VISIBLE else View.GONE
                }
                mainLayout.addView(greenBall); if (!isServer) setScreenOffMode(true)
            }
        }
    }

    private fun manageConnectedSocket(socket: BluetoothSocket, accepted: Boolean) {
        if (accepted) isServer = true
        runOnUiThread { deviceList.visibility = View.GONE; Toast.makeText(this, "Connesso!", Toast.LENGTH_SHORT).show(); updateUIForRole() }
        val thread = ConnectedThread(socket)
        if (isServer) { val side = if (connectedThreads.isEmpty()) "LEFT" else "RIGHT"; thread.assignedSide = side; thread.write("ASSIGN_SIDE:$side;".toByteArray()) }
        connectedThreads.add(thread); thread.start()
    }

    private inner class AcceptThread : Thread() {
        @SuppressLint("MissingPermission")
        override fun run() {
            val mmServerSocket: BluetoothServerSocket? = try { bluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord(NAME, MY_UUID) } catch (e: IOException) { null }
            while (true) { val socket = try { mmServerSocket?.accept() } catch (e: IOException) { break }; socket?.also { manageConnectedSocket(it, true) } }
        }
    }

    private inner class ConnectThread(private val device: BluetoothDevice) : Thread() {
        @SuppressLint("MissingPermission")
        override fun run() {
            val mmSocket: BluetoothSocket? = try { device.createRfcommSocketToServiceRecord(MY_UUID) } catch (e: IOException) { null }
            bluetoothAdapter.cancelDiscovery()
            try { mmSocket?.connect(); mmSocket?.let { manageConnectedSocket(it, false) } } catch (e: IOException) { mmSocket?.close() }
        }
    }

    private inner class ConnectedThread(private val mmSocket: BluetoothSocket) : Thread() {
        private val mmInStream = mmSocket.inputStream; private val mmOutStream = mmSocket.outputStream
        var assignedSide: String? = null
        override fun run() {
            val buffer = ByteArray(1024); var bytes: Int
            while (true) {
                bytes = try { mmInStream.read(buffer) } catch (e: IOException) { connectedThreads.remove(this); break }
                val rawMessage = String(buffer, 0, bytes); val messages = rawMessage.split(";")
                for (message in messages) {
                    if (message.isEmpty()) continue
                    handleMessage(message, this)
                    if (isServer) {
                        if (message.startsWith("PONG_PADDLE:") || message.startsWith("KART_STEER:")) continue
                        val relayMsg = "$message;"; val msgBytes = relayMsg.toByteArray()
                        synchronized(connectedThreads) { for (thread in connectedThreads) { if (thread != this) thread.write(msgBytes) } }
                    }
                }
            }
        }
        private fun handleMessage(message: String, fromThread: ConnectedThread) {
            runOnUiThread {
                try {
                    if (message.startsWith("MOVE:")) { val c = message.substring(5).split(","); redBall.x = c[0].toFloat() * screenWidth; redBall.y = c[1].toFloat() * screenHeight; redBall.visibility = View.VISIBLE }
                    else if (message == "UP") { redBall.visibility = View.GONE }
                    else if (message.startsWith("START_GAME:")) {
                        val g = message.substring(11); when (g) { "Pallina" -> startRedBallGame(); "Snake" -> startSnakeGame(); "Pong" -> startPongGame(); "Kart" -> startKartGame() }
                    } else if (message.startsWith("G2_MOVE:")) { val c = message.substring(8).split(","); updateSnakePosition(remoteSnake, c[0].toFloat() * screenWidth, c[1].toFloat() * screenHeight) }
                    else if (message == "SNAKE_WIN") { currentGame = null; showGameOverSnake(true) }
                    else if (message.startsWith("PONG_PADDLE:")) { val y = message.substring(12).toFloat() * screenHeight; if (isServer) { if (fromThread.assignedSide == "LEFT") localPaddle?.y = y else if (fromThread.assignedSide == "RIGHT") remotePaddle?.y = y } }
                    else if (message.startsWith("KART_STEER:")) { val steer = message.substring(11).toFloat(); if (isServer) { if (fromThread.assignedSide == "LEFT") player1Steer = steer else if (fromThread.assignedSide == "RIGHT") player2Steer = steer } }
                    else if (message.startsWith("ASSIGN_SIDE:")) { this@MainActivity.assignedSide = message.substring(12); updateUIForRole() }
                } catch (e: Exception) {}
            }
        }
        fun write(b: ByteArray) { try { mmOutStream.write(b) } catch (e: IOException) { } }
        fun cancel() { try { mmSocket.close() } catch (e: IOException) { } }
    }

    private fun broadcast(message: String) { val b = message.toByteArray(); synchronized(connectedThreads) { for (thread in connectedThreads) thread.write(b) } }

    private fun showGameOverSnake(won: Boolean, reason: String? = null) {
        runOnUiThread {
            stopGameLoop(); val dialog = AlertDialog.Builder(this).setTitle("Snake").setMessage(if (won) "Vinto!" else "Perso! $reason").setPositiveButton("OK") { _, _ -> clearAllGameViews(); currentGame = null }.setCancelable(false).create()
            dialog.show(); dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus()
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_GYROSCOPE) {
            val rotationX = event.values[0]; smoothedRotationX = smoothedRotationX * (1 - filterAlpha) + rotationX * filterAlpha
            val now = System.currentTimeMillis()
            if (now - lastPaddleUpdate > 25) {
                if (currentGame == "Pong" && assignedSide != null) {
                    val p = if (assignedSide == "LEFT") localPaddle else remotePaddle
                    p?.let { var ny = it.y + smoothedRotationX * 35f; ny = ny.coerceIn(0f, screenHeight.toFloat() - paddleHeight.toFloat()); it.y = ny; broadcast("PONG_PADDLE:${it.y / screenHeight};") }
                } else if (currentGame == "Kart" && assignedSide != null) {
                    broadcast("KART_STEER:$smoothedRotationX;")
                }
                lastPaddleUpdate = now
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onPause() { super.onPause(); sensorManager.unregisterListener(this) }
    override fun onDestroy() { super.onDestroy(); stopGameLoop(); synchronized(connectedThreads) { for (thread in connectedThreads) thread.cancel() }; sensorManager.unregisterListener(this) }

    inner class KartView(context: Context) : View(context) {
        private val paint = Paint()
        override fun onDraw(canvas: Canvas) {
            if (currentGame != "Kart") return
            val w = width.toFloat(); val h = height.toFloat()
            val horizon = h * 0.4f
            
            // Cielo
            paint.color = Color.parseColor("#87CEEB"); canvas.drawRect(0f, 0f, w, horizon, paint)
            // Prato
            paint.color = Color.parseColor("#228B22"); canvas.drawRect(0f, horizon, w, h, paint)
            
            // Rendering Pista (Pseudo-3D)
            for (i in 1 until 100) {
                val z = 100 - i
                val y = horizon + (h - horizon) * (1f / z)
                val nextY = horizon + (h - horizon) * (1f / (z - 1))
                val perspectiveWidth = w * (1f / z) * 15f
                val centerX = w / 2 + (trackCurve * (100 - z) * 0.1f)
                
                paint.color = if ((trackPos + z).toInt() % 10 < 5) Color.DKGRAY else Color.LTGRAY
                canvas.drawRect(centerX - perspectiveWidth, y, centerX + perspectiveWidth, nextY, paint)
            }
            
            // Kart Giocatori
            paint.color = Color.GREEN; canvas.drawRect(w / 2 - 50 + player1X, h - 250, w / 2 + 50 + player1X, h - 150, paint)
            paint.color = Color.RED; canvas.drawRect(w / 2 - 50 + player2X, h - 450, w / 2 + 50 + player2X, h - 350, paint)
        }
    }
}
