package com.igino.twophones

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.system.exitProcess

class MainActivity : AppCompatActivity() {

    private val MY_UUID: UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")
    private val NAME = "TwoPhones"

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var deviceList: ListView
    private lateinit var redBall: View
    private var greenBall: View? = null
    private lateinit var mainLayout: ConstraintLayout
    private var connectedThread: ConnectedThread? = null
    private var serverThread: AcceptThread? = null

    private var screenWidth = 0
    private var screenHeight = 0

    private var currentGame: String? = null
    private val localSnake = mutableListOf<View>()
    private val remoteSnake = mutableListOf<View>()
    private val gridLines = mutableListOf<View>()
    private val naviViews = mutableListOf<View>()
    private val shipViews = mutableListOf<View>()
    private val drawViews = mutableListOf<View>()
    private val snakeSize = 60 // pixels
    private val snakeLength = 8 // raddoppiato da 4 a 8

    private var naviCellSize = 0f
    private var naviStartX = 0f
    private var naviTopY = 0f
    private var naviBottomY = 0f
    private var naviSquareSize = 0

    private var btnStartNavi: Button? = null
    private var statusTextNavi: TextView? = null
    private var localReady = false
    private var remoteReady = false
    private var isServer = false
    private var isMyTurnNavi = false

    private var localHits = 0
    private var remoteHits = 0
    private val totalShipCells = 20

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mainLayout = findViewById(R.id.main)
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

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

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
            if (currentGame == "Snake") {
                handleSnakeSwipe(it)
            } else if (currentGame == "Navi") {
                // Gestito da onTouchListener dei quadrati
            } else if (currentGame == null) {
                if (it.action == MotionEvent.ACTION_DOWN || it.action == MotionEvent.ACTION_MOVE) {
                    val myColor = if (isServer) Color.RED else Color.BLUE
                    createDot(it.x, it.y, myColor)
                    connectedThread?.write("DRAW:${it.x / screenWidth},${it.y / screenHeight}".toByteArray())
                }
            } else {
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
        }
        return super.onTouchEvent(event)
    }

    private fun createDot(x: Float, y: Float, color: Int) {
        val dot = View(this).apply {
            val size = 20
            layoutParams = ViewGroup.LayoutParams(size, size)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            this.x = x - size / 2
            this.y = y - size / 2
        }
        mainLayout.addView(dot)
        drawViews.add(dot)
    }

    private fun handleSnakeSwipe(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
            }
            MotionEvent.ACTION_UP -> {
                val deltaX = event.x - downX
                val deltaY = event.y - downY
                if (abs(deltaX) > abs(deltaY)) {
                    if (abs(deltaX) > 100) {
                        localDirection = if (deltaX > 0) Direction.RIGHT else Direction.LEFT
                    }
                } else {
                    if (abs(deltaY) > 100) {
                        localDirection = if (deltaY > 0) Direction.DOWN else Direction.UP
                    }
                }
            }
        }
    }

    private fun updateSnakePosition(snake: List<View>, x: Float, y: Float) {
        if (snake.isEmpty()) return
        for (i in snake.size - 1 downTo 1) {
            snake[i].x = snake[i - 1].x
            snake[i].y = snake[i - 1].y
            snake[i].visibility = View.VISIBLE
        }
        snake[0].x = x
        snake[0].y = y
        snake[0].visibility = View.VISIBLE
    }

    private fun createGrid(color: Int) {
        val rows = 10
        val cols = 10
        val cellWidth = screenWidth.toFloat() / cols
        val cellHeight = screenHeight.toFloat() / rows

        for (i in 0..cols) {
            val line = View(this).apply {
                layoutParams = ViewGroup.LayoutParams(2, screenHeight)
                setBackgroundColor(color)
                x = i * cellWidth
                y = 0f
            }
            mainLayout.addView(line, 0)
            gridLines.add(line)
        }

        for (i in 0..rows) {
            val line = View(this).apply {
                layoutParams = ViewGroup.LayoutParams(screenWidth, 2)
                setBackgroundColor(color)
                x = 0f
                y = i * cellHeight
            }
            mainLayout.addView(line, 0)
            gridLines.add(line)
        }
    }

    private fun drawNaviGrid(startX: Float, startY: Float, size: Int, color: Int) {
        val cells = 8
        val cellSize = size.toFloat() / cells

        for (i in 0..cells) {
            // Vertical lines
            val vLine = View(this).apply {
                layoutParams = ViewGroup.LayoutParams(2, size)
                setBackgroundColor(color)
                x = startX + i * cellSize
                y = startY
            }
            mainLayout.addView(vLine, 0)
            naviViews.add(vLine)

            // Horizontal lines
            val hLine = View(this).apply {
                layoutParams = ViewGroup.LayoutParams(size, 2)
                setBackgroundColor(color)
                x = startX
                y = startY + i * cellSize
            }
            mainLayout.addView(hLine, 0)
            naviViews.add(hLine)
        }
    }

    private fun clearGrids() {
        gridLines.forEach { mainLayout.removeView(it) }
        gridLines.clear()
    }

    private fun clearAllGameViews() {
        localSnake.forEach { mainLayout.removeView(it) }
        remoteSnake.forEach { mainLayout.removeView(it) }
        localSnake.clear()
        remoteSnake.clear()
        clearGrids()
        naviViews.forEach { mainLayout.removeView(it) }
        naviViews.clear()
        shipViews.forEach { mainLayout.removeView(it) }
        shipViews.clear()
        drawViews.forEach { mainLayout.removeView(it) }
        drawViews.clear()
        btnStartNavi?.let { mainLayout.removeView(it) }
        btnStartNavi = null
        statusTextNavi?.let { mainLayout.removeView(it) }
        statusTextNavi = null
        greenBall?.let { mainLayout.removeView(it) }
        greenBall = null
        localReady = false
        remoteReady = false
        localHits = 0
        remoteHits = 0
        redBall.visibility = View.GONE
    }

    private fun startSnakeGame() {
        runOnUiThread {
            stopGameLoop()
            currentGame = "Snake"
            clearAllGameViews()

            createGrid(Color.argb(80, 200, 200, 200))
            createGrid(Color.argb(80, 150, 150, 150))

            for (i in 0 until snakeLength) {
                val circle = createSnakeCircle(Color.GREEN)
                localSnake.add(circle)
                mainLayout.addView(circle)
            }
            localSnake[0].x = 100f
            localSnake[0].y = 100f

            for (i in 0 until snakeLength) {
                val circle = createSnakeCircle(Color.BLUE)
                remoteSnake.add(circle)
                mainLayout.addView(circle)
            }
            
            startGameLoop()
        }
    }

    private fun createSnakeCircle(color: Int): View {
        return View(this).apply {
            layoutParams = ViewGroup.LayoutParams(snakeSize, snakeSize)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            visibility = View.GONE
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun startNaviGame() {
        runOnUiThread {
            stopGameLoop()
            currentGame = "Navi"
            clearAllGameViews()

            val margin = 60
            val bottomBarHeight = try { findViewById<View>(R.id.bottomBar).height } catch (e: Exception) { 200 }
            val availableWidth = screenWidth - (margin * 2)
            naviSquareSize = availableWidth.coerceAtMost((screenHeight / 2) - 200)
            naviCellSize = naviSquareSize.toFloat() / 8

            naviStartX = (screenWidth - naviSquareSize) / 2f
            naviTopY = 0f
            naviBottomY = screenHeight.toFloat() - naviSquareSize.toFloat() - bottomBarHeight.toFloat()

            // Top Square (Target)
            val topSquare = View(this).apply {
                layoutParams = ViewGroup.LayoutParams(naviSquareSize, naviSquareSize)
                background = GradientDrawable().apply {
                    setStroke(5, Color.BLUE)
                    setColor(Color.argb(30, 0, 0, 255))
                }
                x = naviStartX
                y = naviTopY
            }
            mainLayout.addView(topSquare, 0)
            naviViews.add(topSquare)
            drawNaviGrid(naviStartX, naviTopY, naviSquareSize, Color.BLUE)

            // Bottom Square (Pool/Attack)
            val bottomSquare = View(this).apply {
                layoutParams = ViewGroup.LayoutParams(naviSquareSize, naviSquareSize)
                background = GradientDrawable().apply {
                    setStroke(5, Color.RED)
                    setColor(Color.argb(30, 255, 0, 0))
                }
                x = naviStartX
                y = naviBottomY
            }
            bottomSquare.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN && localReady && remoteReady && isMyTurnNavi) {
                    val col = (event.x / naviCellSize).toInt()
                    val row = (event.y / naviCellSize).toInt()
                    if (col in 0..7 && row in 0..7) {
                        connectedThread?.write("NAVI_ATTACK:$row,$col".toByteArray())
                        isMyTurnNavi = false
                        updateNaviStatusText()
                    }
                }
                false
            }
            mainLayout.addView(bottomSquare, 0)
            naviViews.add(bottomSquare)
            drawNaviGrid(naviStartX, naviBottomY, naviSquareSize, Color.RED)

            addShipsToNavi(naviStartX, naviBottomY, naviSquareSize)

            statusTextNavi = TextView(this).apply {
                layoutParams = ConstraintLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                    bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                    startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                    endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                    verticalBias = 0.45f
                }
                gravity = Gravity.CENTER
                textSize = 24f
                setTextColor(Color.BLACK)
                visibility = View.GONE
            }
            mainLayout.addView(statusTextNavi)

            btnStartNavi = Button(this).apply {
                text = "START"
                layoutParams = ConstraintLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                    bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                    startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                    endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                    verticalBias = 0.45f
                }
                setOnClickListener {
                    if (allShipsInTopGrid()) {
                        localReady = true
                        connectedThread?.write("NAVI_READY".toByteArray())
                        checkStartNaviGame()
                    } else {
                        Toast.makeText(this@MainActivity, "Sposta tutte le navi nel quadrato superiore!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            mainLayout.addView(btnStartNavi)

            Toast.makeText(this, "Navi: Trascina con tocco, doppio tocco per ruotare!", Toast.LENGTH_LONG).show()
        }
    }

    private fun allShipsInTopGrid(): Boolean {
        for (ship in shipViews) {
            if (ship.y > naviTopY + naviSquareSize) return false
        }
        return true
    }

    private fun checkStartNaviGame() {
        if (localReady && remoteReady) {
            btnStartNavi?.visibility = View.GONE
            statusTextNavi?.visibility = View.VISIBLE
            isMyTurnNavi = isServer
            updateNaviStatusText()
        } else if (localReady) {
            btnStartNavi?.text = "ATTESA ALTRO GIOCATORE..."
            btnStartNavi?.isEnabled = false
        }
    }

    private fun updateNaviStatusText() {
        statusTextNavi?.text = if (isMyTurnNavi) "TOCCA A TE" else "ATTENDI L'AVVERSARIO..."
    }

    private fun addShipsToNavi(startX: Float, startY: Float, squareSize: Int) {
        val shipSizes = listOf(4, 3, 3, 2, 2, 2, 1, 1, 1, 1)
        val occupied = Array(8) { BooleanArray(8) }

        for (size in shipSizes) {
            var placed = false
            var attempts = 0
            while (!placed && attempts < 100) {
                val horizontal = (0..1).random() == 0
                val r = (0 until 8).random()
                val c = (0 until 8).random()

                var canPlace = true
                if (horizontal) {
                    if (c + size > 8) canPlace = false
                    else {
                        for (i in 0 until size) if (occupied[r][c + i]) canPlace = false
                    }
                } else {
                    if (r + size > 8) canPlace = false
                    else {
                        for (i in 0 until size) if (occupied[r + i][c]) canPlace = false
                    }
                }

                if (canPlace) {
                    val shipView = View(this).apply {
                        val w = if (horizontal) size * naviCellSize else naviCellSize
                        val h = if (horizontal) naviCellSize else size * naviCellSize
                        layoutParams = ViewGroup.LayoutParams(w.toInt(), h.toInt())
                        background = GradientDrawable().apply {
                            setColor(Color.DKGRAY)
                            setStroke(3, Color.WHITE)
                            cornerRadius = 10f
                        }
                        x = startX + c * naviCellSize
                        y = startY + r * naviCellSize
                    }
                    mainLayout.addView(shipView)
                    naviViews.add(shipView)
                    shipViews.add(shipView)
                    makeDraggable(shipView)

                    if (horizontal) {
                        for (i in 0 until size) occupied[r][c + i] = true
                    } else {
                        for (i in 0 until size) occupied[r + i][c] = true
                    }
                    placed = true
                }
                attempts++
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeDraggable(view: View) {
        var dX = 0f
        var dY = 0f
        var oldX = 0f
        var oldY = 0f
        var lastClickTime = 0L

        view.setOnTouchListener { v, event ->
            if (localReady) return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val clickTime = System.currentTimeMillis()
                    if (clickTime - lastClickTime < 300) {
                        rotateShip(v)
                        lastClickTime = 0
                        return@setOnTouchListener true
                    }
                    lastClickTime = clickTime
                    
                    oldX = v.x
                    oldY = v.y
                    dX = v.x - event.rawX
                    dY = v.y - event.rawY
                    v.bringToFront()
                }
                MotionEvent.ACTION_MOVE -> {
                    v.x = event.rawX + dX
                    v.y = event.rawY + dY
                }
                MotionEvent.ACTION_UP -> {
                    snapAndValidate(v, oldX, oldY)
                }
            }
            true
        }
    }

    private fun rotateShip(v: View) {
        val lp = v.layoutParams
        val oldW = lp.width
        val oldH = lp.height
        lp.width = oldH
        lp.height = oldW
        v.layoutParams = lp
        
        if (!snapAndValidate(v, v.x, v.y, isRotation = true)) {
            lp.width = oldW
            lp.height = oldH
            v.layoutParams = lp
            Toast.makeText(this, "Spazio insufficiente per ruotare", Toast.LENGTH_SHORT).show()
        }
    }

    private fun snapAndValidate(v: View, fallbackX: Float, fallbackY: Float, isRotation: Boolean = false): Boolean {
        val centerY = v.y + v.height / 2
        val targetGridY = if (abs(centerY - (naviTopY + naviSquareSize / 2)) < abs(centerY - (naviBottomY + naviSquareSize / 2))) {
            naviTopY
        } else {
            naviBottomY
        }
        
        val newX = naviStartX + ((v.x - naviStartX) / naviCellSize).roundToInt() * naviCellSize
        val newY = targetGridY + ((v.y - targetGridY) / naviCellSize).roundToInt() * naviCellSize
        
        val tolerance = 5f
        if (newX < naviStartX - tolerance || newX + v.width > naviStartX + naviSquareSize + tolerance ||
            newY < targetGridY - tolerance || newY + v.height > targetGridY + naviSquareSize + tolerance) {
            if (!isRotation) {
                v.x = fallbackX
                v.y = fallbackY
            }
            return false
        }
        
        val currentRect = Rect(newX.toInt() + 2, newY.toInt() + 2, (newX + v.width).toInt() - 2, (newY + v.height).toInt() - 2)
        for (other in shipViews) {
            if (other === v) continue
            val otherRect = Rect(other.x.toInt() + 2, other.y.toInt() + 2, (other.x + other.width).toInt() - 2, (other.y + other.height).toInt() - 2)
            if (Rect.intersects(currentRect, otherRect)) {
                if (!isRotation) {
                    v.x = fallbackX
                    v.y = fallbackY
                }
                return false
            }
        }
        
        v.x = newX
        v.y = newY
        return true
    }

    private fun checkIncomingAttack(r: Int, c: Int) {
        val attackX = naviStartX + c * naviCellSize + naviCellSize / 2
        val attackY = naviTopY + r * naviCellSize + naviCellSize / 2
        
        var isHit = false
        for (ship in shipViews) {
            val shipRect = Rect(ship.x.toInt(), ship.y.toInt(), (ship.x + ship.width).toInt(), (ship.y + ship.height).toInt())
            if (shipRect.contains(attackX.toInt(), attackY.toInt())) {
                isHit = true
                break
            }
        }
        
        val result = if (isHit) "HIT" else "MISS"
        connectedThread?.write("NAVI_RESULT:$r,$c,$result".toByteArray())
        
        drawMarker(r, c, isHit, isTopGrid = true)
        
        if (isHit) {
            remoteHits++
            if (remoteHits >= totalShipCells) {
                showGameOver(false)
                return
            }
        }
        
        isMyTurnNavi = true
        updateNaviStatusText()
    }

    private fun drawMarker(r: Int, c: Int, isHit: Boolean, isTopGrid: Boolean) {
        val gridY = if (isTopGrid) naviTopY else naviBottomY
        val marker = if (isHit) {
            View(this).apply {
                layoutParams = ViewGroup.LayoutParams(naviCellSize.toInt(), naviCellSize.toInt())
                setBackgroundColor(Color.BLACK)
            }
        } else {
            TextView(this).apply {
                layoutParams = ViewGroup.LayoutParams(naviCellSize.toInt(), naviCellSize.toInt())
                text = "X"
                setTextColor(Color.BLACK)
                gravity = Gravity.CENTER
                textSize = 24f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
        }
        marker.x = naviStartX + c * naviCellSize
        marker.y = gridY + r * naviCellSize
        mainLayout.addView(marker)
        naviViews.add(marker)
    }

    private fun showGameOver(won: Boolean) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("Fine Partita")
                .setMessage(if (won) "Complimenti! Hai vinto colpendole tutte!" else "Peccato! L'avversario ha vinto.")
                .setPositiveButton("OK") { _, _ ->
                    clearAllGameViews()
                    currentGame = null
                }
                .setCancelable(false)
                .show()
        }
    }

    private fun startGameLoop() {
        gameRunnable = object : Runnable {
            override fun run() {
                if (currentGame == "Snake") {
                    var newX = localSnake[0].x
                    var newY = localSnake[0].y

                    when (localDirection) {
                        Direction.UP -> newY -= moveSpeed
                        Direction.DOWN -> newY += moveSpeed
                        Direction.LEFT -> newX -= moveSpeed
                        Direction.RIGHT -> newX += moveSpeed
                    }

                    if (newX < 0) newX = screenWidth.toFloat()
                    if (newX > screenWidth) newX = 0f
                    if (newY < 0) newY = screenHeight.toFloat()
                    if (newY > screenHeight) newY = 0f

                    updateSnakePosition(localSnake, newX, newY)
                    
                    val xRatio = newX / screenWidth
                    val yRatio = newY / screenHeight
                    connectedThread?.write("G2_MOVE:$xRatio,$yRatio".toByteArray())

                    gameHandler.postDelayed(this, 100)
                }
            }
        }
        gameHandler.post(gameRunnable!!)
    }

    private fun stopGameLoop() {
        gameRunnable?.let { gameHandler.removeCallbacks(it) }
        gameRunnable = null
    }

    private fun startRedBallGame() {
        runOnUiThread {
            stopGameLoop()
            currentGame = "Pallina"
            clearAllGameViews()
            
            val size = 200
            greenBall = View(this).apply {
                layoutParams = ViewGroup.LayoutParams(size, size)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.GREEN)
                }
                x = (screenWidth / 2f) - (size / 2f)
                y = (screenHeight / 2f) - (size / 2f)
            }
            mainLayout.addView(greenBall)
            makeGreenBallDraggable(greenBall!!)
            
            Toast.makeText(this, "Pallina Attiva: Trascina la pallina verde!", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeGreenBallDraggable(v: View) {
        var dX = 0f
        var dY = 0f
        v.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dX = view.x - event.rawX
                    dY = view.y - event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    view.x = event.rawX + dX
                    view.y = event.rawY + dY
                }
            }
            true
        }
    }

    private fun manageConnectedSocket(socket: BluetoothSocket, accepted: Boolean) {
        isServer = accepted
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
                    manageConnectedSocket(it, true)
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
                    manageConnectedSocket(socket, false)
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
                        
                        greenBall?.let { gb ->
                            if (currentGame == "Pallina") {
                                val redRect = Rect(redBall.x.toInt(), redBall.y.toInt(), (redBall.x + redBall.width).toInt(), (redBall.y + redBall.height).toInt())
                                val greenRect = Rect(gb.x.toInt(), gb.y.toInt(), (gb.x + gb.width).toInt(), (gb.y + gb.height).toInt())
                                if (Rect.intersects(redRect, greenRect)) {
                                    showGameOverPallina(false)
                                    connectedThread?.write("PALLINA_WIN".toByteArray())
                                }
                            }
                        }
                    } catch (e: Exception) { }
                } else if (message == "UP") {
                    redBall.visibility = View.GONE
                } else if (message.startsWith("DRAW:")) {
                    try {
                        val coords = message.substring(5).split(",")
                        val x = coords[0].toFloat() * screenWidth
                        val y = coords[1].toFloat() * screenHeight
                        val opponentColor = if (isServer) Color.BLUE else Color.RED
                        createDot(x, y, opponentColor)
                    } catch (e: Exception) {}
                } else if (message == "PALLINA_WIN") {
                    showGameOverPallina(true)
                } else if (message.startsWith("START_GAME:")) {
                    val gameName = message.substring(11)
                    showGameRequest(gameName)
                } else if (message.startsWith("GAME_ACCEPTED:")) {
                    val gameName = message.substring(14)
                    when (gameName) {
                        "Pallina" -> startRedBallGame()
                        "Snake" -> startSnakeGame()
                        "Navi" -> startNaviGame()
                    }
                } else if (message.startsWith("G2_MOVE:")) {
                    try {
                        val coords = message.substring(8).split(",")
                        val x = coords[0].toFloat() * screenWidth
                        val y = coords[1].toFloat() * screenHeight
                        updateSnakePosition(remoteSnake, x, y)
                    } catch (e: Exception) { }
                } else if (message == "NAVI_READY") {
                    remoteReady = true
                    checkStartNaviGame()
                } else if (message.startsWith("NAVI_ATTACK:")) {
                    val parts = message.substring(12).split(",")
                    val r = parts[0].toInt()
                    val c = parts[1].toInt()
                    checkIncomingAttack(r, c)
                } else if (message.startsWith("NAVI_RESULT:")) {
                    val parts = message.substring(12).split(",")
                    val r = parts[0].toInt()
                    val c = parts[1].toInt()
                    val res = parts[2]
                    drawMarker(r, c, res == "HIT", isTopGrid = false)
                    if (res == "HIT") {
                        localHits++
                        if (localHits >= totalShipCells) {
                            showGameOver(true)
                        }
                    }
                }
            }
        }

        private fun showGameRequest(gameName: String) {
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Richiesta Gioco")
                .setMessage("L'altro dispositivo vuole avviare: $gameName. Vuoi giocare?")
                .setPositiveButton("Sì") { _, _ ->
                    connectedThread?.write("GAME_ACCEPTED:$gameName".toByteArray())
                    when (gameName) {
                        "Pallina" -> startRedBallGame()
                        "Snake" -> startSnakeGame()
                        "Navi" -> startNaviGame()
                        else -> Toast.makeText(this@MainActivity, "Avvio di $gameName...", Toast.LENGTH_SHORT).show()
                    }
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

    private fun showGameOverPallina(won: Boolean) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("Fine Partita Pallina")
                .setMessage(if (won) "Hai Vinto! Hai colpito la pallina avversaria!" else "Hai Perso! L'avversario ha colpito la tua pallina!")
                .setPositiveButton("OK") { _, _ ->
                    clearAllGameViews()
                    currentGame = null
                }
                .setCancelable(false)
                .show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopGameLoop()
        serverThread?.cancel()
        connectedThread?.cancel()
    }
}
