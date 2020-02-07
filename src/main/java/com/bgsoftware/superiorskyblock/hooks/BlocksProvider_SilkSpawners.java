package com.bgsoftware.superiorskyblock.hooks;

import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.objects.Pair;
import com.bgsoftware.superiorskyblock.api.key.Key;
import com.bgsoftware.superiorskyblock.utils.legacy.Materials;
import de.candc.events.SpawnerBreakEvent;
import de.candc.events.SpawnerPlaceEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

public final class BlocksProvider_SilkSpawners implements BlocksProvider {

    private static boolean registered = false;

    public BlocksProvider_SilkSpawners(){
        if(!registered) {
            Bukkit.getPluginManager().registerEvents(new BlocksProvider_SilkSpawners.StackerListener(), SuperiorSkyblockPlugin.getPlugin());
            registered = true;
        }
    }

    @Override
    public Pair<Integer, EntityType> getSpawner(Location location) {
        return new Pair<>(1, null);
    }

    @Override
    public EntityType getSpawnerType(ItemStack itemStack) {
        String name = itemStack.getItemMeta().getLore().get(0).replaceAll("§e", "");
        return EntityType.fromName(name);
    }

    @SuppressWarnings("unused")
    private static class StackerListener implements Listener {

        private final SuperiorSkyblockPlugin plugin = SuperiorSkyblockPlugin.getPlugin();

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onSpawnerPlace(SpawnerPlaceEvent e){
            Island island = plugin.getGrid().getIslandAt(e.getSpawner().getLocation());
            if(island != null)
                island.handleBlockPlace(Key.of(Materials.SPAWNER.toBukkitType() + ":" + e.getSpawnedEntity()), 1);
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onSpawnerUnstack(SpawnerBreakEvent e){
            Island island = plugin.getGrid().getIslandAt(e.getSpawner().getLocation());
            if(island != null)
                island.handleBlockBreak(Key.of(Materials.SPAWNER.toBukkitType() + ":" + e.getSpawnedEntity()), 1);
        }

    }

}
