package com.grove.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.grove.app.R
import com.grove.app.data.AppData
import com.grove.app.data.GoalKind
import com.grove.app.data.Habit
import com.grove.app.data.Logic
import com.grove.app.data.Role
import com.grove.app.data.Store
import java.time.LocalDateTime
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/** The scene is laid out on a 358 x 380 grid (the mockup's size) and scaled to the screen. */
private const val W = 358f
private const val H = 380f

/** Time of day follows the routine: dark until Morning practice, day until Before-bed practice, then night. */
private enum class Mood(val sky: Color, val brightness: Float, val saturation: Float, val tint: Color, val stars: Float, val flies: Boolean) {
    PREDAWN(Color(0xFF232A4F), 0.45f, 0.55f, Color(0x59282E6E), 0.8f, false),
    DAY(Color(0xFFBEE3EE), 1f, 1f, Color.Transparent, 0f, false),
    NIGHT(Color(0xFF1D2A52), 0.55f, 0.6f, Color(0x52243678), 1f, true),
}

private enum class Pose { SLEEP, SIT, HOWL }

private class PlantType(val name: String, val swatch: Color, val height: Float, val ratio: Float, val small: Boolean, val res: (Int) -> Int)

/** One plant type per habit, in habit order; habits past the sixth reuse the list. */
private val PLANTS = listOf(
    PlantType("Evergreens", Color(0xFF7DB38A), 140f, 1f, false) { R.drawable.plant_pine },
    PlantType("Twilight trees", Color(0xFF3B5174), 118f, 0.71f, false) { R.drawable.plant_blue },
    PlantType("Mushrooms", Color(0xFFAF602D), 22f, 0.92f, true) { R.drawable.plant_mushroom },
    PlantType("Teal trees", Color(0xFF00757F), 132f, 0.76f, false) { R.drawable.plant_teal },
    PlantType("Wildflowers", Color(0xFFFCD157), 24f, 1.18f, true) { i -> if (i % 2 == 0) R.drawable.plant_flower_yellow else R.drawable.plant_flower_red },
    PlantType("Leafy trees", Color(0xFF1CA538), 124f, 0.79f, false) { R.drawable.plant_round },
)

private class Placed(val res: Int, val x: Float, val y: Float, val w: Float, val h: Float, val dormant: Boolean, val phase: Float)

private fun rnd(n: Int): Float {
    val x = sin(n * 12.9898) * 43758.5453
    return (x - floor(x)).toFloat()
}

private fun stageOf(t: Int) = when {
    t <= 0 -> "Bare ground"
    t <= 6 -> "Seedlings"
    t <= 20 -> "Saplings"
    t <= 60 -> "Young grove"
    t <= 150 -> "Woodland"
    else -> "Old forest"
}

private fun matrix(brightness: Float, saturation: Float): ColorMatrix {
    val s = saturation
    val r = 0.2126f * (1 - s)
    val g = 0.7152f * (1 - s)
    val b = 0.0722f * (1 - s)
    val k = brightness
    return ColorMatrix(floatArrayOf(
        (r + s) * k, g * k, b * k, 0f, 0f,
        r * k, (g + s) * k, b * k, 0f, 0f,
        r * k, g * k, (b + s) * k, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    ))
}

private fun Color.through(m: ColorMatrix): Color {
    val v = m.values
    return Color(
        (v[0] * red + v[1] * green + v[2] * blue).coerceIn(0f, 1f),
        (v[5] * red + v[6] * green + v[7] * blue).coerceIn(0f, 1f),
        (v[10] * red + v[11] * green + v[12] * blue).coerceIn(0f, 1f),
        alpha,
    )
}

private fun isDormant(data: AppData, h: Habit) = Logic.streakState(data, h).let { it == Logic.StreakState.PAUSED || it == Logic.StreakState.RESET }

/** Where each finished field guide's piece sits: drawable, x, ground line, height. */
private class Piece(val res: Int, val x: Float, val yb: Float, val h: Float, val ratio: Float = 1f)

