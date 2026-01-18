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
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class ItStalksPlugin extends JavaPlugin implements Listener, CommandExecutor {

    private UUID cursedPlayerUUID;
    private UUID itEntityUUID;
    private long cursedLogoutTime = -1;
    private long lastTransferTime = 0;

    // Vex / Stuck Logic State
    private Location lastStalkerPos = null;
    private int secondsStuck = 0;
    private int secondsInVexMode = 0;
    private boolean isVexMode = false;
    
    // Identity Key for Persistence
    private NamespacedKey stalkerKey;

    // Config cache
    private int logoutRetargetDelay;
    private int minTeleportDistance;
    private int fatigueRange;
    private boolean canEnterWater;
    private boolean autoCurseIfEmpty;
    private int transferCooldownSeconds;
    
    // Vex Config
    private boolean vexModeEnabled;
    private int vexTriggerSeconds;
    private int vexDurationSeconds;

    // Speed Config
    private double allowedFormsMovementSpeed;
    private double allowedFormsPathfinderSpeed;
    private double vexMovementSpeed;
    private double vexFlyingSpeed;
    private double vexPathfinderSpeed;

    // Boat Trap Prevention
    private double boatTrapRadius;

    // Fear Config
    private boolean fearFireEnabled;
    private double fearFireRadius;
    private boolean fearSoulTorchEnabled;
    private double fearSoulTorchRadius;
    private boolean fearSoulLanternEnabled;
    private double fearSoulLanternRadius;
    private boolean fearBeaconEnabled;
    private double fearBeaconRadius;

    // Chat Messages
    private String msgCurseAssigned;
    private String msgCurseCooldown;
    private String msgCursePassedAttacker;
    private String msgCursePassedVictim;
    private String msgPlayerNotFound;
    private String msgCurseStartedAdmin;
    private String msgConfigReloaded;

    // Proximity Messages / Tips
    private boolean proximityMessagesEnabled;
    private int proximityCheckIntervalTicks;
    private int proximityDefaultCooldownSeconds;
    private List<ProximityTier> proximityTiers = new ArrayList<>();
    private int proximityTickCounter = 0;
    private final Map<UUID, Map<Integer, Long>> lastProximityMessageMs = new HashMap<>();

    private FearSource cachedFearSource;
    private long lastFearScanMs = 0;

    private List<EntityType> allowedForms = new ArrayList<>();

    private enum FearType {
        FIRE,
        SOUL_TORCH,
        SOUL_LANTERN,
        BEACON
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

    @Override
    public void onEnable() {
        this.stalkerKey = new NamespacedKey(this, "is_stalker");
        
        saveDefaultConfig();
        loadConfig();
        getServer().getPluginManager().registerEvents(this, this);
        
        // Register Commands
        Objects.requireNonNull(getCommand("startcurse")).setExecutor(this);
        Objects.requireNonNull(getCommand("cursereload")).setExecutor(this);

        cleanOldEntities();

        // Main Logic Loop (Runs every 5 ticks = 0.25 seconds)
        new BukkitRunnable() {
            @Override
            public void run() {
                tickLogic();
            }
        }.runTaskTimer(this, 20L, 5L);
        
        // Stuck Check Loop (Runs every 20 ticks = 1.0 second)
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
    
    private void cleanOldEntities() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity e : world.getEntities()) {
                if (e.getPersistentDataContainer().has(stalkerKey, PersistentDataType.BYTE)) {
                    e.remove();
                }
            }
        }
    }

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

        // Boat Trap Prevention
        boatTrapRadius = config.getDouble("boat_trap_prevention_radius", 3.5);

        // Fears
        fearFireEnabled = config.getBoolean("fears.fire.enabled", true);
        fearFireRadius = config.getDouble("fears.fire.radius", 8.0);

        fearSoulTorchEnabled = config.getBoolean("fears.soul_torch.enabled", true);
        fearSoulTorchRadius = config.getDouble("fears.soul_torch.radius", 8.0);

        fearSoulLanternEnabled = config.getBoolean("fears.soul_lantern.enabled", true);
        fearSoulLanternRadius = config.getDouble("fears.soul_lantern.radius", 8.0);

        fearBeaconEnabled = config.getBoolean("fears.beacon.enabled", true);
        fearBeaconRadius = config.getDouble("fears.beacon.radius", 16.0);

        // Messages (supports color codes with & and placeholders like {victim}, {seconds}, etc.)
        msgCurseAssigned = config.getString("messages.curse_assigned", "&4&lYou feel a cold chill... It is following you.");
        msgCurseCooldown = config.getString("messages.curse_cooldown", "&cYou cannot pass the curse yet! Wait {seconds}s.");
        msgCursePassedAttacker = config.getString("messages.curse_passed_attacker", "&aYou have passed the curse to {victim}!");
        msgCursePassedVictim = config.getString("messages.curse_passed_victim", "&4&lTAG! You are now Cursed.");
        msgPlayerNotFound = config.getString("messages.player_not_found", "&cPlayer not found.");
        msgCurseStartedAdmin = config.getString("messages.curse_started_admin", "&cCurse started on {target}");
        msgConfigReloaded = config.getString("messages.config_reloaded", "&aItStalks configuration reloaded!");

        // Proximity message tiers (used for tips/warnings as the stalker approaches)
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

        allowedForms.clear();
        for (String s : config.getStringList("allowed_forms")) {
            try {
                allowedForms.add(EntityType.valueOf(s.toUpperCase()));
            } catch (IllegalArgumentException e) {
                getLogger().warning("Invalid entity type in config: " + s);
            }
        }
    }


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

    // --- Core Logic ---

    private void tickLogic() {
        if (cursedPlayerUUID == null) {
            if (autoCurseIfEmpty) pickRandomTarget();
            return;
        }

        Player victim = Bukkit.getPlayer(cursedPlayerUUID);

        if (victim == null || !victim.isOnline()) {
            if (cursedLogoutTime == -1) cursedLogoutTime = System.currentTimeMillis();
            if ((System.currentTimeMillis() - cursedLogoutTime) / 1000 > logoutRetargetDelay) {
                pickRandomTarget();
            }
            return;
        } else {
            cursedLogoutTime = -1;
        }

        if (victim.getGameMode() == GameMode.SPECTATOR || victim.getGameMode() == GameMode.CREATIVE) {
            removeItEntity();
            return;
        }

        // INTELLIGENT RE-TRACKING
        if (itEntityUUID == null) {
            for (Entity e : victim.getNearbyEntities(100, 100, 100)) {
                if (e.getPersistentDataContainer().has(stalkerKey, PersistentDataType.BYTE)) {
                    itEntityUUID = e.getUniqueId();
                    break;
                }
            }
        }

        Entity it = (itEntityUUID != null) ? Bukkit.getEntity(itEntityUUID) : null;

        // Respawn logic (includes dimension changes)
        if (it == null || !it.isValid() || !isSameWorld(it.getLocation(), victim.getLocation()) || safeDistance(it.getLocation(), victim.getLocation()) > 120) {
            if (it != null) it.remove();
            spawnIt(victim);
            return;
        }

        // Behavior
        if (it instanceof Mob mob) {
            double distToVictim = safeDistance(mob.getLocation(), victim.getLocation());

            // Proximity chat messages (tips/alerts based on distance)
            handleProximityMessages(victim, distToVictim);

            // Visibility
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!p.getUniqueId().equals(cursedPlayerUUID)) {
                    p.hideEntity(this, mob);
                } else {
                    p.showEntity(this, mob);
                }
            }

            // Prevent boat trapping / clean nearby boats
            handleBoatTrapPrevention(mob);

            // Fear logic: certain blocks repel the stalker
            FearSource fearSource = getFearSource(mob.getLocation());
            if (fearSource != null) {
                handleFear(mob, fearSource);
                return; // Do not chase/attack while afraid
            }

            // Pathfinding (Force move to player)
            mob.getPathfinder().moveTo(victim.getLocation(), getCurrentPathfinderSpeed(mob));
            mob.setTarget(victim);

            // Door Breaker
            handleDoors(mob);
            
            // Wall Climbing (Only if not a Vex)
            if (!isVexMode) handleClimbing(mob);
            
            // Ladder Descent
            handleLadderDescent(mob, victim);
            
            // Vex Aggression
            if (isVexMode && mob instanceof Vex vex) {
                vex.setCharging(true); 
            }

            // Water
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
        }

        // Effects
        if (safeDistance(it.getLocation(), victim.getLocation()) <= fatigueRange) {
            victim.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 100, 1, false, false));
        }
    }

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

    private void checkStuckStatus() {
        if (!vexModeEnabled || itEntityUUID == null) return;
        
        Entity it = Bukkit.getEntity(itEntityUUID);
        if (it == null || !it.isValid()) return;
        
        // --- Vex Timer (Turn back to walker) ---
        if (isVexMode) {
            secondsInVexMode++;
            // Check 1: Has enough time passed?
            if (secondsInVexMode >= vexDurationSeconds) {
                // Check 2: Are we close to the ground? (Prevent air-drops)
                if (isSafeToLand((Mob) it)) {
                    Player victim = (cursedPlayerUUID != null) ? Bukkit.getPlayer(cursedPlayerUUID) : null;
                    if (victim != null) morphEntity((Mob) it, victim, null);
                }
            }
            return;
        }

        // --- Walker Stuck Logic (Turn into Vex) ---
        if (lastStalkerPos != null) {
            if (it.getLocation().distance(lastStalkerPos) < 0.1) {
                secondsStuck++;
            } else {
                secondsStuck = 0;
            }
        }
        lastStalkerPos = it.getLocation();

        if (secondsStuck >= vexTriggerSeconds) {
            Player victim = (cursedPlayerUUID != null) ? Bukkit.getPlayer(cursedPlayerUUID) : null;
            if (victim != null) morphEntity((Mob) it, victim, EntityType.VEX);
        }
    }
    
    // --- Helper to check for ground beneath Vex ---
    private boolean isSafeToLand(Mob mob) {
        Location loc = mob.getLocation();
        // Check 1 to 4 blocks below
        for (int i = 1; i <= 4; i++) {
            Block b = loc.clone().subtract(0, i, 0).getBlock();
            if (b.getType().isSolid()) {
                return true;
            }
        }
        return false;
    }

    private void spawnIt(Player target) {
        spawnSpecificEntity(null, target, null);
    }

    private void spawnSpecificEntity(Location specificLoc, Player target, EntityType forcedType) {
        Location spawnLoc;
        World world = target.getWorld();
        
        if (specificLoc == null) {
            spawnLoc = target.getLocation();
            double angle = Math.random() * 2 * Math.PI;
            double xOffset = Math.cos(angle) * minTeleportDistance;
            double zOffset = Math.sin(angle) * minTeleportDistance;
            
            spawnLoc.add(xOffset, 0, zOffset);
            
            int chunkX = spawnLoc.getBlockX() >> 4;
            int chunkZ = spawnLoc.getBlockZ() >> 4;
            if (!world.isChunkLoaded(chunkX, chunkZ)) return;
            
            int highestY = world.getHighestBlockYAt(spawnLoc);
            spawnLoc.setY(highestY + 1);
        } else {
            spawnLoc = specificLoc;
        }

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
        
        entity.getPersistentDataContainer().set(stalkerKey, PersistentDataType.BYTE, (byte) 1);

        isVexMode = (type == EntityType.VEX);
        secondsStuck = 0;
        lastStalkerPos = spawnLoc.clone();
        if (isVexMode) secondsInVexMode = 0;

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

    // --- Cross-dimension safety / distance ---
    private boolean isSameWorld(Location a, Location b) {
        if (a == null || b == null) return false;
        if (a.getWorld() == null || b.getWorld() == null) return false;
        return a.getWorld().equals(b.getWorld());
    }

    private double safeDistance(Location a, Location b) {
        if (!isSameWorld(a, b)) return Double.MAX_VALUE;
        return a.distance(b);
    }

    // --- Speed helpers ---
    private double getCurrentPathfinderSpeed(Mob mob) {
        if (mob instanceof Vex || isVexMode) {
            return vexPathfinderSpeed;
        }
        return allowedFormsPathfinderSpeed;
    }

    // --- Boat trap prevention ---
    private void handleBoatTrapPrevention(Mob mob) {
        if (boatTrapRadius <= 0) return;

        // If already inside a boat, immediately eject and remove the boat
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

    // --- Fear logic ---
    private FearSource getFearSource(Location center) {
        // Throttle: scanning blocks can be expensive
        long now = System.currentTimeMillis();
        if (now - lastFearScanMs < 750) {
            return cachedFearSource;
        }
        lastFearScanMs = now;
        cachedFearSource = scanForFearSource(center);
        return cachedFearSource;
    }

    private FearSource scanForFearSource(Location center) {
        if (center == null || center.getWorld() == null) return null;

        double maxRadius = 0.0;
        if (fearFireEnabled && fearFireRadius > 0) maxRadius = Math.max(maxRadius, fearFireRadius);
        if (fearSoulTorchEnabled && fearSoulTorchRadius > 0) maxRadius = Math.max(maxRadius, fearSoulTorchRadius);
        if (fearSoulLanternEnabled && fearSoulLanternRadius > 0) maxRadius = Math.max(maxRadius, fearSoulLanternRadius);
        if (fearBeaconEnabled && fearBeaconRadius > 0) maxRadius = Math.max(maxRadius, fearBeaconRadius);
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
                    // Beacon
                    else if (fearBeaconEnabled && fearBeaconRadius > 0 && type == Material.BEACON) {
                        fearType = FearType.BEACON;
                        radius = fearBeaconRadius;
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

    private void handleFear(Mob mob, FearSource fearSource) {
        if (fearSource == null) return;

        Location mobLoc = mob.getLocation();
        Vector away = mobLoc.toVector().subtract(fearSource.location.toVector());
        if (away.lengthSquared() < 0.0001) away = new Vector(1, 0, 0);
        away.setY(0).normalize();

        // Move away a few blocks (pathfinding + a small velocity push)
        Location fleeTarget = mobLoc.clone().add(away.clone().multiply(8));
        mob.setTarget(null);
        mob.getPathfinder().moveTo(fleeTarget, getCurrentPathfinderSpeed(mob));
        mob.setVelocity(away.multiply(0.30).setY(0.05));
    }

    @EventHandler
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (!(event.getDamager() instanceof Player attacker)) return;

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
        if (event.getEntity().getUniqueId().equals(itEntityUUID)) event.setCancelled(true);
    }
    
    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.getEntity().getUniqueId().equals(itEntityUUID)) {
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
        }
        else if (command.getName().equalsIgnoreCase("cursereload")) {
            reloadConfig();
            loadConfig();
            sender.sendMessage(formatMessage(msgConfigReloaded, null));
            return true;
        }
        return false;
    }
}