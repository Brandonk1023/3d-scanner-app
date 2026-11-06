package app.facet.android.ui.scan

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.facet.android.R
import app.facet.android.AppContainer
import app.facet.android.appContainer
import app.facet.android.ar.ArController
import app.facet.android.ar.ArEvent
import app.facet.android.ar.ArFrameSnapshot
import app.facet.android.ar.ArStatus
import app.facet.android.ar.PlaneFrame
import app.facet.android.ar.Projector
import app.facet.android.ar.TrackingFailure
import app.facet.android.ui.components.FitText
import app.facet.android.ui.components.InfoDialog
import app.facet.android.ui.components.PrimaryButton
import app.facet.android.ui.components.SegmentedControl
import app.facet.android.ui.components.boxSizeText
import app.facet.android.ui.theme.ArColors
import app.facet.android.ui.theme.Facet
import app.facet.android.ui.theme.Radius
import app.facet.android.ui.theme.Sizes
import app.facet.android.ui.theme.Space
import app.facet.format.formatBytes
import app.facet.geometry.Vec3
import app.facet.scan.BoxAxis
import app.facet.scan.PlacedBox
import app.facet.scan.ScanType
import app.facet.scan.TrackingState
import app.facet.ui.scan.PlacementStage
import app.facet.ui.scan.PlacementUiState
import app.facet.ui.scan.PlacementViewModel
import app.facet.ui.scan.ScanSetup
import app.facet.scan.ResultMode
import app.facet.scan.ScanTuning
import app.facet.android.ar.CaptureEngine
import app.facet.android.ar.MediaPipeSegmenter
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

/**
 * The `scan` route: placement — Find surface (Placement-Surface.png) and Fit box
 * (Placement-Box.png) — then capture ([CaptureLayer]) over the same AR session. Always dark (the
 * caller wraps it in the dark theme).
 */
@Composable
fun ScanScreen(onClose: () -> Unit, onResult: (scanId: String, mode: ResultMode) -> Unit) {
    val context = LocalContext.current
    val container = appContainer()
    val vm: PlacementViewModel = viewModel { PlacementViewModel(container.settings, container.storage) }
    val state by vm.state.collectAsStateWithLifecycle()
    val ar = remember { ArController(context) }
    val status by ar.status.collectAsStateWithLifecycle()
    // Passed down as State so only the overlay pieces that read it redraw every camera frame.
    val frames = ar.frames.collectAsState()
    val haptics = LocalHapticFeedback.current

    /** The running capture, once Start scan has passed its checks. */
    var capture by remember { mutableStateOf<Pair<ScanSetup, CaptureEngine>?>(null) }
    var captureFailed by remember { mutableStateOf(false) }
    LaunchedEffect(state.started) {
        val setup = state.started ?: return@LaunchedEffect
        vm.consumeStarted()
        val tuning = ScanTuning.Default
        // The volume reaches past the placed box so carving doesn't clip the object's top and sides.
        val volumeSize = tuning.volumeSize(setup.box.size)
        val voxel = tuning.liveVoxelMm(volumeSize)
        val volume = withContext(Dispatchers.Default) {
            runCatching { container.meshEngine.createVolume(volumeSize, voxel, tuning.truncationMm(voxel)) }.getOrNull()
        }
        if (volume == null) {
            captureFailed = true
            return@LaunchedEffect
        }
        val dir = context.filesDir.resolve("${AppContainer.SCANS_DIR}/scan-${System.currentTimeMillis()}")
        val appContext = context.applicationContext
        val engine = CaptureEngine(volume, setup.box, dir, tuning, ar.depthFusedSink()) { MediaPipeSegmenter(appContext) }
        ar.startCapture(engine)
        capture = setup to engine
    }
    DisposableEffect(capture) {
        val engine = capture?.second
        onDispose {
            if (engine != null) {
                ar.stopCapture()
                engine.close()
            }
        }
    }

    /** A short-lived message that replaces the banner for a moment. */
    var notice by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(NOTICE_MS)
            notice = null
        }
    }

    LaunchedEffect(ar) {
        ar.events.collect { event ->
            when (event) {
                is ArEvent.BoxPlaced -> {
                    vm.onBoxPlaced(event.box, event.fitted)
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                }
                ArEvent.Measuring -> vm.onFitStarted()
                ArEvent.PlaceMissed -> notice = R.string.place_missed
                is ArEvent.Fitted -> vm.onFitResult(event.box)
                is ArEvent.DepthFused -> Unit // handled by CaptureLayer
            }
        }
    }
    LaunchedEffect(ar) {
        ar.frames.map { it?.hasSurface == true }.distinctUntilChanged().collect(vm::onSurfaceFound)
    }
    LaunchedEffect(state.fitFailed) {
        if (state.fitFailed) {
            notice = R.string.place_fit_failed
            vm.consumeFitFailed()
        }
    }
    LaunchedEffect(state.torchOn) { ar.setTorch(state.torchOn) }
    SideEffect { ar.showPlanes = capture == null && state.stage == PlacementStage.FINDING_SURFACE }

    val startOver = {
        ar.clearAnchor()
        vm.clearBox()
    }
    BackHandler(enabled = capture == null && state.stage == PlacementStage.FITTING_BOX, onBack = startOver)
    LightSystemBarIcons()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        ArCameraView(ar, Modifier.fillMaxSize())

        val running = capture
        if (running != null) {
            CaptureLayer(
                setup = running.first,
                engine = running.second,
                ar = ar,
                frames = frames,
                torchOn = state.torchOn,
                onTorch = vm::toggleTorch,
                onDiscarded = onClose,
                onResult = onResult,
            )
        } else {
            PlacementLayer(state, vm, ar, frames, status, notice, startOver, onClose)
        }
    }

    state.lowStorage?.let { low ->
        InfoDialog(
            title = stringResource(R.string.scan_low_storage_title),
            body = {
                Text(
                    stringResource(R.string.scan_low_storage_body, formatBytes(low.neededBytes), formatBytes(low.freeBytes)),
                    color = Facet.colors.textMuted,
                )
            },
            onDismiss = vm::dismissLowStorage,
        )
    }
    if (captureFailed) {
        InfoDialog(
            title = stringResource(R.string.ar_error_title),
            body = { Text(stringResource(R.string.capture_no_memory), color = Facet.colors.textMuted) },
            onDismiss = { captureFailed = false },
        )
    }
}