private val BOOK_PIECES = mapOf(
    "trees" to Piece(R.drawable.plant_round, 292f, 300f, 150f, 0.79f),
    "wildflowers" to Piece(R.drawable.piece_sunflower, 36f, 346f, 34f),
    "weeds" to Piece(R.drawable.piece_dandelion, 98f, 366f, 24f),
    "mushrooms" to Piece(R.drawable.piece_mushroom, 246f, 352f, 26f),
    "birds" to Piece(R.drawable.piece_bird, 112f, 176f, 26f),
    "mammals" to Piece(R.drawable.piece_deer, 18f, 300f, 56f),
    "rocks" to Piece(R.drawable.piece_gem, 286f, 364f, 18f),
    "frogs" to Piece(R.drawable.piece_frog, 60f, 374f, 22f),
    "snakes" to Piece(R.drawable.piece_snake, 226f, 376f, 22f),
)

/** Something that grows plants: [seed] keeps its placement stable, [total] is how much it has grown. */
private class Grower(val seed: Int, val type: PlantType, val total: Int, val every: Int, val ageScale: Float, val dormant: Boolean)

private fun growers(data: AppData): List<Grower> {
    val out = data.habits.mapIndexed { k, h ->
        val p = PLANTS[k % PLANTS.size]
        Grower(k, p, Logic.totalDays(data, h), if (p.small) 5 else 8, 1f, isDormant(data, h))
    }.toMutableList()
    // Writing sessions grow teal trees where the old daily habit's grew. Weekly goals never go dormant.
    data.goals.filter { it.kind == GoalKind.SESSIONS }.forEach { g ->
        out += Grower(3, PLANTS[3], Logic.totalSessions(data, g), 4, 2.3f, false)
    }
    return out
}

/** Trees fill the middle and back, a few frame the foreground edges; the wolf keeps the open middle. */
private fun placePlants(data: AppData): List<Placed> {
    val out = mutableListOf<Placed>()
    for (gr in growers(data)) {
        val p = gr.type
        val k = gr.seed
        val count = if (gr.total <= 0) 0 else min(if (p.small) 16 else 8, 1 + gr.total / gr.every)
        for (i in 0 until count) {
            val r1 = rnd(k * 97 + i * 13 + 1)
            val r2 = rnd(k * 53 + i * 29 + 7)
            val r3 = rnd(k * 31 + i * 17 + 3)
            val age = (gr.total - i * gr.every) * gr.ageScale
            val sc = if (age < 42) 0.2f + age / 52f else min(1.2f, 1f + (age - 42) / 400f)
            val edge = !p.small && r2 > 0.8f
            val yb = when {
                p.small -> 236 + r2 * 136
                edge -> 340 + (r2 - 0.8f) * 160
                else -> 236 + r2 / 0.8f * 96
            }
            val depth = 0.55f + (yb - 236) / 136f * 0.6f
            val hh = p.height * sc * depth
            val w = hh * p.ratio
            val x = when {
                !edge -> r1 * (W - w)
                r1 < 0.5f -> r1 * 70 - w * 0.3f
                else -> W - w * 0.7f - (1 - r1) * 70
            }
            out += Placed(p.res(i), x, yb - hh, w, hh, gr.dormant, r3 * 2 * PI.toFloat())
        }
    }
    // The reading seedling grows a little with every page logged.
    if (data.goals.any { it.kind == GoalKind.PAGES }) {
        val pages = data.days.values.sumOf { r -> r.pages.values.sum() }
        val total = data.books.sumOf { it.pages }.coerceAtLeast(1)
        val h = 16f + 54f * min(1f, pages.toFloat() / total)
        out += Placed(R.drawable.plant_seedling, 92f - h / 2, 352f - h, h, h, false, 1.3f)
    }
    // Each finished field guide adds its piece for good.
    for (b in data.books) {
        val piece = BOOK_PIECES[b.piece] ?: continue
        if (!Logic.bookFinished(data, b)) continue
        val w = piece.h * piece.ratio
        out += Placed(piece.res, piece.x, piece.yb - piece.h, w, piece.h, false, rnd(b.pages) * 6f)
    }
    return out.sortedBy { it.y + it.h }
}

