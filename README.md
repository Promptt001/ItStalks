# ItStalks 👁️

> *"It doesn't think. It doesn't feel. It doesn't give up."*

**ItStalks** is a Minecraft server plugin built for **PaperMC 1.21+** that recreates the psychological horror antagonist from the movie *It Follows*. It introduces a slow, unstoppable entity that relentlessly pursues a single cursed player across the server.

## 👻 Core Mechanics

### The Entity ("It")
* **Invisible Terror:** Only the currently cursed player can see the entity. To everyone else, the victim appears to be running from nothing.
* **Unstoppable:** The entity has high health, takes no knockback, and cannot be burned by sunlight.
* **Shapeshifter:** "It" can spawn as various mobs (Zombie, Villager, Cow, etc.) but behaves aggressively regardless of form.
* **Smart AI:**
    * **Door Breaker:** Forces open doors and gates.
    * **Wall Climber:** Can scale vertical walls like a spider to reach high places.
    * **Ladder Slider:** Smartly navigates down ladder shafts without getting stuck.
* **Vex Mode (Anti-Stuck):** If the entity gets stuck or trapped for too long, it transforms into a **Vex** (Ghost). It phases through walls and flies toward the player until it finds safe ground to land and resume walking.

### The Curse
* **The Goal:** Pass the curse to someone else to survive.
* **Transfer:** If the cursed player hits another player (PvP), the curse is transferred to the victim.
    * *Cooldown:* There is a configurable cooldown (default 3s) to prevent "hot potato" spamming.
* **Relentless:** If the target disconnects, the entity waits. If they don't return, it finds a new victim.
* **Mining Fatigue:** As the entity gets close, the victim suffers from Mining Fatigue, increasing the panic.

## 📥 Installation

1.  Download the latest release JAR.
2.  Place the JAR file into your server's `plugins` folder.
3.  Restart your server.
4.  (Optional) Edit `plugins/ItStalks/config.yml` and run `/cursereload`.

## 🎮 Commands & Permissions

| Command | Permission | Description |
| :--- | :--- | :--- |
| `/startcurse <player>` | `itstalks.admin` | Manually starts the curse on a specific player. |
| `/cursereload` | `itstalks.admin` | Reloads the configuration file instantly. |

**Default Permission:** OP only.

## ⚙️ Configuration

The `config.yml` allows you to tweak the difficulty and behavior of the stalker.

```yaml
# Time (seconds) to wait for a disconnected cursed player before retargeting
logout_retarget_delay: 300

# Minimum distance the entity must be from the player to spawn/teleport
min_teleport_distance: 50

# Mining Fatigue Aura settings.
fatigue_duration: 100
fatigue_range: 10

# Can the entity swim? If false, it bounces off water (useful for land-locked horror).
can_enter_water: false

# If true, the plugin picks a random victim if the server has no cursed player.
auto_curse_if_empty: true

# Cooldown (seconds) before the curse can be passed back to the previous owner.
curse_transfer_cooldown: 3

# --- Vex Mode (Anti-Stuck) ---
# If stuck, turns into a Vex to phase through walls.
vex_mode_enabled: true
# Seconds standing still before turning into a Vex.
vex_trigger_seconds: 10
# Minimum time to stay as a Vex before trying to land.
vex_duration_seconds: 10

# List of entity types "It" can mimic.
allowed_forms:
  - ZOMBIE
  - SKELETON
  - VILLAGER
  - PIG
  - COW
  - HUSK
  - STRAY
  - DROWNED
```

## 🛠️ Building from Source

**Requirements:**
* Java 21 (JDK)
* Maven
* Git

**Steps:**

1.  Clone the repository:
    ```bash
    git clone https://github.com/Promptt001/ItStalks.git
    cd ItStalks
    ```

2.  Build using Maven:
    ```bash
    mvn clean package
    ```

3.  The compiled plugin will be located in the `target/` directory.

## 📄 License

This project is licensed under the MIT License.
