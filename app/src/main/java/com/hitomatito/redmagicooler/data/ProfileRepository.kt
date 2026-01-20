package com.hitomatito.redmagicooler.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.hitomatito.redmagicooler.CoolerDeviceType
import com.hitomatito.redmagicooler.model.CoolerProfile
import com.hitomatito.redmagicooler.model.CoolerProfileData
import com.hitomatito.redmagicooler.model.LightEffect
import com.hitomatito.redmagicooler.model.RGBConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Repositorio para gestionar los perfiles de coolers
 * Maneja persistencia con SharedPreferences y operaciones CRUD
 */
class ProfileRepository(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    private val _profiles = MutableStateFlow<List<CoolerProfile>>(emptyList())
    val profiles: StateFlow<List<CoolerProfile>> = _profiles.asStateFlow()
    
    private val _activeProfileId = MutableStateFlow<String?>(null)
    val activeProfileId: StateFlow<String?> = _activeProfileId.asStateFlow()
    
    init {
        loadProfiles()
        loadActiveProfileId()
    }
    
    /**
     * Carga todos los perfiles guardados
     */
    private fun loadProfiles() {
        try {
            val jsonString = prefs.getString(KEY_PROFILES, null)
            if (jsonString.isNullOrEmpty()) {
                _profiles.value = emptyList()
                return
            }
            
            val jsonArray = JSONArray(jsonString)
            val loadedProfiles = mutableListOf<CoolerProfile>()
            
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val profile = jsonToProfile(obj)
                if (profile != null) {
                    loadedProfiles.add(profile)
                }
            }
            
            _profiles.value = loadedProfiles.sortedByDescending { it.lastConnectedAt }
            Log.d(TAG, "Cargados ${loadedProfiles.size} perfiles")
        } catch (e: Exception) {
            Log.e(TAG, "Error cargando perfiles: ${e.message}", e)
            _profiles.value = emptyList()
        }
    }
    
    /**
     * Carga el ID del perfil activo
     */
    private fun loadActiveProfileId() {
        _activeProfileId.value = prefs.getString(KEY_ACTIVE_PROFILE, null)
    }
    
    /**
     * Guarda todos los perfiles
     */
    private fun saveProfiles() {
        try {
            val jsonArray = JSONArray()
            _profiles.value.forEach { profile ->
                jsonArray.put(profileToJson(profile))
            }
            prefs.edit().putString(KEY_PROFILES, jsonArray.toString()).apply()
            Log.d(TAG, "Guardados ${_profiles.value.size} perfiles")
        } catch (e: Exception) {
            Log.e(TAG, "Error guardando perfiles: ${e.message}", e)
        }
    }
    
    /**
     * Agrega un nuevo perfil
     * @return El perfil creado
     */
    fun addProfile(profile: CoolerProfile): CoolerProfile {
        // Verificar si ya existe un perfil con la misma MAC
        val existingProfile = _profiles.value.find { it.macAddress == profile.macAddress }
        if (existingProfile != null) {
            Log.d(TAG, "Perfil con MAC ${profile.macAddress} ya existe, actualizando...")
            return updateProfile(existingProfile.copy(
                lastConnectedAt = System.currentTimeMillis(),
                isConnected = true,
                signalStrength = profile.signalStrength
            ))
        }
        
        val updatedList = _profiles.value + profile
        _profiles.value = updatedList.sortedByDescending { it.lastConnectedAt }
        saveProfiles()
        
        // Si es el primer perfil, establecerlo como activo
        if (_profiles.value.size == 1) {
            setActiveProfile(profile.id)
        }
        
        Log.d(TAG, "Perfil agregado: ${profile.displayName} (${profile.macAddress})")
        return profile
    }
    
    /**
     * Actualiza un perfil existente
     * @return El perfil actualizado
     */
    fun updateProfile(profile: CoolerProfile): CoolerProfile {
        val updatedList = _profiles.value.map { 
            if (it.id == profile.id) profile else it 
        }
        _profiles.value = updatedList.sortedByDescending { it.lastConnectedAt }
        saveProfiles()
        Log.d(TAG, "Perfil actualizado: ${profile.displayName}")
        return profile
    }
    
    /**
     * Elimina un perfil
     */
    fun deleteProfile(profileId: String) {
        val profile = _profiles.value.find { it.id == profileId }
        _profiles.value = _profiles.value.filter { it.id != profileId }
        saveProfiles()
        
        // Si era el perfil activo, seleccionar otro
        if (_activeProfileId.value == profileId) {
            _activeProfileId.value = _profiles.value.firstOrNull()?.id
            prefs.edit().putString(KEY_ACTIVE_PROFILE, _activeProfileId.value).apply()
        }
        
        Log.d(TAG, "Perfil eliminado: ${profile?.displayName}")
    }
    
    /**
     * Obtiene un perfil por ID
     */
    fun getProfile(profileId: String): CoolerProfile? {
        return _profiles.value.find { it.id == profileId }
    }
    
    /**
     * Obtiene un perfil por dirección MAC
     */
    fun getProfileByMac(macAddress: String): CoolerProfile? {
        return _profiles.value.find { it.macAddress == macAddress }
    }
    
    /**
     * Establece el perfil activo
     */
    fun setActiveProfile(profileId: String?) {
        _activeProfileId.value = profileId
        prefs.edit().putString(KEY_ACTIVE_PROFILE, profileId).apply()
        Log.d(TAG, "Perfil activo: $profileId")
    }
    
    /**
     * Obtiene el perfil activo
     */
    fun getActiveProfile(): CoolerProfile? {
        val activeId = _activeProfileId.value ?: return null
        return getProfile(activeId)
    }
    
    /**
     * Actualiza el estado de conexión de un perfil
     */
    fun updateConnectionState(profileId: String, isConnected: Boolean, rssi: Int = 0) {
        val profile = getProfile(profileId) ?: return
        updateProfile(profile.copy(
            isConnected = isConnected,
            signalStrength = rssi,
            lastConnectedAt = if (isConnected) System.currentTimeMillis() else profile.lastConnectedAt
        ))
    }
    
    /**
     * Desconecta todos los perfiles (para cuando se cierra la app)
     */
    fun disconnectAllProfiles() {
        val updatedList = _profiles.value.map { it.copy(isConnected = false) }
        _profiles.value = updatedList
        saveProfiles()
    }
    
    /**
     * Actualiza la velocidad del ventilador de un perfil
     */
    fun updateFanSpeed(profileId: String, speed: Int) {
        val profile = getProfile(profileId) ?: return
        updateProfile(profile.copy(fanSpeed = speed))
    }
    
    /**
     * Actualiza el modo automático de un perfil
     */
    fun updateAutoMode(profileId: String, isAutoMode: Boolean) {
        val profile = getProfile(profileId) ?: return
        updateProfile(profile.copy(isAutoMode = isAutoMode))
    }
    
    /**
     * Actualiza la configuración RGB de un perfil
     */
    fun updateRGBConfig(profileId: String, rgbConfig: RGBConfig?) {
        val profile = getProfile(profileId) ?: return
        updateProfile(profile.copy(rgbConfig = rgbConfig))
    }
    
    /**
     * Renombra un perfil
     */
    fun renameProfile(profileId: String, newName: String) {
        val profile = getProfile(profileId) ?: return
        updateProfile(profile.copy(name = newName))
    }
    
    /**
     * Verifica si existe un perfil con la MAC dada
     */
    fun hasProfileWithMac(macAddress: String): Boolean {
        return _profiles.value.any { it.macAddress == macAddress }
    }
    
    /**
     * Convierte un perfil a JSON
     */
    private fun profileToJson(profile: CoolerProfile): JSONObject {
        return JSONObject().apply {
            put("id", profile.id)
            put("name", profile.name)
            put("deviceType", profile.deviceType.name)
            put("macAddress", profile.macAddress)
            put("createdAt", profile.createdAt)
            put("lastConnectedAt", profile.lastConnectedAt)
            put("fanSpeed", profile.fanSpeed)
            put("isAutoMode", profile.isAutoMode)
            
            profile.rgbConfig?.let { rgb ->
                put("rgbEffectCode", rgb.effect.code.toInt())
                put("rgbRed", rgb.red)
                put("rgbGreen", rgb.green)
                put("rgbBlue", rgb.blue)
            }
        }
    }
    
    /**
     * Convierte JSON a un perfil
     */
    private fun jsonToProfile(json: JSONObject): CoolerProfile? {
        return try {
            val deviceType = CoolerDeviceType.valueOf(json.getString("deviceType"))
            
            val rgbConfig = if (json.has("rgbEffectCode")) {
                val effectCode = json.getInt("rgbEffectCode").toByte()
                val effect = LightEffect.entries.find { it.code == effectCode } ?: LightEffect.ALWAYS_BRIGHT
                RGBConfig(
                    effect = effect,
                    red = json.getInt("rgbRed"),
                    green = json.getInt("rgbGreen"),
                    blue = json.getInt("rgbBlue")
                )
            } else null
            
            CoolerProfile(
                id = json.getString("id"),
                name = json.getString("name"),
                deviceType = deviceType,
                macAddress = json.getString("macAddress"),
                createdAt = json.getLong("createdAt"),
                lastConnectedAt = json.getLong("lastConnectedAt"),
                fanSpeed = json.optInt("fanSpeed", 50),
                isAutoMode = json.optBoolean("isAutoMode", false),
                rgbConfig = rgbConfig
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parseando perfil: ${e.message}")
            null
        }
    }
    
    companion object {
        private const val TAG = "ProfileRepository"
        private const val PREFS_NAME = "cooler_profiles"
        private const val KEY_PROFILES = "profiles"
        private const val KEY_ACTIVE_PROFILE = "active_profile"
        
        @Volatile
        private var instance: ProfileRepository? = null
        
        fun getInstance(context: Context): ProfileRepository {
            return instance ?: synchronized(this) {
                instance ?: ProfileRepository(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
