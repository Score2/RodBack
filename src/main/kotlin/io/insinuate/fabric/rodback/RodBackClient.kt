package io.insinuate.fabric.rodback

import io.insinuate.fabric.rodback.config.ModConfig
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.entity.projectile.FishingBobberEntity
import net.minecraft.item.Items
import net.minecraft.util.Hand
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object RodBackClient : ClientModInitializer {
    const val MOD_ID = "rodback"
    val LOGGER: Logger = LoggerFactory.getLogger(MOD_ID)

    // Track previous fish hook state to detect server-side removal
    private var previousFishHook: FishingBobberEntity? = null
    private var justRetracted: Boolean = false

    // Track slot switching state
    private var isSlotSwitching: Boolean = false
    private var originalSlot: Int = -1
    private var slotSwitchTicksRemaining: Int = 0

    // Track protection period after recast
    // Note: Protection period feature requires Mixin which has compatibility issues
    // Keeping the config options but not implementing the blocking behavior for now
    private var protectionPeriodTicksRemaining: Int = 0

    /**
     * Retract fishing rod by temporarily switching to another slot
     * This avoids durability loss
     */
    private fun retractBySlotSwitch(client: MinecraftClient) {
        val player = client.player ?: return
        val inventory = player.inventory

        // Save current slot
        originalSlot = inventory.selectedSlot

        // Find a different slot to switch to (prefer next slot)
        val targetSlot = if (originalSlot < 8) originalSlot + 1 else 0

        // Switch to target slot
        inventory.selectedSlot = targetSlot
        isSlotSwitching = true
        slotSwitchTicksRemaining = 2 // Switch back after 2 ticks

        LOGGER.info("Switched from slot $originalSlot to slot $targetSlot to retract without durability loss")
    }

    override fun onInitializeClient() {
        LOGGER.info("Initializing Rod Back client mod")
        ModConfig.load()
        LOGGER.info("Mod enabled: ${ModConfig.modEnabled}")
        LOGGER.info("Auto-retract enabled: ${ModConfig.autoRetractEnabled}")

        // Register client tick event
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            // Check master toggle first
            if (!ModConfig.modEnabled) {
                return@register
            }

            val player = client.player ?: return@register

            // Protection period countdown (feature disabled due to Mixin compatibility issues)
            // if (protectionPeriodTicksRemaining > 0) {
            //     protectionPeriodTicksRemaining--
            // }

            // Handle slot switching countdown
            if (isSlotSwitching && slotSwitchTicksRemaining > 0) {
                slotSwitchTicksRemaining--
                if (slotSwitchTicksRemaining == 0) {
                    // Switch back to original slot
                    if (originalSlot >= 0) {
                        player.inventory.selectedSlot = originalSlot
                        LOGGER.info("Switched back to original slot $originalSlot")
                        originalSlot = -1
                    }
                    isSlotSwitching = false
                }
                return@register // Don't process other logic during slot switching
            }

            if (!ModConfig.autoRetractEnabled) {
                return@register
            }

            val world = client.world ?: return@register

            // Check which hand has the fishing rod
            val mainHand = player.mainHandStack
            val offHand = player.offHandStack
            val hand = when {
                mainHand.isOf(Items.FISHING_ROD) -> Hand.MAIN_HAND
                offHand.isOf(Items.FISHING_ROD) -> Hand.OFF_HAND
                else -> null
            }

            if (hand == null) {
                previousFishHook = null
                return@register
            }

            // Check if player has a fishing bobber
            val fishHook = player.fishHook

            // Detect fish hook removal (server-side or manual)
            if (previousFishHook != null && fishHook == null && !justRetracted) {
                // This could be either server-side removal or manual retract
                // We can't reliably distinguish between them on the client side
                // So we provide two separate options:
                // 1. recastOnServerRemove - for when server forcibly removes the hook
                // 2. recastOnManualRetract - for manual player retraction

                // Since we can't distinguish, we'll treat this as manual retract
                // unless auto-retract is enabled (which means server removal is more likely)
                val likelyManual = !ModConfig.autoRetractEnabled ||
                                   (!ModConfig.retractOnGround && !ModConfig.retractOnWall &&
                                    !ModConfig.retractOnWater && !ModConfig.retractOnEntity)

                if (likelyManual && ModConfig.recastOnManualRetract) {
                    LOGGER.info("Manual retract detected, scheduling recast")
                    RecastScheduler.scheduleRecast(player, delayTicks = ModConfig.recastDelayTicks)
                } else if (ModConfig.recastOnServerRemove) {
                    LOGGER.info("Server removed fish hook, scheduling recast")
                    RecastScheduler.scheduleRecast(player, delayTicks = ModConfig.recastDelayTicks)
                }
            }

            // Reset the justRetracted flag
            justRetracted = false

            if (fishHook != null) {
                val isInWater = fishHook.isTouchingWater

                // Improved wall detection: check if bobber is touching a block on the side
                // A bobber hits a wall if it's on ground but NOT touching water (water = floor/ground fishing)
                // Or if velocity is near zero and it's colliding with blocks
                val velocityLength = fishHook.velocity.length()
                val isStuck = fishHook.isOnGround && velocityLength < 0.01

                // Check block collision for wall detection
                val pos = fishHook.blockPos
                val hasBlockNorth = !world.getBlockState(pos.north()).isAir
                val hasBlockSouth = !world.getBlockState(pos.south()).isAir
                val hasBlockEast = !world.getBlockState(pos.east()).isAir
                val hasBlockWest = !world.getBlockState(pos.west()).isAir
                val hasBlockBelow = !world.getBlockState(pos.down()).isAir
                val hasSideBlock = hasBlockNorth || hasBlockSouth || hasBlockEast || hasBlockWest

                // Consider it a wall hit if:
                // 1. It's stuck (onGround + no movement) AND has blocks on sides AND not in water
                // 2. OR it's inside a wall according to vanilla check
                val hitWall = (isStuck && hasSideBlock && !isInWater) || fishHook.isInsideWall

                LOGGER.info("Fish hook detected: onGround=${fishHook.isOnGround}, insideWall=${fishHook.isInsideWall}, " +
                           "inWater=${isInWater}, velocity=${velocityLength}, stuck=${isStuck}, " +
                           "sideBlocks=${hasSideBlock}, hitWall=${hitWall}, hookedEntity=${fishHook.hookedEntity}")

                var shouldRetract = false
                var retractReason = ""

                // Check if bobber hit ground (floor) - only if in water or no side blocks
                if (fishHook.isOnGround && !hitWall && ModConfig.retractOnGround) {
                    shouldRetract = true
                    retractReason = "ground"
                    LOGGER.info("Bobber hit ground, will retract")
                }

                // Check if bobber hit wall (improved detection)
                if (hitWall && ModConfig.retractOnWall) {
                    shouldRetract = true
                    retractReason = "wall"
                    LOGGER.info("Bobber hit wall, will retract")
                }

                // Check if bobber is in water
                if (isInWater && ModConfig.retractOnWater) {
                    shouldRetract = true
                    retractReason = "water"
                    LOGGER.info("Bobber in water, will retract")
                }

                // Check if bobber hooked an entity
                if (fishHook.hookedEntity != null && ModConfig.retractOnEntity) {
                    shouldRetract = true
                    retractReason = "entity"
                    LOGGER.info("Bobber hooked entity, will retract")
                }

                if (shouldRetract) {
                    // Choose retract method based on config
                    if (ModConfig.useSlotSwitchRetract && hand == Hand.MAIN_HAND) {
                        // Use slot switching to avoid durability loss
                        retractBySlotSwitch(client)
                        LOGGER.info("Auto-retracted fishing rod via slot switch (reason: $retractReason)")
                        justRetracted = true

                        // Schedule recast if auto recast is enabled
                        if (ModConfig.autoRecast) {
                            RecastScheduler.scheduleRecast(player, delayTicks = ModConfig.recastDelayTicks)
                        }
                    } else {
                        // Use normal right-click method (consumes durability)
                        val interactionManager = client.interactionManager
                        if (interactionManager != null) {
                            interactionManager.interactItem(player, hand)
                            LOGGER.info("Auto-retracted fishing rod via interactItem (reason: $retractReason)")
                            justRetracted = true

                            // Schedule recast if auto recast is enabled
                            if (ModConfig.autoRecast) {
                                RecastScheduler.scheduleRecast(player, delayTicks = ModConfig.recastDelayTicks)
                            }
                        } else {
                            LOGGER.warn("InteractionManager is null, cannot retract")
                        }
                    }
                }
            }

            // Update previous fish hook reference
            previousFishHook = fishHook

            // Process pending recasts
            RecastScheduler.tick(player)
        }
    }
}
