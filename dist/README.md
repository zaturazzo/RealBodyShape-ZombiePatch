# Real Body Shape – patch v6.11 (Project Zomboid Build 42, Windows)

Unofficial companion for the Steam Workshop item **Real Body Shape** (id 3830730496, version 0.3.4 or newer; 0.3.6 for stiff bags and armour; 0.3.9 for the hair and the Sandbox options; 0.3.10 for Room in clothes and cut limbs; 0.4.0 for the arms of a heavy body and the new bodies), which
contains *Real Body Shape*, *Real Body Shape - Zombie Bodies*, *Real Body Shape - Zombie Dismemberment*,
*Real Body Shape - zombie patch through ZombieBuddy* and a few more optional mods.
**Install once (new in v6.11):** you no longer need a new zip when Real Body Shape updates. The patch's code now
comes inside the Real Body Shape Workshop item, which Steam keeps up to date; see Quick install below.

- New in v6.11 (with Real Body Shape 0.4.0): a heavy character's arms hang out from the body by the pose instead of
  sinking into the waist and the clothes; other mods' clothes follow the new body shapes; companions from NPC mods
  get 14 body sizes by weight; room for a single piece goes from -3 to +5 cm; a character keeps its shape until the
  new one is ready. Fixed: after making a man, the first world load could hang on the loading screen (with some
  ragdoll mods).
- v6.9 (with Real Body Shape 0.3.10): Room in clothes - in Options > Mods > Real Body Shape, give clothes
  more room over any part where skin shows through (per body; tops over trousers or a skirt stay over them, the
  canteen moves as a whole); companions from NPC mods keep their body and height; with Zombie Dismemberment a hit can
  take off a zombie's limb (single player); belts, holsters, pouches and straps from other mods sit closer.
- v6.8 (with Real Body Shape 0.3.9): the hairstyles of the optional mod *Real Body Shape - Hair Sway* sway
  as your character moves; animations carry on when the body changes with weight (no jerk, no stuck sitting pose);
  Zombie Bodies takes its chances by sex and line of work, and its heights, from the Sandbox options; the left hand
  stays on a two-handed melee weapon while swinging. Fixed: a zombie
  stretched to a giant height, zombies changing body, height or clothes when they died, far zombies scrunched
  into a ball with heavy lag since Project Viewpoint's latest update, and clothes another mod replaces with its own
  file (Tomb's Player Body - Compatibility's trousers, shorts and skirt) not growing at all.
- Head look: your character turns their head toward the nearest zombie in sight; zombies chasing you look at you;
  left standing still for two minutes, your character looks up at the camera.
- Soft body physics: bellies, bust, buttocks and backpacks bounce, characters breathe, bodies shake when hit; skirts
  lie over the lap when sitting. Bags, packs, canteens, chest rigs and armour stay stiff (v6.5): they sway but do not
  bounce like the body, and chest armour and chest rigs hold the bust and the belly.
- Zombie Bodies: zombies and their corpses get their own body shapes (thin / normal / fat / obese) and heights,
  fixed per zombie, instead of copying the player's shape; their clothes follow that body.
- Zombie Dismemberment (optional): a few percent of zombies are missing a hand, forearm, arm or leg (legless ones
  crawl), clothes included, corpses too.
- Other mods' clothes (v6.7, needs Real Body Shape 0.3.8 and its optional mod Real Body Shape - Other Mods' Clothes): 3D clothes
  the mod has no data for grow and shrink with your character too, roughly.
- Other players in multiplayer (v6.7, needs Real Body Shape 0.3.8 and Zombie Bodies): they get the body nearest
  their weight (thin / normal / fat / obese), sent through the server, instead of your own shape.

**Using ZombieBuddy?** You do not need this zip: enable *Real Body Shape - zombie patch through ZombieBuddy* in the
mod list instead. With both installed, the ZombieBuddy one is used.

Full manual, troubleshooting and source code: https://github.com/zaturazzo/RealBodyShape-ZombiePatch

## Quick install
1. Subscribe to **Real Body Shape** on the Workshop; it brings all its mods.
2. **Close the game.** Run `install.bat` – it patches the game classes from **your** `projectzomboid.jar` into the
   folder `%USERPROFILE%\Zomboid\RealBodyShape\patch`, using the game's own Java runtime (no Java installation
   needed), and adds two entries to `<game>\ProjectZomboid64.json` (backup: `ProjectZomboid64.json.rbs_backup`):
   the patch's code inside the Real Body Shape Workshop item (`rbs_core.jar`) and that folder. If the game folder is
   not found: `install.bat "D:\path\to\steamapps\common\ProjectZomboid"`. Updating from an older version: just run
   it, it replaces the old entry.

   **This download is needed once.** New versions of the patch come with the Workshop item, which Steam keeps up
   to date. When a new version changes more of the game, the patch remakes its folder by itself as the game starts
   and uses it from the next start. Without the Workshop item (unsubscribed, still downloading), the copy kept in
   the folder is used, so the game always starts.
3. Start the game, enable *Real Body Shape* and *Real Body Shape - Zombie Bodies* (plus *Zombie Dismemberment* if you
   want), load your save. `%USERPROFILE%\Zomboid\console.txt` should contain `[RBS hook] ready (classpath) v6.11 ...` and
   `[RealBodyShape] zombie bodies: patch active (classpath), Zombie Bodies mod on`.

- `status.bat` – what is installed, and whether the patch still matches the game version.
- `uninstall.bat` – removes the patch again (the game starts exactly as before).
- After a game update, run `install.bat` again (the same files, no new download: Steam rewrites
  `ProjectZomboid64.json` when the game updates, which removes the entries). If the game closes right at the start, install the newest patch (v6.6 or later is needed for game 42.22.2), or delete the folder `Zomboid\RealBodyShape` in your user folder to play without the patch.
- Settings: the zombie mix (by sex and line of work), zombie heights and limb rates are Sandbox options of
  Zombie Bodies and Zombie Dismemberment (Real Body Shape 0.3.9+); body style and muscle are in Options > Mods >
  Real Body Shape. `JIGGLE` and `LOOK` are still in the mod's `RealBodyShape.lua`.

## (TH) ติดตั้งแบบย่อ
Subscribe Real Body Shape (0.3.4+) → ปิดเกม → รัน `install.bat` → เปิดเกม เปิด mod Real Body Shape + Zombie Bodies (และ Zombie Dismemberment ถ้าต้องการ) → โหลดเซฟ
ใช้ ZombieBuddy อยู่แล้วไม่ต้องใช้ zip นี้ เปิด mod "zombie patch through ZombieBuddy" แทน
โหลด zip นี้ครั้งเดียวพอ: แพตช์รุ่นใหม่มากับ item บน Workshop (Steam อัปเดตให้เอง) ถ้าไม่มี item ก็ใช้สำเนาในโฟลเดอร์ `Zomboid\RealBodyShape\patch` เกมเปิดได้เสมอ
ถอดด้วย `uninstall.bat`, ดูสถานะด้วย `status.bat`, เกมอัปเดตแล้วรัน `install.bat` ซ้ำ ไม่ต้องลง Java เพิ่ม คู่มือละเอียดอยู่ในหน้า GitHub
