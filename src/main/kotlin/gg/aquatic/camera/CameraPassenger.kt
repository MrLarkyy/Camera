package gg.aquatic.camera

import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.Player
import java.util.*

class CameraPassenger(
    val playerUUID: UUID,
    var respawnLocation: Location,
    val previousGameMode: GameMode,
    val wasFlying: Boolean,
    val onClick: (player: Player, isLeft: Boolean) -> Unit,
    val onQuit: (player: Player) -> Unit = {}
) {

    val player: Player
        get() {
            return Bukkit.getPlayer(playerUUID)!!
        }
}