package me.promptt.itfollows;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.data.Openable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * ItStalksPlugin
 *
 * A Paper plugin implementing a "cursed" mechanic where a single stalker entity ("It")
 * relentlessly pursues a cursed player.
 *
 * Key subsystems:
 *  - Curse assignment & transfer
 *  - Stalker spawn/respawn & persistence
 *  - Target pursuit and combat
 *  - Fear-source avoidance (fire / soul items / soul campfire)
 *  - Anti-boat trapping
 *  - Anti-stuck "Vex Mode" (temporary flying form)
 *  - Optional proximity warnings
 */
public class ItStalksPlugin extends JavaPlugin implements Listener, CommandExecutor {

    // --- Runtime State (Curse & Stalker Identity) ---

    /** Current cursed player UUID (single target at any time). */
    private UUID cursedPlayerUUID;

    /** Current stalker entity UUID (spawned and tracked across ticks). */
    private UUID itEntityUUID;

    /** Timestamp used to retarget if the cursed player logs out. */
    private long cursedLogoutTime = -1;

    /** Cooldown timestamp for "tag" transfer mechanic. */
    private long lastTransferTime = 0;

    // --- Vex / Anti-Stuck Logic State ---

    /** The previous position snapshot used to detect if the stalker isn't moving. */
    private Location lastStalkerPos = null;

    /** Seconds counted while the stalker is considered stuck (or intentionally stationary). */
    private int secondsStuck = 0;

    /** Seconds counted while in Vex Mode (temporary flying form). */
    private int secondsInVexMode = 0;

    /** True while the stalker is currently a Vex. */
    private boolean isVexMode = false;

    // --- Identity Key for Persistence ---

    /** PDC key used to mark "our" stalker entities so they can be recovered/cleaned. */
    private NamespacedKey stalkerKey;

    // --- Configuration Cache ---

    private int logoutRetargetDelay;
    private int minTeleportDistance;
    private int fatigueRange;
    private boolean canEnterWater;
    private boolean autoCurseIfEmpty;
    private int transferCooldownSeconds;

    // Vex config
    private boolean vexModeEnabled;
    private int vexTriggerSeconds;
    private int vexDurationSeconds;

    // Speed config
    private double allowedFormsMovementSpeed;
    private double allowedFormsPathfinderSpeed;
    private double vexMovementSpeed;
    private double vexFlyingSpeed;
    private double vexPathfinderSpeed;

    // Boat trap prevention
    private double boatTrapRadius;

    // Fear config
    private boolean fearFireEnabled;
    private double fearFireRadius;
    private boolean fearSoulTorchEnabled;
    private double fearSoulTorchRadius;
    private boolean fearSoulLanternEnabled;
    private double fearSoulLanternRadius;
    private boolean fearSoulCampfireEnabled;
    private double fearSoulCampfireRadius;

    /** Extra buffer added to the base fear radius to create a stable "edge". */
    private double fearAvoidPerimeterBuffer;

    /** How far (in blocks) to step when pathing around a fear zone. */
    private double fearAvoidStepDistance;

    /**
     * Legacy parameter (kept for backwards compatibility with config).
     * Previously used for "inward" dot-product switching.
     */
    private double fearAvoidInwardDotThreshold;

    // Chat messages
    private String msgCurseAssigned;
    private String msgCurseCooldown;
    private String msgCursePassedAttacker;
    private String msgCursePassedVictim;
    private String msgPlayerNotFound;
    private String msgCurseStartedAdmin;
    private String msgConfigReloaded;

    // Proximity messages / tips
    private boolean proximityMessagesEnabled;
    private int proximityCheckIntervalTicks;
    private int proximityDefaultCooldownSeconds;
    private final List<ProximityTier> proximityTiers = new ArrayList<>();
    private int proximityTickCounter = 0;
    private final Map<UUID, Map<Integer, Long>> lastProximityMessageMs = new HashMap<>();

    // Cached fear scan result (throttles block scanning for performance)
    private FearSource cachedFearSource;
    private long lastFearScanMs = 0;

    // Allowed entity forms for the stalker while walking
    private final List<EntityType> allowedForms = new ArrayList<>();

    // --- Internal Types ---

    private enum FearType {
        FIRE,
        SOUL_TORCH,
        SOUL_LANTERN,
        SOUL_CAMPFIRE
    }

    private static class FearSource {
        final FearType type;
        final Location location;

        FearSource(FearType type, Location location) {
            this.type = type;
            this.location = location;
        }
    }

    private static class ProximityTier {
        final double radius;
        final String message;
        final int cooldownSeconds;

        ProximityTier(double radius, String message, int cooldownSeconds) {
            this.radius = radius;
            this.message = message;
            this.cooldownSeconds = cooldownSeconds;
        }
    }

    // --- Plugin Lifecycle ---

    @Override
    public void onEnable() {
        this.stalkerKey = new NamespacedKey(this, "is_stalker");

        saveDefaultConfig();
        loadConfig();

        getServer().getPluginManager().registerEvents(this, this);

        // Register commands
        Objects.requireNonNull(getCommand("startcurse")).setExecutor(this);
        Objects.requireNonNull(getCommand("cursereload")).setExecutor(this);

        // Clean any previous stalkers that were left behind
        cleanOldEntities();

        // Main AI tick (5 ticks = 0.25s). Delay of 20 ticks gives the server time to fully start.
        new BukkitRunnable() {
            @Override
            public void run() {
                tickLogic();
            }
        }.runTaskTimer(this, 20L, 5L);

        // Stuck-check loop (20 ticks = 1.0s)
        new BukkitRunnable() {
            @Override
            public void run() {
                checkStuckStatus();
            }
        }.runTaskTimer(this, 20L, 20L);

        getLogger().info("ItStalks has been enabled. Run.");
    }

