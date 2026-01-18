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

    // Stalker stats
    private double stalkerMaxHealth;
    private double stalkerDamage;

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

    // Cached victim-protection scan (fear sources around the cursed player)
    private FearSource cachedVictimProtectionSource;
    private long lastVictimProtectionScanMs = 0;

    /** True while the cursed player is inside a fear "safe zone". */
    private volatile boolean victimProtectedByFear = false;

    /**
     * Tracks whether we've already allowed a "protection edge" Vex morph during the current
     * victim-protected window.
     *
     * This prevents rapid cycling: walker holds edge -> morphs to Vex -> morphs back ->
     * immediately morphs again while the player remains inside the same safety bubble.
     */
    private boolean vexTriggeredDuringVictimProtection = false;

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

        // Stalker stats
        stalkerMaxHealth = Math.max(1.0, config.getDouble("stalker.max_health", 100.0));
        stalkerDamage = Math.max(0.0, config.getDouble("stalker.damage", 12.0));

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
        // Reset per-tick protection flag. It will be re-enabled if the victim is in a safety radius.
        victimProtectedByFear = false;

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

        // --- Safety Radius (Feared Objects) ---
        // If the cursed player is standing inside the safety radius of any fear source,
        // the stalker should approach the perimeter of that fear radius and stop there.
        // This prevents the "freezing wherever it is" behavior while still respecting the bubble.
        FearSource victimProtection = getVictimProtectionSource(victim.getLocation());
        boolean victimIsProtected = victimProtection != null;
        victimProtectedByFear = victimIsProtected;

        // Leaving a protection bubble resets the one-shot "edge Vex" guard.
        if (!victimIsProtected) {
            vexTriggeredDuringVictimProtection = false;
        }

        if (victimIsProtected) {
            holdAtFearPerimeter(mob, victim, victimProtection);
            return;
        } else {
            // Ensure AI is re-enabled when the victim leaves the safety radius.
            if (!mob.hasAI()) {
                mob.setAI(true);
            }
        }

        // Fear logic: certain blocks repel the stalker.
        // Updated behavior:
        //  - If the cursed player is inside any safety radius, the stalker walks to the edge and holds there.
        //  - Otherwise, feared objects behave as *spherical* no-entry zones:
        //      * walkers step around the perimeter
        //      * Vex (flying) forms can route above the sphere
        FearSource fearSource = getFearSource(mob.getLocation());

        boolean fearOverrodeMovement = false;
        if (fearSource != null) {
            fearOverrodeMovement = handleFear(mob, victim, fearSource);
        }

        // Pursuit: only run normal chase logic if fear logic did not override movement this tick.
        if (!fearOverrodeMovement) {
            mob.getPathfinder().moveTo(victim.getLocation(), getCurrentPathfinderSpeed(mob));
            mob.setTarget(victim);
        }

        // Door opening / breaking
        handleDoors(mob);

        // NOTE:
        // The older "spider" wall-climbing logic is no longer necessary now that Vex mode exists
        // for resolving navigation edge cases. It also tended to interfere with fear-perimeter
        // holding and produced jittery movement.
        //
        // If you ever want this back, re-enable the call below.
        // if (!isVexMode) handleClimbing(mob);

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
                victim.damage(stalkerDamage, mob);
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

            // Once the Vex timer expires we ALWAYS morph back to a walking form.
            // The morph implementation snaps the spawn location down to safe ground,
            // which prevents the "stuck hovering forever" edge case.
            if (secondsInVexMode >= vexDurationSeconds) {
                if (it instanceof Mob mob) {
                    Player victim = (cursedPlayerUUID != null) ? Bukkit.getPlayer(cursedPlayerUUID) : null;
                    if (victim != null) {
                        morphEntity(mob, victim, null);
                    }
                }
            }
            return;
        }

        // --- Walker stuck logic (Turn into Vex) ---
        if (lastStalkerPos != null) {
            Location now = it.getLocation();
            double movedHoriz = horizontalDistance(now, lastStalkerPos);
            double movedY = Math.abs(now.getY() - lastStalkerPos.getY());

            // Consider "still" as very low horizontal drift and small vertical bob.
            // This avoids missing stuck events due to tiny perimeter jitter.
            boolean still = movedHoriz < 0.08 && movedY < 0.25;
            if (still) {
                secondsStuck++;
            } else {
                secondsStuck = 0;
            }
        }
        lastStalkerPos = it.getLocation().clone();

        if (secondsStuck >= vexTriggerSeconds) {
            // While the victim remains protected, only allow ONE Vex morph to avoid
            // constant morph cycling while holding the perimeter.
            if (victimProtectedByFear && vexTriggeredDuringVictimProtection) {
                secondsStuck = 0;
                return;
            }

            Player victim = (cursedPlayerUUID != null) ? Bukkit.getPlayer(cursedPlayerUUID) : null;
            if (victim != null && it instanceof Mob mob) {
                morphEntity(mob, victim, EntityType.VEX);
                if (victimProtectedByFear) {
                    vexTriggeredDuringVictimProtection = true;
                }
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
                living.getAttribute(Attribute.MAX_HEALTH).setBaseValue(stalkerMaxHealth);
            }
            living.setHealth(stalkerMaxHealth);

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

        // When morphing OUT of Vex mode (back to a ground walker), snap the spawn point down
        // onto safe ground so we do not create a walker in mid-air.
        if (newType == null || newType != EntityType.VEX) {
            loc = snapToSafeGround(loc, 32);
        }
        oldEntity.remove();
        spawnSpecificEntity(loc, victim, newType);
        if (loc.getWorld() != null) loc.getWorld().playEffect(loc, org.bukkit.Effect.MOBSPAWNER_FLAMES, 0);
    }

    /**
     * Attempts to move a location down onto a safe ground position (solid floor + 2 blocks of headroom).
     *
     * This is used primarily when reverting from Vex -> walker to prevent the stalker from being
     * stranded hovering on a fear perimeter and never returning to a walking form.
     */
    private Location snapToSafeGround(Location desired, int maxDownBlocks) {
        if (desired == null || desired.getWorld() == null) return desired;

        World world = desired.getWorld();

        int x = desired.getBlockX();
        int z = desired.getBlockZ();
        int startY = desired.getBlockY();

        int minY = Math.max(world.getMinHeight(), startY - Math.max(1, maxDownBlocks));
        for (int y = startY; y >= minY; y--) {
            Block floor = world.getBlockAt(x, y, z);
            if (!floor.getType().isSolid()) continue;

            Block feet = world.getBlockAt(x, y + 1, z);
            Block head = world.getBlockAt(x, y + 2, z);
            if (!feet.getType().isSolid() && !head.getType().isSolid()) {
                Location out = new Location(world, x + 0.5, y + 1, z + 0.5);
                out.setYaw(desired.getYaw());
                out.setPitch(desired.getPitch());
                return out;
            }
        }

        // Fallback: use highest terrain at this XZ.
        int highest = world.getHighestBlockYAt(x, z);
        Location out = new Location(world, x + 0.5, highest + 1, z + 0.5);
        out.setYaw(desired.getYaw());
        out.setPitch(desired.getPitch());
        return out;
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

    /**
     * Hard-freezes the stalker in place.
     *
     * Used when the cursed player is inside a fear-source safety radius.
     * This ensures the entity does not "orbit" the perimeter, does not attack,
     * and does not trigger Vex anti-stuck morphing.
     */
    private void freezeStalkerInPlace(Mob mob) {
        if (mob == null) return;

        // Stop any current navigation request.
        mob.setTarget(null);
        mob.getPathfinder().moveTo(mob.getLocation(), 0.0);

        // Zero out motion.
        mob.setVelocity(new Vector(0, 0, 0));
        mob.setFallDistance(0);

        // Disable AI to prevent idle turning/wandering.
        mob.setAI(false);

        // Ensure anti-stuck logic does not consider this a "stuck" scenario.
        secondsStuck = 0;
        lastStalkerPos = mob.getLocation().clone();
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
     * Returns a fear source that is actively protecting the cursed player
     * (i.e., the player is inside that fear source's BASE radius).
     *
     * This is separate from {@link #getFearSource(Location)} because we need
     * "player safety bubble" behavior even when the stalker is far away.
     */
    private FearSource getVictimProtectionSource(Location victimCenter) {
        long now = System.currentTimeMillis();
        if (now - lastVictimProtectionScanMs < 750) {
            return cachedVictimProtectionSource;
        }
        lastVictimProtectionScanMs = now;
        cachedVictimProtectionSource = scanForProtectingFearSource(victimCenter);
        return cachedVictimProtectionSource;
    }

    /**
     * Full scan around the player for fear sources where the player is WITHIN the BASE radius.
     *
     * This directly supports the behavior:
     *  - While the player is inside the safety radius, the stalker freezes in place.
     */
    private FearSource scanForProtectingFearSource(Location center) {
        if (center == null || center.getWorld() == null) return null;

        double maxRadius = 0.0;
        if (fearFireEnabled && fearFireRadius > 0) maxRadius = Math.max(maxRadius, fearFireRadius);
        if (fearSoulTorchEnabled && fearSoulTorchRadius > 0) maxRadius = Math.max(maxRadius, fearSoulTorchRadius);
        if (fearSoulLanternEnabled && fearSoulLanternRadius > 0) maxRadius = Math.max(maxRadius, fearSoulLanternRadius);
        if (fearSoulCampfireEnabled && fearSoulCampfireRadius > 0) maxRadius = Math.max(maxRadius, fearSoulCampfireRadius);
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
                    if (dist2 > radius * radius) continue;

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
     * Desired behavior:
     *  - Fear zones are treated as spherical volumes.
     *  - If the cursed player is inside a fear zone, the stalker freezes entirely
     *    (handled earlier in tickLogic via {@link #getVictimProtectionSource(Location)}).
     *  - If the player is outside, the stalker will avoid entering the sphere and will
     *    navigate around it. If the stalker is a Vex (flying), it can route "over" the sphere.
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

        final boolean canFly = (mob instanceof Vex) || isVexMode;

        // 0) Always push out if the stalker is inside the perimeter sphere.
        double mobDist = mobLoc.distance(srcLoc);
        if (mobDist < perimeterRadius) {
            // Even for Vex forms, keep fear-edge waypoints on the current Y-slice.
            // This prevents "upper hemisphere" target selection which can look like
            // the stalker is stuck hovering above the perimeter.
            Location edgePoint = perimeterPoint(srcLoc, mobLoc, perimeterRadius, mobLoc.getY());
            mob.setTarget(null);
            mob.getPathfinder().moveTo(edgePoint, getCurrentPathfinderSpeed(mob));

            // A small outward nudge prevents getting "stuck" inside the zone.
            Vector out = mobLoc.toVector().subtract(srcLoc.toVector());
            if (!canFly) out.setY(0);
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

        // 1) If the victim is inside the BASE fear radius, hold at the perimeter.
        //    (This should already be handled by tickLogic, but keep it here for robustness.)
        double victimDist = victimLoc.distance(srcLoc);
        if (victimDist < baseRadius) {
            holdAtFearPerimeter(mob, victim, fearSource);
            return true;
        }

        // 2) Victim is NOT protected by the fear radius.
        //    If the fear zone blocks the direct route, path around the perimeter.
        //    Otherwise, allow the normal chase logic to execute.
        boolean blocked = canFly
                ? segmentIntersectsSphere(mobLoc, victimLoc, srcLoc, perimeterRadius)
                : segmentIntersectsCircleXZ(mobLoc, victimLoc, srcLoc, perimeterRadius);
        if (blocked) {
            // IMPORTANT:
            // Even in Vex mode we do NOT want to "phase" through the safety bubble by flying over it.
            // That defeats the purpose of the fear zone and looks unfair.
            //
            // Instead we path *around the perimeter* for both walkers and fliers. (Vex can still benefit
            // from collision-free navigation, but it must respect the bubble as a no-entry volume.)
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
     * Computes a stable point on the SURFACE of a spherical fear zone at the requested Y level.
     *
     * For walkers we keep path requests on the ground-plane (constant Y) to avoid asking the
     * navigator to move to mid-air points.
     */
    private Location perimeterPoint(Location center, Location toward, double sphereRadius, double y) {
        // Compute the sphere slice radius at this Y: r_slice = sqrt(R^2 - dy^2)
        double dy = y - center.getY();
        double r2 = (sphereRadius * sphereRadius) - (dy * dy);
        double sliceRadius = (r2 > 0) ? Math.sqrt(r2) : 0.0;

        Vector dir = toward.toVector().subtract(center.toVector());
        dir.setY(0);
        if (dir.lengthSquared() < 0.0001) dir = new Vector(1, 0, 0);
        dir.normalize().multiply(sliceRadius);

        Location out = center.clone().add(dir);
        out.setY(y);
        return out;
    }

    /**
     * Computes a point on the surface of a spherical fear zone in full 3D.
     * Used by flying forms (Vex) to avoid the zone without flattening to XZ.
     */
    private Location perimeterPointOnSphere(Location center, Location toward, double sphereRadius) {
        Vector dir = toward.toVector().subtract(center.toVector());
        if (dir.lengthSquared() < 0.0001) dir = new Vector(1, 0, 0);
        dir.normalize().multiply(sphereRadius);
        return center.clone().add(dir);
    }

    /** Horizontal (XZ) distance between two locations. */
    private double horizontalDistance(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Checks if the segment from start -> end intersects the interior of a sphere.
     *
     * This is used to decide when the stalker must path around a spherical fear zone
     * ("player on the other side").
     */
    private boolean segmentIntersectsSphere(Location start, Location end, Location center, double radius) {
        if (!isSameWorld(start, end) || !isSameWorld(start, center)) return false;

        // Translate so the sphere is at the origin.
        double ax = start.getX() - center.getX();
        double ay = start.getY() - center.getY();
        double az = start.getZ() - center.getZ();

        double bx = end.getX() - center.getX();
        double by = end.getY() - center.getY();
        double bz = end.getZ() - center.getZ();

        double dx = bx - ax;
        double dy = by - ay;
        double dz = bz - az;

        double r2 = radius * radius;

        // If either endpoint is inside the sphere, treat as intersecting.
        double a2 = ax * ax + ay * ay + az * az;
        double b2 = bx * bx + by * by + bz * bz;
        if (a2 < r2) return true;
        if (b2 < r2) return true;

        double d2 = dx * dx + dy * dy + dz * dz;
        if (d2 < 0.000001) {
            // Segment length is ~zero; endpoints already checked.
            return false;
        }

        // Solve |a + t d|^2 = r^2 for t in [0,1]
        double bDot = 2.0 * (ax * dx + ay * dy + az * dz);
        double c = a2 - r2;

        double disc = bDot * bDot - 4.0 * d2 * c;
        if (disc < 0.0) return false;

        double sqrt = Math.sqrt(disc);
        double t1 = (-bDot - sqrt) / (2.0 * d2);
        double t2 = (-bDot + sqrt) / (2.0 * d2);

        return (t1 >= 0.0 && t1 <= 1.0) || (t2 >= 0.0 && t2 <= 1.0);
    }

    /**
     * Computes the next waypoint while moving along the fear perimeter towards the victim.
     *
     * This does not try to perfectly solve shortest-path around a circle; it is an intentional
     * "committed" stepping approach that is stable and avoids constant jitter.
     */
    private Location stepAlongPerimeterTowardsVictim(Location center, Location mobLoc, Location victimLoc, double perimeterRadius) {
        // Walkers move around the sphere on the current Y slice.
        double dy = mobLoc.getY() - center.getY();
        double sliceR2 = (perimeterRadius * perimeterRadius) - (dy * dy);
        double sliceRadius = (sliceR2 > 0) ? Math.sqrt(sliceR2) : 0.0;

        // If the slice is effectively a point (near the top/bottom), fall back to a minimal circle.
        if (sliceRadius < 0.5) sliceRadius = Math.max(0.5, perimeterRadius * 0.35);

        // Stay slightly OUTSIDE the exact perimeter to reduce "bouncing" on the edge.
        sliceRadius += Math.max(0.25, fearAvoidPerimeterBuffer * 0.35);

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
        double stepAngle = fearAvoidStepDistance / Math.max(1.0, sliceRadius);
        stepAngle = clamp(stepAngle, 0.02, 0.45);

        double nextTheta = thetaMob + dir * stepAngle;

        double wx = center.getX() + sliceRadius * Math.cos(nextTheta);
        double wz = center.getZ() + sliceRadius * Math.sin(nextTheta);

        // Keep the current Y to avoid weird vertical demands on the ground pathfinder.
        return new Location(center.getWorld(), wx, mobLoc.getY(), wz);
    }

    /**
     * Flying avoidance waypoint: place a target above the sphere so the Vex can fly "over" the safe zone.
     */
    private Location flyOverSphereWaypoint(Location center, double sphereRadius, Location victimLoc) {
        Vector dir = victimLoc.toVector().subtract(center.toVector());
        dir.setY(0);
        if (dir.lengthSquared() < 0.0001) dir = new Vector(1, 0, 0);
        dir.normalize().multiply(sphereRadius + 2.0);

        Location wp = center.clone().add(dir);
        // Ensure we're meaningfully above the bubble even if the victim is higher up.
        wp.setY(Math.max(center.getY() + sphereRadius + 4.0, victimLoc.getY() + 3.0));
        return wp;
    }

    /**
     * When the cursed player is inside a fear radius, the stalker should approach
     * the perimeter (edge) of that safety bubble and hold there.
     */
    private void holdAtFearPerimeter(Mob mob, Player victim, FearSource protectingSource) {
        if (mob == null || victim == null || protectingSource == null) return;
        if (!isSameWorld(mob.getLocation(), protectingSource.location)) return;

        double baseRadius = getBaseFearRadius(protectingSource.type);
        if (baseRadius <= 0.0) return;

        double perimeterRadius = baseRadius + Math.max(0.0, fearAvoidPerimeterBuffer);

        Location center = protectingSource.location;
        Location mobLoc = mob.getLocation();

        final boolean canFly = (mob instanceof Vex) || isVexMode;

        // IMPORTANT:
        // When the victim is protected by a fear bubble we want the stalker to *walk up to the edge*
        // and then *hold that edge point* rather than constantly "orbit" the bubble.
        //
        // The old behavior targeted the perimeter point "closest to the victim direction", which
        // causes continuous movement around the bubble as the victim moves.
        //
        // New behavior: target the perimeter point on the same radial line from the source -> stalker.
        // This yields a stable edge point and makes the stalker stand still once it reaches it.
        // NOTE:
        // For flying forms we intentionally keep the "hold" waypoint near the victim's Y-level.
        // This prevents the Vex from selecting an "upper" point on the sphere and appearing
        // stuck hovering in mid-air at the perimeter.
        Location edge = canFly
                ? perimeterPoint(center, mobLoc, perimeterRadius, victim.getLocation().getY())
                : perimeterPoint(center, mobLoc, perimeterRadius, mobLoc.getY());

        // If we ended up inside the zone (terrain/pathfinder weirdness), push outward.
        double mobDist = mobLoc.distance(center);
        if (mobDist < perimeterRadius) {
            Vector out = mobLoc.toVector().subtract(center.toVector());
            if (!canFly) out.setY(0);
            if (out.lengthSquared() < 0.0001) out = new Vector(1, 0, 0);
            out.normalize();
            mob.setVelocity(out.multiply(0.18).setY(0.04));
        }

        mob.setTarget(null);

        // If we are basically already at the perimeter point, hard-freeze the mob.
        // This prevents micro-jitter from repeated path requests.
        double holdThreshold = canFly ? 1.05 : 0.85;
        if (mobLoc.distance(edge) <= holdThreshold) {
            mob.getPathfinder().stopPathfinding();
            mob.setVelocity(new Vector(0, Math.min(0.02, mob.getVelocity().getY()), 0));
            mob.setFallDistance(0);
            mob.setAI(false);
        } else {
            if (!mob.hasAI()) {
                mob.setAI(true);
            }
            mob.getPathfinder().moveTo(edge, getCurrentPathfinderSpeed(mob));
        }
    }

    /**
     * 2D (XZ) segment-circle intersection. This is used for walker forms so they don't
     * attempt to "go over" a fear bubble based on a 3D line-of-sight that isn't walkable.
     */
    private boolean segmentIntersectsCircleXZ(Location start, Location end, Location center, double radius) {
        if (!isSameWorld(start, end) || !isSameWorld(start, center)) return false;

        double ax = start.getX() - center.getX();
        double az = start.getZ() - center.getZ();

        double bx = end.getX() - center.getX();
        double bz = end.getZ() - center.getZ();

        double dx = bx - ax;
        double dz = bz - az;

        double r2 = radius * radius;

        // Endpoint checks
        double a2 = ax * ax + az * az;
        double b2 = bx * bx + bz * bz;
        if (a2 < r2) return true;
        if (b2 < r2) return true;

        double d2 = dx * dx + dz * dz;
        if (d2 < 0.000001) return false;

        // Solve |a + t d|^2 = r^2 for t in [0,1]
        double bDot = 2.0 * (ax * dx + az * dz);
        double c = a2 - r2;
        double disc = bDot * bDot - 4.0 * d2 * c;
        if (disc < 0.0) return false;

        double sqrt = Math.sqrt(disc);
        double t1 = (-bDot - sqrt) / (2.0 * d2);
        double t2 = (-bDot + sqrt) / (2.0 * d2);
        return (t1 >= 0.0 && t1 <= 1.0) || (t2 >= 0.0 && t2 <= 1.0);
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
