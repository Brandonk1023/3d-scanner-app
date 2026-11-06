package app.facet.android.ui.scan

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.facet.android.R
import app.facet.android.appContainer
import app.facet.android.ar.ArController
import app.facet.android.ar.ArEvent
import app.facet.android.ar.ArFrameSnapshot
import app.facet.android.ar.CaptureEngine
import app.facet.android.ar.PlaneFrame
import app.facet.android.ar.Projector
import app.facet.android.ui.components.BusyIndicator
import app.facet.android.ui.components.ConfirmDialog
import app.facet.android.ui.components.OutlineButton
import app.facet.android.ui.components.PrimaryButton
import app.facet.android.ui.components.RadioCard
import app.facet.android.ui.components.TextLinkButton
import app.facet.android.ui.theme.ArColors
import app.facet.android.ui.theme.Facet
import app.facet.android.ui.theme.Radius
import app.facet.android.ui.theme.Sizes
import app.facet.android.ui.theme.Space
import app.facet.data.CaptureInfo
import app.facet.data.ResultQuality
import app.facet.data.Scan
import app.facet.export.ExportFormat
import app.facet.mesh.ScanPipeline
import app.facet.mesh.bounds
import app.facet.platform.AndroidHapticsPlayer
import app.facet.scan.CoverageBand
import app.facet.scan.CoverageState
import app.facet.scan.GuidanceMessage
import app.facet.scan.ResultMode
import app.facet.scan.ScanTuning
import app.facet.scan.TrackingState
import app.facet.ui.scan.CaptureFrame
import app.facet.ui.scan.CapturePhase
import app.facet.ui.scan.CaptureSheet
import app.facet.ui.scan.CaptureUiState
import app.facet.ui.scan.CaptureViewModel
import app.facet.ui.scan.ScanSetup
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Capture (A-Capture.png, Capture-TrackingLost.png) with Finish (Finish-Incomplete.png,
 * Finish-Mode.png) and interruption (Scan-Interrupted.png). Drawn over the camera view owned by
 * [ScanScreen]; the live mesh itself is drawn in GL by [CaptureEngine].
 */
