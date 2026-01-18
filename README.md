# ItStalks 👁️

> *"It doesn't think. It doesn't feel. It doesn't give up."*

**ItStalks** is a Minecraft server plugin built for **PaperMC 1.21+** that recreates the psychological horror antagonist from the movie *It Follows*. It introduces a slow, unstoppable entity that relentlessly pursues a single cursed player across the server.

## 👻 Core Mechanics

### The Entity ("It")
- **Invisible Terror:** Only the currently cursed player can see the entity. To everyone else, the victim appears to be running from nothing.
- **Unstoppable:** The entity has high health, takes no knockback, and cannot be burned by sunlight.
- **Shapeshifter:** "It" can spawn as various mobs (Zombie, Villager, Cow, etc.) but behaves aggressively regardless of form.
- **Smart AI:**
  - **Door Breaker:** Forces open doors and gates.
  - **Wall Climber:** Can scale vertical walls to reach high places.
  - **Ladder Slider:** Navigates down ladder shafts without getting stuck.
  - **Boat Trap Prevention:** The stalker cannot enter boats and will push away/remove nearby empty boats.
- **Vex Mode (Anti-Stuck):** If the entity gets stuck or trapped for too long, it transforms into a **Vex** (Ghost). It phases through walls and flies toward the player until it finds safe ground to land and resume walking.
- **Fears:** The entity can be configured to fear certain light sources/blocks (e.g., fire, soul lights, soul campfires) and will stop at the perimeter and try to path around them instead of oscillating in and out of the radius.

### The Curse
- **The Goal:** Pass the curse to someone else to survive.
- **Transfer:** If the cursed player hits another player (PvP), the curse is transferred to the victim.
  - *Cooldown:* There is a configurable cooldown (default 3s) to prevent "hot potato" spamming.
- **Relentless:** If the target disconnects, the entity waits. If they don't return, it finds a new victim.
- **Mining Fatigue:** As the entity gets close, the victim suffers from Mining Fatigue, increasing the panic.
- **Proximity Tips / Alerts:** You can configure extra chat messages that trigger when the stalker gets within certain radiuses of the cursed player (useful for tips, warnings, and roleplay).

## 📥 Installation

1. Download the latest release JAR.
2. Place the JAR file into your server's `plugins` folder.
3. Restart your server.
4. (Optional) Edit `plugins/ItStalks/config.yml` and run `/cursereload`.

## 🎮 Commands & Permissions

| Command | Permission | Description |
| :--- | :--- | :--- |
| `/startcurse <player>` | `itstalks.admin` | Manually starts the curse on a specific player. |
| `/cursereload` | `itstalks.admin` | Reloads the configuration file instantly. |

**Default Permission:** OP only.

## ⚙️ Configuration

The `config.yml` allows you to tweak the difficulty and behavior of the stalker.

### Color codes
All chat messages support Minecraft color codes using `&`.

### Messages
You can customize all player/admin chat messages in:

```yaml
messages:
  curse_assigned: "&4&lYou feel a cold chill... It is following you."
  curse_cooldown: "&cYou cannot pass the curse yet! Wait {seconds}s."
  curse_passed_attacker: "&aYou have passed the curse to {victim}!"
  curse_passed_victim: "&4&lTAG! You are now Cursed."
  player_not_found: "&cPlayer not found."
  curse_started_admin: "&cCurse started on {target}"
  config_reloaded: "&aItStalks configuration reloaded!"
```

**Placeholders:**
- `{victim}` = the newly cursed player
- `{target}` = the admin-selected cursed player
- `{seconds}` = transfer cooldown seconds remaining

### Proximity messages (tips/warnings)
Configure messages that trigger as the stalker approaches:

```yaml
proximity_messages:
  enabled: true
  check_interval_ticks: 20
  default_cooldown_seconds: 180
  tiers:
    - radius: 30
      message: "&eTip: Fire and soul lights can scare it away."
      cooldown_seconds: 180
    - radius: 10
      message: "&cIt's very close. RUN."
      cooldown_seconds: 60
```

**Placeholders:**
- `{distance}` = current stalker distance to the cursed player
- `{radius}` = the tier radius that triggered

The plugin automatically chooses the **closest matching tier** (the smallest radius that the stalker is currently within).

### Speed configuration
```yaml
speeds:
  allowed_forms:
    movement: 0.12
    pathfinder: 1.0
  vex_form:
    movement: 0.12
    flying: 0.12
    pathfinder: 1.0
```

### Boat trap prevention
```yaml
boat_trap_prevention_radius: 3.5
```

### Fears
```yaml
fears:
  fire:
    enabled: true
    radius: 8.0
  soul_torch:
    enabled: true
    radius: 16.0
  soul_lantern:
    enabled: true
    radius: 16.0
  soul_campfire:
    enabled: true
    radius: 32.0

  # Fear avoidance tuning (prevents the stalker from repeatedly entering the fear zone and fleeing)
  # perimeter_buffer: extra distance added on top of each fear radius to create a stable "edge"
  # step_distance: how far (in blocks) each avoidance step tries to move per pathing update
  # inward_dot_threshold: how aggressively it switches to tangential movement around the fear zone
  avoidance:
    perimeter_buffer: 0.75
    step_distance: 6.0
    inward_dot_threshold: 0.15
```

## 🛠️ Building from Source

**Requirements:**
- Java 21 (JDK)
- Maven
- Git

**Steps:**

1. Clone the repository:
   ```bash
   git clone https://github.com/Promptt001/ItStalks.git
   cd ItStalks
   ```

2. Build using Maven:
   ```bash
   mvn clean package
   ```

3. The compiled plugin will be located in the `target/` directory.

## 📄 License

This project is licensed under the MIT License.
