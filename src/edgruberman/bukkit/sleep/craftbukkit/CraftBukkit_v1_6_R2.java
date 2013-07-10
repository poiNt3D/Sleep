package edgruberman.bukkit.sleep.craftbukkit;

import net.minecraft.server.v1_6_R2.ChunkCoordinates;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.craftbukkit.v1_6_R2.CraftWorld;
import org.bukkit.craftbukkit.v1_6_R2.entity.CraftPlayer;
import org.bukkit.entity.Player;

public class CraftBukkit_v1_6_R2 extends CraftBukkit {

    @Override
    public void wakeUpPlayer(final Player player) {
        final CraftPlayer cp = (CraftPlayer) player;
        cp.getHandle().a(true, true, true); // reset sleep ticks, update sleeper status for world, set as current bed spawn
    }

    @Override
    public Location getBedLocation(final Player player) {
        final CraftPlayer cp = (CraftPlayer) player;

        final World world = Bukkit.getServer().getWorld(cp.getHandle().spawnWorld);
        if (world == null) return null;

        final ChunkCoordinates bed = cp.getHandle().getBed();
        if (bed == null) return null;

        final int id = ( cp.getHandle().isRespawnForced() ? Material.BED_BLOCK.getId() : CraftBukkit.getBlockTypeIdAt(world, bed.x, bed.y, bed.z) );
        if (id != Material.BED_BLOCK.getId()) return null;

        return new Location(world, bed.x, bed.y, bed.z);
    }

    @Override
    public boolean isDaytime(final World world) {
        final CraftWorld cworld = (CraftWorld) world;
        return cworld.getHandle().v();
    }

}