@OptIn(ExperimentalTime::class)
@Composable
internal fun CaptureLayer(
    setup: ScanSetup,
    engine: CaptureEngine,
    ar: ArController,
    frames: State<ArFrameSnapshot?>,
    torchOn: Boolean,
    onTorch: () -> Unit,
    onDiscarded: () -> Unit,
    onResult: (scanId: String, mode: ResultMode) -> Unit,
) {
    val context = LocalContext.current
    val container = appContainer()
    val haptics = remember { AndroidHapticsPlayer(context.applicationContext) { true } }
    val vm: CaptureViewModel = viewModel(key = engine.scanDir.name) { CaptureViewModel(setup, container.settings, haptics) }
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirmCancel by rememberSaveable { mutableStateOf(false) }
    var building by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Int?>(null) }

    // AR frames → session.
    LaunchedEffect(engine) {
        ar.frames.collect { snap ->
            if (snap == null) return@collect
            val anchor = snap.anchor
            val frame = anchor?.let(::PlaneFrame)
            val camera = frame?.let { cameraInPlane(Projector(snap, 1f, 1f), it) }
            vm.onFrame(
                CaptureFrame(
                    timestampNs = snap.timestampNs,
                    tracking = if (anchor == null) TrackingState.LIMITED else snap.tracking,
                    camera = camera.takeIf { snap.tracking == TrackingState.TRACKING },
                    boxInView = frame?.let { boxInViewFraction(snap, setup.box, it) } ?: 0f,
                    lightIntensity = snap.lightIntensity,
                    anchorTracking = anchor != null,
                ),
            )
        }
    }
    LaunchedEffect(engine) {
        ar.events.filterIsInstance<ArEvent.DepthFused>().collect { vm.onDepthFused(it.meanConfidence) }
    }
    // Session → AR layer.
    LaunchedEffect(state.fusing, state.meshVisible, state.trackingLost) {
        engine.fusing = state.fusing
        engine.meshVisible = state.meshVisible
        engine.dimmed = state.trackingLost
    }
    LaunchedEffect(notice) {
        if (notice != null) {
            kotlinx.coroutines.delay(2_500)
            notice = null
        }
    }

    // Leaving the app mid-scan: show the interrupted sheet on return and keep the volume on disk.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, vm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && vm.state.value.finished == null && !building) {
                vm.onInterrupted()
                scope.launch { engine.saveVolume() }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.discarded) {
        if (state.discarded) {
            haptics.cancel()
            engine.scanDir.deleteRecursively()
            onDiscarded()
        }
    }
    LaunchedEffect(state.finished) {
        val mode = state.finished ?: return@LaunchedEffect
        building = true
        engine.fusing = false
        if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) engine.saveVolume()
        val raw = engine.extractFinal()
        if (raw.mesh.triangleCount == 0) {
            building = false
            notice = R.string.capture_empty
            vm.keepScanning()
            return@LaunchedEffect
        }
        val settings = container.settings.settings.first()
        val cleanup = ScanTuning.Default.cleanup.params(settings.watertight, settings.smoothing, raw.hasTable)
        val mesh = withContext(Dispatchers.Default) {
            ScanPipeline(container.meshEngine).finalMesh(raw.mesh, cleanup).mesh
        }
        val id = engine.scanDir.name
        // High detail needs the box its keyframe poses refer to, and the depth calibration.
        withContext(Dispatchers.IO) {
            runCatching { container.scanFolder(id).writeCaptureInfo(CaptureInfo.of(engine.box.size, engine.calibratedDepthScale)) }
        }
        // Saved as Quick either way: High detail (if chosen) upgrades it from the Processing screen.
        container.scans.add(
            Scan(
                id = id,
                name = container.scans.nextScanName(),
                createdAt = Clock.System.now(),
                quality = ResultQuality.QUICK,
                size = mesh.bounds().size,
                triangleCount = mesh.triangleCount,
                hasSavedPhotos = engine.keyframeCount > 0,
                modelBytes = ExportFormat.STL.estimatedBytes(mesh),
                photoBytes = engine.keyframeBytes,
            ),
            mesh,
        )
        onResult(id, mode)
    }

    BackHandler {
        when (state.sheet) {
            CaptureSheet.INCOMPLETE, CaptureSheet.CHOOSE_RESULT -> vm.dismissSheet()
            CaptureSheet.INTERRUPTED, null -> confirmCancel = true
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Box outline + coverage ring on the table.
        Canvas(Modifier.fillMaxSize()) {
            val snap = frames.value ?: return@Canvas
            val anchor = snap.anchor ?: return@Canvas
            val frame = PlaneFrame(anchor)
            val projector = Projector(snap, size.width, size.height)
            drawCaptureBox(setup.box, frame, projector)
            val band = state.band ?: CoverageBand.LOW
            drawCoverageRing(setup.box, frame, projector, state.coverage, band, state.headingDeg, state.coverage.captured[0].size)
        }

        // Top bar, then the one guidance message under it, laid out together so a taller bar
        // or banner (large font) pushes down instead of overlapping.
        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CaptureTopBar(state = state, torchOn = torchOn, onCancel = { confirmCancel = true }, onTorch = onTorch)
            Column(
                Modifier.padding(top = Space.xxs, start = Space.m, end = Space.m),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                val n = notice
                when {
                    n != null -> GlassPill(stringResource(n), R.drawable.ic_info)
                    state.sheet == null && state.phase == CapturePhase.CAPTURING -> state.message?.let { GuidanceBanner(it) }
                }
            }
        }

        state.toast?.let { band ->
            Box(Modifier.align(Alignment.Center)) {
                GlassPill(
                    stringResource(
                        when (band) {
                            CoverageBand.LOW -> R.string.toast_ring_low
                            CoverageBand.MID -> R.string.toast_ring_mid
                            CoverageBand.HIGH -> R.string.toast_ring_high
                        },
                    ),
                    R.drawable.ic_check,
                    iconTint = ArColors.guide,
                )
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Space.m, vertical = Space.m),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            if (state.trackingLost) TrackingLostPanel(state.coverage.overall) else CoveragePanel(state.coverage)
            CaptureControls(
                state = state,
                onPause = vm::togglePause,
                onFinish = vm::finish,
                onMesh = vm::toggleMesh,
            )
        }

        when (state.sheet) {
            CaptureSheet.INCOMPLETE -> SheetScrim(onDismiss = vm::dismissSheet) { IncompleteSheet(state, vm::keepScanning, vm::finishAnyway) }
            CaptureSheet.CHOOSE_RESULT -> SheetScrim(onDismiss = vm::dismissSheet) { ChooseResultSheet(state, vm::chooseResult) }
            CaptureSheet.INTERRUPTED -> SheetScrim(onDismiss = null) {
                InterruptedSheet(state, vm::resumeAfterInterruption, vm::finishWithWhatIHave, onDiscard = { confirmCancel = true })
            }
            null -> Unit
        }

        if (building) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .swallowTouches(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Space.m),
                ) {
                    BusyIndicator(color = ArColors.guide)
                    Text(stringResource(R.string.capture_building), style = MaterialTheme.typography.titleMedium, color = Color.White)
                }
            }
        }
    }

    if (confirmCancel) {
        ConfirmDialog(
            title = stringResource(R.string.capture_cancel_title),
            body = stringResource(R.string.capture_cancel_body),
            confirmLabel = stringResource(R.string.discard_scan),
            onConfirm = {
                confirmCancel = false
                vm.discard()
            },
            onDismiss = { confirmCancel = false },
        )
    }
}

