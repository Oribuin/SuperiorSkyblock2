package com.bgsoftware.superiorskyblock.island;

import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.enums.Rating;
import com.bgsoftware.superiorskyblock.api.events.IslandTransferEvent;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.island.IslandPermission;
import com.bgsoftware.superiorskyblock.api.island.IslandSettings;
import com.bgsoftware.superiorskyblock.api.island.PlayerRole;
import com.bgsoftware.superiorskyblock.api.key.Key;
import com.bgsoftware.superiorskyblock.api.missions.Mission;
import com.bgsoftware.superiorskyblock.api.wrappers.BlockPosition;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.bgsoftware.superiorskyblock.Locale;
import com.bgsoftware.superiorskyblock.hooks.PaperHook;
import com.bgsoftware.superiorskyblock.menu.MenuUniqueVisitors;
import com.bgsoftware.superiorskyblock.utils.database.CachedResultSet;
import com.bgsoftware.superiorskyblock.utils.database.DatabaseObject;
import com.bgsoftware.superiorskyblock.utils.database.Query;
import com.bgsoftware.superiorskyblock.handlers.MissionsHandler;
import com.bgsoftware.superiorskyblock.hooks.BlocksProvider_WildStacker;
import com.bgsoftware.superiorskyblock.api.events.IslandWorthCalculatedEvent;
import com.bgsoftware.superiorskyblock.menu.MenuGlobalWarps;
import com.bgsoftware.superiorskyblock.menu.MenuIslandMissions;
import com.bgsoftware.superiorskyblock.menu.MenuIslandRatings;
import com.bgsoftware.superiorskyblock.menu.MenuMemberManage;
import com.bgsoftware.superiorskyblock.menu.MenuMemberRole;
import com.bgsoftware.superiorskyblock.menu.MenuMembers;
import com.bgsoftware.superiorskyblock.menu.MenuPermissions;
import com.bgsoftware.superiorskyblock.menu.MenuSettings;
import com.bgsoftware.superiorskyblock.menu.MenuUpgrades;
import com.bgsoftware.superiorskyblock.menu.MenuValues;
import com.bgsoftware.superiorskyblock.menu.MenuVisitors;
import com.bgsoftware.superiorskyblock.menu.MenuWarps;
import com.bgsoftware.superiorskyblock.utils.BigDecimalFormatted;
import com.bgsoftware.superiorskyblock.utils.StringUtils;
import com.bgsoftware.superiorskyblock.utils.islands.IslandDeserializer;
import com.bgsoftware.superiorskyblock.utils.islands.IslandSerializer;
import com.bgsoftware.superiorskyblock.utils.LocationUtils;
import com.bgsoftware.superiorskyblock.api.objects.Pair;
import com.bgsoftware.superiorskyblock.utils.islands.SortingComparators;
import com.bgsoftware.superiorskyblock.utils.legacy.Materials;
import com.bgsoftware.superiorskyblock.utils.queue.Queue;
import com.bgsoftware.superiorskyblock.utils.key.KeyMap;
import com.bgsoftware.superiorskyblock.utils.threads.Executor;
import com.bgsoftware.superiorskyblock.utils.threads.SyncedObject;
import com.bgsoftware.superiorskyblock.wrappers.SBlockPosition;
import com.bgsoftware.superiorskyblock.wrappers.SSuperiorPlayer;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.WeatherType;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.EntityType;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

@SuppressWarnings("unused")
public final class SIsland extends DatabaseObject implements Island {

    public static final String VISITORS_WARP_NAME = "visit";
    private static final int NO_BLOCK_LIMIT = -1;

    protected static SuperiorSkyblockPlugin plugin = SuperiorSkyblockPlugin.getPlugin();

    private static boolean calcProcess = false;
    private static Queue<CalcIslandData> islandCalcsQueue = new Queue<>();

    /*
     * Island identifiers
     */
    private SuperiorPlayer owner;
    private final BlockPosition center;

    /*
     * Island flags
     */

    private final SyncedObject<Boolean> beingRecalculated = SyncedObject.of(false);

    /*
     * Island data
     */

    private final SyncedObject<PriorityQueue<SuperiorPlayer>> members = SyncedObject.of(new PriorityQueue<>(SortingComparators.ISLAND_MEMBERS_COMPARATOR));
    private final SyncedObject<PriorityQueue<SuperiorPlayer>> playersInside = SyncedObject.of(new PriorityQueue<>(SortingComparators.PLAYER_NAMES_COMPARATOR));
    private final SyncedObject<PriorityQueue<SuperiorPlayer>> uniqueVisitors = SyncedObject.of(new PriorityQueue<>(SortingComparators.PLAYER_NAMES_COMPARATOR));
    private final SyncedObject<Set<SuperiorPlayer>> banned = SyncedObject.of(new HashSet<>());
    private final SyncedObject<Set<SuperiorPlayer>> coop = SyncedObject.of(new HashSet<>());
    private final SyncedObject<Set<SuperiorPlayer>> invitedPlayers = SyncedObject.of(new HashSet<>());
    private final SyncedObject<Map<Object, SPermissionNode>> permissionNodes = SyncedObject.of(new HashMap<>());
    private final SyncedObject<Map<String, Integer>> cobbleGenerator = SyncedObject.of(new HashMap<>());
    private final SyncedObject<Set<IslandSettings>> islandSettings = SyncedObject.of(new HashSet<>());
    private final SyncedObject<Map<String, Integer>> upgrades = SyncedObject.of(new HashMap<>());
    private final SyncedObject<KeyMap<Integer>> blockCounts = SyncedObject.of(new KeyMap<>());
    private final SyncedObject<KeyMap<Integer>> blockLimits = SyncedObject.of(new KeyMap<>(plugin.getSettings().defaultBlockLimits));
    private final SyncedObject<Map<String, WarpData>> warps = SyncedObject.of(new HashMap<>());
    private final SyncedObject<BigDecimalFormatted> islandBank = SyncedObject.of(BigDecimalFormatted.ZERO);
    private final SyncedObject<BigDecimalFormatted> islandWorth = SyncedObject.of(BigDecimalFormatted.ZERO);
    private final SyncedObject<BigDecimalFormatted> islandLevel = SyncedObject.of(BigDecimalFormatted.ZERO);
    private final SyncedObject<BigDecimalFormatted> bonusWorth = SyncedObject.of(BigDecimalFormatted.ZERO);
    private final SyncedObject<String> discord = SyncedObject.of("None");
    private final SyncedObject<String> paypal = SyncedObject.of("None");
    private final SyncedObject<Integer> islandSize = SyncedObject.of(plugin.getSettings().defaultIslandSize);
    private final SyncedObject<Map<World.Environment, Location>> teleportLocations = SyncedObject.of(new HashMap<>());
    private final SyncedObject<Location> visitorsLocation = SyncedObject.of(null);
    private final SyncedObject<Boolean> locked = SyncedObject.of(false);
    private final SyncedObject<String> islandName = SyncedObject.of("");
    private final SyncedObject<String> description = SyncedObject.of("");
    private final SyncedObject<Map<UUID, Rating>> ratings = SyncedObject.of(new HashMap<>());
    private final SyncedObject<Map<Mission, Integer>> completedMissions = SyncedObject.of(new HashMap<>());
    private final SyncedObject<Biome> biome = SyncedObject.of(null);
    private final SyncedObject<Boolean> ignored = SyncedObject.of(false);
    private final SyncedObject<String> generatedSchematics = SyncedObject.of("normal");
    private final SyncedObject<String> schemName = SyncedObject.of("");
    private final SyncedObject<String> unlockedWorlds = SyncedObject.of("");

    /*
     * Island multipliers & limits
     */

    private final SyncedObject<Integer> warpsLimit = SyncedObject.of(plugin.getSettings().defaultWarpsLimit);
    private final SyncedObject<Integer> teamLimit = SyncedObject.of(plugin.getSettings().defaultTeamLimit);
    private final SyncedObject<Double> cropGrowth = SyncedObject.of((double) plugin.getSettings().defaultCropGrowth);
    private final SyncedObject<Double> spawnerRates = SyncedObject.of((double) plugin.getSettings().defaultSpawnerRates);
    private final SyncedObject<Double> mobDrops = SyncedObject.of((double) plugin.getSettings().defaultMobDrops);

