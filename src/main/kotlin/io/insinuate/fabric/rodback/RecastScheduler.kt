package io.insinuate.fabric.rodback

import net.minecraft.client.MinecraftClient
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.Items
import net.minecraft.util.Hand
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages delayed recasting of fishing rods to ensure proper client-server synchronization
 */
object RecastScheduler {
    private val LOGGER = LoggerFactory.getLogger("rodback")

    private data class RecastTask(
        val playerUuid: UUID,
        val ticksRemaining: Int,
        val retryCount: Int = 0
    )

    private val pendingRecasts = ConcurrentHashMap<UUID, RecastTask>()

    /**
     * Schedule a recast for a player after a delay
     * @param player The player to recast for
     * @param delayTicks Number of ticks to wait before recasting (default: 3 ticks = ~150ms)
     */
    fun scheduleRecast(player: PlayerEntity, delayTicks: Int = 3) {
        pendingRecasts[player.uuid] = RecastTask(player.uuid, delayTicks)
        LOGGER.info("Scheduled recast for player ${player.name.string} in $delayTicks ticks")
    }

    /**
     * Cancel any pending recast for a player
     */
    fun cancelRecast(player: PlayerEntity) {
        pendingRecasts.remove(player.uuid)
    }

    /**
     * Called every client tick to process pending recasts
     */
    fun tick(player: PlayerEntity) {
        val task = pendingRecasts[player.uuid] ?: return

        if (task.ticksRemaining <= 0) {
            // Time to recast
            val success = executeRecast(player, task.retryCount)
            if (success) {
                pendingRecasts.remove(player.uuid)
            } else {
                // Retry after 2 ticks if failed (max 10 retries = 20 ticks = 1 second)
                if (task.retryCount < 10) {
                    pendingRecasts[player.uuid] = RecastTask(
                        playerUuid = player.uuid,
                        ticksRemaining = 2,
                        retryCount = task.retryCount + 1
                    )
                    // Only log first retry and then every 5th attempt to reduce spam
                    if (task.retryCount == 0 || task.retryCount % 5 == 0) {
                        LOGGER.info("Recast waiting for bobber removal, retrying (attempt ${task.retryCount + 1}/10)")
                    }
                } else {
                    LOGGER.warn("Recast failed after 10 retries, giving up")
                    pendingRecasts.remove(player.uuid)
                }
            }
        } else {
            // Decrement the counter
            pendingRecasts[player.uuid] = task.copy(ticksRemaining = task.ticksRemaining - 1)
        }
    }

    /**
     * Execute the recast action
     * @return true if recast was executed successfully, false if it should be retried
     */
    private fun executeRecast(player: PlayerEntity, retryCount: Int): Boolean {
        // Check if player still has a fishing rod and no bobber
        if (player.fishHook != null) {
            // Bobber still exists, need to wait for server to confirm removal
            if (retryCount == 0) {
                LOGGER.debug("Cannot recast yet: Player still has active fish hook, will retry")
            }
            return false
        }

        val mainHand = player.mainHandStack
        val offHand = player.offHandStack

        val hand = when {
            mainHand.isOf(Items.FISHING_ROD) -> Hand.MAIN_HAND
            offHand.isOf(Items.FISHING_ROD) -> Hand.OFF_HAND
            else -> null
        }

        if (hand == null) {
            LOGGER.debug("Cannot recast: Player doesn't have fishing rod")
            return true // Don't retry if no fishing rod
        }

        // Use interaction manager to send packet to server
        val client = MinecraftClient.getInstance()
        val interactionManager = client.interactionManager
        if (interactionManager == null) {
            LOGGER.warn("Cannot recast: InteractionManager is null")
            return true // Don't retry if interaction manager is null
        }

        // Set RECASTING state to allow immediate recast without delay
        RodBackClient.setRecastingState()

        // Mark as scheduled recast to bypass protection period and state checks
        RodBackClient.beginScheduledRecast()
        try {
            interactionManager.interactItem(player, hand)
            LOGGER.info("Executed recast for player ${player.name.string} (retry: $retryCount), state -> RECASTING")
        } finally {
            // Always clear the flag, even if interactItem throws
            RodBackClient.endScheduledRecast()
        }

        // Start protection period after recast
        RodBackClient.startProtectionPeriod()

        return true
    }

    /**
     * Check if a player has a pending recast
     */
    fun hasPendingRecast(player: PlayerEntity): Boolean {
        return pendingRecasts.containsKey(player.uuid)
    }
}
