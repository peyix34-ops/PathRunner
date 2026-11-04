package com.novastudio.pathrunner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    private enum class State { READY, RUNNING, PAUSED, GAME_OVER }

    private enum class ObstacleKind { SPIKE, BLOCK, BIRD }

    private var state = State.READY

    private val dpr = resources.displayMetrics.density
    private fun dp(v: Float) = v * dpr

    private var viewW = 0
    private var viewH = 0
    private var groundY = 0f

    @Volatile private var running = false
    private var thread: GameThread? = null

    private var time = 0f
    private var distance = 0f
    private var speed = 0f
    private var score = 0
    private var best = 0
    private var coinsCollected = 0

    private var scroll = 0f
    private var runCycle = 0f
    private var dustTimer = 0f

    private val gravity = dp(2800f)
    private val jumpVel = sqrt(2f * gravity * dp(160f))

    private var py = -1f        // player feet Y (-1 = not initialized)
    private var vy = 0f
    private var jumps = 0
    private val maxJumps = 2

    private var spawnIn = 0.9f
    private var deathTimer = 0f
    private var shake = 0f
    private var newRecord = false

    // ---------------- Entities ----------------

    private class Obstacle(
        var x: Float, val w: Float, val h: Float,
        val kind: ObstacleKind, val baseY: Float = 0f, val phase: Float = 0f
    )

    private class Coin(val xBase: Float, var x: Float, val y: Float, val phase: Float)

    private class Particle(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        var life: Float, val maxLife: Float, val color: Int, val size: Float
    )

    private val obstacles = ArrayList<Obstacle>()
    private val coins = ArrayList<Coin>()
    private val particles = ArrayList<Particle>()

    // ---------------- Drawing / audio ----------------

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    private val prefs = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)

    private val tone: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, 60)
    } catch (_: Exception) {
        null
    }

    init {
        best = prefs.getInt(MainActivity.KEY_BEST, 0)
        holder.addCallback(this)
        isFocusable = true
    }

    // ================================================================
    //  Thread / Surface lifecycle
    // ================================================================

    private inner class GameThread : Thread() {
        override fun run() {
            var last = System.nanoTime()
            while (running) {
                val now = System.nanoTime()
                var dt = (now - last) / 1_000_000_000f
                last = now
                if (dt > 0.05f) dt = 0.05f
                update(dt)
                val c = holder.lockCanvas()
                if (c != null) {
                    try {
                        render(c)
                    } finally {
                        holder.unlockCanvasAndPost(c)
                    }
                }
            }
        }
    }

    fun resumeGame() = startThread()

    fun pauseGame() {
        if (state == State.RUNNING) state = State.PAUSED
        stopThread()
    }

    private fun startThread() {
        if (running) return
        if (!holder.surface.isValid) return
        running = true
        thread = GameThread().also { it.start() }
    }

    private fun stopThread() {
        running = false
        thread?.let {
            try {
                it.join(400)
            } catch (_: InterruptedException) {
            }
        }
        thread = null
    }

    override fun surfaceCreated(holder: SurfaceHolder) = startThread()

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) = stopThread()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        viewW = w
        viewH = h
        groundY = h * 0.78f
        if (py < 0f) py = groundY
    }

    override fun onDetachedFromWindow() {
        try {
            tone?.release()
        } catch (_: Exception) {
        }
        super.onDetachedFromWindow()
    }

    // ================================================================
    //  Input
    // ================================================================

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                when (state) {
                    State.READY -> state = State.RUNNING
                    State.RUNNING -> jump()
                    State.PAUSED -> state = State.RUNNING
                    State.GAME_OVER -> if (deathTimer <= 0f) resetAndStart()
                }
                performClick()
                return true
            }
            MotionEvent.ACTION_UP -> {
                // Variable jump height: releasing early cuts the jump short
                if (state == State.RUNNING && vy < 0f) vy *= 0.55f
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    private fun jump() {
        if (jumps < maxJumps) {
            vy = -jumpVel * (if (jumps == 0) 1f else 0.92f)
            jumps++
            burst(px(), py - dp(6f), 8, 0xCCFFFFFF.toInt(), dp(4f), spread = 220f, up = 260f)
            beep(if (jumps == 1) ToneGenerator.TONE_DTMF_5 else ToneGenerator.TONE_DTMF_8, 70)
        }
    }

    private fun px() = viewW * 0.22f

    // ================================================================
    //  Game logic
    // ================================================================

    private fun update(dt: Float) {
        time += dt
        updateParticles(dt)
        if (shake > 0f) shake = (shake - dt * 30f).coerceAtLeast(0f)

        when (state) {
            State.RUNNING -> updateRunning(dt)
            State.GAME_OVER -> if (deathTimer > 0f) deathTimer -= dt
            else -> {}
        }
    }

    private fun updateRunning(dt: Float) {
        if (viewW == 0) return

        speed = ((420f + distance * 0.012f).coerceAtMost(1400f)) * dpr
        distance += speed * dt
        scroll += speed * dt
        score = (distance / dpr / 10f).toInt() + coinsCollected * 10
        runCycle += dt * (speed / dpr) * 0.05f

        // --- Player physics ---
        if (py < groundY - 1f || vy != 0f) {
            vy += gravity * dt
            py += vy * dt
            if (py >= groundY) {
                py = groundY
                vy = 0f
                jumps = 0
            }
        } else {
            py = groundY
        }

        // Run dust
        dustTimer -= dt
        if (py >= groundY - 1f && dustTimer <= 0f) {
            dustTimer = 0.07f
            particles.add(
                Particle(
                    px() - dp(10f), py - dp(3f),
                    -speed * 0.25f, -dp(30f),
                    0.4f, 0.4f, 0x55FFFFFF, dp(4f)
                )
            )
        }

        // --- Spawn obstacles ---
        spawnIn -= dt
        if (spawnIn <= 0f) {
            spawnObstacle()
            val speedFactor = (speed / dpr / 520f).coerceAtLeast(0.7f)
            spawnIn = (0.95f + Random.nextFloat() * 0.85f) * speedFactor
        }

        // --- Move obstacles + collision ---
        val playerL = px() - dp(16f)
        val playerR = px() + dp(16f)
        val playerT = py - dp(60f)
        val playerB = py - dp(4f)

        val it = obstacles.iterator()
        while (it.hasNext()) {
            val o = it.next()
            o.x -= (if (o.kind == ObstacleKind.BIRD) speed + dp(120f) else speed) * dt
            if (o.x + o.w < -dp(40f)) {
                it.remove()
                continue
            }
            val (ol, orr, ot, ob) = obstacleRect(o)
            if (playerR > ol && playerL < orr && playerB > ot && playerT < ob) {
                gameOver()
                return
            }
        }

        // --- Coins ---
        val cit = coins.iterator()
        while (cit.hasNext()) {
            val c = cit.next()
            c.x -= speed * dt
            if (c.x < -dp(30f)) {
                cit.remove()
                continue
            }
            val cy = c.y + sin(time * 3f + c.phase) * dp(6f)
            if (abs(c.x - px()) < dp(30f) && abs(cy - (py - dp(35f))) < dp(55f)) {
                cit.remove()
                coinsCollected++
                burst(c.x, cy, 10, 0xFFFFD54F.toInt(), dp(4f), spread = 320f, up = 200f)
                beep(ToneGenerator.TONE_PROP_BEEP, 80)
            }
        }
    }

    private fun obstacleRect(o: Obstacle): FloatArray {
        return when (o.kind) {
            ObstacleKind.SPIKE -> floatArrayOf(
                o.x + dp(3f), o.x + o.w - dp(3f),
                groundY - o.h + dp(5f), groundY
            )
            ObstacleKind.BLOCK -> floatArrayOf(
                o.x + dp(3f), o.x + o.w - dp(3f),
                groundY - o.h + dp(4f), groundY
            )
            ObstacleKind.BIRD -> {
                val cy = birdY(o)
                floatArrayOf(
                    o.x + dp(4f), o.x + o.w - dp(4f),
                    cy - o.h / 2f + dp(4f), cy + o.h / 2f - dp(4f)
                )
            }
        }
    }

    private fun birdY(o: Obstacle) = o.baseY + sin(time * 3f + o.phase) * dp(22f)

    private fun spawnObstacle() {
        val startX = viewW + dp(60f)
        val difficulty = (distance / dpr / 4000f).coerceIn(0f, 1f)
        val r = Random.nextFloat()

        when {
            r < 0.45f -> {
                val w = dp(Random.nextInt(26, 40).toFloat())
                val h = dp(Random.nextInt(36, 70).toFloat())
                obstacles.add(Obstacle(startX, w, h, ObstacleKind.SPIKE))
            }
            r < 0.45f + 0.3f * (1f - difficulty * 0.5f) -> {
                val w = dp(Random.nextInt(24, 34).toFloat())
                val h = dp(Random.nextInt(80, 120).toFloat())
                obstacles.add(Obstacle(startX, w, h, ObstacleKind.BLOCK))
            }
            else -> {
                val w = dp(56f)
                val h = dp(36f)
                val baseY = groundY - dp(Random.nextInt(110, 180).toFloat())
                obstacles.add(Obstacle(startX, w, h, ObstacleKind.BIRD, baseY, Random.nextFloat() * 6f))
            }
        }

        // Coin arc above ground obstacles
        if (Random.nextFloat() < 0.6f) {
            val count = Random.nextInt(3, 6)
            for (i in 0 until count) {
                val t = i / (count - 1f)
                val arcH = dp(90f) * sin(t * Math.PI.toFloat())
                coins.add(
                    Coin(
                        startX + dp(40f) + i * dp(46f), 0f,
                        groundY - dp(60f) - arcH, Random.nextFloat() * 6f
                    )
                )
            }
        }
    }

    private fun gameOver() {
        state = State.GAME_OVER
        deathTimer = 0.9f
        shake = 14f
        burst(px(), py - dp(30f), 26, 0xFFFF7043.toInt(), dp(6f), spread = 520f, up = 500f)
        burst(px(), py - dp(30f), 14, 0xFFFFFFFF.toInt(), dp(4f), spread = 420f, up = 400f)
        beep(ToneGenerator.TONE_PROP_NACK, 250)
        vibrate(200)
        newRecord = score > best
        if (newRecord) {
            best = score
            prefs.edit().putInt(MainActivity.KEY_BEST, best).apply()
        }
    }

    private fun resetAndStart() {
        obstacles.clear()
        coins.clear()
        particles.clear()
        distance = 0f
        speed = 0f
        score = 0
        coinsCollected = 0
        scroll = 0f
        vy = 0f
        jumps = 0
        py = groundY
        spawnIn = 0.9f
        newRecord = false
        state = State.RUNNING
    }

    private fun updateParticles(dt: Float) {
        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.life -= dt
            if (p.life <= 0f) {
                it.remove()
                continue
            }
            p.vy += gravity * 0.5f * dt
            p.x += p.vx * dt
            p.y += p.vy * dt
        }
    }

    private fun burst(
        x: Float, y: Float, count: Int, color: Int,
        size: Float, spread: Float, up: Float
    ) {
        for (i in 0 until count) {
            val angle = Random.nextFloat() * Math.PI.toFloat()
            val force = (0.4f + Random.nextFloat() * 0.6f)
            particles.add(
                Particle(
                    x, y,
                    (Random.nextFloat() * 2f - 1f) * spread * force,
                    -up * (0.4f + Random.nextFloat() * 0.8f),
                    0.5f + Random.nextFloat() * 0.3f,
                    0.8f, color, size * (0.6f + Random.nextFloat() * 0.8f)
                )
            )
        }
        // angle unused above intentionally; random is fine
    }

    private fun beep(type: Int, durationMs: Int) {
        try {
            tone?.startTone(type, durationMs)
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate(ms: Long) {
        try {
            val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                v.vibrate(ms)
            }
        } catch (_: Exception) {
        }
    }

    // ================================================================
    //  Rendering
    // ================================================================

    private fun render(c: Canvas) {
        if (shake > 0f) {
            c.translate(
                (Random.nextFloat() * 2f - 1f) * shake,
                (Random.nextFloat() * 2f - 1f) * shake
            )
        }
        drawSky(c)
        drawSun(c)
        drawClouds(c)
        drawMountains(c, groundY - dp(70f), dp(150f), scroll * 0.15f, dp(340f), 0x995C6BC0.toInt())
        drawMountains(c, groundY - dp(20f), dp(95f), scroll * 0.35f, dp(240f), 0xCC3F51B5.toInt())
        drawRoad(c)
        for (coin in coins) drawCoin(c, coin)
        for (o in obstacles) drawObstacle(c, o)
        if (state != State.GAME_OVER || deathTimer > 0.3f) drawPlayer(c)
        drawParticles(c)
        drawHud(c)

        when (state) {
            State.READY -> drawReady(c)
            State.PAUSED -> drawPaused(c)
            State.GAME_OVER -> drawGameOver(c)
            State.RUNNING -> {}
        }
    }

    private fun drawSky(c: Canvas) {
        paint.shader = LinearGradient(
            0f, 0f, 0f, viewH.toFloat(),
            0xFF2193B0.toInt(), 0xFF6DD5ED.toInt(), Shader.TileMode.CLAMP
        )
        paint.style = Paint.Style.FILL
        c.drawRect(0f, 0f, viewW.toFloat(), viewH.toFloat(), paint)
        paint.shader = null
    }

    private fun drawSun(c: Canvas) {
        val sx = viewW * 0.8f
        val sy = viewH * 0.18f
        val pulse = 1f + sin(time * 2f) * 0.06f
        paint.style = Paint.Style.FILL
        paint.color = 0x55FFF59D
        c.drawCircle(sx, sy, dp(62f) * pulse, paint)
        paint.color = 0xFFFFF59D.toInt()
        c.drawCircle(sx, sy, dp(34f) * pulse, paint)
    }

    private fun drawClouds(c: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = 0xB3FFFFFF.toInt()
        val period = viewW + dp(400f)
        val bases = arrayOf(0.15f, 0.28f, 0.10f)
        val scales = arrayOf(1.0f, 0.7f, 1.25f)
        for (i in bases.indices) {
            val baseX = viewW * (0.25f + 0.35f * i)
            val cx = ((baseX - scroll * 0.2f) % period + period) % period - dp(200f)
            drawCloud(c, cx, viewH * bases[i], scales[i])
        }
    }

    private fun drawCloud(c: Canvas, cx: Float, cy: Float, s: Float) {
        val r = dp(22f) * s
        c.drawCircle(cx, cy, r, paint)
        c.drawCircle(cx - r * 1.2f, cy + r * 0.25f, r * 0.75f, paint)
        c.drawCircle(cx + r * 1.2f, cy + r * 0.25f, r * 0.8f, paint)
    }

    private fun drawMountains(
        c: Canvas, baseY: Float, height: Float,
        offset: Float, period: Float, color: Int
    ) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = color
        path.reset()
        val off = ((offset % period) + period) % period
        var x = -off - period
        path.moveTo(x, baseY)
        while (x < viewW + period) {
            path.lineTo(x + period / 2f, baseY - height)
            path.lineTo(x + period, baseY)
            x += period
        }
        path.lineTo(viewW + period, viewH.toFloat())
        path.lineTo(-period, viewH.toFloat())
        path.close()
        c.drawPath(path, paint)
    }

    private fun drawRoad(c: Canvas) {
        paint.style = Paint.Style.FILL
        // grass
        paint.color = 0xFF66BB6A.toInt()
        c.drawRect(0f, groundY - dp(14f), viewW.toFloat(), groundY, paint)
        // grass blades
        paint.color = 0xFF43A047.toInt()
        val bladeGap = dp(46f)
        var bx = -((scroll * 1.05f % bladeGap) + bladeGap) % bladeGap
        while (bx < viewW + bladeGap) {
            path.reset()
            path.moveTo(bx, groundY - dp(14f))
            path.lineTo(bx + dp(5f), groundY - dp(24f))
            path.lineTo(bx + dp(10f), groundY - dp(14f))
            path.close()
            c.drawPath(path, paint)
            bx += bladeGap
        }
        // asphalt
        paint.color = 0xFF37474F.toInt()
        c.drawRect(0f, groundY, viewW.toFloat(), viewH.toFloat(), paint)
        // edge line
        paint.color = 0xDDFFFFFF.toInt()
        c.drawRect(0f, groundY + dp(6f), viewW.toFloat(), groundY + dp(9f), paint)
        // center dashes
        paint.color = 0xB3FFFFFF.toInt()
        val dashL = dp(46f)
        val gapL = dp(30f)
        val cycle = dashL + gapL
        val y = groundY + (viewH - groundY) * 0.55f
        var x = -((scroll % cycle) + cycle) % cycle - cycle
        while (x < viewW + cycle) {
            c.drawRect(x, y, x + dashL, y + dp(5f), paint)
            x += cycle
        }
    }

    private fun drawCoin(c: Canvas, coin: Coin) {
        val cy = coin.y + sin(time * 3f + coin.phase) * dp(6f)
        val w = abs(sin(time * 5f + coin.phase)) * dp(11f) + dp(3f)
        paint.style = Paint.Style.FILL
        paint.color = 0x66FFD54F
        c.drawCircle(coin.x, cy, dp(17f), paint)
        paint.color = 0xFFFFD54F.toInt()
        rect.set(coin.x - w, cy - dp(12f), coin.x + w, cy + dp(12f))
        c.drawOval(rect, paint)
        paint.color = 0xFFFFA000.toInt()
        rect.set(coin.x - w * 0.55f, cy - dp(7f), coin.x + w * 0.55f, cy + dp(7f))
        c.drawOval(rect, paint)
    }

    private fun drawObstacle(c: Canvas, o: Obstacle) {
        when (o.kind) {
            ObstacleKind.SPIKE -> {
                val top = groundY - o.h
                paint.style = Paint.Style.FILL
                paint.color = 0xFF455A64.toInt()
                rect.set(o.x, top + o.h * 0.25f, o.x + o.w, groundY)
                c.drawRoundRect(rect, dp(6f), dp(6f), paint)
                paint.color = 0xFF37474F.toInt()
                path.reset()
                path.moveTo(o.x, top + o.h * 0.35f)
                path.lineTo(o.x + o.w * 0.5f, top)
                path.lineTo(o.x + o.w, top + o.h * 0.35f)
                path.close()
                c.drawPath(path, paint)
                // red tip
                paint.color = 0xFFEF5350.toInt()
                path.reset()
                path.moveTo(o.x + o.w * 0.32f, top + o.h * 0.14f)
                path.lineTo(o.x + o.w * 0.5f, top)
                path.lineTo(o.x + o.w * 0.68f, top + o.h * 0.14f)
                path.close()
                c.drawPath(path, paint)
            }
            ObstacleKind.BLOCK -> {
                val top = groundY - o.h
                paint.style = Paint.Style.FILL
                paint.color = 0xFF6D4C41.toInt()
                rect.set(o.x, top, o.x + o.w, groundY)
                c.drawRoundRect(rect, dp(5f), dp(5f), paint)
                paint.color = 0xFF4E342E.toInt()
                val stripeH = o.h / 5f
                for (i in 1..4) {
                    c.drawRect(o.x + dp(4f), top + stripeH * i, o.x + o.w - dp(4f), top + stripeH * i + dp(2f), paint)
                }
                paint.color = 0xFFFFD54F.toInt()
                c.drawRect(o.x, top, o.x + o.w, top + dp(6f), paint)
            }
            ObstacleKind.BIRD -> drawBird(c, o)
        }
    }

    private fun drawBird(c: Canvas, o: Obstacle) {
        val cy = birdY(o)
        val cx = o.x + o.w / 2f
        paint.style = Paint.Style.FILL

        // wing (flapping)
        val flap = sin(time * 14f + o.phase)
        paint.color = 0xFF78909C.toInt()
        path.reset()
        path.moveTo(cx, cy)
        path.lineTo(cx - dp(6f), cy - dp(20f) * flap - dp(4f))
        path.lineTo(cx + dp(10f), cy - dp(4f))
        path.close()
        c.drawPath(path, paint)

        // body
        paint.color = 0xFF455A64.toInt()
        rect.set(o.x, cy - o.h / 2f, o.x + o.w, cy + o.h / 2f)
        c.drawOval(rect, paint)

        // beak
        paint.color = 0xFFFFB300.toInt()
        path.reset()
        path.moveTo(o.x, cy - dp(3f))
        path.lineTo(o.x - dp(10f), cy + dp(2f))
        path.lineTo(o.x, cy + dp(6f))
        path.close()
        c.drawPath(path, paint)

        // eye
        paint.color = Color.WHITE
        c.drawCircle(o.x + dp(14f), cy - dp(5f), dp(4f), paint)
        paint.color = Color.BLACK
        c.drawCircle(o.x + dp(15f), cy - dp(5f), dp(2f), paint)
    }

    private fun drawPlayer(c: Canvas) {
        val feet = py
        val onGround = py >= groundY - 1f

        // shadow
        paint.style = Paint.Style.FILL
        paint.color = 0x40000000
        val airRatio = ((groundY - py) / dp(220f)).coerceIn(0f, 1f)
        val sw = dp(26f) * (1f - airRatio * 0.4f)
        rect.set(px() - sw, groundY + dp(4f), px() + sw, groundY + dp(12f))
        c.drawOval(rect, paint)

        val hipY = feet - dp(26f)
        val torsoTop = hipY - dp(30f)

        // scarf (trailing animated)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(5f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = 0xFFFF5252.toInt()
        val scarfBaseY = torsoTop + dp(8f)
        for (i in 0..2) {
            val segX = px() - dp(12f) - i * dp(11f) - (if (onGround) 0f else dp(6f))
            val segY = scarfBaseY + sin(time * 10f - i * 0.9f) * dp(4f) + i * dp(3f)
            c.drawLine(
                px() - dp(8f) - i * dp(11f), scarfBaseY + i * dp(3f),
                segX, segY, paint
            )
        }

        // legs
        paint.strokeWidth = dp(7f)
        paint.color = 0xFF4DD0E1.toInt()
        if (onGround) {
            val s1 = sin(runCycle) * dp(15f)
            val s2 = sin(runCycle + Math.PI.toFloat()) * dp(15f)
            c.drawLine(px(), hipY, px() + s1, feet, paint)
            c.drawLine(px(), hipY, px() + s2, feet, paint)
            paint.style = Paint.Style.FILL
            paint.color = 0xFF263238.toInt()
            c.drawCircle(px() + s1, feet, dp(5.5f), paint)
            c.drawCircle(px() + s2, feet, dp(5.5f), paint)
        } else {
            c.drawLine(px(), hipY, px() + dp(12f), feet - dp(10f), paint)
            c.drawLine(px(), hipY, px() - dp(10f), feet - dp(18f), paint)
            paint.style = Paint.Style.FILL
            paint.color = 0xFF263238.toInt()
            c.drawCircle(px() + dp(12f), feet - dp(10f), dp(5.5f), paint)
            c.drawCircle(px() - dp(10f), feet - dp(18f), dp(5.5f), paint)
        }

        // torso
        paint.style = Paint.Style.FILL
        paint.color = 0xFFFF7043.toInt()
        rect.set(px() - dp(14f), torsoTop, px() + dp(14f), hipY + dp(3f))
        c.drawRoundRect(rect, dp(9f), dp(9f), paint)

        // arm
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(6f)
        paint.color = 0xFFFFAB91.toInt()
        val shY = torsoTop + dp(10f)
        if (onGround) {
            val a = sin(runCycle + Math.PI.toFloat()) * dp(12f)
            c.drawLine(px(), shY, px() + a, shY + dp(16f), paint)
        } else {
            c.drawLine(px(), shY, px() + dp(10f), shY - dp(12f), paint)
        }

        // head with visor
        paint.style = Paint.Style.FILL
        val headR = dp(12f)
        val headCy = torsoTop - headR + dp(2f)
        paint.color = 0xFFFFCC80.toInt()
        c.drawCircle(px(), headCy, headR, paint)
        paint.color = 0xFF37474F.toInt()
        rect.set(px() - headR, headCy - dp(4f), px() + headR, headCy + dp(5f))
        c.drawRoundRect(rect, dp(4f), dp(4f), paint)
        paint.color = 0xFF80DEEA.toInt()
        rect.set(px() + dp(1f), headCy - dp(3f), px() + headR * 0.85f, headCy + dp(4f))
        c.drawRoundRect(rect, dp(3f), dp(3f), paint)
        // hair
        paint.color = 0xFF5D4037.toInt()
        rect.set(px() - headR, headCy - headR - dp(2f), px() + headR * 0.4f, headCy - dp(2f))
        c.drawArc(rect, 180f, 180f, true, paint)
    }

    private fun drawParticles(c: Canvas) {
        paint.style = Paint.Style.FILL
        for (p in particles) {
            paint.color = p.color
            val alpha = (p.life / p.maxLife).coerceIn(0f, 1f)
            paint.alpha = (alpha * 255).toInt()
            c.drawCircle(p.x, p.y, p.size * alpha, paint)
        }
        paint.alpha = 255
    }

    private fun drawHud(c: Canvas) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.textSize = dp(20f)
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.LEFT
        paint.isFakeBoldText = true
        c.drawText("SCORE  $score", dp(16f), dp(34f), paint)

        // coin icon + count
        paint.color = 0xFFFFD54F.toInt()
        c.drawCircle(dp(26f), dp(52f), dp(9f), paint)
        paint.color = 0xFFFFA000.toInt()
        c.drawCircle(dp(26f), dp(52f), dp(5f), paint)
        paint.color = Color.WHITE
        paint.textSize = dp(18f)
        c.drawText("x $coinsCollected", dp(42f), dp(58f), paint)

        paint.textSize = dp(20f)
        paint.textAlign = Paint.Align.RIGHT
        c.drawText("BEST  $best", viewW - dp(16f), dp(34f), paint)
        paint.textAlign = Paint.Align.LEFT
        paint.isFakeBoldText = false
    }

    private fun drawReady(c: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = 0x33000000
        c.drawRect(0f, 0f, viewW.toFloat(), viewH.toFloat(), paint)

        paint.textAlign = Paint.Align.CENTER
        paint.isFakeBoldText = true
        paint.color = Color.WHITE
        paint.textSize = dp(48f)
        val bounce = sin(time * 2.2f) * dp(6f)
        c.drawText("PATH RUNNER", viewW / 2f, viewH * 0.36f + bounce, paint)

        paint.textSize = dp(20f)
        paint.isFakeBoldText = false
        val blink = (sin(time * 4f) + 1f) / 2f
        paint.alpha = (80 + blink * 175).toInt()
        c.drawText("Tap to start", viewW / 2f, viewH * 0.5f, paint)
        paint.alpha = 255
        paint.color = 0xFFCFD8DC.toInt()
        c.drawText("Hold to jump higher - tap mid-air to double jump - collect coins", viewW / 2f, viewH * 0.57f, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    private fun drawPaused(c: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = 0x88000000.toInt()
        c.drawRect(0f, 0f, viewW.toFloat(), viewH.toFloat(), paint)
        paint.textAlign = Paint.Align.CENTER
        paint.isFakeBoldText = true
        paint.color = Color.WHITE
        paint.textSize = dp(42f)
        c.drawText("PAUSED", viewW / 2f, viewH * 0.44f, paint)
        paint.textSize = dp(18f)
        paint.isFakeBoldText = false
        c.drawText("Tap to resume", viewW / 2f, viewH * 0.53f, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    private fun drawGameOver(c: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = 0x99000000.toInt()
        c.drawRect(0f, 0f, viewW.toFloat(), viewH.toFloat(), paint)

        paint.textAlign = Paint.Align.CENTER
        paint.isFakeBoldText = true
        paint.textSize = dp(46f)
        paint.color = 0xFFFF5252.toInt()
        c.drawText("GAME OVER", viewW / 2f, viewH * 0.32f, paint)

        if (newRecord) {
            val glow = (sin(time * 6f) + 1f) / 2f
            paint.color = 0xFFFFD54F.toInt()
            paint.alpha = (160 + glow * 95).toInt()
            paint.textSize = dp(26f)
            c.drawText("NEW RECORD!", viewW / 2f, viewH * 0.41f, paint)
            paint.alpha = 255
        }

        paint.color = Color.WHITE
        paint.textSize = dp(24f)
        c.drawText("Score: $score    Coins: $coinsCollected", viewW / 2f, viewH * 0.5f, paint)
        paint.color = 0xFFFFD54F.toInt()
        c.drawText("Best: $best", viewW / 2f, viewH * 0.57f, paint)

        if (deathTimer <= 0f) {
            paint.color = Color.WHITE
            paint.textSize = dp(18f)
            paint.isFakeBoldText = false
            val blink = (sin(time * 4f) + 1f) / 2f
            paint.alpha = (80 + blink * 175).toInt()
            c.drawText("Tap to restart", viewW / 2f, viewH * 0.66f, paint)
            paint.alpha = 255
        }
        paint.textAlign = Paint.Align.LEFT
    }
}
