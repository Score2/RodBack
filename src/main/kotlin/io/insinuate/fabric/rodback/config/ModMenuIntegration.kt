package io.insinuate.fabric.rodback.config

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi
import me.shedaniel.clothconfig2.api.ConfigBuilder
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text

class ModMenuIntegration : ModMenuApi {
    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> {
        return ConfigScreenFactory { parent -> createConfigScreen(parent) }
    }

    private fun createConfigScreen(parent: Screen?): Screen {
        val builder = ConfigBuilder.create()
            .setParentScreen(parent)
            .setTitle(Text.translatable("config.rodback.title"))

        val general = builder.getOrCreateCategory(Text.translatable("config.rodback.category.general"))
        val retract = builder.getOrCreateCategory(Text.translatable("config.rodback.category.retract"))
        val recast = builder.getOrCreateCategory(Text.translatable("config.rodback.category.recast"))

        val entryBuilder = builder.entryBuilder()

        // General settings
        general.addEntry(
            entryBuilder.startBooleanToggle(
                Text.translatable("config.rodback.auto_retract"),
                ModConfig.autoRetractEnabled
            )
                .setDefaultValue(true)
                .setTooltip(Text.translatable("config.rodback.auto_retract.tooltip"))
                .setSaveConsumer { value ->
                    ModConfig.setAutoRetract(value)
                }
                .build()
        )

        general.addEntry(
            entryBuilder.startBooleanToggle(
                Text.translatable("config.rodback.use_slot_switch"),
                ModConfig.useSlotSwitchRetract
            )
                .setDefaultValue(false)
                .setTooltip(Text.translatable("config.rodback.use_slot_switch.tooltip"))
                .setSaveConsumer { value ->
                    ModConfig.setUseSlotSwitchRetract(value)
                }
                .build()
        )

        // Retract conditions
        retract.addEntry(
            entryBuilder.startBooleanToggle(
                Text.translatable("config.rodback.retract_on_ground"),
                ModConfig.retractOnGround
            )
                .setDefaultValue(true)
                .setTooltip(Text.translatable("config.rodback.retract_on_ground.tooltip"))
                .setSaveConsumer { value ->
                    ModConfig.setRetractOnGround(value)
                }
                .build()
        )

        retract.addEntry(
            entryBuilder.startBooleanToggle(
                Text.translatable("config.rodback.retract_on_wall"),
                ModConfig.retractOnWall
            )
                .setDefaultValue(true)
                .setTooltip(Text.translatable("config.rodback.retract_on_wall.tooltip"))
                .setSaveConsumer { value ->
                    ModConfig.setRetractOnWall(value)
                }
                .build()
        )

        retract.addEntry(
            entryBuilder.startBooleanToggle(
                Text.translatable("config.rodback.retract_on_water"),
                ModConfig.retractOnWater
            )
                .setDefaultValue(false)
                .setTooltip(Text.translatable("config.rodback.retract_on_water.tooltip"))
                .setSaveConsumer { value ->
                    ModConfig.setRetractOnWater(value)
                }
                .build()
        )

        retract.addEntry(
            entryBuilder.startBooleanToggle(
                Text.translatable("config.rodback.retract_on_entity"),
                ModConfig.retractOnEntity
            )
                .setDefaultValue(true)
                .setTooltip(Text.translatable("config.rodback.retract_on_entity.tooltip"))
                .setSaveConsumer { value ->
                    ModConfig.setRetractOnEntity(value)
                }
                .build()
        )

        // Recast settings
        recast.addEntry(
            entryBuilder.startBooleanToggle(
                Text.translatable("config.rodback.auto_recast"),
                ModConfig.autoRecast
            )
                .setDefaultValue(false)
                .setTooltip(Text.translatable("config.rodback.auto_recast.tooltip"))
                .setSaveConsumer { value ->
                    ModConfig.setAutoRecast(value)
                }
                .build()
        )

        recast.addEntry(
            entryBuilder.startBooleanToggle(
                Text.translatable("config.rodback.recast_on_manual"),
                ModConfig.recastOnManualRetract
            )
                .setDefaultValue(false)
                .setTooltip(Text.translatable("config.rodback.recast_on_manual.tooltip"))
                .setSaveConsumer { value ->
                    ModConfig.setRecastOnManualRetract(value)
                }
                .build()
        )

        recast.addEntry(
            entryBuilder.startBooleanToggle(
                Text.translatable("config.rodback.recast_on_server_remove"),
                ModConfig.recastOnServerRemove
            )
                .setDefaultValue(false)
                .setTooltip(Text.translatable("config.rodback.recast_on_server_remove.tooltip"))
                .setSaveConsumer { value ->
                    ModConfig.setRecastOnServerRemove(value)
                }
                .build()
        )

        recast.addEntry(
            entryBuilder.startIntSlider(
                Text.translatable("config.rodback.recast_delay"),
                ModConfig.recastDelayTicks,
                1,
                20
            )
                .setDefaultValue(3)
                .setTooltip(Text.translatable("config.rodback.recast_delay.tooltip"))
                .setSaveConsumer { value ->
                    ModConfig.setRecastDelayTicks(value)
                }
                .build()
        )

        recast.addEntry(
            entryBuilder.startBooleanToggle(
                Text.translatable("config.rodback.block_manual_retract"),
                ModConfig.blockManualRetractAfterRecast
            )
                .setDefaultValue(false)
                .setTooltip(Text.translatable("config.rodback.block_manual_retract.tooltip"))
                .setSaveConsumer { value ->
                    ModConfig.setBlockManualRetractAfterRecast(value)
                }
                .build()
        )

        recast.addEntry(
            entryBuilder.startIntSlider(
                Text.translatable("config.rodback.block_manual_retract_ticks"),
                ModConfig.blockManualRetractTicks,
                1,
                100
            )
                .setDefaultValue(10)
                .setTooltip(Text.translatable("config.rodback.block_manual_retract_ticks.tooltip"))
                .setSaveConsumer { value ->
                    ModConfig.setBlockManualRetractTicks(value)
                }
                .build()
        )

        builder.setSavingRunnable {
            ModConfig.save()
        }

        return builder.build()
    }
}
