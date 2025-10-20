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
        val ticksRemaining: Int
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
            executeRecast(player)
            pendingRecasts.remove(player.uuid)
        } else {
            // Decrement the counter
            pendingRecasts[player.uuid] = task.copy(ticksRemaining = task.ticksRemaining - 1)
        }
    }

    /**
     * Execute the recast action
     */
    private fun executeRecast(player: PlayerEntity) {
        // Check if player still has a fishing rod and no bobber
        if (player.fishHook == null) {
            val mainHand = player.mainHandStack
            val offHand = player.offHandStack

            val hand = when {
                mainHand.isOf(Items.FISHING_ROD) -> Hand.MAIN_HAND
                offHand.isOf(Items.FISHING_ROD) -> Hand.OFF_HAND
                else -> null
            }

            if (hand != null) {
                // Use interaction manager to send packet to server
                val client = MinecraftClient.getInstance()
                val interactionManager = client.interactionManager
                if (interactionManager != null) {
                    interactionManager.interactItem(player, hand)
                    LOGGER.info("Executed recast for player ${player.name.string}")

                    // Protection period feature disabled due to Mixin compatibility issues
                    // RodBackClient.startProtectionPeriod()
                } else {
                    LOGGER.warn("Cannot recast: InteractionManager is null")
                }
            } else {
                LOGGER.debug("Cannot recast: Player doesn't have fishing rod")
            }
        } else {
            LOGGER.debug("Cannot recast: Player still has active fish hook")
        }
    }

    /**
     * Check if a player has a pending recast
     */
    fun hasPendingRecast(player: PlayerEntity): Boolean {
        return pendingRecasts.containsKey(player.uuid)
    }
}