    public SIsland(CachedResultSet resultSet){
        this.owner = SSuperiorPlayer.of(UUID.fromString(resultSet.getString("owner")));

        this.center = SBlockPosition.of(Objects.requireNonNull(LocationUtils.getLocation(resultSet.getString("center"))));
        this.visitorsLocation.set(LocationUtils.getLocation(resultSet.getString("visitorsLocation")));

        IslandDeserializer.deserializeLocations(resultSet.getString("teleportLocation"), this.teleportLocations);
        IslandDeserializer.deserializePlayers(resultSet.getString("members"), this.members);
        IslandDeserializer.deserializePlayers(resultSet.getString("banned"), this.banned);
        IslandDeserializer.deserializePermissions(resultSet.getString("permissionNodes"), this.permissionNodes);
        IslandDeserializer.deserializeUpgrades(resultSet.getString("upgrades"), this.upgrades);
        IslandDeserializer.deserializeWarps(resultSet.getString("warps"), this.warps);
        IslandDeserializer.deserializeBlockCounts(resultSet.getString("blockCounts"), this);
        IslandDeserializer.deserializeBlockLimits(resultSet.getString("blockLimits"), this.blockLimits);
        IslandDeserializer.deserializeRatings(resultSet.getString("ratings"), this.ratings);
        IslandDeserializer.deserializeMissions(resultSet.getString("missions"), this.completedMissions);
        IslandDeserializer.deserializeSettings(resultSet.getString("settings"), this.islandSettings);
        IslandDeserializer.deserializeGenerators(resultSet.getString("generator"), this.cobbleGenerator);
        IslandDeserializer.deserializePlayers(resultSet.getString("uniqueVisitors"), this.uniqueVisitors);

        this.islandBank.set(BigDecimalFormatted.of(resultSet.getString("islandBank")));
        this.bonusWorth.set(BigDecimalFormatted.of(resultSet.getString("bonusWorth")));
        this.islandSize.set(resultSet.getInt("islandSize"));
        this.teamLimit.set(resultSet.getInt("teamLimit"));
        this.warpsLimit.set(resultSet.getInt("warpsLimit"));
        this.cropGrowth.set(resultSet.getDouble("cropGrowth"));
        this.spawnerRates.set(resultSet.getDouble("spawnerRates"));
        this.mobDrops.set(resultSet.getDouble("mobDrops"));
        this.discord.set(resultSet.getString("discord"));
        this.paypal.set(resultSet.getString("paypal"));
        this.locked.set(resultSet.getBoolean("locked"));
        this.islandName.set(resultSet.getString("name"));
        this.description.set(resultSet.getString("description"));
        this.ignored.set(resultSet.getBoolean("ignored"));
        this.generatedSchematics.set(resultSet.getString("generatedSchematics"));
        this.schemName.set(resultSet.getString("schemName"));
        this.unlockedWorlds.set(resultSet.getString("unlockedWorlds"));

        if(blockCounts.get().isEmpty())
            calcIslandWorth(null);

        assignPermissionNodes();
        assignSettings();
        checkMembersDuplication();

        Executor.sync(() -> biome.set(getCenter(World.Environment.NORMAL).getBlock().getBiome()));
    }

    public SIsland(SuperiorPlayer superiorPlayer, Location location, String islandName, String schemName){
        this(superiorPlayer, SBlockPosition.of(location), islandName, schemName);
    }

    @SuppressWarnings("WeakerAccess")
    public SIsland(SuperiorPlayer superiorPlayer, SBlockPosition wrappedLocation, String islandName, String schemName){
        if(superiorPlayer != null){
            this.owner = superiorPlayer.getIslandLeader();
            superiorPlayer.setPlayerRole(SPlayerRole.lastRole());
        }else{
            this.owner = null;
        }
        this.center = wrappedLocation;
        this.islandName.set(islandName);
        this.schemName.set(schemName);
        assignPermissionNodes();
        assignSettings();
        assignGenerator();
    }

    /*
     *  General methods
     */

    @Override
    public SuperiorPlayer getOwner() {
        return owner;
    }

    /*
     *  Player related methods
     */

    @Override
    public List<SuperiorPlayer> getIslandMembers(boolean includeOwner) {
        List<SuperiorPlayer> members = new ArrayList<>();

        if(includeOwner)
            members.add(owner);

        this.members.run((Consumer<PriorityQueue<SuperiorPlayer>>) members::addAll);

        return members;
    }

    @Override
    public List<SuperiorPlayer> getBannedPlayers() {
        return banned.run((Function<Set<SuperiorPlayer>, ArrayList<SuperiorPlayer>>) ArrayList::new);
    }

    @Override
    public List<SuperiorPlayer> getIslandVisitors() {
        return playersInside.run(playersInside -> {
            return playersInside.stream().filter(superiorPlayer -> !isMember(superiorPlayer)).collect(Collectors.toList());
        });
    }

    @Override
    public List<SuperiorPlayer> getAllPlayersInside() {
        return playersInside.run((Function<PriorityQueue<SuperiorPlayer>, ArrayList<SuperiorPlayer>>) ArrayList::new);
    }

    @Override
    public List<SuperiorPlayer> getUniqueVisitors() {
        return uniqueVisitors.run((Function<PriorityQueue<SuperiorPlayer>, ArrayList<SuperiorPlayer>>) ArrayList::new);
    }

    @Override
    public void inviteMember(SuperiorPlayer superiorPlayer){
        boolean result = invitedPlayers.run(invitedPlayers -> {
            if (invitedPlayers.contains(superiorPlayer))
                return false;

            invitedPlayers.add(superiorPlayer);
            return true;
        });

        //Revoke the invite after 5 minutes
        Executor.sync(() -> revokeInvite(superiorPlayer), 6000L);
    }

    @Override
    public void revokeInvite(SuperiorPlayer superiorPlayer){
        invitedPlayers.run(invitedPlayers -> {
            invitedPlayers.remove(superiorPlayer);
        });
    }

    @Override
    public boolean isInvited(SuperiorPlayer superiorPlayer){
        return invitedPlayers.run(invitedPlayers -> {
            return invitedPlayers.contains(superiorPlayer);
        });
    }

    @Override
    public List<SuperiorPlayer> getInvitedPlayers() {
        return invitedPlayers.run(invitedPlayers -> {
            return Collections.unmodifiableList(new ArrayList<>(invitedPlayers));
        });
    }

    @Override
    public void addMember(SuperiorPlayer superiorPlayer, PlayerRole playerRole) {
        members.run(members -> {
            members.add(superiorPlayer);

            superiorPlayer.setIslandLeader(owner);
            superiorPlayer.setPlayerRole(playerRole);

            MenuMembers.refreshMenus();

            Query.ISLAND_SET_MEMBERS.getStatementHolder()
                    .setString(IslandSerializer.serializePlayers(members))
                    .setString(owner.getUniqueId().toString())
                    .execute(true);
        });
    }

    @Override
    public void kickMember(SuperiorPlayer superiorPlayer){
        members.run(members -> {
            members.remove(superiorPlayer);
            superiorPlayer.setIslandLeader(superiorPlayer);

            if (superiorPlayer.isOnline())
                superiorPlayer.teleport(plugin.getGrid().getSpawnIsland());

            MenuMemberManage.destroyMenus(superiorPlayer);
            MenuMemberRole.destroyMenus(superiorPlayer);
            MenuMembers.refreshMenus();

            Query.ISLAND_SET_MEMBERS.getStatementHolder()
                    .setString(IslandSerializer.serializePlayers(members))
                    .setString(owner.getUniqueId().toString())
                    .execute(true);
        });
    }

    @Override
    public boolean isMember(SuperiorPlayer superiorPlayer){
        return owner.equals(superiorPlayer.getIslandLeader());
    }

    @Override
    public void banMember(SuperiorPlayer superiorPlayer){
        banned.run(banned -> {
            banned.add(superiorPlayer);

            if (isMember(superiorPlayer))
                kickMember(superiorPlayer);

            if (superiorPlayer.isOnline() && isInside(superiorPlayer.getLocation()))
                superiorPlayer.teleport(plugin.getGrid().getSpawnIsland());

            Query.ISLAND_SET_BANNED.getStatementHolder()
                    .setString(IslandSerializer.serializePlayers(banned))
                    .setString(owner.getUniqueId().toString())
                    .execute(true);
        });
    }

    @Override
    public void unbanMember(SuperiorPlayer superiorPlayer) {
        banned.run(banned -> {
            banned.remove(superiorPlayer);
            Query.ISLAND_SET_BANNED.getStatementHolder()
                    .setString(IslandSerializer.serializePlayers(banned))
                    .setString(owner.getUniqueId().toString())
                    .execute(true);
        });
    }

    @Override
    public boolean isBanned(SuperiorPlayer superiorPlayer){
        return banned.run(banned -> {
            return banned.contains(superiorPlayer);
        });
    }

    @Override
    public void addCoop(SuperiorPlayer superiorPlayer) {
        coop.run(coop -> {
            coop.add(superiorPlayer);
        });
    }

    @Override
    public void removeCoop(SuperiorPlayer superiorPlayer) {
        coop.run(coop -> {
            coop.remove(superiorPlayer);
        });
    }

    @Override
    public boolean isCoop(SuperiorPlayer superiorPlayer) {
        return coop.run(coop -> {
            return coop.contains(superiorPlayer);
        });
    }

    @Override
    public void setPlayerInside(SuperiorPlayer superiorPlayer, boolean inside) {
        playersInside.run(playersInside -> {
            if (inside)
                playersInside.add(superiorPlayer);
            else
                playersInside.remove(superiorPlayer);
        });

        if(inside && !isMember(superiorPlayer)){
            boolean newVisitor = uniqueVisitors.run(uniqueVisitors -> {
                if(!uniqueVisitors.contains(superiorPlayer)){
                    uniqueVisitors.add(superiorPlayer);
                    return true;
                }

                return false;
            });

            if(newVisitor){
                Query.ISLAND_SET_VISITORS.getStatementHolder()
                        .setString(IslandSerializer.serializePlayers(uniqueVisitors))
                        .setString(owner.getUniqueId().toString())
                        .execute(true);

                MenuUniqueVisitors.refreshMenus();
            }
        }

        MenuVisitors.refreshMenus();
    }

    /*
     *  Location related methods
     */

    @Override
    @Deprecated
    public Location getCenter(){
        return getCenter(World.Environment.NORMAL);
    }

    @Override
    public Location getCenter(World.Environment environment){
        World world = plugin.getGrid().getIslandsWorld(environment);

        if(world == null)
            throw new NullPointerException("Couldn't find world for environment " + environment);

        return center.parse(world).add(0.5, 0, 0.5);
    }

    @Override
    public Location getVisitorsLocation() {
        Location visitorsLocation = this.visitorsLocation.get();
        return visitorsLocation == null ? null : visitorsLocation.clone();
    }