@Composable
fun ForestScreen(data: AppData, go: (Screen) -> Unit, tab: (Screen) -> Unit) {
    val context = LocalContext.current
    val totals = data.habits.map { Logic.totalDays(data, it) }
    val sum = totals.sum()
    val stage = (if (sum == 0) "Bare clearing" else stageOf(sum / maxOf(1, data.habits.size))) + " · $sum ${if (sum == 1) "day" else "days"} of growth"

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp, 16.dp, 8.dp)) { Title("Forest", stage) }
        Tabs(forest = true, onToday = { tab(Screen.Today) }, onForest = {})
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val mood = Scene(data)

            Card {
                Column {
                    data.habits.filter { Logic.scheduledOn(it, Logic.date(data.currentDay)) }.forEach { h ->
                        val done = Logic.isDoneToday(data, h)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CheckMark(done, (if (done) "Uncheck " else "Check off ") + h.name, square = false, onToggle = {
                                Store.update(context) { Logic.toggleHabit(it, h.id, LocalDateTime.now()) }
                            })
                            ItemTitle(h.name, done, Modifier.weight(1f), size = 15)
                            Text("${Logic.streak(data, h)}d", color = GroveColors.Green, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            if (data.goals.isNotEmpty()) Column { GoalCards(data, go) }

            Text(message(data, mood, sum), fontSize = 15.sp)

            Card {
                Column {
                    data.habits.forEachIndexed { k, h ->
                        val p = PLANTS[k % PLANTS.size]
                        val dormant = isDormant(data, h)
                        val streak = Logic.streak(data, h)
                        Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(if (dormant) Color(0xFF8A8F96) else p.swatch))
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(p.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text("${h.name} · ${stageOf(totals[k])}", fontSize = 12.sp, color = GroveColors.Muted, maxLines = 1)
                            }
                            Text(
                                if (dormant) "Dormant" else if (streak > 0) "${streak}d streak" else "Waiting",
                                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                color = if (dormant) GroveColors.Warn else GroveColors.Green,
                                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (dormant) GroveColors.WarnBg else GroveColors.Surface2)
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
            if (data.goals.any { it.kind != GoalKind.PROMPT }) {
                Card {
                    Column {
                        data.goals.filter { it.kind != GoalKind.PROMPT }.forEach { g ->
                            val (name, swatch, detail) = if (g.kind == GoalKind.PAGES) {
                                val finished = data.books.count { Logic.bookFinished(data, it) }
                                Triple("Reading seedling", Color(0xFF86D72F), "${g.name} · $finished of ${data.books.size} books finished, each adds its own piece")
                            } else Triple(PLANTS[3].name, PLANTS[3].swatch, "${g.name} · ${Logic.totalSessions(data, g)} sessions")
                            Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(swatch))
                                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    Text(name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Text(detail, fontSize = 12.sp, color = GroveColors.Muted, maxLines = 2)
                                }
                                Text("${Logic.progress(data, g)}/${Logic.target(data, g)}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = GroveColors.Green,
                                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(GroveColors.Surface2).padding(horizontal = 10.dp, vertical = 4.dp))
                            }
                        }
                    }
                }
            }
            Text("Total days decide how much has grown, so the forest never shrinks. A paused streak turns that habit's plants dormant until you check in again.",
                fontSize = 12.sp, color = GroveColors.Muted)
        }
    }
}

private fun moodOf(data: AppData): Mood {
    val morning = data.habits.firstOrNull { it.role == Role.MORNING }
    val bedtime = data.habits.firstOrNull { it.role == Role.BEDTIME }
    return when {
        bedtime != null && Logic.isDoneToday(data, bedtime) -> Mood.NIGHT
        morning == null && Logic.doneCount(data) > 0 -> Mood.DAY
        morning != null && Logic.isDoneToday(data, morning) -> Mood.DAY
        else -> Mood.PREDAWN
    }
}

private fun message(data: AppData, mood: Mood, sum: Int): String {
    val done = Logic.doneCount(data)
    val n = data.habits.size
    var m = when (mood) {
        Mood.PREDAWN -> "Still dark in the grove. Your wolf sleeps until you finish Morning practice."
        Mood.DAY -> "Daytime. Your wolf is up and keeping watch. $done of $n habits done."
        Mood.NIGHT -> if (done == n) "Every habit done. Your wolf is howling at the moon." else "Night has fallen after Before-bed practice. $done of $n habits done today."
    }
    if (sum == 0) m += " The clearing is bare for now: every check-in grows something."
    if (data.habits.any { isDormant(data, it) }) m += " Part of the grove is dormant: check in to bring it back."
    return m
}

@Composable
private fun Scene(data: AppData): Mood {
    val mood = moodOf(data)
    val done = Logic.doneCount(data)
    val allDone = done == data.habits.size && data.habits.isNotEmpty()

    // A short howl and hop each time something gets checked off.
    // Any progress counts as a reason to howl: habits, writing sessions, pages.
    val cheer = done + data.today.sessions.size + Logic.pagesOn(data, data.currentDay)
    var lastDone by remember { mutableIntStateOf(cheer) }
    var celebrating by remember { mutableStateOf(false) }
    val hop = remember { Animatable(0f) }
    LaunchedEffect(cheer) {
        if (cheer > lastDone) {
            celebrating = true
            hop.animateTo(-16f, tween(280)); hop.animateTo(0f, tween(240))
            hop.animateTo(-6f, tween(150)); hop.animateTo(0f, tween(150))
            kotlinx.coroutines.delay(1600)
            celebrating = false
        }
        lastDone = cheer
    }
    val pose = when {
        mood == Mood.PREDAWN -> Pose.SLEEP
        celebrating || (mood == Mood.NIGHT && allDone) -> Pose.HOWL
        else -> Pose.SIT
    }

    val loop = rememberInfiniteTransition(label = "forest")
    val t by loop.animateFloat(0f, 2 * PI.toFloat(), infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "sway")
    val wag by loop.animateFloat(-5f, 9f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "wag")
    val breath by loop.animateFloat(1f, 1.035f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "breath")
    val head by animateFloatAsState(when (pose) { Pose.HOWL -> -28f; Pose.SLEEP -> 16f; Pose.SIT -> 0f }, tween(600), label = "head")
    val lie by animateFloatAsState(if (pose == Pose.SLEEP) 1f else 0f, tween(600), label = "lie")

    val land = matrix(mood.brightness, mood.saturation)
    val landFilter = if (mood == Mood.DAY) null else ColorFilter.colorMatrix(land)
    val dormantFilter = ColorFilter.colorMatrix(matrix(mood.brightness * 0.95f, mood.saturation * 0.15f))
    val plants = remember(data.habits, data.days, data.goals, data.books) { placePlants(data) }
    val aria = "Forest with ${plants.size} plants. Your wolf is ${if (pose == Pose.SLEEP) "asleep" else "awake"}."

    BoxWithConstraints(
        Modifier.fillMaxWidth().aspectRatio(W / H).clip(RoundedCornerShape(22.dp)).background(mood.sky).semantics { contentDescription = aria }
    ) {
        val u: Dp = maxWidth / W
        fun Modifier.place(x: Float, y: Float, w: Float, h: Float) = this.offset(u * x, u * y).size(u * w, u * h)

        Canvas(Modifier.fillMaxSize()) {
            val k = size.width / W
            for (i in 0 until 46) {
                if (mood.stars == 0f) break
                val a = (mood.stars * (0.5f + rnd(i * 23 + 1) * 0.5f) * (0.65f + 0.35f * sin(t * 2 + i))).coerceIn(0f, 1f)
                drawCircle(Color.White.copy(alpha = a), (1f + rnd(i * 7 + 2) * 1.8f) / 2 * k, Offset(rnd(i * 11 + 5) * size.width, rnd(i * 19 + 3) * 165 * k))
            }
            when (mood) {
                Mood.DAY -> {
                    val c = Offset(65 * k, 61 * k)
                    for (r in 0 until 8) {
                        val ang = r * PI / 4
                        val from = Offset((c.x + kotlin.math.cos(ang) * 30 * k).toFloat(), (c.y + kotlin.math.sin(ang) * 30 * k).toFloat())
                        val to = Offset((c.x + kotlin.math.cos(ang) * 42 * k).toFloat(), (c.y + kotlin.math.sin(ang) * 42 * k).toFloat())
                        drawLine(Color(0xFFFFF082), from, to, 4.3f * k, androidx.compose.ui.graphics.StrokeCap.Round)
                    }
                    drawCircle(Color(0xFFFFF082), 15.3f * k, c)
                    drawCircle(Color(0xFFFFF8C1), 8.7f * k, c)
                }
                Mood.NIGHT -> drawCircle(Color(0xFFF2EFDC), 28 * k, Offset(278 * k, 52 * k))
                Mood.PREDAWN -> {
                    drawCircle(Color(0xFFE9E4CC), 19 * k, Offset(280 * k, 54 * k))
                    drawCircle(mood.sky, 19 * k, Offset(291 * k, 49 * k))
                }
            }
        }

        Image(painterResource(R.drawable.scene_mountains), null, Modifier.place(-16f, 92f, 226f, 226f), colorFilter = landFilter)
        Image(painterResource(R.drawable.scene_mountains), null, Modifier.place(196f, 128f, 176f, 176f).graphicsLayer { scaleX = -1f }, colorFilter = landFilter)
        Canvas(Modifier.fillMaxSize()) {
            val k = size.width / W
            drawOval(Color(0xFF9CCB86).through(land), Offset(-60 * k, 200 * k), Size(280 * k, 96 * k))
            drawOval(Color(0xFF8DC277).through(land), Offset(140 * k, 192 * k), Size(280 * k, 110 * k))
            drawRect(Color(0xFF7DB35F).through(land), Offset(0f, 230 * k), Size(size.width, size.height))
            drawRect(Color(0xFF6A9E50).through(land), Offset(0f, 326 * k), Size(size.width, size.height))
        }

        plants.forEach { p ->
            Image(
                painterResource(p.res), null,
                Modifier.place(p.x, p.y, p.w, p.h).zIndex(p.y + p.h).graphicsLayer {
                    rotationZ = sin(t + p.phase) * 1.2f
                    transformOrigin = TransformOrigin(0.5f, 1f)
                },
                contentScale = ContentScale.Fit,
                alignment = Alignment.BottomCenter,
                colorFilter = if (p.dormant) dormantFilter else landFilter,
            )
        }

        if (mood == Mood.DAY && done >= 2) {
            Image(painterResource(R.drawable.critter_squirrel), null,
                Modifier.place(252f, 334f + (if (sin(t * 3) > 0.92f) -6f else 0f), 36f, 36f).zIndex(990f), colorFilter = landFilter)
        }

        // The wolf: lying down hides his legs below the body line and lowers him onto the grass.
        val wolf = 90f
        Box(Modifier.place(134f, 267f + lie * wolf * 133f / 512f + hop.value, wolf, wolf).zIndex(350f)) {
            Box(Modifier.size(u * wolf, u * wolf * (1f - lie * (172f / 512f))).clipToBounds()) {
                Box(
                    Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).size(u * wolf).graphicsLayer {
                        scaleY = if (pose == Pose.SLEEP) 1f + (breath - 1f) * 0.5f else breath
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                ) {
                    Image(painterResource(R.drawable.wolf_tail), null, Modifier.fillMaxSize().graphicsLayer {
                        rotationZ = if (pose == Pose.SLEEP) -5f else wag
                        transformOrigin = TransformOrigin(122f / 512f, 210f / 512f)
                    }, colorFilter = landFilter)
                    Image(painterResource(R.drawable.wolf_body), null, Modifier.fillMaxSize(), colorFilter = landFilter)
                    Image(painterResource(R.drawable.wolf_head), null, Modifier.fillMaxSize().graphicsLayer {
                        rotationZ = head
                        transformOrigin = TransformOrigin(352f / 512f, 262f / 512f)
                    }, colorFilter = landFilter)
                }
            }
        }
        if (pose == Pose.SLEEP) {
            Text("z", Modifier.place(214f, 296f - (t % 1f) * 10f, 20f, 20f).zIndex(1001f), color = Color(0xFFDDE3F0), fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }

        Box(Modifier.fillMaxSize().zIndex(1001f).background(mood.tint))

        if (mood.flies) {
            Canvas(Modifier.fillMaxSize().zIndex(1002f)) {
                val k = size.width / W
                for (i in 0 until 9) {
                    val phase = t + rnd(i * 5) * 6
                    val glow = (0.35f + 0.65f * (0.5f + 0.5f * sin(phase))).coerceIn(0f, 1f)
                    val c = Offset((20 + rnd(i * 13 + 4) * 300 + sin(phase) * 8) * k, (235 + rnd(i * 17 + 9) * 95 - sin(phase) * 10) * k)
                    drawCircle(Color(0x66F6E7A0).copy(alpha = 0.4f * glow), 5 * k, c)
                    drawCircle(Color(0xFFF6E7A0).copy(alpha = glow), 1.6f * k, c)
                }
            }
        }
    }
    return mood
}
