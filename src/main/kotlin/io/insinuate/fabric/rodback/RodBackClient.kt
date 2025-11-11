package io.insinuate.fabric.rodback

import io.insinuate.fabric.rodback.config.ModConfig
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import net.minecraft.entity.projectile.FishingBobberEntity
import net.minecraft.item.Items
import net.minecraft.util.ActionResult
import net.minecraft.util.Hand
import net.minecraft.util.TypedActionResult
import org.lwjgl.glfw.GLFW
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object RodBackClient : ClientModInitializer {
    const val MOD_ID = "rodback"
    val LOGGER: Logger = LoggerFactory.getLogger(MOD_ID)

    // Keybinding for quick fishing rod cast
    private val quickCastKey: KeyBinding = KeyBindingHelper.registerKeyBinding(
        KeyBinding(
            "key.rodback.quick_cast",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_R,
            "category.rodback.keybindings"
        )
    )

    // Track previous fish hook state to detect server-side removal
    private var previousFishHook: FishingBobberEntity? = null
    private var justRetracted: Boolean = false

    // Track slot switching state
    private var isSlotSwitching: Boolean = false
    private var originalSlot: Int = -1
    private var slotSwitchTicksRemaining: Int = 0

    // Track protection period after recast
    private var protectionPeriodTicksRemaining: Int = 0

    // Track if currently executing a scheduled recast (to bypass protection period)
    private var isExecutingScheduledRecast: Boolean = false

    // Track server delay handling
    private enum class FishingState {
        IDLE,           // No fishing rod in use, ready for any action
        CASTING,        // Cast action sent, waiting for server to spawn bobber
        ACTIVE,         // Bobber active and confirmed by server
        RETRACTING,     // Retract action sent, waiting for server to remove bobber
        RECASTING       // Recast scheduled/in progress, can proceed immediately
    }

    private var fishingState: FishingState = FishingState.IDLE
    private var waitingForServerTicks: Int = 0
    private const val MAX_SERVER_WAIT_TICKS: Int = 40 // 2 seconds timeout

    /**
     * Check if currently in protection period (blocking manual retract)
     */
    fun isInProtectionPeriod(): Boolean {
        return ModConfig.blockManualRetractAfterRecast && protectionPeriodTicksRemaining > 0
    }

    /**
     * Start protection period after recast
     */
    fun startProtectionPeriod() {
        if (ModConfig.blockManualRetractAfterRecast) {
            protectionPeriodTicksRemaining = ModConfig.blockManualRetractTicks
            LOGGER.info("Started protection period for ${ModConfig.blockManualRetractTicks} ticks")
        }
    }

    /**
     * Set the fishing state to RECASTING (for scheduled recasts)
     */
    fun setRecastingState() {
        fishingState = FishingState.RECASTING
        waitingForServerTicks = 0
    }

    /**
     * Mark that a scheduled recast is being executed (to bypass protection period)
     */
    fun beginScheduledRecast() {
        isExecutingScheduledRecast = true
    }

    /**
     * Mark that the scheduled recast execution is complete
     */
    fun endScheduledRecast() {
        isExecutingScheduledRecast = false
    }

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

    /**
     * Handle quick cast keybinding - find fishing rod in hotbar and cast it
     * If already holding a fishing rod with a bobber, retract and recast
     */
    private fun handleQuickCast(client: MinecraftClient) {
        val player = client.player ?: return
        val inventory = player.inventory

        // Don't quick cast if waiting for server response
        if (fishingState == FishingState.CASTING || fishingState == FishingState.RETRACTING) {
            LOGGER.info("Quick cast blocked: waiting for server response (state: $fishingState)")
            return
        }

        val currentSlot = inventory.selectedSlot
        val currentStack = inventory.getStack(currentSlot)

        // Check if currently holding a fishing rod
        if (currentStack.isOf(Items.FISHING_ROD)) {
            val hasBobber = player.fishHook != null
            val interactionManager = client.interactionManager

            if (interactionManager != null) {
                val hand = Hand.MAIN_HAND

                if (hasBobber) {
                    // Already has bobber out, retract it first and schedule recast
                    interactionManager.interactItem(player, hand)
                    LOGGER.info("Quick cast: retracting existing bobber for recast")

                    // Update state to RETRACTING
                    fishingState = FishingState.RETRACTING
                    waitingForServerTicks = 0

                    // Schedule immediate recast (1 tick delay to ensure retract completes)
                    RecastScheduler.scheduleRecast(player, delayTicks = 1)
                } else {
                    // No bobber, just cast
                    interactionManager.interactItem(player, hand)
                    LOGGER.info("Quick cast: casting fishing rod from current hand")

                    // Update state
                    fishingState = FishingState.CASTING
                    waitingForServerTicks = 0
                }
            }
            return
        }

        // Not holding fishing rod, find it in hotbar (slots 0-8)
        var rodSlot = -1
        for (i in 0..8) {
            val stack = inventory.getStack(i)
            if (stack.isOf(Items.FISHING_ROD)) {
                rodSlot = i
                break
            }
        }

        if (rodSlot == -1) {
            LOGGER.info("Quick cast failed: no fishing rod found in hotbar")
            return
        }

        // Switch to fishing rod slot
        inventory.selectedSlot = rodSlot
        LOGGER.info("Quick cast: switched to fishing rod in slot $rodSlot")

        // Cast the fishing rod (note: bobber cannot exist if we just switched)
        val interactionManager = client.interactionManager
        if (interactionManager != null) {
            val hand = Hand.MAIN_HAND
            interactionManager.interactItem(player, hand)
            LOGGER.info("Quick cast: casting fishing rod after slot switch")

            // Update state
            fishingState = FishingState.CASTING
            waitingForServerTicks = 0
        }
    }

    override fun onInitializeClient() {
        LOGGER.info("Initializing Rod Back client mod")
        ModConfig.load()
        LOGGER.info("Mod enabled: ${ModConfig.modEnabled}")
        LOGGER.info("Auto-retract enabled: ${ModConfig.autoRetractEnabled}")

        // Register use item callback to block manual retraction during protection period
        // and prevent repeated actions while waiting for server response
        UseItemCallback.EVENT.register { player, _, hand ->
            val itemStack = player.getStackInHand(hand)

            // Only intercept fishing rod usage
            if (!itemStack.isOf(Items.FISHING_ROD)) {
                return@register TypedActionResult.pass(itemStack)
            }

            // Block if in protection period (but allow scheduled recasts to bypass)
            if (isInProtectionPeriod() && !isExecutingScheduledRecast) {
                LOGGER.info("Blocked manual fishing rod use during protection period (${protectionPeriodTicksRemaining} ticks remaining)")
                return@register TypedActionResult.fail(itemStack)
            }

            // Block if waiting for server response (except during recast which can proceed immediately)
            // Also allow scheduled recasts to bypass this check
            if ((fishingState == FishingState.CASTING || fishingState == FishingState.RETRACTING) && !isExecutingScheduledRecast) {
                LOGGER.info("Blocked fishing rod use while waiting for server response (state: $fishingState, waiting: ${waitingForServerTicks} ticks)")
                return@register TypedActionResult.fail(itemStack)
            }

            // Allow the action and update state
            val hasBobber = player.fishHook != null
            if (hasBobber) {
                // Retracting
                fishingState = FishingState.RETRACTING
                waitingForServerTicks = 0
                LOGGER.info("Player initiating retract, state -> RETRACTING")
            } else {
                // Casting (or recasting)
                if (fishingState == FishingState.RECASTING) {
                    // Recasting can proceed immediately without delay
                    LOGGER.info("Player recasting, state -> CASTING (immediate)")
                } else {
                    LOGGER.info("Player initiating cast, state -> CASTING")
                }
                fishingState = FishingState.CASTING
                waitingForServerTicks = 0
            }

            TypedActionResult.pass(itemStack)
        }

        // Register client tick event
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            // Check master toggle first
            if (!ModConfig.modEnabled) {
                return@register
            }

            val player = client.player ?: return@register

            // Handle quick cast keybinding
            if (quickCastKey.wasPressed()) {
                handleQuickCast(client)
            }

            // Handle protection period countdown
            if (protectionPeriodTicksRemaining > 0) {
                protectionPeriodTicksRemaining--
                if (protectionPeriodTicksRemaining == 0) {
                    LOGGER.info("Protection period ended, manual retract now allowed")
                }
            }

            // Update fishing state based on server response
            val currentBobber = player.fishHook
            when (fishingState) {
                FishingState.CASTING -> {
                    if (currentBobber != null) {
                        // Server confirmed bobber spawn
                        fishingState = FishingState.ACTIVE
                        waitingForServerTicks = 0
                        LOGGER.info("Server confirmed bobber spawn, state -> ACTIVE")
                    } else {
                        waitingForServerTicks++
                        if (waitingForServerTicks >= MAX_SERVER_WAIT_TICKS) {
                            LOGGER.warn("Timeout waiting for bobber spawn, state -> IDLE")
                            fishingState = FishingState.IDLE
                            waitingForServerTicks = 0
                        }
                    }
                }
                FishingState.RETRACTING -> {
                    if (currentBobber == null) {
                        // Server confirmed bobber removal
                        fishingState = FishingState.IDLE
                        waitingForServerTicks = 0
                        LOGGER.info("Server confirmed bobber removal, state -> IDLE")
                    } else {
                        waitingForServerTicks++
                        if (waitingForServerTicks >= MAX_SERVER_WAIT_TICKS) {
                            // Timeout - check if we have pending recast
                            if (RecastScheduler.hasPendingRecast(player)) {
                                // Have pending recast, assume bobber will be removed and go to IDLE
                                LOGGER.warn("Timeout waiting for bobber removal (with pending recast), forcing state -> IDLE")
                                fishingState = FishingState.IDLE
                            } else {
                                // No pending recast, bobber is likely still active
                                LOGGER.warn("Timeout waiting for bobber removal, state -> ACTIVE")
                                fishingState = FishingState.ACTIVE
                            }
                            waitingForServerTicks = 0
                        }
                    }
                }
                FishingState.ACTIVE -> {
                    if (currentBobber == null) {
                        // Bobber was removed (manual or server-side)
                        fishingState = FishingState.IDLE
                        LOGGER.info("Bobber removed unexpectedly, state -> IDLE")
                    }
                }
                FishingState.RECASTING -> {
                    // Recasting state is temporary, will transition via UseItemCallback
                    if (currentBobber != null) {
                        fishingState = FishingState.ACTIVE
                        LOGGER.info("Recast complete, state -> ACTIVE")
                    }
                }
                FishingState.IDLE -> {
                    // Check if bobber appeared without us knowing (shouldn't happen normally)
                    if (currentBobber != null) {
                        fishingState = FishingState.ACTIVE
                        LOGGER.info("Bobber detected while IDLE, state -> ACTIVE")
                    }
                }
            }

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

            // Don't auto-retract if we're waiting for server response
            if (fishingState == FishingState.CASTING || fishingState == FishingState.RETRACTING) {
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
                        // For slot switch, manually set state since UseItemCallback won't be triggered
                        fishingState = FishingState.RETRACTING
                        waitingForServerTicks = 0

                        retractBySlotSwitch(client)
                        LOGGER.info("Auto-retracted fishing rod via slot switch (reason: $retractReason), state -> RETRACTING")
                        justRetracted = true

                        // Schedule recast if auto recast is enabled
                        if (ModConfig.autoRecast) {
                            RecastScheduler.scheduleRecast(player, delayTicks = ModConfig.recastDelayTicks)
                        }
                    } else {
                        // Use normal right-click method (consumes durability)
                        // Don't set state before calling interactItem - let UseItemCallback handle it
                        val interactionManager = client.interactionManager
                        if (interactionManager != null) {
                            // interactItem will trigger UseItemCallback which updates state to RETRACTING
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