    @Override
    @Deprecated
    public Location getTeleportLocation() {
        return getTeleportLocation(World.Environment.NORMAL);
    }

    @Override
    public Location getTeleportLocation(World.Environment environment) {
        Location teleportLocation = teleportLocations.run(teleportLocations -> {
            return teleportLocations.get(environment);
        });

        if (teleportLocation == null)
            teleportLocation = getCenter(environment);

        return teleportLocation == null ? null : teleportLocation.clone();
    }

    @Override
    public void setTeleportLocation(Location teleportLocation) {
        teleportLocations.run(teleportLocations -> {
            teleportLocations.put(teleportLocation.getWorld().getEnvironment(), teleportLocation.clone());
            Query.ISLAND_SET_TELEPORT_LOCATION.getStatementHolder()
                    .setString(IslandSerializer.serializeLocations(teleportLocations))
                    .setString(owner.getUniqueId().toString())
                    .execute(true);
        });
    }

    @Override
    public void setVisitorsLocation(Location visitorsLocation) {
        this.visitorsLocation.set(visitorsLocation);

        if(visitorsLocation == null){
            deleteWarp(VISITORS_WARP_NAME);
        }
        else{
            setWarpLocation(VISITORS_WARP_NAME, visitorsLocation, false);
        }

        Query.ISLAND_SET_VISITORS_LOCATION.getStatementHolder()
                .setString(LocationUtils.getLocation(getVisitorsLocation()))
                .setString(owner.getUniqueId().toString())
                .execute(true);
    }

    @Override
    public Location getMinimum(){
        int islandDistance = plugin.getSettings().maxIslandSize;
        return getCenter(World.Environment.NORMAL).subtract(islandDistance, 0, islandDistance);
    }

    @Override
    public Location getMaximum(){
        int islandDistance = plugin.getSettings().maxIslandSize;
        return getCenter(World.Environment.NORMAL).add(islandDistance, 0, islandDistance);
    }

    @Override
    public List<Chunk> getAllChunks() {
        return getAllChunks(false);
    }

    @Override
    public List<Chunk> getAllChunks(boolean onlyProtected){
        List<Chunk> chunks = new ArrayList<>();

        for(World.Environment environment : World.Environment.values()) {
            try {
                chunks.addAll(getAllChunks(environment, onlyProtected));
            }catch(NullPointerException ignored){}
        }

        return chunks;
    }

    @Override
    public List<Chunk> getAllChunks(World.Environment environment) {
        return getAllChunks(environment, false);
    }

    @Override
    public List<Chunk> getAllChunks(World.Environment environment, boolean onlyProtected) {
        int islandSize = getIslandSize();
        Location center = getCenter(environment);
        Location min = onlyProtected ? center.clone().subtract(islandSize, 0, islandSize) : getMinimum();
        Location max = onlyProtected ? center.clone().add(islandSize, 0, islandSize) : getMaximum();
        Chunk minChunk = min.getChunk(), maxChunk = max.getChunk();

        List<Chunk> chunks = new ArrayList<>();

        for(int x = minChunk.getX(); x <= maxChunk.getX(); x++){
            for(int z = minChunk.getZ(); z <= maxChunk.getZ(); z++){
                chunks.add(minChunk.getWorld().getChunkAt(x, z));
            }
        }


        return chunks;
    }

    @Override
    public boolean isInside(Location location){
        if(!plugin.getGrid().isIslandsWorld(location.getWorld()))
            return false;

        Location min = getMinimum(), max = getMaximum();
        return min.getBlockX() <= location.getBlockX() && min.getBlockZ() <= location.getBlockZ() &&
                max.getBlockX() >= location.getBlockX() && max.getBlockZ() >= location.getBlockZ();
    }

    @Override
    public boolean isInsideRange(Location location){
        return isInsideRange(location, 0);
    }

    public boolean isInsideRange(Location location, int extra){
        if(!plugin.getGrid().isIslandsWorld(location.getWorld()))
            return false;

        int islandSize = getIslandSize();
        Location min = center.parse().subtract(islandSize + extra, 0, islandSize + extra);
        Location max = center.parse().add(islandSize + extra, 0, islandSize + extra);
        return min.getBlockX() <= location.getBlockX() && min.getBlockZ() <= location.getBlockZ() &&
                max.getBlockX() >= location.getBlockX() && max.getBlockZ() >= location.getBlockZ();
    }

    @Override
    public boolean isInsideRange(Chunk chunk) {
        if(!plugin.getGrid().isIslandsWorld(chunk.getWorld()))
            return false;

        int islandSize = getIslandSize();
        Location min = center.parse().subtract(islandSize, 0, islandSize);
        Location max = center.parse().add(islandSize, 0, islandSize);

        return (min.getBlockX() >> 4) <= chunk.getX() && (min.getBlockZ() >> 4) <= chunk.getZ() &&
                (max.getBlockX() >> 4) >= chunk.getX() &&(max.getBlockZ() >> 4) >= chunk.getZ();
    }

    @Override
    public boolean isNetherEnabled() {
        return plugin.getSettings().netherWorldUnlocked || unlockedWorlds.run(unlockedWorlds -> {
            return unlockedWorlds.contains("nether");
        });
    }

    @Override
    public void setNetherEnabled(boolean enabled) {
        String unlockedWorlds = this.unlockedWorlds.run(_unlockedWorlds -> {
            if(enabled){
                return _unlockedWorlds.replace("nether", "") + "nether";
            }
            else{
                return _unlockedWorlds.replace("nether", "");
            }
        });

        this.unlockedWorlds.set(unlockedWorlds);

        Query.ISLAND_SET_UNLOCK_WORLDS.getStatementHolder()
                .setString(unlockedWorlds)
                .setString(owner.getUniqueId().toString())
                .execute(true);
    }

    @Override
    public boolean isEndEnabled() {
        return plugin.getSettings().endWorldUnlocked || unlockedWorlds.run(unlockedWorlds -> {
            return unlockedWorlds.contains("the_end");
        });
    }

    @Override
    public void setEndEnabled(boolean enabled) {
        String unlockedWorlds = this.unlockedWorlds.run(_unlockedWorlds -> {
            if(enabled){
                return _unlockedWorlds.replace("the_end", "") + "the_end";
            }
            else{
                return _unlockedWorlds.replace("the_end", "");
            }
        });

        this.unlockedWorlds.set(unlockedWorlds);

        Query.ISLAND_SET_UNLOCK_WORLDS.getStatementHolder()
                .setString(unlockedWorlds)
                .setString(owner.getUniqueId().toString())
                .execute(true);
    }

    /*
     *  Permissions related methods
     */

    @Override
    public boolean hasPermission(CommandSender sender, IslandPermission islandPermission){
        return sender instanceof ConsoleCommandSender || hasPermission(SSuperiorPlayer.of(sender), islandPermission);
    }

    @Override
    public boolean hasPermission(SuperiorPlayer superiorPlayer, IslandPermission islandPermission){
        return superiorPlayer.hasBypassModeEnabled() || superiorPlayer.hasPermissionWithoutOP("superior.admin.bypass." + islandPermission) || getPermissionNode(superiorPlayer).hasPermission(islandPermission);
    }

    @Override
    public void setPermission(PlayerRole playerRole, IslandPermission islandPermission, boolean value) {
        permissionNodes.run(permissionNodes -> {
            permissionNodes.get(playerRole).setPermission(islandPermission, value);
            Query.ISLAND_SET_PERMISSION_NODES.getStatementHolder()
                    .setString(IslandSerializer.serializePermissions(permissionNodes))
                    .setString(owner.getUniqueId().toString())
                    .execute(true);
        });
    }

    @Override
    public void setPermission(SuperiorPlayer superiorPlayer, IslandPermission islandPermission, boolean value) {
        permissionNodes.run(permissionNodes -> {
            SPermissionNode permissionNode = permissionNodes.getOrDefault(superiorPlayer.getUniqueId(), new SPermissionNode("", null));

            permissionNode.setPermission(islandPermission, value);

            permissionNodes.put(superiorPlayer.getUniqueId(), permissionNode);

            Query.ISLAND_SET_PERMISSION_NODES.getStatementHolder()
                    .setString(IslandSerializer.serializePermissions(permissionNodes))
                    .setString(owner.getUniqueId().toString())
                    .execute(true);
        });

        MenuPermissions.refreshMenus();
    }

    @Override
    public SPermissionNode getPermissionNode(PlayerRole playerRole) {
        return permissionNodes.run(permissionNodes -> {
            return permissionNodes.get(playerRole);
        });
    }

    @Override
    public SPermissionNode getPermissionNode(SuperiorPlayer superiorPlayer) {
        PlayerRole playerRole = isMember(superiorPlayer) ? superiorPlayer.getPlayerRole() : isCoop(superiorPlayer) ? SPlayerRole.coopRole() : SPlayerRole.guestRole();
        return permissionNodes.run(permissionNodes -> {
            return permissionNodes.getOrDefault(superiorPlayer.getUniqueId(), getPermissionNode(playerRole));
        });
    }

    @Override
    public PlayerRole getRequiredPlayerRole(IslandPermission islandPermission) {
        return plugin.getPlayers().getRoles().stream()
                .filter(playerRole -> getPermissionNode(playerRole).hasPermission(islandPermission))
                .min(Comparator.comparingInt(PlayerRole::getWeight)).orElse(SPlayerRole.guestRole());
    }

    /*
     *  General methods
     */

    @Override
    public boolean isSpawn() {
        return false;
    }