// ---- Top bar: Cancel · status pill · mini dial · Torch ----------------------------------------

@Composable
private fun CaptureTopBar(state: CaptureUiState, torchOn: Boolean, onCancel: () -> Unit, onTorch: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.m, vertical = Space.xs),
        verticalAlignment = Alignment.Top,
    ) {
        GlassCircleButton(R.drawable.ic_close, stringResource(R.string.cd_cancel_scan), onCancel)
        Spacer(Modifier.width(Space.s))
        Box(Modifier.weight(1f).heightIn(min = Sizes.touchMinAr), contentAlignment = Alignment.CenterStart) {
            StatusPill(state.phase == CapturePhase.CAPTURING && state.sheet == null, state.elapsedMs)
        }
        Spacer(Modifier.width(Space.s))
        MiniDial(state.coverage, size = 88.dp)
        Spacer(Modifier.width(Space.s))
        GlassCircleButton(R.drawable.ic_light, stringResource(R.string.cd_torch), onTorch, active = torchOn)
    }
}

@Composable
private fun StatusPill(recording: Boolean, elapsedMs: Long) {
    val seconds = elapsedMs / 1000
    val time = "%02d:%02d".format(java.util.Locale.ROOT, seconds / 60, seconds % 60)
    Row(
        Modifier
            .clip(CircleShape)
            .background(ArColors.glass)
            .border(1.dp, ArColors.glassBorder, CircleShape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Box(Modifier.size(9.dp).background(if (recording) ArColors.recording else Color.White, CircleShape))
        Text(
            stringResource(if (recording) R.string.scanning_timer else R.string.scanning_paused, time),
            style = Facet.type.dataMono.copy(fontSize = 14.sp, letterSpacing = 0.04.sp),
            color = Color.White,
            maxLines = 1,
        )
    }
}

@Composable
private fun GlassCircleButton(
    @DrawableRes icon: Int,
    description: String,
    onClick: () -> Unit,
    active: Boolean = false,
    size: Dp = Sizes.touchMinAr,
    enabled: Boolean = true,
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (active) Color.White else ArColors.glass)
            .border(1.dp, ArColors.glassBorder, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon), null,
            tint = if (active) ArColors.onAdvice else Color.White,
            modifier = Modifier.size(if (size > Sizes.touchMinAr) 28.dp else Sizes.icon),
        )
    }
}

// ---- Mini dial: top-down coverage map, the user's heading always at the bottom ----------------

