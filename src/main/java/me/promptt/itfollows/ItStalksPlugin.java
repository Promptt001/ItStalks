package me.promptt.itfollows;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
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

    // Config cache
    private int logoutRetargetDelay;
    private int minTeleportDistance;
    private int fatigueRange;
    private boolean canEnterWater;
    private boolean autoCurseIfEmpty;
    private List<EntityType> allowedForms = new ArrayList<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("startcurse")).setExecutor(this);

        // Main Logic Loop (Runs every 5 ticks for performance)
        new BukkitRunnable() {
            @Override
            public void run() {
                tickLogic();
            }
        }.runTaskTimer(this, 20L, 5L);

        getLogger().info("ItStalks has been enabled. Run.");
    }

    @Override
    public void onDisable() {
        removeItEntity();
    }

    private void loadConfig() {
        FileConfiguration config = getConfig();
        logoutRetargetDelay = config.getInt("logout_retarget_delay", 300);
        minTeleportDistance = config.getInt("min_teleport_distance", 50);
        fatigueRange = config.getInt("fatigue_range", 10);
        canEnterWater = config.getBoolean("can_enter_water", false);
        autoCurseIfEmpty = config.getBoolean("auto_curse_if_empty", true);

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

        // Respawn logic
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

            // Water
            if (!canEnterWater && mob.isInWater()) {
                Vector away = mob.getLocation().toVector().subtract(victim.getLocation().toVector()).normalize().multiply(0.5).setY(0.5);
                mob.setVelocity(away);
            }

            // Passive Mob Aggression
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

    private void spawnIt(Player target) {
        if (allowedForms.isEmpty()) return;

        Location spawnLoc = target.getLocation();
        double angle = Math.random() * 2 * Math.PI;
        spawnLoc.add(Math.cos(angle) * minTeleportDistance, 0, Math.sin(angle) * minTeleportDistance);
        
        World world = spawnLoc.getWorld();
        int highestY = world.getHighestBlockYAt(spawnLoc);
        spawnLoc.setY(highestY + 1);

        EntityType type = allowedForms.get(new Random().nextInt(allowedForms.size()));
        Entity entity = world.spawnEntity(spawnLoc, type);
        itEntityUUID = entity.getUniqueId();

        if (entity instanceof LivingEntity living) {
            if (living.getAttribute(Attribute.MAX_HEALTH) != null) {
                living.getAttribute(Attribute.MAX_HEALTH).setBaseValue(100.0);
            }
            living.setHealth(100.0);

            // Half Zombie Speed (0.12)
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
        }
    }

    private void removeItEntity() {
        if (itEntityUUID != null) {
            Entity e = Bukkit.getEntity(itEntityUUID);
            if (e != null) e.remove();
            itEntityUUID = null;
        }
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

    // --- Listeners ---
    @EventHandler
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (victim.getUniqueId().equals(cursedPlayerUUID)) {
            setCursedPlayer(attacker);
            victim.sendMessage(ChatColor.GREEN + "You have passed the curse!");
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