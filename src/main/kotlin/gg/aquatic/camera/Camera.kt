package gg.aquatic.camera

import com.destroystokyo.paper.profile.PlayerProfile
import gg.aquatic.packetutils.Packet
import gg.aquatic.packetutils.Packet.sendPacket
import gg.aquatic.packetutils.PacketEntity
import gg.aquatic.packetutils.PacketEntityData
import gg.aquatic.packetutils.profile.ProfileEntry
import gg.aquatic.packetutils.profile.UserProfile
import net.minecraft.network.protocol.game.ClientboundGameEventPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.craftbukkit.CraftServer
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class Camera(
    location: Location,
    val updateYaw: Boolean = false
) {

    val uuid: UUID = UUID.randomUUID()
    val packetEntity = PacketEntity.create(location, EntityType.BLOCK_DISPLAY, uuid)!!
    val horseEntity = if (updateYaw) PacketEntity.create(location, EntityType.HORSE) else null

    val passengers = ConcurrentHashMap<UUID, CameraPassenger>()
    private var updatePacket: ClientboundSetEntityDataPacket? = null

    @Volatile
    var registered = false

    init {
        horseEntity?.apply {
            val data = generateHorseVisuals()
            val packet = Packet.setEntityData(entityId, listOf(data))
            updatePacket = packet
        }
    }

    private fun generateHorseVisuals(): SynchedEntityData.DataValue<*> {
        val result = (0 or 0x20).toByte()
        return SynchedEntityData.DataValue(0, EntityDataSerializers.BYTE, result)
    }
    fun attachPlayer(
        player: Player,
        onClick: (player: Player, isLeft: Boolean) -> Unit,
        onQuit: (player: Player) -> Unit = {}
    ): CameraPassenger {
        val passenger = if (passengers.containsKey(player.uniqueId)) {
            val previous = passengers[player.uniqueId]!!
            CameraPassenger(
                player.uniqueId,
                previous.respawnLocation,
                previous.previousGameMode,
                previous.wasFlying,
                onClick,
                onQuit
            )
        } else {
            CameraPassenger(
                player.uniqueId,
                player.location,
                player.gameMode,
                player.isFlying,
                onClick
            )
        }
        attachPlayer(passenger)
        return passenger
    }

    var location: Location = location
        private set

    var teleportInterpolation: Int = 0
        set(value) {
            field = value
            val teleportInterpolationData = PacketEntityData.displayTeleportInterpolation(value)
            updatePacket = Packet.setEntityData(packetEntity.entityId, listOf(teleportInterpolationData))
            updateCameraData()
        }

    private fun updateCameraData() {
        updatePacket?.let { packet ->
            passengers.values.forEach {
                it.player.sendPacket(packet, true)
            }
        }
    }

    fun teleport(location: Location) {
        if (location.world != this.location.world) {
            throw Exception("Cannot teleport to a different world")
        }
        updateCameraLocation(location)
    }

    private fun hideFromPlayers(passenger: Player) {
        for (player in Bukkit.getOnlinePlayers()) {
            player.hidePlayer(CameraHandler.plugin!!, passenger)
        }
    }

    private fun showToPlayers(passenger: Player) {
        for (player in Bukkit.getOnlinePlayers()) {
            player.showPlayer(CameraHandler.plugin!!, passenger)
        }
    }

    private fun updateCameraLocation(location: Location) {
        packetEntity.teleport(location, *passengers.values.map { it.player }.toTypedArray())
        this.location = location
    }

    private fun attachPlayer(passenger: CameraPassenger) {
        if (!registered) {
            register()
        }
        val player = passenger.player

        //val bundlePacket = Waves.NMS_HANDLER.createBundlePacket(packets)

        val wasPassenger = passengers.containsKey(passenger.playerUUID)
        if (!wasPassenger) {
            passengers[player.uniqueId] = passenger
        }

        val delay: Long = if (player.location.world == location.world) 0 else 5
        player.teleportAsync(location).thenAccept {
            (Bukkit.getServer() as CraftServer).handle.server.scheduleOnMain {
                hideFromPlayers(player)
                player.allowFlight = true
                player.isFlying = true
                player.isInvisible = true
                player.gameMode = GameMode.SPECTATOR
            }

            Bukkit.getScheduler().runTaskLater(CameraHandler.plugin!!, Runnable {
                if (!wasPassenger) {
                    val spawnPacket = packetEntity.spawnPacket
                    player.sendPacket(spawnPacket)
                    updatePacket?.let { player.sendPacket(it) }
                }
                // INFO UPDATE
                val infoPacket = Packet.updatePlayerInfo(
                    2, ProfileEntry(
                        player.playerProfile.toUserProfile(),
                        true,
                        0,
                        GameMode.CREATIVE,
                        null,
                        true,
                        player.playerListOrder
                    )
                )
                player.sendPacket(infoPacket, true)

                // PASSENGERS - FOR YAW MOVEMENT
                if (updateYaw) {
                    val horseEntity = horseEntity!!
                    if (!wasPassenger) {
                        horseEntity.sendSpawnComplete(player)
                    }
                    val passengersPacket = Packet.setPassengers(
                        horseEntity.entityId,
                        intArrayOf(passenger.player.entityId)
                    )
                    player.sendPacket(passengersPacket)
                }

                // GAMEMODE UPDATE
                val gamemodePacket = Packet.changeGameState(
                    ClientboundGameEventPacket.CHANGE_GAME_MODE,
                    GameMode.SPECTATOR.value.toFloat()
                )
                player.sendPacket(gamemodePacket, true)
                // SPECTATE
                val cameraPacket = Packet.setCamera(packetEntity.entityId)
                player.sendPacket(cameraPacket)
            }, delay)
        }
    }

    fun detachPlayer(player: Player) {
        detachPlayer(passengers[player.uniqueId] ?: return)
    }

    fun detachPlayer(passenger: CameraPassenger) {
        if (!passengers.containsKey(passenger.playerUUID)) return
        try {
            val player = passenger.player
            val gameEventPacket = Packet.changeGameState(
                ClientboundGameEventPacket.CHANGE_GAME_MODE,
                passenger.previousGameMode.value.toFloat()
            )
            val cameraPacket = Packet.setCamera(player.entityId)
            player.sendPacket(gameEventPacket)
            player.sendPacket(cameraPacket)

            packetEntity.destroy(player)

            if (updateYaw) {
                val horseEntity = horseEntity!!
                val passengersPacket = Packet.setPassengers(
                    horseEntity.entityId,
                    intArrayOf()
                )
                player.sendPacket(passengersPacket)
                horseEntity.destroy(player)
            }

            val runnable = {
                if (!passenger.wasFlying) {
                    player.isFlying = false
                    player.allowFlight = false
                }
                showToPlayers(player)
                player.gameMode = passenger.previousGameMode
                player.isInvisible = false
                player.teleport(passenger.respawnLocation)
            }
            if (Bukkit.isPrimaryThread()) {
                runnable()
            } else {
                (Bukkit.getServer() as CraftServer).handle.server.scheduleOnMain {
                    runnable()
                }
            }
            passengers.remove(player.uniqueId)
            if (passengers.isEmpty()) {
                unregister()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun PlayerProfile.toUserProfile(): UserProfile {
        val profile = UserProfile(
            this.id ?: UUID.randomUUID(),
            this.name ?: "",
            this.properties.map {
                UserProfile.TextureProperty(it.name, it.value, it.signature ?: "")
            }.toMutableList()
        )
        return profile
    }

    fun destroy() {
        for (passenger in passengers.values.toList()) {
            detachPlayer(passenger)
        }
        passengers.clear()
        unregister()
    }

    private fun register() {
        registered = true
        CameraHandler.cameras[uuid] = this
    }

    private fun unregister() {
        registered = false
        CameraHandler.cameras -= uuid
    }

}