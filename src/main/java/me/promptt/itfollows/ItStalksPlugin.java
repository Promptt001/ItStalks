package me.promptt.itfollows;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
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
import org.bukkit.event.world.ChunkLoadEvent;
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

    // Bee / Stuck Logic State
    private Location lastStalkerPos = null;
    private int secondsStuck = 0;
    private int secondsInBeeMode = 0;
    private boolean isBeeMode = false;
    
    // Identity Key for Persistence (Fixes Duplicate Issue)
    private NamespacedKey stalkerKey;

    // Config cache
    private int logoutRetargetDelay;
    private int minTeleportDistance;
    private int fatigueRange;
    private boolean canEnterWater;
    private boolean autoCurseIfEmpty;
    private int transferCooldownSeconds;
    
    // Bee Config
    private boolean beeModeEnabled;
    private int beeTriggerSeconds;
    private int beeDurationSeconds;

    private List<EntityType> allowedForms = new ArrayList<>();

    @Override
    public void onEnable() {
        this.stalkerKey = new NamespacedKey(this, "is_stalker");
        
        saveDefaultConfig();
        loadConfig();
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("startcurse")).setExecutor(this);

        // CLEANUP: Kill any persistent stalkers from previous restarts to fix duplicates
        cleanOldEntities();

        // Main Logic Loop (Runs every 5 ticks = 0.25 seconds)
        new BukkitRunnable() {
            @Override
            public void run() {
                tickLogic();
            }
        }.runTaskTimer(this, 20L, 5L);
        
        // Stuck Check Loop (Runs every 20 ticks = 1.0 second)
        // Separating this makes the check much more reliable against jitter
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
        removeItEntity();
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
        
        beeModeEnabled = config.getBoolean("bee_mode_enabled", true);
        beeTriggerSeconds = config.getInt("bee_trigger_seconds", 10);
        beeDurationSeconds = config.getInt("bee_duration_seconds", 10);

        allowedForms.clear();
        for (String s : config.getStringList("allowed_forms")) {
            try {
                allowedForms.add(EntityType.valueOf(s.toUpperCase()));
            } catch (IllegalArgumentException e) {
                getLogger().warning("Invalid entity type in config: " + s);
            }
        }
    }

    // --- Core Logic ---

    private void tickLogic() {
        if (cursedPlayerUUID == null) {
            if (autoCurseIfEmpty) pickRandomTarget();
            return;
        }

        Player victim = Bukkit.getPlayer(cursedPlayerUUID);

        // Handle Logout Logic
        if (victim == null || !victim.isOnline()) {
            if (cursedLogoutTime == -1) cursedLogoutTime = System.currentTimeMillis();
            if ((System.currentTimeMillis() - cursedLogoutTime) / 1000 > logoutRetargetDelay) {
                pickRandomTarget();
            }
            // If victim is offline, we remove the entity so it doesn't stand around
            removeItEntity();
            return;
        } else {
            cursedLogoutTime = -1;
        }

        if (victim.getGameMode() == GameMode.SPECTATOR || victim.getGameMode() == GameMode.CREATIVE) {
            removeItEntity();
            return;
        }

        Entity it = (itEntityUUID != null) ? Bukkit.getEntity(itEntityUUID) : null;

        // Respawn logic if missing
        // If 'it' is null (unloaded chunk or dead), we respawn.
        // The Tag cleanup handles duplicates if the old one was merely unloaded.
        if (it == null || !it.isValid() || it.getLocation().distance(victim.getLocation()) > 120) {
            if (it != null) it.remove();
            spawnIt(victim); 
            return;
        }

        // Behavior
        if (it instanceof Mob mob) {
            // Pathfinding (Force move to player)
            mob.getPathfinder().moveTo(victim.getLocation(), 1.0);
            mob.setTarget(victim);

            // Visibility
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!p.getUniqueId().equals(cursedPlayerUUID)) {
                    p.hideEntity(this, mob);
                } else {
                    p.showEntity(this, mob);
                }
            }

            // Door Breaker
            handleDoors(mob);
            
            // Wall Climbing (Only if not a bee)
            if (!isBeeMode) handleClimbing(mob);
            
            // Bee Aggression Tweaks
            if (isBeeMode && mob instanceof Bee bee) {
                bee.setCannotEnterHiveTicks(Integer.MAX_VALUE);
                bee.setAnger(Integer.MAX_VALUE);
            }

            // Water
            if (!canEnterWater && mob.isInWater()) {
                Vector away = mob.getLocation().toVector().subtract(victim.getLocation().toVector()).normalize().multiply(0.5).setY(0.5);
                mob.setVelocity(away);
            }

            // Passive Mob / Bee Aggression
            if (mob.getLocation().distance(victim.getLocation()) < 1.5) {
                if (victim.getNoDamageTicks() == 0) {
                    victim.damage(12.0, mob);
                    mob.swingMainHand();
                }
            }
        }

        // Effects
        if (it.getLocation().distance(victim.getLocation()) <= fatigueRange) {
            victim.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 100, 1, false, false));
        }
    }

    private void checkStuckStatus() {
        if (!beeModeEnabled || itEntityUUID == null) return;
        
        Entity it = Bukkit.getEntity(itEntityUUID);
        if (it == null || !it.isValid()) return;
        
        // --- Bee Timer (Turn back to walker) ---
        if (isBeeMode) {
            secondsInBeeMode++;
            if (secondsInBeeMode >= beeDurationSeconds) {
                // Time to land
                Player victim = (cursedPlayerUUID != null) ? Bukkit.getPlayer(cursedPlayerUUID) : null;
                if (victim != null) morphEntity((Mob) it, victim, null);
            }
            return;
        }

        // --- Walker Stuck Logic (Turn into Bee) ---
        if (lastStalkerPos != null) {
            // Check if moved less than 1.5 blocks in the last SECOND
            if (it.getLocation().distance(lastStalkerPos) < 1.5) {
                secondsStuck++;
            } else {
                secondsStuck = 0;
            }
        }
        lastStalkerPos = it.getLocation();

        if (secondsStuck >= beeTriggerSeconds) {
            Player victim = (cursedPlayerUUID != null) ? Bukkit.getPlayer(cursedPlayerUUID) : null;
            if (victim != null) morphEntity((Mob) it, victim, EntityType.BEE);
        }
    }

    private void spawnIt(Player target) {
        spawnSpecificEntity(target.getLocation(), target, null);
    }

    private void spawnSpecificEntity(Location specificLoc, Player target, EntityType forcedType) {
        Location spawnLoc;
        
        if (specificLoc == null) {
            spawnLoc = target.getLocation();
            double angle = Math.random() * 2 * Math.PI;
            spawnLoc.add(Math.cos(angle) * minTeleportDistance, 0, Math.sin(angle) * minTeleportDistance);
            
            World world = spawnLoc.getWorld();
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
        
        // MARK THE ENTITY so we can delete duplicates later
        entity.getPersistentDataContainer().set(stalkerKey, PersistentDataType.BYTE, (byte) 1);

        // Update state flags
        isBeeMode = (type == EntityType.BEE);
        secondsStuck = 0;
        lastStalkerPos = spawnLoc.clone();
        if (isBeeMode) secondsInBeeMode = 0;

        if (entity instanceof LivingEntity living) {
            if (living.getAttribute(Attribute.MAX_HEALTH) != null) {
                living.getAttribute(Attribute.MAX_HEALTH).setBaseValue(100.0);
            }
            living.setHealth(100.0);

            // Speed: 0.12 (Slow)
            if (living.getAttribute(Attribute.MOVEMENT_SPEED) != null) {
                living.getAttribute(Attribute.MOVEMENT_SPEED).setBaseValue(0.12);
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
        
        // Visual poof
        loc.getWorld().playEffect(loc, org.bukkit.Effect.MOBSPAWNER_FLAMES, 0);
    }

    private void removeItEntity() {
        if (itEntityUUID != null) {
            Entity e = Bukkit.getEntity(itEntityUUID);
            if (e != null) e.remove();
            itEntityUUID = null;
        }
        isBeeMode = false;
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
        player.sendMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "You feel a cold chill... It is following you.");
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
    
    // --- SPIDER CLIMBING LOGIC ---
    private void handleClimbing(Mob mob) {
        Location loc = mob.getLocation();
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
        return b.getType().isSolid() 
                && !b.getType().toString().contains("DOOR") 
                && !b.getType().toString().contains("GATE")
                && !b.getType().toString().contains("TRAPDOOR");
    }

    // --- Listeners ---
    @EventHandler
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (!(event.getDamager() instanceof Player attacker)) return;

        // Curse Transfer Logic
        if (attacker.getUniqueId().equals(cursedPlayerUUID)) {
            
            // Check Cooldown from Config
            long cooldownMs = transferCooldownSeconds * 1000L;
            if (System.currentTimeMillis() - lastTransferTime < cooldownMs) {
                long timeLeft = (cooldownMs - (System.currentTimeMillis() - lastTransferTime)) / 1000;
                attacker.sendMessage(ChatColor.RED + "You cannot pass the curse yet! Wait " + timeLeft + "s.");
                return;
            }

            setCursedPlayer(victim);
            lastTransferTime = System.currentTimeMillis();
            
            attacker.sendMessage(ChatColor.GREEN + "You have passed the curse to " + victim.getName() + "!");
            victim.sendMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "TAG! You are now Cursed.");
        }
    }
    
    // Safety check: When any chunk loads, if it contains an "Old" Stalker that isn't our current one, delete it.
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        for (Entity e : event.getChunk().getEntities()) {
            if (e.getPersistentDataContainer().has(stalkerKey, PersistentDataType.BYTE)) {
                if (itEntityUUID == null || !e.getUniqueId().equals(itEntityUUID)) {
                    e.remove(); // Kill the duplicate
                }
            }
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
                sender.sendMessage(ChatColor.RED + "Player not found.");
                return true;
            }
            setCursedPlayer(target);
            sender.sendMessage(ChatColor.RED + "Curse started on " + target.getName());
            return true;
        }
        return false;
    }
}