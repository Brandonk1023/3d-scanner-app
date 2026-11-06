package app.facet.ui.scale

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.facet.data.Scan
import app.facet.data.ScanRepository
import app.facet.format.formatLength
import app.facet.geometry.BoxSize
import app.facet.geometry.Vec3
import app.facet.mesh.Mesh
import app.facet.scan.ScanTuning
import app.facet.settings.LengthUnit
import app.facet.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Scale check, manual flow (docs/04 §5, Scale-Manual.png): two points on the mesh, the real length
 * between them typed in the display unit, then a uniform scale.
 */
data class ScaleCheckUiState(
    val loading: Boolean = true,
    val scan: Scan? = null,
    val mesh: Mesh? = null,
    val units: LengthUnit = LengthUnit.MILLIMETRES,
    /** Mesh-space points (mm), at most two. */
    val points: List<Vec3> = emptyList(),
    /** What the user typed, in [units]. */
    val input: String = "",
    val applying: Boolean = false,
    /** One-shot: the scale was applied, close the screen. */
    val applied: Boolean = false,
    val tuning: ScanTuning.Scale = ScanTuning.Default.scale,
) {
    val missing: Boolean get() = !loading && scan == null

    /** Distance between the two points, or null until both are placed. */
    val measuredMm: Double? get() = if (points.size == 2) (points[1] - points[0]).length() else null

    val tooShort: Boolean get() = measuredMm?.let { it < tuning.minMeasuredMm } ?: false

    /** The typed length in mm, or null if it isn't a positive number. */
    val realMm: Double? get() = parseLength(input)?.let(units::toMm)

    val factor: Double?
        get() {
            val measured = measuredMm?.takeIf { it >= tuning.minMeasuredMm } ?: return null
            return realMm?.let { it / measured }
        }

    /** +0.0166 = the model grows 1.66 %. */
    val correction: Double? get() = factor?.let { it - 1 }

    val warnLarge: Boolean get() = correction?.let { abs(it) > tuning.warnCorrectionAbove } ?: false

    /** Beyond [ScanTuning.Scale.maxCorrection]: almost certainly a typo or the wrong edge. */
    val outOfRange: Boolean get() = correction?.let { abs(it) > tuning.maxCorrection } ?: false

    val newSize: BoxSize?
        get() {
            val f = factor ?: return null
            val s = scan?.size ?: return null
            return BoxSize(s.widthMm * f, s.depthMm * f, s.heightMm * f)
        }

    val canApply: Boolean get() = factor != null && !outOfRange && !applying && !applied
}

/** "98", "98.0", "98,5" → value; null if not a positive finite number. */
fun parseLength(text: String): Double? =
    text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }

class ScaleCheckViewModel(
    private val scanId: String,
    private val repository: ScanRepository,
    settings: SettingsRepository,
    tuning: ScanTuning = ScanTuning.Default,
) : ViewModel() {
    private val _state = MutableStateFlow(ScaleCheckUiState(tuning = tuning.scale))
    val state: StateFlow<ScaleCheckUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val mesh = repository.loadMesh(scanId)
            combine(repository.scan(scanId), settings.settings) { scan, s -> scan to s.units }
                .collect { (scan, units) ->
                    _state.update { it.copy(loading = false, scan = scan, mesh = mesh, units = units) }
                }
        }
    }

    /** A tap on the mesh: places the first, then the second point; after that moves the nearer one. */
    fun tap(point: Vec3) = _state.update { s ->
        if (s.points.size < 2) {
            s.copy(points = s.points + point)
        } else {
            val nearer = if ((s.points[0] - point).length() <= (s.points[1] - point).length()) 0 else 1
            s.copy(points = s.points.toMutableList().also { it[nearer] = point })
        }
    }

    fun movePoint(index: Int, point: Vec3) = _state.update { s ->
        if (index !in s.points.indices) s else s.copy(points = s.points.toMutableList().also { it[index] = point })
    }

    fun resetPoints() = _state.update { it.copy(points = emptyList()) }

    /** Keeps digits and one decimal separator. */
    fun setInput(text: String) {
        var separator = false
        val clean = buildString {
            for (c in text) {
                when {
                    c.isDigit() -> append(c)
                    (c == '.' || c == ',') && !separator -> { separator = true; append(c) }
                }
            }
        }.take(MAX_INPUT)
        _state.update { it.copy(input = clean) }
    }

    fun apply() {
        val s = _state.value
        val factor = s.factor ?: return
        val real = s.realMm ?: return
        if (!s.canApply) return
        _state.update { it.copy(applying = true) }
        viewModelScope.launch {
            repository.applyScale(scanId, factor, "${formatLength(real, s.units)} ${s.units.symbol}")
            _state.update { it.copy(applying = false, applied = true) }
        }
    }

    private companion object {
        const val MAX_INPUT = 9
    }
}