    @Override
    public String getName() {
        return islandName.get();
    }

    @Override
    public void setName(String islandName) {
        this.islandName.set(islandName);
        Query.ISLAND_SET_NAME.getStatementHolder()
                .setString(islandName)
                .setString(owner.getUniqueId().toString())
                .execute(true);
    }

    @Override
    public String getDescription() {
        return description.get();
    }

    @Override
    public void setDescription(String description) {
        this.description.set(description);
        Query.ISLAND_SET_DESCRIPTION.getStatementHolder()
                .setString(description)
                .setString(owner.getUniqueId().toString())
                .execute(true);
    }

    @Override
    public void disbandIsland(){
        getIslandMembers(true).forEach(superiorPlayer -> {
            if (isMember(superiorPlayer))
                kickMember(superiorPlayer);

            if (plugin.getSettings().disbandInventoryClear)
                plugin.getNMSAdapter().clearInventory(superiorPlayer.asOfflinePlayer());

            superiorPlayer.getCompletedMissions().forEach(mission -> {
                MissionsHandler.MissionData missionData = plugin.getMissions().getMissionData(mission);
                if (missionData != null && missionData.disbandReset)
                    superiorPlayer.resetMission(mission);
            });
        });

        plugin.getGrid().deleteIsland(this);

        List<Chunk> chunks = getAllChunks(World.Environment.NORMAL, true);

        if(wasSchematicGenerated(World.Environment.NETHER))
            chunks.addAll(getAllChunks(World.Environment.NETHER, true));

        if(wasSchematicGenerated(World.Environment.THE_END))
            chunks.addAll(getAllChunks(World.Environment.THE_END, true));

        plugin.getNMSAdapter().regenerateChunks(chunks);
    }

    @Override
    public void calcIslandWorth(SuperiorPlayer asker) {
        calcIslandWorth(asker, null);
    }