@Composable
internal fun MiniDial(coverage: CoverageState, size: Dp, headingDeg: Double? = null, background: Boolean = true) {
    val description = stringResource(
        R.string.cd_dial,
        (coverage.band(CoverageBand.LOW) * 100).roundToInt(),
        (coverage.band(CoverageBand.MID) * 100).roundToInt(),
        (coverage.band(CoverageBand.HIGH) * 100).roundToInt(),
    )
    Canvas(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(Radius.panel))
            .then(if (background) Modifier.background(ArColors.glass).border(1.dp, ArColors.glassBorder, RoundedCornerShape(Radius.panel)) else Modifier)
            .semantics { contentDescription = description },
    ) {
        val c = Offset(this.size.width / 2, this.size.height / 2)
        val outer = this.size.minDimension / 2 - 7.dp.toPx()
        val ring = outer * 0.24f
        val gap = outer * 0.07f
        val wedges = coverage.captured[0].size / 2
        val wedgeSpan = 360f / wedges
        val heading = (headingDeg ?: 0.0).toFloat()
        CoverageBand.entries.forEach { band ->
            // Outer ring = Low, then Mid, inner = High.
            val r = outer - ring / 2 - band.ordinal * (ring + gap)
            val topLeft = Offset(c.x - r, c.y - r)
            val arcSize = Size(2 * r, 2 * r)
            for (w in 0 until wedges) {
                if (!coverage.counted[band.ordinal][w * 2] && !coverage.counted[band.ordinal][w * 2 + 1]) continue
                // Azimuth (counter-clockwise from above) → screen angle with the heading at the bottom.
                val azEnd = (w + 1) * wedgeSpan
                val start = 90f - (azEnd - heading) + WEDGE_GAP / 2
                val sweep = wedgeSpan - WEDGE_GAP
                if (coverage.wedgeFilled(band, w)) {
                    drawArc(ArColors.guide, start, sweep, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(ring))
                } else {
                    drawArc(
                        ArColors.missing.copy(alpha = 0.55f), start, sweep, useCenter = false, topLeft = topLeft, size = arcSize,
                        style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx()))),
                    )
                }
            }
        }
        // Needle: the user, at the bottom.
        drawLine(Color.White, c, Offset(c.x, c.y + outer + 3.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
        drawCircle(Color.White, 3.5.dp.toPx(), c)
    }
}

private const val WEDGE_GAP = 4f

// ---- Coverage panel ---------------------------------------------------------------------------

private val panelShape = RoundedCornerShape(Radius.panel)

private fun Modifier.glass() = this
    .fillMaxWidth()
    .clip(panelShape)
    .background(ArColors.glass)
    .border(1.dp, ArColors.glassBorder, panelShape)

@Composable
private fun CoveragePanel(coverage: CoverageState) {
    // Read as one item: "Coverage 49%. Low 100%, mid 40%, high 0%."
    val description = stringResource(
        R.string.cd_coverage_panel,
        (coverage.overall * 100).roundToInt(),
        (coverage.band(CoverageBand.LOW) * 100).roundToInt(),
        (coverage.band(CoverageBand.MID) * 100).roundToInt(),
        (coverage.band(CoverageBand.HIGH) * 100).roundToInt(),
    )
    Row(
        Modifier.glass().clearAndSetSemantics { contentDescription = description }.padding(horizontal = 20.dp, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.widthIn(min = 104.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${(coverage.overall * 100).roundToInt()}", style = Facet.type.displayMono.copy(fontSize = 44.sp, lineHeight = 48.sp), color = Color.White)
                Text("%", style = Facet.type.valueMono.copy(fontSize = 20.sp), color = Color.White, modifier = Modifier.padding(bottom = 6.dp))
            }
            Text(stringResource(R.string.coverage), style = MaterialTheme.typography.bodyMedium, color = ArColors.textSecondary)
        }
        Spacer(Modifier.width(Space.s))
        BandBars(coverage, Modifier.weight(1f))
    }
}