    @Override
    public void onDisable() {
        itEntityUUID = null;
    }

    /** Removes any entities marked as stalkers from previous plugin sessions. */
    private void cleanOldEntities() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity e : world.getEntities()) {
                if (e.getPersistentDataContainer().has(stalkerKey, PersistentDataType.BYTE)) {
                    e.remove();
                }
            }
        }
    }

    /** Loads all configuration values into local caches for fast access during tick loops. */
    private void loadConfig() {
        FileConfiguration config = getConfig();

        logoutRetargetDelay = config.getInt("logout_retarget_delay", 300);
        minTeleportDistance = config.getInt("min_teleport_distance", 50);
        fatigueRange = config.getInt("fatigue_range", 10);
        canEnterWater = config.getBoolean("can_enter_water", false);
        autoCurseIfEmpty = config.getBoolean("auto_curse_if_empty", true);
        transferCooldownSeconds = config.getInt("curse_transfer_cooldown", 3);

        vexModeEnabled = config.getBoolean("vex_mode_enabled", true);
        vexTriggerSeconds = config.getInt("vex_trigger_seconds", 10);
        vexDurationSeconds = config.getInt("vex_duration_seconds", 10);

        // Speeds
        allowedFormsMovementSpeed = config.getDouble("speeds.allowed_forms.movement", 0.12);
        allowedFormsPathfinderSpeed = config.getDouble("speeds.allowed_forms.pathfinder", 1.0);
        vexMovementSpeed = config.getDouble("speeds.vex_form.movement", 0.12);
        vexFlyingSpeed = config.getDouble("speeds.vex_form.flying", 0.12);
        vexPathfinderSpeed = config.getDouble("speeds.vex_form.pathfinder", 1.0);

        // Boat trap prevention
        boatTrapRadius = config.getDouble("boat_trap_prevention_radius", 3.5);

        // Fears
        fearFireEnabled = config.getBoolean("fears.fire.enabled", true);
        fearFireRadius = config.getDouble("fears.fire.radius", 8.0);

        fearSoulTorchEnabled = config.getBoolean("fears.soul_torch.enabled", true);
        fearSoulTorchRadius = config.getDouble("fears.soul_torch.radius", 8.0);

        fearSoulLanternEnabled = config.getBoolean("fears.soul_lantern.enabled", true);
        fearSoulLanternRadius = config.getDouble("fears.soul_lantern.radius", 8.0);

        // Soul Campfire (replaces beacon). Falls back to old beacon keys for backwards compatibility.
        fearSoulCampfireEnabled = config.getBoolean("fears.soul_campfire.enabled", config.getBoolean("fears.beacon.enabled", true));
        fearSoulCampfireRadius = config.getDouble("fears.soul_campfire.radius", config.getDouble("fears.beacon.radius", 32.0));

        // Fear avoidance tuning
        fearAvoidPerimeterBuffer = Math.max(0.0, config.getDouble("fears.avoidance.perimeter_buffer", 0.75));
        fearAvoidStepDistance = Math.max(1.0, config.getDouble("fears.avoidance.step_distance", 6.0));
        fearAvoidInwardDotThreshold = config.getDouble("fears.avoidance.inward_dot_threshold", 0.15);

        // Messages
        msgCurseAssigned = config.getString("messages.curse_assigned", "&4&lYou feel a cold chill... It is following you.");
        msgCurseCooldown = config.getString("messages.curse_cooldown", "&cYou cannot pass the curse yet! Wait {seconds}s.");
        msgCursePassedAttacker = config.getString("messages.curse_passed_attacker", "&aYou have passed the curse to {victim}!");
        msgCursePassedVictim = config.getString("messages.curse_passed_victim", "&4&lTAG! You are now Cursed.");
        msgPlayerNotFound = config.getString("messages.player_not_found", "&cPlayer not found.");
        msgCurseStartedAdmin = config.getString("messages.curse_started_admin", "&cCurse started on {target}");
        msgConfigReloaded = config.getString("messages.config_reloaded", "&aItStalks configuration reloaded!");

        // Proximity messages
        proximityMessagesEnabled = config.getBoolean("proximity_messages.enabled", true);
        proximityCheckIntervalTicks = Math.max(5, config.getInt("proximity_messages.check_interval_ticks", 20));
        proximityDefaultCooldownSeconds = Math.max(0, config.getInt("proximity_messages.default_cooldown_seconds", 180));

        proximityTiers.clear();
        List<Map<?, ?>> tiers = config.getMapList("proximity_messages.tiers");
        for (Map<?, ?> m : tiers) {
            if (m == null) continue;
            double radius = toDouble(m.get("radius"), -1);
            if (radius <= 0) continue;
            Object msgObj = m.get("message");
            if (msgObj == null) continue;

            String message = String.valueOf(msgObj);
            int cooldown = proximityDefaultCooldownSeconds;
            if (m.containsKey("cooldown_seconds")) {
                cooldown = (int) Math.round(toDouble(m.get("cooldown_seconds"), cooldown));
            }

            proximityTiers.add(new ProximityTier(radius, message, cooldown));
        }
        proximityTiers.sort(Comparator.comparingDouble(t -> t.radius));

        lastProximityMessageMs.clear();
        proximityTickCounter = 0;

        // Allowed forms
        allowedForms.clear();
        for (String s : config.getStringList("allowed_forms")) {
            try {
                allowedForms.add(EntityType.valueOf(s.toUpperCase()));
            } catch (IllegalArgumentException e) {
                getLogger().warning("Invalid entity type in config: " + s);
            }
        }
    }

    // --- Formatting / Utility ---

    private String colorize(String msg) {
        if (msg == null) return "";
        return ChatColor.translateAlternateColorCodes('&', msg);
    }

    private String formatMessage(String template, Map<String, String> placeholders) {
        if (template == null) return "";
        String out = template;
        if (placeholders != null) {
            for (Map.Entry<String, String> e : placeholders.entrySet()) {
                out = out.replace("{" + e.getKey() + "}", e.getValue());
            }
        }
        return colorize(out);
    }

    private static double toDouble(Object o, double def) {
        if (o instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (Exception ignored) {
            return def;
        }
    }

    // --- Core Logic Loop ---

    /**
     * Primary tick loop (runs every 5 ticks).
     *
     * Responsibilities:
     *  - Ensure we have a valid cursed player
     *  - Ensure the stalker exists and is in the correct world
     *  - Apply fear avoidance / pursuit behavior
     *  - Handle combat effects
     */
    private void tickLogic() {
        // 1) Ensure a valid cursed player exists (or auto-pick)
        if (cursedPlayerUUID == null) {
            if (autoCurseIfEmpty) pickRandomTarget();
            return;
        }

        Player victim = Bukkit.getPlayer(cursedPlayerUUID);

        // 2) Handle victim disconnect & retargeting
        if (victim == null || !victim.isOnline()) {
            if (cursedLogoutTime == -1) cursedLogoutTime = System.currentTimeMillis();
            if ((System.currentTimeMillis() - cursedLogoutTime) / 1000 > logoutRetargetDelay) {
                pickRandomTarget();
            }
            return;
        } else {
            cursedLogoutTime = -1;
        }

        // 3) Do not stalk creative/spectator
        if (victim.getGameMode() == GameMode.SPECTATOR || victim.getGameMode() == GameMode.CREATIVE) {
            removeItEntity();
            return;
        }

        // 4) Attempt to recover stalker UUID if lost (e.g., reload)
        if (itEntityUUID == null) {
            for (Entity e : victim.getNearbyEntities(100, 100, 100)) {
                if (e.getPersistentDataContainer().has(stalkerKey, PersistentDataType.BYTE)) {
                    itEntityUUID = e.getUniqueId();
                    break;
                }
            }
        }

        Entity it = (itEntityUUID != null) ? Bukkit.getEntity(itEntityUUID) : null;

        // 5) Respawn logic: invalid, wrong world, or very far away
        if (it == null
                || !it.isValid()
                || !isSameWorld(it.getLocation(), victim.getLocation())
                || safeDistance(it.getLocation(), victim.getLocation()) > 120) {
            if (it != null) it.remove();
            spawnIt(victim);
            return;
        }

        // 6) Behavior loop (mob only)
        if (!(it instanceof Mob mob)) return;

        double distToVictim = safeDistance(mob.getLocation(), victim.getLocation());

        // Proximity chat messages (tips/alerts based on distance)
        handleProximityMessages(victim, distToVictim);

        // Visibility: only the cursed player can see the stalker
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.getUniqueId().equals(cursedPlayerUUID)) {
                p.hideEntity(this, mob);
            } else {
                p.showEntity(this, mob);
            }
        }

        // Prevent boat trapping / clean nearby boats
        handleBoatTrapPrevention(mob);

        // Fear logic: certain blocks repel the stalker.
        // IMPORTANT CHANGE:
        //  - The stalker no longer constantly "orbits" the fear perimeter.
        //  - If the victim is inside the fear radius, the stalker holds still at the edge.
        //  - If the victim is outside but the fear zone blocks the direct path, the stalker will
        //    take committed steps around the perimeter until it has a clear route.
        FearSource fearSource = getFearSource(mob.getLocation());

        boolean fearOverrodeMovement = false;
        if (fearSource != null && !isVexMode) {
            fearOverrodeMovement = handleFear(mob, victim, fearSource);
        }

        // Pursuit: only run normal chase logic if fear logic did not override movement this tick.
        if (!fearOverrodeMovement) {
            mob.getPathfinder().moveTo(victim.getLocation(), getCurrentPathfinderSpeed(mob));
            mob.setTarget(victim);
        }

        // Door opening / breaking
        handleDoors(mob);

        // Wall climbing (only if not a Vex)
        if (!isVexMode) handleClimbing(mob);

        // Ladder descent
        handleLadderDescent(mob, victim);

        // Vex aggression
        if (isVexMode && mob instanceof Vex vex) {
            vex.setCharging(true);
        }

        // Water avoidance
        if (!canEnterWater && mob.isInWater()) {
            Vector away = mob.getLocation().toVector().subtract(victim.getLocation().toVector()).normalize().multiply(0.5).setY(0.5);
            mob.setVelocity(away);
        }

        // Attack
        if (distToVictim < 1.5) {
            if (victim.getNoDamageTicks() == 0) {
                victim.damage(12.0, mob);
                mob.swingMainHand();
            }
        }

        // Effects
        if (safeDistance(it.getLocation(), victim.getLocation()) <= fatigueRange) {
            victim.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 100, 1, false, false));
        }
    }

    // --- Proximity Messages ---

    private void handleProximityMessages(Player victim, double distToVictim) {
        if (!proximityMessagesEnabled || proximityTiers.isEmpty() || victim == null) return;

        // tickLogic runs every 5 ticks; use an interval counter to reduce spam
        proximityTickCounter += 5;
        if (proximityTickCounter < proximityCheckIntervalTicks) return;
        proximityTickCounter = 0;

        // Choose the closest matching tier (smallest radius that the stalker is currently within)
        ProximityTier chosen = null;
        int chosenIndex = -1;
        for (int i = 0; i < proximityTiers.size(); i++) {
            ProximityTier tier = proximityTiers.get(i);
            if (distToVictim <= tier.radius) {
                chosen = tier;
                chosenIndex = i;
                break;
            }
        }
        if (chosen == null) return;

        long now = System.currentTimeMillis();
        long cooldownMs = Math.max(0, chosen.cooldownSeconds) * 1000L;

        Map<Integer, Long> perTier = lastProximityMessageMs.computeIfAbsent(victim.getUniqueId(), k -> new HashMap<>());
        long lastSent = perTier.getOrDefault(chosenIndex, 0L);
        if (cooldownMs > 0 && (now - lastSent) < cooldownMs) return;

        String distStr = String.format(Locale.US, "%.1f", distToVictim);
        String radiusStr = String.format(Locale.US, "%.1f", chosen.radius);

        victim.sendMessage(formatMessage(chosen.message, Map.of("distance", distStr, "radius", radiusStr)));
        perTier.put(chosenIndex, now);
    }

    // --- Anti-Stuck / Vex Mode ---

    /**
     * Runs once per second.
     *
     * If the stalker stays effectively motionless for a period of time, it morphs into a Vex.
     * This helps handle edge cases where pathfinding fails (stairs, holes, water edges).
     *
     * NOTE: With the updated fear behavior, the stalker may also intentionally hold still
     * on a fear perimeter. In those situations, it can still become a Vex (as requested)
     * and then fly/phase to the target.
     */
    private void checkStuckStatus() {
        if (!vexModeEnabled || itEntityUUID == null) return;

        Entity it = Bukkit.getEntity(itEntityUUID);
        if (it == null || !it.isValid()) return;

        // --- Vex Timer (Turn back to walker) ---
        if (isVexMode) {
            secondsInVexMode++;

            // Has enough time passed AND we are close to the ground? (Prevent air-drops)
            if (secondsInVexMode >= vexDurationSeconds) {
                if (it instanceof Mob mob && isSafeToLand(mob)) {
                    Player victim = (cursedPlayerUUID != null) ? Bukkit.getPlayer(cursedPlayerUUID) : null;
                    if (victim != null) morphEntity(mob, victim, null);
                }
            }
            return;
        }

        // --- Walker stuck logic (Turn into Vex) ---
        if (lastStalkerPos != null) {
            if (it.getLocation().distance(lastStalkerPos) < 0.1) {
                secondsStuck++;
            } else {
                secondsStuck = 0;
            }
        }
        lastStalkerPos = it.getLocation().clone();

        if (secondsStuck >= vexTriggerSeconds) {
            Player victim = (cursedPlayerUUID != null) ? Bukkit.getPlayer(cursedPlayerUUID) : null;
            if (victim != null && it instanceof Mob mob) {
                morphEntity(mob, victim, EntityType.VEX);
            }
        }
    }

    /**
     * Simple ground check to prevent morphing out of Vex mode when the mob is too high.
     *
     * @param mob the current stalker mob
     * @return true if there is solid ground within 4 blocks below
     */
    private boolean isSafeToLand(Mob mob) {
        Location loc = mob.getLocation();
        for (int i = 1; i <= 4; i++) {
            Block b = loc.clone().subtract(0, i, 0).getBlock();
            if (b.getType().isSolid()) {
                return true;
            }
        }
        return false;
    }

    // --- Spawning / Morphing ---

    private void spawnIt(Player target) {
        spawnSpecificEntity(null, target, null);
    }

    /**
     * Spawns the stalker either at a specific location (morph) or at a random ring around the target.
     */
    private void spawnSpecificEntity(Location specificLoc, Player target, EntityType forcedType) {
        Location spawnLoc;
        World world = target.getWorld();

        if (specificLoc == null) {
            // Spawn in a ring around the player at minTeleportDistance
            spawnLoc = target.getLocation();
            double angle = Math.random() * 2 * Math.PI;
            double xOffset = Math.cos(angle) * minTeleportDistance;
            double zOffset = Math.sin(angle) * minTeleportDistance;

            spawnLoc.add(xOffset, 0, zOffset);

            // Only spawn if the destination chunk is loaded
            int chunkX = spawnLoc.getBlockX() >> 4;
            int chunkZ = spawnLoc.getBlockZ() >> 4;
            if (!world.isChunkLoaded(chunkX, chunkZ)) return;

            int highestY = world.getHighestBlockYAt(spawnLoc);
            spawnLoc.setY(highestY + 1);
        } else {
            spawnLoc = specificLoc;
        }

        // Choose entity type
        EntityType type;
        if (forcedType != null) {
            type = forcedType;
        } else if (!allowedForms.isEmpty()) {
            type = allowedForms.get(new Random().nextInt(allowedForms.size()));
        } else {
            return;
        }

        Entity entity = spawnLoc.getWorld().spawnEntity(spawnLoc, type);
        itEntityUUID = entity.getUniqueId();

        // Mark as stalker
        entity.getPersistentDataContainer().set(stalkerKey, PersistentDataType.BYTE, (byte) 1);

        // Reset mode/state
        isVexMode = (type == EntityType.VEX);
        secondsStuck = 0;
        lastStalkerPos = spawnLoc.clone();
        if (isVexMode) secondsInVexMode = 0;

        // Configure stats
        if (entity instanceof LivingEntity living) {
            if (living.getAttribute(Attribute.MAX_HEALTH) != null) {
                living.getAttribute(Attribute.MAX_HEALTH).setBaseValue(100.0);
            }
            living.setHealth(100.0);

            if (living.getAttribute(Attribute.MOVEMENT_SPEED) != null) {
                double moveSpeed = isVexMode ? vexMovementSpeed : allowedFormsMovementSpeed;
                living.getAttribute(Attribute.MOVEMENT_SPEED).setBaseValue(moveSpeed);
            }
            if (living.getAttribute(Attribute.FLYING_SPEED) != null) {
                double flySpeed = isVexMode ? vexFlyingSpeed : allowedFormsMovementSpeed;
                living.getAttribute(Attribute.FLYING_SPEED).setBaseValue(flySpeed);
            }

            if (living.getAttribute(Attribute.KNOCKBACK_RESISTANCE) != null) {
                living.getAttribute(Attribute.KNOCKBACK_RESISTANCE).setBaseValue(1.0);
            }

            if (living instanceof Mob mob) {
                if (mob.getEquipment() != null) mob.getEquipment().clear();
            }

            living.setSilent(true);
            living.setRemoveWhenFarAway(false);
            living.setPersistent(true);
        }
    }

    /**
     * Morphs the current stalker into another type (e.g., walker -> Vex or Vex -> random walker).
     */
    private void morphEntity(Mob oldEntity, Player victim, EntityType newType) {
        Location loc = oldEntity.getLocation();
        oldEntity.remove();
        spawnSpecificEntity(loc, victim, newType);
        if (loc.getWorld() != null) loc.getWorld().playEffect(loc, org.bukkit.Effect.MOBSPAWNER_FLAMES, 0);
    }

    private void removeItEntity() {
        if (itEntityUUID != null) {
            Entity e = Bukkit.getEntity(itEntityUUID);
            if (e != null) e.remove();
            itEntityUUID = null;
        }
        isVexMode = false;
        secondsStuck = 0;
    }

    // --- Curse Targeting ---

    private void pickRandomTarget() {
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        players.removeIf(p -> p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR);

        if (!players.isEmpty()) {
            Player target = players.get(new Random().nextInt(players.size()));
            setCursedPlayer(target);
        } else {
            cursedPlayerUUID = null;
        }
    }

    private void setCursedPlayer(Player player) {
        this.cursedPlayerUUID = player.getUniqueId();
        player.sendMessage(formatMessage(msgCurseAssigned, null));
        removeItEntity();
    }

    // --- Doors / Climbing / Ladders ---

    private void handleDoors(Mob mob) {
        Block blockInFront = mob.getEyeLocation().add(mob.getLocation().getDirection()).getBlock();
        Block blockAtFeet = mob.getLocation().add(mob.getLocation().getDirection()).getBlock();
        forceOpen(blockInFront);
        forceOpen(blockAtFeet);
    }

    private void forceOpen(Block block) {
        if (block.getType().toString().contains("DOOR") || block.getType().toString().contains("GATE")) {
            if (block.getBlockData() instanceof Openable openable) {
                if (!openable.isOpen()) {
                    openable.setOpen(true);
                    block.setBlockData(openable);
                    block.getWorld().playSound(block.getLocation(), org.bukkit.Sound.BLOCK_IRON_DOOR_OPEN, 1f, 0.5f);
                }
            }
        }
    }

    private void handleLadderDescent(Mob mob, Player victim) {
        String blockType = mob.getLocation().getBlock().getType().toString();
        if (blockType.contains("LADDER") || blockType.contains("VINE")) {
            if (victim.getLocation().getY() < mob.getLocation().getY()) {
                Vector vel = mob.getVelocity();
                vel.setY(-0.15);
                mob.setVelocity(vel);
                mob.setFallDistance(0);
            }
        }
    }

    /**
     * A lightweight "climb" behavior for walker forms:
     * If a solid obstacle is directly in front, apply a small upward + forward impulse.
     */
    private void handleClimbing(Mob mob) {
        Location loc = mob.getLocation();

        String blockName = loc.getBlock().getType().toString();
        if (blockName.contains("LADDER") || blockName.contains("VINE")) {
            return;
        }

        Vector direction = loc.getDirection().setY(0).normalize();
        Block frontFeet = loc.clone().add(direction.clone().multiply(0.6)).getBlock();
        Block frontHead = loc.clone().add(0, 1, 0).add(direction.clone().multiply(0.6)).getBlock();

        if (isObstacle(frontFeet) || isObstacle(frontHead)) {
            Vector velocity = mob.getVelocity();
            velocity.setY(0.2);
            velocity.add(direction.multiply(0.1));
            mob.setVelocity(velocity);
            mob.setFallDistance(0);
        }
    }

    private boolean isObstacle(Block b) {
        String name = b.getType().toString();
        return b.getType().isSolid()
                && !name.contains("DOOR")
                && !name.contains("GATE")
                && !name.contains("TRAPDOOR")
                && !name.contains("LADDER")
                && !name.contains("VINE");
    }

    // --- Cross-Dimension Safety / Distance ---

    private boolean isSameWorld(Location a, Location b) {
        if (a == null || b == null) return false;
        if (a.getWorld() == null || b.getWorld() == null) return false;
        return a.getWorld().equals(b.getWorld());
    }

    private double safeDistance(Location a, Location b) {
        if (!isSameWorld(a, b)) return Double.MAX_VALUE;
        return a.distance(b);
    }

    // --- Speed Helpers ---

    private double getCurrentPathfinderSpeed(Mob mob) {
        if (mob instanceof Vex || isVexMode) {
            return vexPathfinderSpeed;
        }
        return allowedFormsPathfinderSpeed;
    }

    // --- Boat Trap Prevention ---

    /**
     * Prevents players from trapping the stalker in a boat.
     *
     * - If the stalker is inside a boat: eject and remove it.
     * - If a boat is nearby: remove it if empty, and push the stalker away.
     */
    private void handleBoatTrapPrevention(Mob mob) {
        if (boatTrapRadius <= 0) return;

        if (mob.getVehicle() instanceof Boat boat) {
            boat.eject();
            boat.remove();
        }

        for (Entity e : mob.getNearbyEntities(boatTrapRadius, boatTrapRadius, boatTrapRadius)) {
            if (e instanceof Boat boat) {
                boolean hasPlayerPassenger = boat.getPassengers().stream().anyMatch(p -> p instanceof Player);
                if (!hasPlayerPassenger) {
                    boat.remove();
                }

                Vector away = mob.getLocation().toVector().subtract(boat.getLocation().toVector());
                if (away.lengthSquared() < 0.0001) away = new Vector(1, 0, 0);
                away.setY(0).normalize();
                mob.setVelocity(away.multiply(0.35).setY(0.05));
            }
        }
    }

    @EventHandler
    public void onStalkerEnterBoat(VehicleEnterEvent event) {
        if (!(event.getVehicle() instanceof Boat)) return;
        if (event.getEntered() == null) return;
        if (event.getEntered().getPersistentDataContainer().has(stalkerKey, PersistentDataType.BYTE)) {
            event.setCancelled(true);
        }
    }

    // --- Fear Logic ---

    /**
     * Returns the nearest fear source within any configured fear radius.
     * Throttled because scanning blocks is expensive.
     */
    private FearSource getFearSource(Location center) {
        long now = System.currentTimeMillis();
        if (now - lastFearScanMs < 750) {
            return cachedFearSource;
        }
        lastFearScanMs = now;
        cachedFearSource = scanForFearSource(center);
        return cachedFearSource;
    }

    /**
     * Full scan of blocks within the maximum possible fear radius.
     * Picks the closest fear source that is inside its own perimeter radius.
     */
    private FearSource scanForFearSource(Location center) {
        if (center == null || center.getWorld() == null) return null;

        double maxRadius = 0.0;
        if (fearFireEnabled && fearFireRadius > 0) maxRadius = Math.max(maxRadius, fearFireRadius + fearAvoidPerimeterBuffer);
        if (fearSoulTorchEnabled && fearSoulTorchRadius > 0) maxRadius = Math.max(maxRadius, fearSoulTorchRadius + fearAvoidPerimeterBuffer);
        if (fearSoulLanternEnabled && fearSoulLanternRadius > 0) maxRadius = Math.max(maxRadius, fearSoulLanternRadius + fearAvoidPerimeterBuffer);
        if (fearSoulCampfireEnabled && fearSoulCampfireRadius > 0) maxRadius = Math.max(maxRadius, fearSoulCampfireRadius + fearAvoidPerimeterBuffer);
        if (maxRadius <= 0) return null;

        int r = (int) Math.ceil(maxRadius);
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();

        FearSource best = null;
        double bestDist2 = Double.MAX_VALUE;

        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    int x = cx + dx;
                    int y = cy + dy;
                    int z = cz + dz;

                    Block block = center.getWorld().getBlockAt(x, y, z);
                    Material type = block.getType();

                    FearType fearType = null;
                    double radius = 0.0;

                    // Fire / Soul Fire
                    if (fearFireEnabled && fearFireRadius > 0 && (type == Material.FIRE || type == Material.SOUL_FIRE)) {
                        fearType = FearType.FIRE;
                        radius = fearFireRadius;
                    }
                    // Soul Torch
                    else if (fearSoulTorchEnabled && fearSoulTorchRadius > 0 && (type == Material.SOUL_TORCH || type == Material.SOUL_WALL_TORCH)) {
                        fearType = FearType.SOUL_TORCH;
                        radius = fearSoulTorchRadius;
                    }
                    // Soul Lantern
                    else if (fearSoulLanternEnabled && fearSoulLanternRadius > 0 && type == Material.SOUL_LANTERN) {
                        fearType = FearType.SOUL_LANTERN;
                        radius = fearSoulLanternRadius;
                    }
                    // Soul Campfire
                    else if (fearSoulCampfireEnabled && fearSoulCampfireRadius > 0 && type == Material.SOUL_CAMPFIRE) {
                        fearType = FearType.SOUL_CAMPFIRE;
                        radius = fearSoulCampfireRadius;
                    }

                    if (fearType == null) continue;

                    double dist2 = dx * dx + dy * dy + dz * dz;

                    // Expand detection slightly so the stalker can settle at the perimeter
                    double perimeterRadius = radius + fearAvoidPerimeterBuffer;
                    if (dist2 > perimeterRadius * perimeterRadius) continue;

                    if (dist2 < bestDist2) {
                        bestDist2 = dist2;
                        best = new FearSource(fearType, block.getLocation().add(0.5, 0.5, 0.5));
                    }
                }
            }
        }

        return best;
    }

    /**
     * Fear avoidance update.
     *
     * IMPORTANT CHANGE (per request):
     *  - The stalker will stand still at the perimeter when the victim is inside the fear radius.
     *  - If the victim is outside the radius but the fear zone is "between" the stalker and victim,
     *    the stalker commits to stepping around the perimeter until the direct route clears.
     *
     * @return true if fear logic set the movement for this tick (skip normal chase)
     */
    private boolean handleFear(Mob mob, Player victim, FearSource fearSource) {
        if (fearSource == null || mob == null) return false;

        double baseRadius = getBaseFearRadius(fearSource.type);
        if (baseRadius <= 0.0) return false;

        // "Perimeter" radius adds a buffer to reduce oscillation.
        double perimeterRadius = baseRadius + Math.max(0.0, fearAvoidPerimeterBuffer);

        Location mobLoc = mob.getLocation();
        Location srcLoc = fearSource.location;
        if (!isSameWorld(mobLoc, srcLoc)) return false;

        // 0) Always push out if the stalker is inside the perimeter.
        double mobDist = horizontalDistance(mobLoc, srcLoc);
        if (mobDist < perimeterRadius) {
            Location edgePoint = perimeterPoint(srcLoc, mobLoc, perimeterRadius, mobLoc.getY());
            mob.setTarget(null);
            mob.getPathfinder().moveTo(edgePoint, getCurrentPathfinderSpeed(mob));

            // A small outward nudge prevents getting "stuck" inside the zone.
            Vector out = mobLoc.toVector().subtract(srcLoc.toVector());
            out.setY(0);
            if (out.lengthSquared() < 0.0001) out = new Vector(1, 0, 0);
            out.normalize();
            mob.setVelocity(out.multiply(0.12).setY(0.04));
            return true;
        }

        // If we do not have a valid victim context, just hold the perimeter.
        if (victim == null || !victim.isOnline() || !isSameWorld(srcLoc, victim.getLocation())) {
            Location hold = perimeterPoint(srcLoc, mobLoc, perimeterRadius, mobLoc.getY());
            mob.setTarget(null);
            mob.getPathfinder().moveTo(hold, getCurrentPathfinderSpeed(mob));
            return true;
        }

        Location victimLoc = victim.getLocation();

        // 1) If the victim is inside the BASE fear radius, hold still at the edge.
        //    This prevents the undesirable "orbiting" behavior while the player is protected.
        double victimDist = horizontalDistance(victimLoc, srcLoc);
        if (victimDist < baseRadius) {
            // Player is actively protected by the fear source.
            // Hold still at the perimeter (do NOT orbit / track around the edge).
            Location holdEdge = perimeterPoint(srcLoc, mobLoc, perimeterRadius, mobLoc.getY());
            mob.setTarget(null);
            mob.getPathfinder().moveTo(holdEdge, getCurrentPathfinderSpeed(mob));

            // Reduce jitter when already "at" the edge.
            if (mobLoc.distanceSquared(holdEdge) < 0.5 * 0.5) {
                mob.setVelocity(new Vector(0, 0, 0));
            }

            return true;
        }

        // 2) Victim is NOT protected by the fear radius.
        //    If the fear zone blocks the direct route, path around the perimeter.
        //    Otherwise, allow the normal chase logic to execute.
        boolean blocked = segmentIntersectsCircle2D(mobLoc, victimLoc, srcLoc, perimeterRadius);
        if (blocked) {
            Location around = stepAlongPerimeterTowardsVictim(srcLoc, mobLoc, victimLoc, perimeterRadius);
            mob.setTarget(null);
            mob.getPathfinder().moveTo(around, getCurrentPathfinderSpeed(mob));
            return true;
        }

        return false;
    }

    private double getBaseFearRadius(FearType type) {
        return switch (type) {
            case FIRE -> fearFireRadius;
            case SOUL_TORCH -> fearSoulTorchRadius;
            case SOUL_LANTERN -> fearSoulLanternRadius;
            case SOUL_CAMPFIRE -> fearSoulCampfireRadius;
        };
    }

    /**
     * Computes a stable point on the perimeter circle (XZ plane) in the direction of {@code toward}.
     */
    private Location perimeterPoint(Location center, Location toward, double radius, double y) {
        Vector dir = toward.toVector().subtract(center.toVector());
        dir.setY(0);
        if (dir.lengthSquared() < 0.0001) dir = new Vector(1, 0, 0);
        dir.normalize().multiply(radius);

        Location out = center.clone().add(dir);
        out.setY(y);
        return out;
    }

    /**
     * Horizontal (XZ) distance between two locations.
     * The fear avoidance is modeled as a cylinder for walkers.
     */
    private double horizontalDistance(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Checks if the segment from start -> end intersects the interior of a circle in the XZ plane.
     *
     * This is used to decide when the stalker must path around the fear radius ("player on the other side").
     */
    private boolean segmentIntersectsCircle2D(Location start, Location end, Location center, double radius) {
        if (!isSameWorld(start, end) || !isSameWorld(start, center)) return false;

        // Translate to circle-at-origin coordinates
        double ax = start.getX() - center.getX();
        double az = start.getZ() - center.getZ();
        double bx = end.getX() - center.getX();
        double bz = end.getZ() - center.getZ();

        double dx = bx - ax;
        double dz = bz - az;

        double r2 = radius * radius;

        // If either endpoint is inside the circle, treat as intersecting.
        if ((ax * ax + az * az) < r2) return true;
        if ((bx * bx + bz * bz) < r2) return true;

        double d2 = dx * dx + dz * dz;
        if (d2 < 0.000001) {
            // Segment length is ~zero; endpoints already checked.
            return false;
        }

        // Find closest approach to origin along the segment
        double t = -((ax * dx) + (az * dz)) / d2;
        if (t < 0.0) t = 0.0;
        if (t > 1.0) t = 1.0;

        double cx = ax + dx * t;
        double cz = az + dz * t;

        double dist2 = cx * cx + cz * cz;
        return dist2 < r2;
    }

    /**
     * Computes the next waypoint while moving along the fear perimeter towards the victim.
     *
     * This does not try to perfectly solve shortest-path around a circle; it is an intentional
     * "committed" stepping approach that is stable and avoids constant jitter.
     */
    private Location stepAlongPerimeterTowardsVictim(Location center, Location mobLoc, Location victimLoc, double perimeterRadius) {
        double ax = mobLoc.getX() - center.getX();
        double az = mobLoc.getZ() - center.getZ();
        double bx = victimLoc.getX() - center.getX();
        double bz = victimLoc.getZ() - center.getZ();

        // Current polar angles
        double thetaMob = Math.atan2(az, ax);
        double thetaVictim = Math.atan2(bz, bx);

        double delta = wrapRadians(thetaVictim - thetaMob);
        double dir = (delta >= 0) ? 1.0 : -1.0; // ccw if positive, cw if negative

        // Convert configured step distance (blocks) to an angular step.
        // Clamp to avoid huge jumps on small radiuses and tiny jitter on large radiuses.
        double stepAngle = fearAvoidStepDistance / Math.max(1.0, perimeterRadius);
        stepAngle = clamp(stepAngle, 0.02, 0.45);

        double nextTheta = thetaMob + dir * stepAngle;

        double wx = center.getX() + perimeterRadius * Math.cos(nextTheta);
        double wz = center.getZ() + perimeterRadius * Math.sin(nextTheta);

        // Keep the current Y to avoid weird vertical demands on the ground pathfinder.
        return new Location(center.getWorld(), wx, mobLoc.getY(), wz);
    }

    private double wrapRadians(double r) {
        // Normalize to (-pi, pi]
        while (r <= -Math.PI) r += 2 * Math.PI;
        while (r > Math.PI) r -= 2 * Math.PI;
        return r;
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    // --- Events (Curse Transfer / Safety) ---

    @EventHandler
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (!(event.getDamager() instanceof Player attacker)) return;

        // Only the currently cursed player can transfer the curse
        if (attacker.getUniqueId().equals(cursedPlayerUUID)) {
            long cooldownMs = transferCooldownSeconds * 1000L;
            if (System.currentTimeMillis() - lastTransferTime < cooldownMs) {
                long timeLeft = (cooldownMs - (System.currentTimeMillis() - lastTransferTime)) / 1000;
                attacker.sendMessage(formatMessage(msgCurseCooldown, Map.of("seconds", String.valueOf(timeLeft))));
                return;
            }

            setCursedPlayer(victim);
            lastTransferTime = System.currentTimeMillis();

            attacker.sendMessage(formatMessage(msgCursePassedAttacker, Map.of("victim", victim.getName())));
            victim.sendMessage(formatMessage(msgCursePassedVictim, null));
        }
    }

    @EventHandler
    public void onSunBurn(EntityCombustEvent event) {
        if (itEntityUUID != null && event.getEntity().getUniqueId().equals(itEntityUUID)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (itEntityUUID != null && event.getEntity().getUniqueId().equals(itEntityUUID)) {
            event.getDrops().clear();
            event.setDroppedExp(0);
            itEntityUUID = null;
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (itEntityUUID != null) {
            Entity it = Bukkit.getEntity(itEntityUUID);
            if (it != null && !event.getPlayer().getUniqueId().equals(cursedPlayerUUID)) {
                event.getPlayer().hideEntity(this, it);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (event.getPlayer().getUniqueId().equals(cursedPlayerUUID)) {
            cursedLogoutTime = System.currentTimeMillis();
        }
    }

    // --- Commands ---

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (command.getName().equalsIgnoreCase("startcurse")) {
            if (args.length != 1) return false;
            Player target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                sender.sendMessage(formatMessage(msgPlayerNotFound, null));
                return true;
            }
            setCursedPlayer(target);
            sender.sendMessage(formatMessage(msgCurseStartedAdmin, Map.of("target", target.getName())));
            return true;
        } else if (command.getName().equalsIgnoreCase("cursereload")) {
            reloadConfig();
            loadConfig();
            sender.sendMessage(formatMessage(msgConfigReloaded, null));
            return true;
        }
        return false;
    }
}
