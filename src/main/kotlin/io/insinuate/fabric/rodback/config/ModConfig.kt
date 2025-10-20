package io.insinuate.fabric.rodback.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader
import java.io.File
import java.io.FileReader
import java.io.FileWriter

object ModConfig {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val configFile: File = FabricLoader.getInstance()
        .configDir
        .resolve("rodback.json")
        .toFile()

    var autoRetractEnabled: Boolean = true
        private set

    var retractOnGround: Boolean = true
        private set

    var retractOnWall: Boolean = true
        private set

    var retractOnWater: Boolean = false
        private set

    var retractOnEntity: Boolean = true
        private set

    var useSlotSwitchRetract: Boolean = false
        private set

    var autoRecast: Boolean = false
        private set

    var recastOnManualRetract: Boolean = false
        private set

    var recastOnServerRemove: Boolean = false
        private set

    var recastDelayTicks: Int = 3
        private set

    var blockManualRetractAfterRecast: Boolean = false
        private set

    var blockManualRetractTicks: Int = 10
        private set

    data class ConfigData(
        var autoRetractEnabled: Boolean = true,
        var retractOnGround: Boolean = true,
        var retractOnWall: Boolean = true,
        var retractOnWater: Boolean = false,
        var retractOnEntity: Boolean = true,
        var useSlotSwitchRetract: Boolean = false,
        var autoRecast: Boolean = false,
        var recastOnManualRetract: Boolean = false,
        var recastOnServerRemove: Boolean = false,
        var recastDelayTicks: Int = 3,
        var blockManualRetractAfterRecast: Boolean = false,
        var blockManualRetractTicks: Int = 10
    )

    fun load() {
        if (!configFile.exists()) {
            save()
            return
        }

        try {
            FileReader(configFile).use { reader ->
                val data = gson.fromJson(reader, ConfigData::class.java)
                autoRetractEnabled = data.autoRetractEnabled
                retractOnGround = data.retractOnGround
                retractOnWall = data.retractOnWall
                retractOnWater = data.retractOnWater
                retractOnEntity = data.retractOnEntity
                useSlotSwitchRetract = data.useSlotSwitchRetract
                autoRecast = data.autoRecast
                recastOnManualRetract = data.recastOnManualRetract
                recastOnServerRemove = data.recastOnServerRemove
                recastDelayTicks = data.recastDelayTicks.coerceIn(1, 20)
                blockManualRetractAfterRecast = data.blockManualRetractAfterRecast
                blockManualRetractTicks = data.blockManualRetractTicks.coerceIn(1, 100)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            save()
        }
    }

    fun save() {
        try {
            configFile.parentFile.mkdirs()
            FileWriter(configFile).use { writer ->
                val data = ConfigData(
                    autoRetractEnabled,
                    retractOnGround,
                    retractOnWall,
                    retractOnWater,
                    retractOnEntity,
                    useSlotSwitchRetract,
                    autoRecast,
                    recastOnManualRetract,
                    recastOnServerRemove,
                    recastDelayTicks,
                    blockManualRetractAfterRecast,
                    blockManualRetractTicks
                )
                gson.toJson(data, writer)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun setAutoRetract(enabled: Boolean) {
        autoRetractEnabled = enabled
        save()
    }

    fun setRetractOnGround(enabled: Boolean) {
        retractOnGround = enabled
        save()
    }

    fun setRetractOnWall(enabled: Boolean) {
        retractOnWall = enabled
        save()
    }

    fun setRetractOnWater(enabled: Boolean) {
        retractOnWater = enabled
        save()
    }

    fun setRetractOnEntity(enabled: Boolean) {
        retractOnEntity = enabled
        save()
    }

    fun setUseSlotSwitchRetract(enabled: Boolean) {
        useSlotSwitchRetract = enabled
        save()
    }

    fun setAutoRecast(enabled: Boolean) {
        autoRecast = enabled
        save()
    }

    fun setRecastOnManualRetract(enabled: Boolean) {
        recastOnManualRetract = enabled
        save()
    }

    fun setRecastOnServerRemove(enabled: Boolean) {
        recastOnServerRemove = enabled
        save()
    }

    fun setRecastDelayTicks(ticks: Int) {
        recastDelayTicks = ticks.coerceIn(1, 20)
        save()
    }

    fun setBlockManualRetractAfterRecast(enabled: Boolean) {
        blockManualRetractAfterRecast = enabled
        save()
    }

    fun setBlockManualRetractTicks(ticks: Int) {
        blockManualRetractTicks = ticks.coerceIn(1, 100)
        save()
    }
}