    @Override
    public void calcIslandWorth(SuperiorPlayer asker, Runnable callback) {
        if(!Bukkit.isPrimaryThread()){
            Executor.sync(() -> calcIslandWorth(asker, callback));
            return;
        }

        if(calcProcess) {
            islandCalcsQueue.push(new CalcIslandData(this, asker, callback));
            return;
        }

        calcProcess = true;
        beingRecalculated.set(true);

        List<Chunk> chunks = new ArrayList<>();
        List<ChunkSnapshot> chunkSnapshots = new ArrayList<>();

        if(!PaperHook.isUsingPaper())
            loadCalculateChunks(chunks, chunkSnapshots);

        blockCounts.run((Consumer<KeyMap<Integer>>) KeyMap::clear);
        islandWorth.set(BigDecimalFormatted.ZERO);
        islandLevel.set(BigDecimalFormatted.ZERO);

        Executor.async(() -> {
            if(PaperHook.isUsingPaper())
                loadCalculateChunks(chunks, chunkSnapshots);

            Set<Pair<Location, Integer>> spawnersToCheck = new HashSet<>();

            ExecutorService scanService = Executors.newCachedThreadPool(new ThreadFactoryBuilder().setNameFormat("SuperiorSkyblock Blocks Scanner %d").build());

            for (ChunkSnapshot chunkSnapshot : chunkSnapshots) {
                scanService.execute(() -> {
                    if(LocationUtils.isChunkEmpty(chunkSnapshot))
                        return;

                    double islandWorth = 0;

                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            for (int y = 0; y < 256; y++) {
                                Key blockKey = Key.of("AIR");

                                try{
                                    blockKey = plugin.getNMSAdapter().getBlockKey(chunkSnapshot, x, y, z);
                                }catch(ArrayIndexOutOfBoundsException ignored){ }

                                if(blockKey.toString().contains("AIR"))
                                    continue;

                                Location location = new Location(Bukkit.getWorld(chunkSnapshot.getWorldName()), (chunkSnapshot.getX() * 16) + x, y, (chunkSnapshot.getZ() * 16) + z);
                                int blockCount = plugin.getGrid().getBlockAmount(location);

                                if(blockKey.toString().contains("SPAWNER")){
                                    Pair<Integer, EntityType> entry = plugin.getProviders().getSpawner(location);
                                    blockCount = entry.getKey();
                                    if(entry.getValue() == null){
                                        spawnersToCheck.add(new Pair<>(location, blockCount));
                                        continue;
                                    }
                                    else{
                                        blockKey = Key.of(Materials.SPAWNER.toBukkitType().name() + ":" + entry.getValue());
                                    }
                                }

                                Pair<Integer, Material> blockPair = plugin.getProviders().getBlock(location);

                                if(blockPair != null){
                                    blockCount = blockPair.getKey();
                                    blockKey = Key.of(blockPair.getValue().name());
                                }

                                handleBlockPlace(blockKey, blockCount, false);
                            }
                        }
                    }
                });
            }

            try{
                scanService.shutdown();
                scanService.awaitTermination(1, TimeUnit.MINUTES);
            }catch(Exception ex){
                ex.printStackTrace();
            }

            Executor.sync(() -> {
                Key blockKey;
                int blockCount;

                for(Pair<Location, Integer> pair : spawnersToCheck){
                    try {
                        CreatureSpawner creatureSpawner = (CreatureSpawner) pair.getKey().getBlock().getState();
                        blockKey = Key.of(Materials.SPAWNER.toBukkitType().name() + ":" + creatureSpawner.getSpawnedType());
                        blockCount = pair.getValue();
                        if(blockCount <= 0)
                            blockCount = plugin.getProviders().getSpawner(pair.getKey()).getKey();
                        handleBlockPlace(blockKey, blockCount, false);
                    }catch(Throwable ignored){}
                }
                spawnersToCheck.clear();

                BigDecimal islandLevel = getIslandLevel();
                BigDecimal islandWorth = getWorth();

                Bukkit.getPluginManager().callEvent(new IslandWorthCalculatedEvent(this, asker, islandLevel, islandWorth));

                if(asker != null)
                    Locale.ISLAND_WORTH_RESULT.send(asker, islandWorth, islandLevel);

                if(callback != null)
                    callback.run();

                for(Chunk chunk : chunks)
                    BlocksProvider_WildStacker.uncacheChunk(chunk);

                saveBlockCounts();

                MenuValues.refreshMenus();

                calcProcess = false;
                beingRecalculated.set(false);

                if(islandCalcsQueue.size() != 0){
                    CalcIslandData calcIslandData = islandCalcsQueue.pop();
                    calcIslandData.island.calcIslandWorth(calcIslandData.asker, calcIslandData.callback);
                }
            });
        });
    }

    @Override
    public void updateBorder() {
        getAllPlayersInside().forEach(superiorPlayer -> plugin.getNMSAdapter().setWorldBorder(superiorPlayer, this));
    }

    @Override
    public int getIslandSize() {
        return islandSize.get();
    }

    @Override
    public void setIslandSize(int islandSize) {
        this.islandSize.set(islandSize);
        Query.ISLAND_SET_SIZE.getStatementHolder()
                .setInt(islandSize)
                .setString(owner.getUniqueId().toString())
                .execute(true);
    }

    @Override
    public String getDiscord() {
        return discord.get();
    }

    @Override
    public void setDiscord(String discord) {
        this.discord.set(discord);
        Query.ISLAND_SET_DISCORD.getStatementHolder()
                .setString(discord)
                .setString(owner.getUniqueId().toString())
                .execute(true);
    }

    @Override
    public String getPaypal() {
        return paypal.get();
    }

    @Override
    public void setPaypal(String paypal) {
        this.paypal.set(paypal);
        Query.ISLAND_SET_PAYPAL.getStatementHolder()
                .setString(paypal)
                .setString(owner.getUniqueId().toString())
                .execute(true);
    }

    @Override
    public Biome getBiome() {
        return biome.get();
    }

    @Override
    public void setBiome(Biome biome){
        plugin.getNMSAdapter().setBiome(getCenter(World.Environment.NORMAL).getChunk(), biome);
        this.biome.set(biome);
    }

    @Override
    public boolean isLocked() {
        return locked.get();
    }

    @Override
    public void setLocked(boolean locked) {
        this.locked.set(locked);
        if(locked){
            for(SuperiorPlayer victimPlayer : getAllPlayersInside()){
                if(!hasPermission(victimPlayer, IslandPermission.CLOSE_BYPASS)){
                    victimPlayer.teleport(plugin.getGrid().getSpawnIsland());
                    Locale.ISLAND_WAS_CLOSED.send(victimPlayer);
                }
            }
        }

        Query.ISLAND_SET_LOCKED.getStatementHolder()
                .setBoolean(locked)
                .setString(owner.getUniqueId().toString())
                .execute(true);
    }

    @Override
    public boolean isIgnored() {
        return ignored.get();
    }

    @Override
    public void setIgnored(boolean ignored) {
        this.ignored.set(ignored);

        Query.ISLAND_SET_IGNORED.getStatementHolder()
                .setBoolean(ignored)
                .setString(owner.getUniqueId().toString())
                .execute(true);
    }

    @Override
    public boolean transferIsland(SuperiorPlayer superiorPlayer) {
        if(superiorPlayer.equals(owner))
            return false;

        SuperiorPlayer previousOwner = getOwner();

        IslandTransferEvent islandTransferEvent = new IslandTransferEvent(this, previousOwner, superiorPlayer);
        Bukkit.getPluginManager().callEvent(islandTransferEvent);

        if(islandTransferEvent.isCancelled())
            return false;

        executeDeleteStatement(true);

        //Kick member without saving to database
        members.run(members -> {
            members.remove(superiorPlayer);
        });

        superiorPlayer.setPlayerRole(SPlayerRole.lastRole());

        //Add member without saving to database
        members.run(members -> {
            members.add(previousOwner);
        });

        PlayerRole previousRole = SPlayerRole.lastRole().getPreviousRole();
        previousOwner.setPlayerRole(previousRole == null ? SPlayerRole.lastRole() : previousRole);

        //Changing owner of the island and updating all players
        owner = superiorPlayer;
        getIslandMembers(true).forEach(islandMember -> islandMember.setIslandLeader(owner));

        executeInsertStatement(true);

        plugin.getGrid().transferIsland(previousOwner.getUniqueId(), owner.getUniqueId());

        return true;
    }

    @Override
    public void sendMessage(String message, UUID... ignoredMembers){
        List<UUID> ignoredList = Arrays.asList(ignoredMembers);

        getIslandMembers(true).stream()
                .filter(superiorPlayer -> !ignoredList.contains(superiorPlayer.getUniqueId()) && superiorPlayer.isOnline())
                .forEach(superiorPlayer -> Locale.sendMessage(superiorPlayer, message));
    }

    public void sendMessage(Locale message, List<UUID> ignoredMembers, Object... args){
        getIslandMembers(true).stream()
                .filter(superiorPlayer -> !ignoredMembers.contains(superiorPlayer.getUniqueId()) && superiorPlayer.isOnline())
                .forEach(superiorPlayer -> message.send(superiorPlayer, args));
    }

    @Override
    public boolean isBeingRecalculated() {
        return beingRecalculated.get();
    }

    /*
     *  Bank related methods
     */

    @Override
    @Deprecated
    public BigDecimal getMoneyInBankAsBigDecimal() {
        return getMoneyInBank();
    }

    @Override
    public BigDecimal getMoneyInBank() {
        return islandBank.run(islandBank -> {
            if(islandBank.doubleValue() < 0) {
                islandBank = BigDecimalFormatted.ZERO;
                this.islandBank.set(islandBank);
            }

            return islandBank;
        });
    }

    @Override
    public void depositMoney(double amount){
        this.islandBank.run(islandBank -> {
            islandBank = islandBank.add(BigDecimalFormatted.of(amount));
            this.islandBank.set(islandBank);
            Query.ISLAND_SET_BANK.getStatementHolder()
                    .setString(islandBank.getAsString())
                    .setString(owner.getUniqueId().toString())
                    .execute(true);
        });
    }

    @Override
    public void withdrawMoney(double amount){
        islandBank.run(islandBank -> {
            islandBank = this.islandBank.get();
            this.islandBank.set(islandBank.subtract(BigDecimalFormatted.of(amount)));
            Query.ISLAND_SET_BANK.getStatementHolder()
                    .setString(islandBank.getAsString())
                    .setString(owner.getUniqueId().toString())
                    .execute(true);
        });
    }

    /*
     *  Worth related methods
     */

    @Override
    public void handleBlockPlace(Block block){
        handleBlockPlace(Key.of(block), 1);
    }

    @Override
    public void handleBlockPlace(Block block, int amount){
        handleBlockPlace(Key.of(block), amount, true);
    }

    @Override
    public void handleBlockPlace(Block block, int amount, boolean save) {
        handleBlockPlace(Key.of(block), amount, save);
    }

    @Override
    public void handleBlockPlace(Key key, int amount){
        handleBlockPlace(key, amount, true);
    }

    @Override
    public void handleBlockPlace(Key key, int amount, boolean save) {
        BigDecimal blockValue = plugin.getBlockValues().getBlockWorth(key);
        BigDecimal blockLevel = plugin.getBlockValues().getBlockLevel(key);

        boolean increaseAmount = false;

        if(blockValue.doubleValue() >= 0){
            islandWorth.run(islandWorth -> {
                this.islandWorth.set(islandWorth.add(blockValue.multiply(new BigDecimal(amount))));
            });
            increaseAmount = true;
        }

        if(blockLevel.doubleValue() >= 0){
            islandLevel.run(islandLevel -> {
                this.islandLevel.set(islandLevel.add(blockLevel.multiply(new BigDecimal(amount))));
            });
            increaseAmount = true;
        }

        boolean hasBlockLimit = blockLimits.run(blockLimits -> {
            return blockLimits.containsKey(key);
        });

        if(increaseAmount || hasBlockLimit) {
            blockLimits.run(blockLimits -> {
                blockCounts.run(blockCounts -> {
                    Key _key = plugin.getBlockValues().getBlockKey(key);
                    int currentAmount = blockCounts.getRaw(_key, 0);
                    blockCounts.put(_key, currentAmount + amount);

                    if (!_key.toString().equals(blockLimits.getKey(_key).toString())) {
                        _key = blockLimits.getKey(_key);
                        currentAmount = blockCounts.getRaw(_key, 0);
                        blockCounts.put(_key, currentAmount + amount);
                    }
                });
            });

            if(save){
                MenuValues.refreshMenus();
                saveBlockCounts();
            }
        }
    }

    @Override
    public void handleBlockBreak(Block block){
        handleBlockBreak(Key.of(block), 1);
    }

    @Override
    public void handleBlockBreak(Block block, int amount){
        handleBlockBreak(block, amount, true);
    }

    @Override
    public void handleBlockBreak(Block block, int amount, boolean save) {
        handleBlockBreak(Key.of(block), amount, save);
    }

    @Override
    public void handleBlockBreak(Key key, int amount){
        handleBlockBreak(key, amount, true);
    }

    @Override
    public void handleBlockBreak(Key key, int amount, boolean save) {
        BigDecimal blockValue = plugin.getBlockValues().getBlockWorth(key);
        BigDecimal blockLevel = plugin.getBlockValues().getBlockLevel(key);

        boolean decreaseAmount = false;

        if(blockValue.doubleValue() >= 0){
            BigDecimalFormatted islandWorth = this.islandWorth.get();
            this.islandWorth.set(islandWorth.subtract(blockValue.multiply(new BigDecimal(amount))));
            if(islandWorth.doubleValue() < 0)
                this.islandWorth.set(BigDecimalFormatted.ZERO);
            decreaseAmount = true;
        }

        if(blockLevel.doubleValue() >= 0){
            BigDecimalFormatted islandLevel = this.islandLevel.get();
            this.islandLevel.set(islandLevel.subtract(blockLevel.multiply(new BigDecimal(amount))));
            if(islandLevel.doubleValue() < 0)
                this.islandLevel.set(BigDecimalFormatted.ZERO);
            decreaseAmount = true;
        }

        boolean hasBlockLimit = blockLimits.run(blockLimits -> {
            return blockLimits.containsKey(key);
        });

        if(decreaseAmount || hasBlockLimit){
            blockLimits.run(blockLimits -> {
                blockCounts.run(blockCounts -> {
                    Key _key = plugin.getBlockValues().getBlockKey(key);
                    int currentAmount = blockCounts.getRaw(_key, 0);
                    if(currentAmount <= amount)
                        blockCounts.removeRaw(_key);
                    else
                        blockCounts.put(_key, currentAmount - amount);

                    if(!_key.toString().equals(blockLimits.getKey(_key).toString())){
                        _key = blockLimits.getKey(_key);
                        currentAmount = blockCounts.getRaw(_key, 0);
                        if(currentAmount <= amount)
                            blockCounts.removeRaw(_key);
                        else
                            blockCounts.put(_key, currentAmount - amount);
                    }
                });
            });

            MenuValues.refreshMenus();

            if(save) saveBlockCounts();
        }
    }

    @Override
    public int getBlockCount(Key key){
        return blockCounts.run(blockCounts -> {
            return blockCounts.getOrDefault(key, 0);
        });
    }

    @Override
    public int getExactBlockCount(Key key) {
        return blockCounts.run(blockCounts -> {
            return blockCounts.getRaw(key, 0);
        });
    }

    @Override
    @Deprecated
    public BigDecimal getWorthAsBigDecimal() {
        return getWorth();
    }

    @Override
    public BigDecimal getWorth() {
        int bankWorthRate = plugin.getSettings().bankWorthRate;
        BigDecimalFormatted islandWorth = this.islandWorth.get(), islandBank = this.islandBank.get(), bonusWorth = this.bonusWorth.get();
        //noinspection BigDecimalMethodWithoutRoundingCalled
        BigDecimal finalIslandWorth = bankWorthRate <= 0 ? getRawWorth() : islandWorth.add(islandBank.divide(new BigDecimal(bankWorthRate)));
        return islandWorth.add(bonusWorth);
    }

    @Override
    @Deprecated
    public BigDecimal getRawWorthAsBigDecimal() {
        return getRawWorth();
    }

    @Override
    public BigDecimal getRawWorth() {
        return islandWorth.get();
    }

    @Override
    public void setBonusWorth(BigDecimal bonusWorth){
        this.bonusWorth.set(bonusWorth instanceof BigDecimalFormatted ? (BigDecimalFormatted) bonusWorth : BigDecimalFormatted.of(bonusWorth));
        Query.ISLAND_SET_BONUS_WORTH.getStatementHolder()
                .setString(this.bonusWorth.get().getAsString())
                .setString(owner.getUniqueId().toString())
                .execute(true);
    }

    @Override
    @Deprecated
    public BigDecimal getIslandLevelAsBigDecimal() {
        return getIslandLevel();
    }

    @Override
    public BigDecimalFormatted getIslandLevel() {
        BigDecimalFormatted islandLevel = this.islandLevel.get(), bonusWorth = this.bonusWorth.get();
        return plugin.getSettings().bonusAffectLevel ? islandLevel.add(new BigDecimal(plugin.getBlockValues().convertValueToLevel(bonusWorth))) : islandLevel;
    }

    private void saveBlockCounts(){
        blockCounts.run(blockCounts -> {
            Query.ISLAND_SET_BLOCK_COUNTS.getStatementHolder()
                    .setString(IslandSerializer.serializeBlockCounts(blockCounts))
                    .setString(owner.getUniqueId().toString())
                    .execute(true);
        });
    }

    /*
     *  Upgrade related methods
     */

    @Override
    public int getUpgradeLevel(String upgradeName){
        return upgrades.run(upgrades -> {
            return upgrades.getOrDefault(upgradeName, 1);
        });
    }

    @Override
    public void setUpgradeLevel(String upgradeName, int level){
        upgrades.run(upgrades -> {
            upgrades.put(upgradeName, Math.min(plugin.getUpgrades().getMaxUpgradeLevel(upgradeName), level));
            Query.ISLAND_SET_UPGRADES.getStatementHolder()
                    .setString(IslandSerializer.serializeUpgrades(upgrades))
                    .setString(owner.getUniqueId().toString())
                    .execute(true);
        });

        MenuUpgrades.refreshMenus();
    }

    @Override
    public double getCropGrowthMultiplier() {
        return cropGrowth.get();
    }

    @Override
    public void setCropGrowthMultiplier(double cropGrowth) {
        this.cropGrowth.set(cropGrowth);
        Query.ISLAND_SET_CROP_GROWTH.getStatementHolder()
                .setDouble(cropGrowth)
                .setString(owner.getUniqueId().toString())
                .execute(true);
    }

    @Override
    public double getSpawnerRatesMultiplier() {
        return spawnerRates.get();
    }

    @Override
    public void setSpawnerRatesMultiplier(double spawnerRates) {
        this.spawnerRates.set(spawnerRates);
        Query.ISLAND_SET_SPAWNER_RATES.getStatementHolder()
                .setDouble(spawnerRates)
                .setString(owner.getUniqueId().toString())
                .execute(true);
    }

    @Override
    public double getMobDropsMultiplier() {
        return mobDrops.get();
    }

    @Override
    public void setMobDropsMultiplier(double mobDrops) {
        this.mobDrops.set(mobDrops);
        Query.ISLAND_SET_MOB_DROPS.getStatementHolder()
                .setDouble(mobDrops)
                .setString(owner.getUniqueId().toString())
                .execute(true);
    }

    @Override
    public int getBlockLimit(Key key) {
        return blockLimits.run(blockLimits -> {
            return blockLimits.getOrDefault(key, NO_BLOCK_LIMIT);
        });
    }

    @Override
    public int getExactBlockLimit(Key key) {
        return blockLimits.run(blockLimits -> {
            return blockLimits.getRaw(key, NO_BLOCK_LIMIT);
        });
    }

    @Override
    public Map<Key, Integer> getBlocksLimits() {
        return blockLimits.run((Function<KeyMap<Integer>, Map<Key, Integer>>) KeyMap::asKeyMap);
    }

    @Override
    public void setBlockLimit(Key key, int limit) {
        blockLimits.run(blockLimits -> {
            if(limit <= NO_BLOCK_LIMIT)
                blockLimits.removeRaw(key);
            else
                blockLimits.put(key, limit);

            Query.ISLAND_SET_BLOCK_LIMITS.getStatementHolder()
                    .setString(IslandSerializer.serializeBlockLimits(blockLimits))
                    .setString(owner.getUniqueId().toString())
                    .execute(true);
        });
    }

    @Override
    public boolean hasReachedBlockLimit(Key key) {
        return hasReachedBlockLimit(key, 1);
    }

    @Override
    public boolean hasReachedBlockLimit(Key key, int amount) {
        int blockLimit = getExactBlockLimit(key);

        //Checking for the specific provided key.
        if(blockLimit > SIsland.NO_BLOCK_LIMIT)
            return getBlockCount(key) + amount > blockLimit;

        //Getting the global key values.
        key = Key.of(key.toString().split(":")[0]);
        blockLimit = getBlockLimit(key);

        return blockLimit > SIsland.NO_BLOCK_LIMIT && getBlockCount(key) + amount > blockLimit;
    }

    @Override
    public int getTeamLimit() {
        return teamLimit.get();
    }

    @Override
    public void setTeamLimit(int teamLimit) {
        this.teamLimit.set(teamLimit);
        Query.ISLAND_SET_TEAM_LIMIT.getStatementHolder()
                .setInt(teamLimit)
                .setString(owner.getUniqueId().toString())
                .execute(true);
    }

    @Override
    public int getWarpsLimit() {
        return warpsLimit.get();
    }

    @Override
    public void setWarpsLimit(int warpsLimit) {
        this.warpsLimit.set(warpsLimit);
        Query.ISLAND_SET_WARPS_LIMIT.getStatementHolder()
                .setInt(warpsLimit)
                .setString(owner.getUniqueId().toString())
                .execute(true);
    }

    /*
     *  Warps related methods
     */

    @Override
    public Location getWarpLocation(String name){
        return warps.run(warps -> warps.containsKey(name.toLowerCase()) ? warps.get(name.toLowerCase()).location.clone() : null);
    }

    @Override
    public boolean isWarpPrivate(String name) {
        return warps.run(warps -> !warps.containsKey(name.toLowerCase()) || warps.get(name.toLowerCase()).privateFlag);
    }

    @Override
    public void setWarpLocation(String name, Location location, boolean privateFlag) {
        warps.run(warps -> {
            warps.put(name.toLowerCase(), new WarpData(location.clone(), privateFlag));

            Query.ISLAND_SET_WARPS.getStatementHolder()
                    .setString(IslandSerializer.serializeWarps(warps))
                    .setString(owner.getUniqueId().toString())
                    .execute(true);
        });

        MenuGlobalWarps.refreshMenus();
        MenuWarps.refreshMenus();
    }

    @Override
    public void warpPlayer(SuperiorPlayer superiorPlayer, String warp){
        if(plugin.getSettings().warpsWarmup > 0) {
            Locale.TELEPORT_WARMUP.send(superiorPlayer, StringUtils.formatTime(superiorPlayer.getUserLocale(), plugin.getSettings().warpsWarmup));
            ((SSuperiorPlayer) superiorPlayer).setTeleportTask(Executor.sync(() ->
                    warpPlayerWithoutWarmup(superiorPlayer, warp), plugin.getSettings().warpsWarmup / 50));
        }
        else {
            warpPlayerWithoutWarmup(superiorPlayer, warp);
        }
    }

    private void warpPlayerWithoutWarmup(SuperiorPlayer superiorPlayer, String warp){
        Location location = warps.run(warps -> {
            return warps.get(warp.toLowerCase()).location.clone();
        });

        Block warpBlock = location.getBlock();
        ((SSuperiorPlayer) superiorPlayer).setTeleportTask(null);

        if(!isInsideRange(location)){
            Locale.UNSAFE_WARP.send(superiorPlayer);
            deleteWarp(warp);
            return;
        }

        if(!LocationUtils.isSafeBlock(warpBlock)){
            Locale.UNSAFE_WARP.send(superiorPlayer);
            return;
        }

        if(superiorPlayer.asPlayer().teleport(location.add(0.5, 0, 0.5))){
            Locale.TELEPORTED_TO_WARP.send(superiorPlayer);
        }
    }

    @Override
    public void deleteWarp(SuperiorPlayer superiorPlayer, Location location){
        warps.run(warps -> {
            for(String warpName : new ArrayList<>(warps.keySet())){
                if(LocationUtils.isSameBlock(location, warps.get(warpName).location)){
                    deleteWarp(warpName);
                    if(superiorPlayer != null)
                        Locale.DELETE_WARP.send(superiorPlayer, warpName);
                }
            }
        });
    }

    @Override
    public void deleteWarp(String name){
        warps.run(warps -> {
            warps.remove(name);
            Query.ISLAND_SET_WARPS.getStatementHolder()
                    .setString(IslandSerializer.serializeWarps(warps))
                    .setString(owner.getUniqueId().toString())
                    .execute(true);
        });

        MenuGlobalWarps.refreshMenus();
        MenuWarps.refreshMenus();
    }

    @Override
    public List<String> getAllWarps(){
        return warps.run(warps -> {
            return new ArrayList<>(warps.keySet());
        });
    }

    @Override
    public boolean hasMoreWarpSlots() {
        int warpsLimit = this.warpsLimit.get();
        return warps.run(warps -> warps.size() < warpsLimit);
    }

    /*
     *  Ratings related methods
     */

    @Override
    public Rating getRating(SuperiorPlayer superiorPlayer) {
        return ratings.run(ratings -> {
            return ratings.getOrDefault(superiorPlayer.getUniqueId(), Rating.UNKNOWN);
        });
    }

    @Override
    public void setRating(SuperiorPlayer superiorPlayer, Rating rating) {
        ratings.run(ratings -> {
            if(rating == Rating.UNKNOWN)
                ratings.remove(superiorPlayer.getUniqueId());
            else
                ratings.put(superiorPlayer.getUniqueId(), rating);

            Query.ISLAND_SET_RATINGS.getStatementHolder()
                    .setString(IslandSerializer.serializeRatings(ratings))
                    .setString(owner.getUniqueId().toString())
                    .execute(true);
        });

        MenuIslandRatings.refreshMenus();
    }

    @Override
    public double getTotalRating() {
        double avg = ratings.run(ratings -> {
           double _avg = 0;

            for(Rating rating : ratings.values())
                _avg += rating.getValue();

            return _avg;
        });

        return avg == 0 ? 0 : avg / getRatingAmount();
    }

    @Override
    public int getRatingAmount() {
        return ratings.run((Function<Map<UUID, Rating>, Integer>) Map::size);
    }

    @Override
    public Map<UUID, Rating> getRatings() {
        return ratings.run((Function<Map<UUID, Rating>, HashMap<UUID, Rating>>) HashMap::new);
    }

    /*
     *  Missions related methods
     */

    @Override
    public void completeMission(Mission mission) {
        completedMissions.run(completedMissions -> {
            completedMissions.put(mission, completedMissions.getOrDefault(mission, 0) + 1);
            Query.ISLAND_SET_MISSIONS.getStatementHolder()
                    .setString(IslandSerializer.serializeMissions(completedMissions))
                    .setString(owner.getUniqueId().toString())
                    .execute(true);
        });

        MenuIslandMissions.refreshMenus();
    }

    @Override
    public void resetMission(Mission mission) {
        completedMissions.run(completedMissions -> {
            if(completedMissions.getOrDefault(mission, 0) > 0) {
                completedMissions.put(mission, completedMissions.get(mission) - 1);
            }
            else {
                completedMissions.remove(mission);
            }

            Query.ISLAND_SET_MISSIONS.getStatementHolder()
                    .setString(IslandSerializer.serializeMissions(completedMissions))
                    .setString(owner.getUniqueId().toString())
                    .execute(true);
        });

        MenuIslandMissions.refreshMenus();
    }

    @Override
    public boolean hasCompletedMission(Mission mission) {
        return completedMissions.run(completedMissions -> {
            return completedMissions.containsKey(mission);
        });
    }

    @Override
    public boolean canCompleteMissionAgain(Mission mission) {
        MissionsHandler.MissionData missionData = plugin.getMissions().getMissionData(mission);
        return getAmountMissionCompleted(mission) < missionData.resetAmount;
    }

    @Override
    public int getAmountMissionCompleted(Mission mission) {
        return completedMissions.run(completedMissions -> {
            return completedMissions.getOrDefault(mission, 0);
        });
    }

    @Override
    public List<Mission> getCompletedMissions() {
        return completedMissions.run(completedMissions -> {
            return new ArrayList<>(completedMissions.keySet());
        });
    }

    /*
     *  Settings related methods
     */

    @Override
    public boolean hasSettingsEnabled(IslandSettings settings) {
        return islandSettings.run(islandSettings -> {
            return islandSettings.contains(settings);
        });
    }

    @Override
    public void enableSettings(IslandSettings settings) {
        islandSettings.run(islandSettings -> {
            islandSettings.add(settings);
        });

        boolean disableTime = false, disableWeather = false;

        //Updating times / weather if necessary
        switch (settings){
            case ALWAYS_DAY:
                getAllPlayersInside().forEach(superiorPlayer -> superiorPlayer.asPlayer().setPlayerTime(0, false));
                disableTime = true;
                break;
            case ALWAYS_MIDDLE_DAY:
                getAllPlayersInside().forEach(superiorPlayer -> superiorPlayer.asPlayer().setPlayerTime(6000, false));
                disableTime = true;
                break;
            case ALWAYS_NIGHT:
                getAllPlayersInside().forEach(superiorPlayer -> superiorPlayer.asPlayer().setPlayerTime(14000, false));
                disableTime = true;
                break;
            case ALWAYS_MIDDLE_NIGHT:
                getAllPlayersInside().forEach(superiorPlayer -> superiorPlayer.asPlayer().setPlayerTime(18000, false));
                disableTime = true;
                break;
            case ALWAYS_SHINY:
                getAllPlayersInside().forEach(superiorPlayer -> superiorPlayer.asPlayer().setPlayerWeather(WeatherType.CLEAR));
                disableWeather = true;
                break;
            case ALWAYS_RAIN:
                getAllPlayersInside().forEach(superiorPlayer -> superiorPlayer.asPlayer().setPlayerWeather(WeatherType.DOWNFALL));
                disableWeather = true;
                break;
            case PVP:
                if(plugin.getSettings().teleportOnPVPEnable)
                    getIslandVisitors().forEach(superiorPlayer -> {
                        superiorPlayer.teleport(plugin.getGrid().getSpawnIsland());
                        Locale.ISLAND_GOT_PVP_ENABLED_WHILE_INSIDE.send(superiorPlayer);
                    });
                break;
        }

        boolean DISABLE_TIME = disableTime, DISABLE_WEATHER = disableWeather;

        islandSettings.run(islandSettings -> {
            if(DISABLE_TIME){
                //Disabling settings without saving to database.
                if(settings != IslandSettings.ALWAYS_DAY)
                    islandSettings.remove(IslandSettings.ALWAYS_DAY);
                if(settings != IslandSettings.ALWAYS_MIDDLE_DAY)
                    islandSettings.remove(IslandSettings.ALWAYS_MIDDLE_DAY);
                if(settings != IslandSettings.ALWAYS_NIGHT)
                    islandSettings.remove(IslandSettings.ALWAYS_NIGHT);
                if(settings != IslandSettings.ALWAYS_MIDDLE_NIGHT)
                    islandSettings.remove(IslandSettings.ALWAYS_MIDDLE_NIGHT);
            }

            if(DISABLE_WEATHER){
                if(settings != IslandSettings.ALWAYS_RAIN)
                    islandSettings.remove(IslandSettings.ALWAYS_RAIN);
                if(settings != IslandSettings.ALWAYS_SHINY)
                    islandSettings.remove(IslandSettings.ALWAYS_SHINY);
            }

            Query.ISLAND_SET_SETTINGS.getStatementHolder()
                    .setString(IslandSerializer.serializeSettings(islandSettings))
                    .setString(owner.getUniqueId().toString())
                    .execute(true);
        });

        MenuSettings.refreshMenus();
    }

    @Override
    public void disableSettings(IslandSettings settings) {
        islandSettings.run(islandSettings -> {
            islandSettings.remove(settings);
            Query.ISLAND_SET_SETTINGS.getStatementHolder()
                    .setString(IslandSerializer.serializeSettings(islandSettings))
                    .setString(owner.getUniqueId().toString())
                    .execute(true);
        });

        switch (settings){
            case ALWAYS_DAY:
            case ALWAYS_MIDDLE_DAY:
            case ALWAYS_NIGHT:
            case ALWAYS_MIDDLE_NIGHT:
                getAllPlayersInside().forEach(superiorPlayer -> superiorPlayer.asPlayer().resetPlayerTime());
                break;
            case ALWAYS_RAIN:
            case ALWAYS_SHINY:
                getAllPlayersInside().forEach(superiorPlayer -> superiorPlayer.asPlayer().resetPlayerWeather());
                break;
        }

        MenuSettings.refreshMenus();
    }

    /*
     *  Generator related methods
     */

    @Override
    public void setGeneratorPercentage(Key key, int percentage) {
        if(percentage < 0 || percentage > 100){
            throw new IllegalArgumentException("Percentage must be between 0 and 100 - got " + percentage + ".");
        }
        else if(percentage == 0){
            setGeneratorAmount(key, 0);
        }
        else if(percentage == 100){
            cobbleGenerator.run(cobbleGenerator -> {
                cobbleGenerator.clear();
                cobbleGenerator.put(key.toString(), 1);
            });
        }
        else {
            //Removing the key from the generator
            setGeneratorAmount(key, 0);
            int totalAmount = getGeneratorTotalAmount();
            double realPercentage = percentage / 100D;
            double amount = (realPercentage * totalAmount) / (1 - realPercentage);
            if(amount < 1){
                cobbleGenerator.run(cobbleGenerator -> {
                    cobbleGenerator.keySet().forEach(mat -> cobbleGenerator.put(mat, cobbleGenerator.get(mat) * 10));
                });
                amount *= 10;
            }
            setGeneratorAmount(key, (int) Math.round(amount));
        }
    }

    @Override
    public int getGeneratorPercentage(Key key) {
        int totalAmount = getGeneratorTotalAmount();
        return totalAmount == 0 ? 0 : (getGeneratorAmount(key) * 100) / totalAmount;
    }

    public double getGeneratorPercentageDecimal(Key key){
        int totalAmount = getGeneratorTotalAmount();
        return totalAmount == 0 ? 0 : (getGeneratorAmount(key) * 100D) / totalAmount;
    }

    @Override
    public Map<String, Integer> getGeneratorPercentages() {
        Map<String, Integer> generatorPercentages = new HashMap<>();
        cobbleGenerator.run(cobbleGenerator -> {
            cobbleGenerator.keySet().forEach(key -> generatorPercentages.put(key, getGeneratorPercentage(Key.of(key))));
        });
        return generatorPercentages;
    }

    @Override
    public void setGeneratorAmount(Key key, int amount) {
        cobbleGenerator.run(cobbleGenerator -> {
            if(amount <= 0)
                cobbleGenerator.remove(key.toString());
            else
                cobbleGenerator.put(key.toString(), amount);

            Query.ISLAND_SET_GENERATOR.getStatementHolder()
                    .setString(IslandSerializer.serializeGenerator(cobbleGenerator))
                    .setString(owner.getUniqueId().toString())
                    .execute(true);
        });
    }

    @Override
    public int getGeneratorAmount(Key key) {
        return cobbleGenerator.run(cobbleGenerator -> {
            return cobbleGenerator.getOrDefault(key.toString(), 0);
        });
    }

    @Override
    public int getGeneratorTotalAmount() {
        return cobbleGenerator.run(cobbleGenerator -> {
            int totalAmount = 0;
            for(int amn : cobbleGenerator.values())
                totalAmount += amn;
            return totalAmount;
        });
    }

    @Override
    public Map<String, Integer> getGeneratorAmounts() {
        return cobbleGenerator.run((Function<Map<String, Integer>, HashMap<String, Integer>>) HashMap::new);
    }

    /*
     *  Schematic methods
     */

    @Override
    public boolean wasSchematicGenerated(World.Environment environment) {
        return generatedSchematics.get().contains(environment.name().toLowerCase());
    }

    @Override
    public void setSchematicGenerate(World.Environment environment) {
        String generatedSchematics = this.generatedSchematics.get();
        this.generatedSchematics.set(generatedSchematics + environment.name().toLowerCase());
        Query.ISLAND_SET_GENERATED_SCHEMATICS.getStatementHolder()
                .setString(generatedSchematics)
                .setString(owner.getUniqueId().toString())
                .execute(true);
    }

    @Override
    public String getSchematicName() {
        String schemName = this.schemName.get();
        return schemName == null ? "" : schemName;
    }

    /*
     *  Data related methods
     */

    @Override
    public void executeUpdateStatement(boolean async){
        Query.ISLAND_UPDATE.getStatementHolder()
                .setString(LocationUtils.getLocation(getTeleportLocation(World.Environment.NORMAL)))
                .setString(LocationUtils.getLocation(visitorsLocation.get()))
                .setString(IslandSerializer.serializePlayers(members))
                .setString(IslandSerializer.serializePlayers(banned))
                .setString(IslandSerializer.serializePermissions(permissionNodes))
                .setString(IslandSerializer.serializeUpgrades(upgrades))
                .setString(IslandSerializer.serializeWarps(warps))
                .setString(islandBank.get().getAsString())
                .setInt(islandSize.get())
                .setString(IslandSerializer.serializeBlockLimits(blockLimits))
                .setInt(teamLimit.get())
                .setFloat((float) (double) cropGrowth.get())
                .setFloat((float) (double) spawnerRates.get())
                .setFloat((float) (double) mobDrops.get())
                .setString(discord.get())
                .setString(paypal.get())
                .setInt(warpsLimit.get())
                .setString(bonusWorth.get().getAsString())
                .setBoolean(locked.get())
                .setString(IslandSerializer.serializeBlockCounts(blockCounts))
                .setString(islandName.get())
                .setString(description.get())
                .setString(IslandSerializer.serializeRatings(ratings))
                .setString(IslandSerializer.serializeMissions(completedMissions))
                .setString(IslandSerializer.serializeSettings(islandSettings))
                .setBoolean(ignored.get())
                .setString(IslandSerializer.serializeGenerator(cobbleGenerator))
                .setString(generatedSchematics.get())
                .setString(schemName.get())
                .setString(IslandSerializer.serializePlayers(uniqueVisitors))
                .setString(unlockedWorlds.get())
                .setString(owner.getUniqueId().toString())
                .execute(async);
    }

    @Override
    public void executeDeleteStatement(boolean async){
        Query.ISLAND_DELETE.getStatementHolder()
                .setString(owner.getUniqueId().toString())
                .execute(async);
    }

    @Override
    public void executeInsertStatement(boolean async){
        Query.ISLAND_INSERT.getStatementHolder()
                .setString(owner.getUniqueId().toString())
                .setString(LocationUtils.getLocation(center.parse()))
                .setString(IslandSerializer.serializeLocations(teleportLocations))
                .setString(IslandSerializer.serializePlayers(members))
                .setString(IslandSerializer.serializePlayers(banned))
                .setString(IslandSerializer.serializePermissions(permissionNodes))
                .setString(IslandSerializer.serializeUpgrades(upgrades))
                .setString(IslandSerializer.serializeWarps(warps))
                .setString(islandBank.get().getAsString())
                .setInt(islandSize.get())
                .setString(IslandSerializer.serializeBlockLimits(blockLimits))
                .setInt(teamLimit.get())
                .setFloat((float) (double) cropGrowth.get())
                .setFloat((float) (double) spawnerRates.get())
                .setFloat((float) (double) mobDrops.get())
                .setString(discord.get())
                .setString(paypal.get())
                .setInt(warpsLimit.get())
                .setString(bonusWorth.get().getAsString())
                .setBoolean(locked.get())
                .setString(IslandSerializer.serializeBlockCounts(blockCounts))
                .setString(islandName.get())
                .setString(LocationUtils.getLocation(visitorsLocation.get()))
                .setString(description.get())
                .setString(IslandSerializer.serializeRatings(ratings))
                .setString(IslandSerializer.serializeMissions(completedMissions))
                .setString(IslandSerializer.serializeSettings(islandSettings))
                .setBoolean(ignored.get())
                .setString(IslandSerializer.serializeGenerator(cobbleGenerator))
                .setString(generatedSchematics.get())
                .setString(schemName.get())
                .setString(IslandSerializer.serializePlayers(uniqueVisitors))
                .setString(unlockedWorlds.get())
                .execute(async);
    }

    /*
     *  Object related methods
     */

    @Override
    public boolean equals(Object obj) {
        return obj instanceof SIsland && owner.equals(((SIsland) obj).owner);
    }

    @Override
    public int hashCode() {
        return owner.hashCode();
    }

    @Override
    @SuppressWarnings("all")
    public int compareTo(Island other) {
        if(other == null)
            return -1;

        if(plugin.getSettings().islandTopOrder.equals("WORTH")){
            int compare = getWorth().compareTo(other.getWorth());
            if(compare != 0) return compare;
        }
        else {
            int compare = getIslandLevel().compareTo(other.getIslandLevel());
            if(compare != 0) return compare;
        }

        return getOwner().getName().compareTo(other.getOwner().getName());
    }

    /*
     *  Private methods
     */

    private void loadCalculateChunks(List<Chunk> chunks, List<ChunkSnapshot> chunkSnapshots){
        chunks.addAll(getAllChunks(World.Environment.NORMAL, true));

        if(wasSchematicGenerated(World.Environment.NETHER))
            chunks.addAll(getAllChunks(World.Environment.NETHER, true));

        if(wasSchematicGenerated(World.Environment.THE_END))
            chunks.addAll(getAllChunks(World.Environment.THE_END, true));

        for (Chunk chunk : chunks) {
            chunkSnapshots.add(chunk.getChunkSnapshot(true, false, false));
            BlocksProvider_WildStacker.cacheChunk(chunk);
        }
    }

    private void assignPermissionNodes(){
        permissionNodes.run(permissionNodes -> {
            boolean save = false;

            for(PlayerRole playerRole : plugin.getPlayers().getRoles()) {
                if(!permissionNodes.containsKey(playerRole)) {
                    PlayerRole previousRole = SPlayerRole.of(playerRole.getWeight() - 1);
                    permissionNodes.put(playerRole, new SPermissionNode(((SPlayerRole) playerRole).getDefaultPermissions(), permissionNodes.get(previousRole)));
                    save = true;
                }
            }

            if(save && owner != null){
                Query.ISLAND_SET_PERMISSION_NODES.getStatementHolder()
                        .setString(IslandSerializer.serializePermissions(permissionNodes))
                        .setString(owner.getUniqueId().toString())
                        .execute(true);
            }
        });
    }

    private void assignSettings(){
        islandSettings.run(islandSettings -> {
            if(!islandSettings.isEmpty() || owner == null)
                return;

            plugin.getSettings().defaultSettings.forEach(setting -> {
                try{
                    islandSettings.add(IslandSettings.valueOf(setting));
                }catch(Exception ignored){}
            });

            Query.ISLAND_SET_SETTINGS.getStatementHolder()
                    .setString(IslandSerializer.serializeSettings(islandSettings))
                    .setString(owner.getUniqueId().toString())
                    .execute(true);
        });
    }

    private void assignGenerator(){
        cobbleGenerator.run(cobbleGenerator -> {
            if(!cobbleGenerator.isEmpty() || owner == null)
                return;

            cobbleGenerator.putAll(plugin.getSettings().defaultGenerator);

            Query.ISLAND_SET_GENERATOR.getStatementHolder()
                    .setString(IslandSerializer.serializeGenerator(cobbleGenerator))
                    .setString(owner.getUniqueId().toString())
                    .execute(true);
        });
    }

    private void checkMembersDuplication(){
        members.run(members -> {
            Iterator<SuperiorPlayer> iterator = members.iterator();
            boolean removed = false;

            while (iterator.hasNext()){
                SuperiorPlayer superiorPlayer = iterator.next();
                if(!superiorPlayer.getIslandLeader().equals(owner)){
                    iterator.remove();
                    removed = true;
                }
            }

            if(removed){
                Query.ISLAND_SET_MEMBERS.getStatementHolder()
                        .setString(IslandSerializer.serializePlayers(members))
                        .setString(owner.getUniqueId().toString())
                        .execute(true);
            }
        });
    }

    private static class CalcIslandData {

        private final Island island;
        private final SuperiorPlayer asker;
        private final Runnable callback;

        private CalcIslandData(Island island, SuperiorPlayer asker, Runnable callback){
            this.island = island;
            this.asker = asker;
            this.callback = callback;
        }

    }

    public static class WarpData{

        public Location location;
        public boolean privateFlag;

        public WarpData(Location location, boolean privateFlag){
            this.location = location;
            this.privateFlag = privateFlag;
        }

    }
}
