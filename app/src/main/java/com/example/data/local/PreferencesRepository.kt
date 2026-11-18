package com.example.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.domain.model.UnitSystem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class UserPreferences(
    val darkCadMode: Boolean = false,
    val unitSystem: UnitSystem = UnitSystem.METERS,
    val precisionDecimals: Int = 2,
    val angleToleranceDeg: Double = 0.5
)

class PreferencesRepository(private val dataStore: DataStore<Preferences>) {

    private object Keys {
        val DARK_CAD_MODE = booleanPreferencesKey("dark_cad_mode")
        val UNIT_SYSTEM = stringPreferencesKey("unit_system")
        val PRECISION_DECIMALS = intPreferencesKey("precision_decimals")
        val ANGLE_TOLERANCE_DEG = doublePreferencesKey("angle_tolerance_deg")
    }

    val preferences: Flow<UserPreferences> = dataStore.data.map { prefs ->
        UserPreferences(
            darkCadMode = prefs[Keys.DARK_CAD_MODE] ?: false,
            unitSystem = UnitSystem.fromCode(prefs[Keys.UNIT_SYSTEM]),
            precisionDecimals = (prefs[Keys.PRECISION_DECIMALS] ?: 2).coerceIn(0, 4),
            angleToleranceDeg = (prefs[Keys.ANGLE_TOLERANCE_DEG] ?: 0.5).coerceIn(0.05, 5.0)
        )
    }

    suspend fun setDarkCadMode(enabled: Boolean) {
        dataStore.edit { it[Keys.DARK_CAD_MODE] = enabled }
    }

    suspend fun setUnitSystem(unit: UnitSystem) {
        dataStore.edit { it[Keys.UNIT_SYSTEM] = unit.symbol }
    }

    suspend fun setPrecisionDecimals(decimals: Int) {
        dataStore.edit { it[Keys.PRECISION_DECIMALS] = decimals.coerceIn(0, 4) }
    }

    suspend fun setAngleToleranceDeg(toleranceDeg: Double) {
        dataStore.edit { it[Keys.ANGLE_TOLERANCE_DEG] = toleranceDeg.coerceIn(0.05, 5.0) }
    }
}
