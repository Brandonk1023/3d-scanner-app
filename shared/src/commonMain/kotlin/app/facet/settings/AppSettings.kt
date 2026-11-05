package app.facet.settings

import app.facet.mesh.Smoothing

/** Persisted with DataStore from Phase 1. Defaults match the designs. */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val units: LengthUnit = LengthUnit.MILLIMETRES,
    val defaultResult: DefaultResult = DefaultResult.ASK,
    val watertight: Boolean = true,
    val smoothing: Smoothing = Smoothing.LOW,
    val hapticGuidance: Boolean = true,
    val onboardingDone: Boolean = false,
)

/** Appearance. Camera/AR screens are always dark regardless. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Display only — everything is stored and exported in millimetres. */
enum class LengthUnit(val mmPerUnit: Double) {
    MILLIMETRES(1.0),
    INCHES(25.4);

    fun fromMm(mm: Double): Double = mm / mmPerUnit
    fun toMm(value: Double): Double = value * mmPerUnit
}

enum class DefaultResult { ASK, QUICK, HIGH_DETAIL }