/** HIGH / MID / LOW rows: complete = solid + check, partial = outlined with fill + %, empty = dashed + 0%. */
@Composable
internal fun BandBars(coverage: CoverageState, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        listOf(CoverageBand.HIGH to R.string.band_high, CoverageBand.MID to R.string.band_mid, CoverageBand.LOW to R.string.band_low).forEach { (band, label) ->
            val f = coverage.band(band)
            // One item per band: "HIGH, 40%" / "LOW, Complete". Widths grow with the font.
            Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(label), style = Facet.type.dataMono, color = ArColors.textSecondary, modifier = Modifier.widthIn(min = 44.dp).padding(end = Space.xxs))
                Canvas(Modifier.weight(1f).height(12.dp)) {
                    val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
                    when {
                        f >= 1f -> drawRoundRect(ArColors.guide, cornerRadius = r)
                        f > 0f -> {
                            drawRoundRect(
                                ArColors.guide,
                                size = Size(size.width * f, size.height),
                                cornerRadius = r,
                            )
                            drawRoundRect(Color.White.copy(alpha = 0.6f), cornerRadius = r, style = Stroke(1.dp.toPx()))
                        }
                        else -> drawRoundRect(
                            Color.White.copy(alpha = 0.6f), cornerRadius = r,
                            style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                        )
                    }
                }
                Box(Modifier.widthIn(min = 48.dp).padding(start = Space.xxs), contentAlignment = Alignment.CenterEnd) {
                    if (f >= 1f) {
                        Icon(painterResource(R.drawable.ic_check), stringResource(R.string.a11y_complete), tint = ArColors.guide, modifier = Modifier.size(20.dp))
                    } else {
                        Text("${(f * 100).roundToInt()}%", style = Facet.type.dataMono, color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackingLostPanel(overall: Float) {
    Row(
        Modifier.glass().semantics(mergeDescendants = true) {}.padding(horizontal = 20.dp, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Text("${(overall * 100).roundToInt()}%", style = Facet.type.displayMono, color = ArColors.textSecondary)
        Text(stringResource(R.string.tracking_paused_note), style = MaterialTheme.typography.bodyMedium, color = ArColors.textSecondary)
    }
}

// ---- Guidance banner (one at a time) ----------------------------------------------------------

private data class GuidanceStyle(@StringRes val text: Int, @DrawableRes val icon: Int, val background: Color, val content: Color, val critical: Boolean = false)

private fun styleOf(message: GuidanceMessage): GuidanceStyle = when (message) {
    GuidanceMessage.TRACKING_LOST -> GuidanceStyle(R.string.g_tracking_lost, R.drawable.ic_crosshair_x, ArColors.critical, ArColors.onCritical, critical = true)
    GuidanceMessage.TOO_CLOSE -> GuidanceStyle(R.string.g_too_close, R.drawable.ic_arrow_down, ArColors.warning, ArColors.onWarning)
    GuidanceMessage.TOO_FAR -> GuidanceStyle(R.string.g_too_far, R.drawable.ic_arrow_up, ArColors.warning, ArColors.onWarning)
    GuidanceMessage.MOVE_SLOWER -> GuidanceStyle(R.string.g_move_slower, R.drawable.ic_speed, ArColors.guide, ArColors.onGuide)
    GuidanceMessage.MORE_LIGHT -> GuidanceStyle(R.string.g_more_light, R.drawable.ic_light, ArColors.advice, ArColors.onAdvice)
    GuidanceMessage.HIGHER_ANGLES -> GuidanceStyle(R.string.g_higher, R.drawable.ic_arrow_up, ArColors.guide, ArColors.onGuide)
    GuidanceMessage.LOWER_ANGLES -> GuidanceStyle(R.string.g_lower, R.drawable.ic_arrow_down, ArColors.guide, ArColors.onGuide)
}

@Composable
private fun GuidanceBanner(message: GuidanceMessage) {
    val style = styleOf(message)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Box(
            Modifier
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                .background(ArColors.halo, CircleShape)
                .padding(ArColors.haloExtra + 1.dp),
        ) {
            Row(
                Modifier
                    .background(style.background, CircleShape)
                    .then(if (style.critical) Modifier.border(2.dp, style.content.copy(alpha = 0.8f), CircleShape) else Modifier)
                    .padding(horizontal = Space.l, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                Icon(painterResource(style.icon), null, tint = style.content, modifier = Modifier.size(26.dp))
                Text(stringResource(style.text), style = Facet.type.guidance, color = style.content, textAlign = TextAlign.Center)
            }
        }
        if (message == GuidanceMessage.TRACKING_LOST) GlassPill(stringResource(R.string.g_tracking_lost_sub), null)
    }
}

@Composable
private fun GlassPill(text: String, @DrawableRes icon: Int?, iconTint: Color = Color.White) {
    Row(
        Modifier
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .clip(CircleShape)
            .background(ArColors.glass)
            .border(1.dp, ArColors.glassBorder, CircleShape)
            .padding(horizontal = 20.dp, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        icon?.let { Icon(painterResource(it), null, tint = iconTint, modifier = Modifier.size(20.dp)) }
        Text(text, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = Color.White, textAlign = TextAlign.Center)
    }
}

// ---- Controls (thumb zone): Pause · Finish · Mesh ---------------------------------------------

@Composable
private fun CaptureControls(state: CaptureUiState, onPause: () -> Unit, onFinish: () -> Unit, onMesh: () -> Unit) {
    val paused = state.phase == CapturePhase.PAUSED
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        GlassCircleButton(
            if (paused) R.drawable.ic_play else R.drawable.ic_pause,
            stringResource(if (paused) R.string.cd_resume else R.string.cd_pause),
            onPause,
            size = Sizes.fab,
            enabled = state.phase != CapturePhase.INTERRUPTED,
        )
        val enabled = state.finishEnabled
        val finishLabel = stringResource(R.string.finish)
        val finishDescription = if (enabled) finishLabel else stringResource(R.string.cd_finish_disabled, (state.finishEnabledAt * 100).roundToInt())
        Row(
            Modifier
                .padding(horizontal = Space.xs)
                .weight(1f, fill = false)
                .widthIn(min = Sizes.finishWidth)
                .heightIn(min = Sizes.finishHeight)
                .alpha(if (enabled) 1f else 0.45f)
                .clip(CircleShape)
                .background(Color.White)
                .clickable(enabled = enabled, role = Role.Button, onClick = onFinish)
                .semantics { contentDescription = finishDescription }
                .padding(horizontal = Space.m, vertical = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(painterResource(R.drawable.ic_check), null, tint = ArColors.onAdvice, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(Space.xs))
            Text(finishLabel, style = MaterialTheme.typography.titleLarge, color = ArColors.onAdvice)
        }
        Box(Modifier.alpha(if (state.meshVisible) 1f else 0.55f)) {
            GlassCircleButton(
                R.drawable.ic_cube,
                stringResource(if (state.meshVisible) R.string.cd_mesh else R.string.cd_mesh_show),
                onMesh,
                size = Sizes.fab,
            )
        }
    }
}

// ---- Sheets -----------------------------------------------------------------------------------

@Composable
private fun SheetScrim(onDismiss: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            // Tap outside to dismiss. Not a TalkBack target: Back does the same.
            .pointerInput(onDismiss) { detectTapGestures { onDismiss?.invoke() } },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .statusBarsPadding()
                .clip(RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet))
                .background(Facet.colors.surface)
                .swallowTouches()
                // Scrolls when large text makes it taller than the screen.
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = Space.l, vertical = Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.m),
            content = content,
        )
    }
}

@Composable
private fun DialTile(coverage: CoverageState) {
    Box(
        Modifier.size(104.dp).clip(RoundedCornerShape(Radius.panel)).background(Color(0xFF0E0F10)),
        contentAlignment = Alignment.Center,
    ) {
        MiniDial(coverage, size = 96.dp, background = false)
    }
}

@Composable
private fun IncompleteSheet(state: CaptureUiState, onKeepScanning: () -> Unit, onFinishAnyway: () -> Unit) {
    val percent = (state.coverage.overall * 100).roundToInt()
    val (title, body) = when (state.missingBand) {
        CoverageBand.HIGH -> R.string.incomplete_title to R.string.incomplete_body
        CoverageBand.MID -> R.string.incomplete_title_mid to R.string.incomplete_body_mid
        CoverageBand.LOW -> R.string.incomplete_title_low to R.string.incomplete_body_low
        null -> R.string.incomplete_title_overall to R.string.incomplete_body_overall
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        DialTile(state.coverage)
        Column(verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                Icon(painterResource(R.drawable.ic_warning), null, tint = ArColors.warning, modifier = Modifier.size(24.dp))
                Text(stringResource(title), style = MaterialTheme.typography.titleLarge, color = Facet.colors.text, modifier = Modifier.semantics { heading() })
            }
            Text(stringResource(body, percent), style = MaterialTheme.typography.bodyMedium, color = Facet.colors.textMuted)
        }
    }
    BandBars(
        state.coverage,
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.card)).background(Facet.colors.surface2).padding(Space.m),
    )
    PrimaryButton(stringResource(R.string.keep_scanning), onKeepScanning, Modifier.fillMaxWidth(), minHeight = Sizes.touchMinAr)
    OutlineButton(stringResource(R.string.finish_anyway), onFinishAnyway, Modifier.fillMaxWidth(), minHeight = Sizes.touchMinAr)
    Text(
        stringResource(R.string.incomplete_tip),
        style = MaterialTheme.typography.bodyMedium,
        color = Facet.colors.textMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ChooseResultSheet(state: CaptureUiState, onChoose: (ResultMode, Boolean) -> Unit) {
    var mode by rememberSaveable { mutableStateOf(if (state.highDetailBadge) ResultMode.HIGH_DETAIL else ResultMode.QUICK) }
    var remember by rememberSaveable { mutableStateOf(false) }
    Text(stringResource(R.string.mode_title), style = MaterialTheme.typography.headlineSmall, color = Facet.colors.text, modifier = Modifier.semantics { heading() })
    RadioCard(selected = mode == ResultMode.QUICK, onClick = { mode = ResultMode.QUICK }) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.mode_quick), style = MaterialTheme.typography.titleMedium, color = Facet.colors.text)
            Text(stringResource(R.string.mode_quick_time, state.quickEstimateSec), style = Facet.type.dataMono, color = Facet.colors.text)
            Text(stringResource(R.string.mode_quick_body), style = MaterialTheme.typography.bodyMedium, color = Facet.colors.textMuted)
        }
    }
    RadioCard(selected = mode == ResultMode.HIGH_DETAIL, onClick = { mode = ResultMode.HIGH_DETAIL }) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text(stringResource(R.string.mode_high), style = MaterialTheme.typography.titleMedium, color = Facet.colors.text)
                if (state.highDetailBadge) {
                    Text(
                        stringResource(R.string.mode_high_badge),
                        style = MaterialTheme.typography.labelMedium,
                        color = Facet.colors.onAccent,
                        modifier = Modifier.clip(RoundedCornerShape(Radius.chip)).background(Facet.colors.accent).padding(horizontal = Space.xs, vertical = 2.dp),
                    )
                }
            }
            Text(stringResource(R.string.mode_high_time, state.highDetailEstimateMin), style = Facet.type.dataMono, color = Facet.colors.text)
            Text(stringResource(R.string.mode_high_body), style = MaterialTheme.typography.bodyMedium, color = Facet.colors.textMuted)
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.touchMinAr)
            .toggleable(value = remember, onValueChange = { remember = it }, role = Role.Checkbox),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = remember,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(checkedColor = Facet.colors.accent, checkmarkColor = Facet.colors.onAccent, uncheckedColor = Facet.colors.text),
        )
        Spacer(Modifier.width(Space.s))
        Text(stringResource(R.string.mode_remember), style = MaterialTheme.typography.bodyLarge, color = Facet.colors.text)
    }
    PrimaryButton(stringResource(R.string.action_continue), { onChoose(mode, remember) }, Modifier.fillMaxWidth(), minHeight = Sizes.touchMinAr)
    Text(
        stringResource(R.string.mode_note),
        style = MaterialTheme.typography.bodyMedium,
        color = Facet.colors.textMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun InterruptedSheet(state: CaptureUiState, onResume: () -> Unit, onFinish: () -> Unit, onDiscard: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        DialTile(state.coverage)
        Column(verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
            Text(stringResource(R.string.paused_title), style = MaterialTheme.typography.headlineSmall, color = Facet.colors.text, modifier = Modifier.semantics { heading() })
            Text(
                stringResource(R.string.paused_body, (state.coverage.overall * 100).roundToInt()),
                style = MaterialTheme.typography.bodyMedium,
                color = Facet.colors.textMuted,
            )
        }
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.card)).background(Facet.colors.surface2).padding(Space.m),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Icon(painterResource(R.drawable.ic_info), null, tint = Facet.colors.textMuted, modifier = Modifier.size(18.dp).padding(top = 2.dp))
        Text(stringResource(R.string.paused_tip, stringResource(R.string.app_name)), style = MaterialTheme.typography.bodyMedium, color = Facet.colors.text)
    }
    PrimaryButton(
        text = stringResource(if (state.resumeAvailable) R.string.resume_scan else R.string.capture_looking),
        onClick = onResume,
        icon = if (state.resumeAvailable) R.drawable.ic_play else null,
        enabled = state.resumeAvailable,
        modifier = Modifier.fillMaxWidth(),
        minHeight = Sizes.touchMinAr,
    )
    OutlineButton(stringResource(R.string.finish_with_what_i_have), onFinish, Modifier.fillMaxWidth(), minHeight = Sizes.touchMinAr)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        TextLinkButton(stringResource(R.string.discard_scan), onDiscard, minHeight = Sizes.touchMinAr)
    }
}

/** Eats taps so they don't reach the camera view underneath, without adding a TalkBack target. */
private fun Modifier.swallowTouches() = pointerInput(Unit) { detectTapGestures { } }
