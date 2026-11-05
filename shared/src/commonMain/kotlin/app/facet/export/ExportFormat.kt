package app.facet.export

import app.facet.mesh.Mesh

/** v1 export formats. More (OBJ, PLY, GLB, USDZ) are tracked in DEV_TODO.md. */
enum class ExportFormat(val extension: String, val mimeType: String) {
    /** Binary STL, millimetres. */
    STL("stl", "model/stl"),

    /** 3MF with unit="millimeter". */
    THREE_MF("3mf", "model/3mf");

    /**
     * File size for the export sheet. STL is exact (80-byte header + count + 50 bytes per triangle).
     * 3MF is an estimate of the uncompressed model XML plus package overhead, shown until the export
     * sheet has written the real file and measured it.
     */
    fun estimatedBytes(mesh: Mesh): Long = when (this) {
        STL -> 84L + 50L * mesh.triangleCount
        THREE_MF -> 1_500L + 48L * mesh.vertexCount + 44L * mesh.triangleCount
    }
}

/**
 * A safe file name stem from what the user typed: keeps letters, digits, space, '-', '_' and '.',
 * replaces anything else with '_', trims, and never returns an empty name.
 */
fun sanitizeFileName(input: String, fallback: String = "scan"): String {
    val cleaned = buildString {
        input.trim().forEach { c -> append(if (c.isLetterOrDigit() || c in " -_.") c else '_') }
    }.trim().trim('.').take(MAX_FILE_NAME)
    return cleaned.ifEmpty { fallback }
}

private const val MAX_FILE_NAME = 80