/** Find surface + Fit box overlays, gestures, handles and panels. */
@Composable
private fun PlacementLayer(
    state: PlacementUiState,
    vm: PlacementViewModel,
    ar: ArController,
    frames: State<ArFrameSnapshot?>,
    status: ArStatus,
    notice: Int?,
    startOver: () -> Unit,
    onClose: () -> Unit,
) {
    val tracking by remember { derivedStateOf { frames.value?.tracking ?: TrackingState.NOT_TRACKING } }
    val failure by remember { derivedStateOf { frames.value?.failure ?: TrackingFailure.NONE } }
    val boxVisible by remember { derivedStateOf { frames.value?.anchor != null } }

    Box(Modifier.fillMaxSize()) {
        val stage = state.stage
        val box = state.box
        Canvas(Modifier.fillMaxSize()) {
            val snapshot = frames.value ?: return@Canvas
            val projector = Projector(snapshot, size.width, size.height)
            when (stage) {
                PlacementStage.FINDING_SURFACE -> drawSurfaces(snapshot, projector)
                PlacementStage.FITTING_BOX -> {
                    val anchor = snapshot.anchor
                    if (box != null && anchor != null) drawBox(box, PlaneFrame(anchor), projector)
                }
            }
        }

        // Tap to place (or re-place on another object) and two-finger rotate (Fit box).
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { ar.placeAt(it.x, it.y) } }
                .pointerInput(stage) {
                    if (stage == PlacementStage.FITTING_BOX) {
                        detectTransformGestures { _, _, _, rotation ->
                            // Screen rotation is clockwise-positive; yaw is counter-clockwise seen from above.
                            if (rotation != 0f) vm.rotate(-rotation.toDouble())
                        }
                    }
                },
        )

        if (stage == PlacementStage.FITTING_BOX && box != null) BoxHandles(frames, box, vm)

        TopControls(
            torchOn = state.torchOn,
            onClose = onClose,
            onTorch = vm::toggleTorch,
            onStartOver = if (stage == PlacementStage.FITTING_BOX) startOver else null,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        if (status is ArStatus.Running) {
            val banner = bannerFor(state, tracking, failure, boxVisible, notice)
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 84.dp, start = Space.m, end = Space.m),
            ) {
                ArBanner(banner)
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(Space.m),
        ) {
            when {
                status !is ArStatus.Running -> Unit
                stage == PlacementStage.FINDING_SURFACE && state.surfaceFound && tracking == TrackingState.TRACKING ->
                    SurfaceFoundCard()
                stage == PlacementStage.FITTING_BOX && box != null -> PlacementPanel(
                    state = state,
                    box = box,
                    onFit = {
                        vm.onFitStarted()
                        ar.fitAround(box)
                    },
                    onScanType = vm::setScanType,
                    onStart = vm::startScan,
                )
            }
        }

        (status as? ArStatus.Failed)?.let { failed ->
            ArErrorCard(failed.reason, onClose, Modifier.align(Alignment.Center))
        }
    }
}

