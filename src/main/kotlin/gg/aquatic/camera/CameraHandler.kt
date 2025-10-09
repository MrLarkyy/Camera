package gg.aquatic.camera

import gg.aquatic.eventutils.EventUtils
import gg.aquatic.eventutils.event
import gg.aquatic.packetutils.event.PacketReceiveEvent
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket
import net.minecraft.network.protocol.game.ServerboundInteractPacket
import org.bukkit.Bukkit
import org.bukkit.craftbukkit.CraftServer
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.*
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.java.JavaPlugin
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.iterator

object CameraHandler {

    val cameras = ConcurrentHashMap<UUID, Camera>()

    var plugin: JavaPlugin? = null
        private set

    fun initialize(plugin: JavaPlugin) {
        this.plugin = plugin
        if (EventUtils.plugin == null) {
            EventUtils.initialize(plugin)
        }

        event<PacketReceiveEvent>(ignoredCancelled = true) {
            val player = it.player
            when (val packet = it.packet) {
                is ServerboundContainerClickPacket -> {
                    val passenger = findPassenger(player) ?: return@event
                    it.isCancelled = true

                    (Bukkit.getServer() as CraftServer).handle.server.scheduleOnMain {
                        player.closeInventory()
                        player.updateInventory()
                    }
                }

                is ServerboundInteractPacket -> {
                    val (_, passenger) = findPassenger(player) ?: return@event
                    if (!packet.isUsingSecondaryAction) {
                        passenger.onClick(player, true)
                    }
                    it.isCancelled = true
                }
            }
        }

        event<PlayerJoinEvent> {
            for (camera in cameras.values) {
                for ((_, passenger) in camera.passengers) {
                    it.player.hidePlayer(plugin, passenger.player)
                }
            }
        }
        event<PlayerQuitEvent> {
            for (camera in cameras.values) {
                for ((_, passenger) in camera.passengers) {
                    it.player.showPlayer(plugin, passenger.player)
                }
            }
            val player = it.player
            val (camera, passenger) = findPassenger(player) ?: return@event
            passenger.onQuit(player)
            camera.detachPlayer(passenger)
        }
        event<PlayerInteractEvent> {
            val player = it.player
            val (_, passenger) = findPassenger(player) ?: return@event
            it.isCancelled = true
        }
        event<PlayerInteractEntityEvent> {
            val player = it.player
            val (_, passenger) = findPassenger(player) ?: return@event
            it.isCancelled = true
            if (it.hand == EquipmentSlot.OFF_HAND) return@event
            passenger.onClick(it.player, false)
        }
        event<PlayerInteractAtEntityEvent> {
            val player = it.player
            val (_, _) = findPassenger(player) ?: return@event
            it.isCancelled = true
            if (it.hand == EquipmentSlot.OFF_HAND) return@event
        }

        event<EntityDamageByEntityEvent> {
            val player = it.damager as? Player ?: return@event
            val (_, passenger) = findPassenger(player) ?: return@event
            it.isCancelled = true
            passenger.onClick(player, true)
        }
    }

    fun findPassenger(player: Player): Pair<Camera, CameraPassenger>? {
        for ((_, camera) in cameras) {
            return camera to (camera.passengers[player.uniqueId] ?: continue)
        }
        return null
    }

}