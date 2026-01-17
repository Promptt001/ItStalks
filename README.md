# ItStalks 👁️

> *"It doesn't think. It doesn't feel. It doesn't give up."*

**ItStalks** is a Minecraft server plugin built for **PaperMC 1.21+** that recreates the psychological horror antagonist from the movie *It Follows*. It introduces a slow, unstoppable, shapeshifting entity that relentlessly pursues a single cursed player across the server.

## 👻 Core Mechanics

### The Entity ("It")
* **Invisible to Others:** Only the currently cursed player can see the entity. To everyone else, the victim appears to be running from nothing.
* **Unstoppable:** The entity has high health, takes no knockback, and cannot be burned by sunlight.
* **Shapeshifter:** "It" can spawn as various mobs (Zombie, Villager, Cow, etc.) but behaves aggressively regardless of form. Even passive mobs will walk toward you and inflict damage.
* **Door Breaker:** Walls and doors will not save you. The entity forces open doors and gates to reach its target.
* **Relentless AI:** It walks slowly but constantly. If you run far away or fly, it will despawn and respawn closer to you to maintain the pressure.

### The Curse
* **The Goal:** Pass the curse to someone else to survive.
* **Transfer:** If the cursed player hits another player (PvP), the curse is immediately transferred to the victim. The entity vanishes for the attacker and begins stalking the new victim.
* **Mining Fatigue:** As the entity gets close, the victim suffers from Mining Fatigue, making it harder to dig through obstacles or defend themselves.

## 📥 Installation

1.  Download the latest release JAR.
2.  Place the JAR file into your server's `plugins` folder.
3.  Restart your server.
4.  (Optional) Edit `plugins/ItStalks/config.yml` to customize the entity's behavior.

## 🎮 Commands & Permissions

| Command | Permission | Description |
| :--- | :--- | :--- |
| `/startcurse <player>` | `itstalks.admin` | Manually starts the curse on a specific player. |

**Default Permission:** OP only.

## ⚙️ Configuration

The `config.yml` allows you to tweak the difficulty and behavior of the stalker.

```yaml
# Time (seconds) to wait if the cursed player disconnects before picking a new target.
logout_retarget_delay: 300

# The distance (blocks) the entity spawns away from the player when respawning.
min_teleport_distance: 50

# Mining Fatigue Aura settings.
fatigue_duration: 100
fatigue_range: 10

# Can the entity swim? If false, it bounces off water (useful for specific map designs).
can_enter_water: false

# If true, the plugin picks a random victim if the server has no cursed player.
auto_curse_if_empty: true

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
    git clone https://github.com/yourusername/ItStalks.git
    cd ItStalks
    ```

2.  Build using Maven:
    ```bash
    mvn clean package
    ```

3.  The compiled plugin will be located in the `target/` directory:
    * `target/itstalks-1.0-SNAPSHOT.jar`

## 🧩 Technical Details

* **API:** Paper 1.21.4 (Utilizes modern Attribute API).
* **Persistence:** The entity does not persist in chunk data to avoid corruption. It is dynamic and linked to the player's session.
* **Performance:** Logic runs on a 5-tick (0.25s) loop to minimize server impact while maintaining responsive AI.

## 📄 License

This project is licensed under the MIT License - see the LICENSE file for details.