private const val NOTICE_MS = 2_500L

// ---- Handles ---------------------------------------------------------------------------------

/** An in-progress handle drag. Axis choice is frozen for its duration. */
private class HandleDrag(
    val kind: HandleKind,
    val sideAxis: BoxAxis,
    val frontSide: Int,
    val startScreen: Offset,
    val startTable: Vec3?,
    val startCenterX: Double,
    val startCenterY: Double,
) {
    var total = Offset.Zero
}

@Composable
private fun BoxHandles(frames: State<ArFrameSnapshot?>, box: PlacedBox, vm: PlacementViewModel) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        var drag by remember { mutableStateOf<HandleDrag?>(null) }

        val snapshot = frames.value ?: return@BoxWithConstraints
        val anchor = snapshot.anchor ?: return@BoxWithConstraints
        val layout = handleLayout(box, PlaneFrame(anchor), Projector(snapshot, width, height), drag?.sideAxis)
            ?: return@BoxWithConstraints

        fun start(kind: HandleKind) {
            val snap = frames.value ?: return
            val a = snap.anchor ?: return
            val b = vm.state.value.box ?: return
            val screen = layout.screen[kind] ?: return
            val table = if (kind == HandleKind.MOVE) Projector(snap, width, height).hitTable(screen, PlaneFrame(a)) else null
            drag = HandleDrag(kind, layout.sideAxis, layout.frontSide, screen, table, b.centerX, b.centerY)
        }

        fun move(delta: Offset) {
            val d = drag ?: return
            val snap = frames.value ?: return
            val frame = PlaneFrame(snap.anchor ?: return)
            val projector = Projector(snap, width, height)
            val b = vm.state.value.box ?: return
            if (d.kind == HandleKind.MOVE) {
                d.total += delta
                val from = d.startTable ?: return
                val to = projector.hitTable(d.startScreen + d.total, frame) ?: return
                vm.moveTo(d.startCenterX + to.x - from.x, d.startCenterY + to.y - from.y)
                return
            }
            val (axis, side) = when (d.kind) {
                HandleKind.TOP -> BoxAxis.HEIGHT to 1
                HandleKind.SIDE_PLUS -> d.sideAxis to 1
                else -> d.sideAxis to -1
            }
            // Screen direction of "this face moves outward by 10 mm"; project the finger onto it.
            val point = handlePoint(b, d.kind, d.sideAxis, d.frontSide)
            val s0 = projector.project(frame.toWorld(point)) ?: return
            val s1 = projector.project(frame.toWorld(point + b.direction(axis) * (10.0 * side))) ?: return
            val v = s1 - s0
            val len2 = v.x * v.x + v.y * v.y
            if (len2 < MIN_AXIS_PX * MIN_AXIS_PX) return // axis points at the camera: no reliable direction
            vm.resize(axis, side, (delta.x * v.x + delta.y * v.y) / len2 * 10.0)
        }

        layout.screen.forEach { (kind, position) ->
            key(kind) {
                Handle(kind, position, onStart = { start(kind) }, onDrag = ::move, onEnd = { drag = null })
            }
        }
    }
}

private const val MIN_AXIS_PX = 1.5f

@Composable
private fun Handle(kind: HandleKind, position: Offset, onStart: () -> Unit, onDrag: (Offset) -> Unit, onEnd: () -> Unit) {
    val touch = Sizes.touchMin
    val touchPx = with(LocalDensity.current) { touch.toPx() }
    val description = stringResource(
        when (kind) {
            HandleKind.TOP -> R.string.cd_handle_height
            HandleKind.MOVE -> R.string.cd_handle_move
            else -> R.string.cd_handle_side
        },
    )
    val currentStart by rememberUpdatedState(onStart)
    val currentDrag by rememberUpdatedState(onDrag)
    val currentEnd by rememberUpdatedState(onEnd)
    val dot = if (kind == HandleKind.TOP) 26.dp else 22.dp
    Box(
        modifier = Modifier
            .offset { IntOffset((position.x - touchPx / 2).roundToInt(), (position.y - touchPx / 2).roundToInt()) }
            .size(touch)
            .semantics { contentDescription = description }
            .pointerInput(kind) {
                detectDragGestures(
                    onDragStart = { currentStart() },
                    onDragEnd = { currentEnd() },
                    onDragCancel = { currentEnd() },
                ) { change, amount ->
                    change.consume()
                    currentDrag(amount)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(dot + ArColors.haloExtra * 2).background(ArColors.halo, CircleShape))
        Box(
            Modifier
                .size(dot)
                .background(Color.White, CircleShape)
                .border(1.dp, Color.Black.copy(alpha = 0.35f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (kind == HandleKind.TOP) {
                Icon(painterResource(R.drawable.ic_arrow_up), null, tint = ArColors.onAdvice, modifier = Modifier.size(16.dp))
            }
        }
    }
}

// ---- Banner ----------------------------------------------------------------------------------

private enum class BannerStyle { ADVICE, GLASS }

private data class Banner(@StringRes val text: Int, @DrawableRes val icon: Int?, val style: BannerStyle)

/** One message at a time; transient notices win, then tracking problems, then the step's instruction. */
private fun bannerFor(
    state: PlacementUiState,
    tracking: TrackingState,
    failure: TrackingFailure,
    boxVisible: Boolean,
    notice: Int?,
): Banner {
    if (notice != null) return Banner(notice, R.drawable.ic_info, BannerStyle.GLASS)
    if (state.fitting) return Banner(R.string.place_measuring, R.drawable.ic_ruler, BannerStyle.GLASS)
    if (tracking != TrackingState.TRACKING) {
        return when (failure) {
            TrackingFailure.INSUFFICIENT_LIGHT -> Banner(R.string.g_more_light, R.drawable.ic_light, BannerStyle.GLASS)
            TrackingFailure.EXCESSIVE_MOTION -> Banner(R.string.g_move_slower, R.drawable.ic_speed, BannerStyle.GLASS)
            TrackingFailure.INSUFFICIENT_FEATURES -> Banner(R.string.place_need_texture, R.drawable.ic_smartphone, BannerStyle.GLASS)
            else -> if (state.stage == PlacementStage.FITTING_BOX) {
                Banner(R.string.g_tracking_lost_sub, R.drawable.ic_warning, BannerStyle.GLASS)
            } else {
                Banner(R.string.place_find_surface, R.drawable.ic_smartphone, BannerStyle.GLASS)
            }
        }
    }
    return when (state.stage) {
        PlacementStage.FINDING_SURFACE ->
            if (state.surfaceFound) Banner(R.string.place_tap_object, R.drawable.ic_add, BannerStyle.ADVICE)
            else Banner(R.string.place_find_surface, R.drawable.ic_smartphone, BannerStyle.GLASS)
        PlacementStage.FITTING_BOX ->
            if (boxVisible) Banner(R.string.place_drag_handles, null, BannerStyle.GLASS)
            else Banner(R.string.g_tracking_lost_sub, R.drawable.ic_warning, BannerStyle.GLASS)
    }
}

@Composable
private fun ArBanner(banner: Banner) {
    val text = stringResource(banner.text)
    val live = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    when (banner.style) {
        BannerStyle.ADVICE -> Box(
            live
                .background(ArColors.halo, CircleShape)
                .padding(ArColors.haloExtra + 1.dp),
        ) {
            Row(
                Modifier
                    .background(ArColors.advice, CircleShape)
                    .padding(horizontal = Space.l, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                banner.icon?.let { Icon(painterResource(it), null, tint = ArColors.onAdvice, modifier = Modifier.size(26.dp)) }
                Text(text, style = Facet.type.guidance, color = ArColors.onAdvice, textAlign = TextAlign.Center)
            }
        }
        BannerStyle.GLASS -> Row(
            live
                .background(ArColors.glass, CircleShape)
                .border(1.dp, ArColors.glassBorder, CircleShape)
                .padding(horizontal = 20.dp, vertical = Space.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            banner.icon?.let { Icon(painterResource(it), null, tint = Color.White, modifier = Modifier.size(20.dp)) }
            Text(
                text,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ---- Controls --------------------------------------------------------------------------------

@Composable
private fun TopControls(
    torchOn: Boolean,
    onClose: () -> Unit,
    onTorch: () -> Unit,
    onStartOver: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = Space.m, vertical = Space.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        GlassIconButton(R.drawable.ic_close, stringResource(R.string.cd_cancel_scan), onClose)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            if (onStartOver != null) {
                GlassIconButton(R.drawable.ic_refresh, stringResource(R.string.place_start_over), onStartOver)
            }
            GlassIconButton(R.drawable.ic_light, stringResource(R.string.cd_torch), onTorch, active = torchOn)
        }
    }
}

@Composable
private fun GlassIconButton(@DrawableRes icon: Int, description: String, onClick: () -> Unit, active: Boolean = false) {
    Box(
        Modifier
            .size(Sizes.touchMin)
            .clip(CircleShape)
            .background(if (active) Color.White else ArColors.glass)
            .border(1.dp, ArColors.glassBorder, CircleShape)
            .clickable(onClickLabel = description, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), null, tint = if (active) ArColors.onAdvice else Color.White, modifier = Modifier.size(Sizes.icon))
    }
}

private val panelShape = RoundedCornerShape(Radius.panel)

private fun Modifier.glassPanel() = this
    .fillMaxWidth()
    .clip(panelShape)
    .background(ArColors.glass)
    .border(1.dp, ArColors.glassBorder, panelShape)

@Composable
private fun SurfaceFoundCard() {
    Row(
        Modifier.glassPanel().padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Box(Modifier.size(40.dp).background(ArColors.guide, CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_check), null, tint = ArColors.onGuide, modifier = Modifier.size(22.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.place_surface_found), style = MaterialTheme.typography.titleMedium, color = Color.White)
            Text(stringResource(R.string.place_surface_tip), style = MaterialTheme.typography.bodyMedium, color = ArColors.textSecondary)
        }
    }
}

@Composable
private fun PlacementPanel(
    state: PlacementUiState,
    box: PlacedBox,
    onFit: () -> Unit,
    onScanType: (ScanType) -> Unit,
    onStart: () -> Unit,
) {
    Column(Modifier.glassPanel().padding(20.dp), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.place_box_size), style = MaterialTheme.typography.bodyMedium, color = ArColors.textSecondary)
                FitText(
                    boxSizeText(box.size, state.units),
                    style = Facet.type.valueMono.copy(fontSize = 20.sp, lineHeight = 26.sp),
                    color = Color.White,
                    minSize = 13.sp,
                )
            }
            Spacer(Modifier.width(Space.s))
            FitButton(fitting = state.fitting, onClick = onFit)
        }
        val types = listOf(ScanType.FULL_OBJECT, ScanType.ONE_SIDE)
        SegmentedControl(
            options = listOf(stringResource(R.string.scan_type_full), stringResource(R.string.scan_type_side)),
            selectedIndex = types.indexOf(state.scanType),
            onSelect = { onScanType(types[it]) },
            modifier = Modifier.fillMaxWidth(),
            container = Color.White.copy(alpha = 0.08f),
            equalWidth = true,
        )
        Text(
            stringResource(if (state.scanType == ScanType.ONE_SIDE) R.string.scan_type_side_sub else R.string.scan_type_full_sub),
            style = MaterialTheme.typography.bodyMedium,
            color = ArColors.textSecondary,
        )
        PrimaryButton(
            text = stringResource(R.string.start_scan),
            onClick = onStart,
            icon = R.drawable.ic_play,
            modifier = Modifier.fillMaxWidth().height(Sizes.finishHeight),
        )
    }
}

@Composable
private fun FitButton(fitting: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .heightIn(min = Sizes.touchMin)
            .clip(CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
            .clickable(enabled = !fitting, role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        if (fitting) {
            CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
        } else {
            Icon(painterResource(R.drawable.ic_fit), null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Text(stringResource(R.string.place_fit), style = MaterialTheme.typography.labelLarge, color = Color.White)
    }
}

@Composable
private fun ArErrorCard(reason: ArStatus.Reason, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .padding(Space.l)
            .glassPanel()
            .padding(Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Text(stringResource(R.string.ar_error_title), style = MaterialTheme.typography.titleLarge, color = Color.White)
        Text(
            stringResource(if (reason == ArStatus.Reason.CAMERA_UNAVAILABLE) R.string.ar_error_camera else R.string.ar_error_ar),
            style = MaterialTheme.typography.bodyMedium,
            color = ArColors.textSecondary,
        )
        Spacer(Modifier.height(Space.xs))
        PrimaryButton(stringResource(R.string.action_close), onClose, Modifier.fillMaxWidth())
    }
}
