package rbs;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Soft bellies: a fat or obese zombie's belly, and the player's once the character is overweight, bounces and sways
 * when it runs, lunges, stops or gets pushed, and settles when it stands or shuffles.
 *
 * The skeleton has no belly bone and the animations drive every bone there is, so the belly is moved in the vertex
 * shader: the Real Body Shape mod ships RBSJiggle.vert/.frag (the game's basicEffect plus the belly), zombie bodies,
 * the player's body and the clothes they wear are switched to it, and the shader moves the vertices in front of the
 * trunk by the displacement found here. The region is picked from each vertex's bind-pose position, so the body and every piece
 * on top of it get the same push and the clothes stay on the belly.
 *
 * The shader gets the numbers through the bone palette: the game uploads, for every mesh, the whole array that
 * AnimationPlayer.getSkinTransforms returns (MatrixPalette[60] in the shader), so a human mesh's array is handed
 * back 60 long with this character's belly in slot 59 (zeros: still). The player's body model is the game's shared
 * one (mannequins, corpses, other players, the creation screen use it too), so every human mesh gets the slot and it
 * always belongs to the character being drawn (a slot never uploaded would keep the previous character's).
 *
 * The bounce is a damped spring per zombie, worked out after each animation update: the belly lags behind the
 * pelvis (its bob in the animation, and how the zombie moves over the ground), overshoots and settles. Nothing is
 * synced in multiplayer: every machine works it out from what it shows.
 * If the shader is missing or does not compile, nothing is switched and zombies keep the game's shader.
 *
 * On top of the belly (0.3.4), in slot 58 when the mesh's palette leaves it free (a marker says the slot is ours):
 *   - the bust: its own softer spring, women (players and zombies) and fat / obese men's chests
 *   - bags on the back: a looser spring, everyone; the shader moves what is skinned to Bip01_BackPack. Bags, packs,
 *     webbing, canteens, chest rigs and armour get RBSJiggleBag (v6.5): only the belly, breathing and that sway, see SHADER_BAG
 *   - the buttocks, thighs and the underside of the upper arms: the belly's up/down lag (fat and obese only)
 *   - breathing (players): deeper and faster when out of breath, running or sprinting
 *   - hits and falls kick every spring (a hit from the front throws the soft parts forward, landing on the floor too)
 * The bones the shader needs (bag, thighs, upper arms) are looked up by name in each mesh's own skinning data.
 * Characters whose body another mod picks (zombies kept by RBSHook, players not on the shared vanilla body) get none.
 * Config: ~/Zomboid/Lua/RealBodyShape_jiggle.txt (enabled=1, strength=<percent>, players=1, player_from=<kg>,
 * bust, bags, limbs, breathing, hits = <percent>).
 */
final class Jiggle {
    static final String CONFIG = "RealBodyShape_jiggle.txt";
    static final String SHADER = "RBSJiggle";
    /** bags, packs, webbing, canteens, chest rigs (v6.5): RBSJiggle with RBS_BAG - the belly, breathing and their own sway, not the
     *  bust / buttocks / thighs / arms, which bounce under them. A duffel over the buttocks and the straps of a pack
     *  across the bust bounced like flesh (user, 2026-11-08: the straps stay still, the bust moves under them) */
    static final String SHADER_BAG = "RBSJiggleBag";
    /** 0 not looked for yet, 1 compiled, 2 missing (Real Body Shape older than 0.3.6) or failed: bags get SHADER */
    private static volatile int bagShaderState = 0;
    static final int SLOTS = 60, SLOT = 59, SLOT2 = 58;
    /** row 0 w of slot 58: the shader trusts the slot only with this in it (a real bone matrix never has exactly 58) */
    static final float MAGIC = 58f;

    // spring of the belly (torso lengths; the pelvis -> neck length is about 0.55 m)
    static final float FREQ = 3.4f, DAMP = 0.30f;
    static final float M_TO_L = 1f / 0.55f;
    static final float MAX_LAG = 0.09f;
    /** how much each body variant's belly moves (Thin, Normal, Fat, Obese) */
    static final float[] GAIN = { 0f, 0f, 1.0f, 1.4f };
    static final float GAIN_UP = 1.0f, GAIN_FORWARD = 0.6f;
    /** player: no bounce up to this weight (kg), the Fat zombie's at 95 kg, the Obese one's at 125 kg and above */
    static final float PLAYER_FROM_KG = 82f;
    private static volatile float playerFrom = PLAYER_FROM_KG;
    /** frame-to-frame noise: the anchor speed is smoothed over this long (s), a single change is capped (torso lengths/s) */
    static final float SPEED_SMOOTH = 0.07f, MAX_KICK = 1.5f;
    /** the bust: a softer spring a little slower than the belly; women, and fat / obese men's chests */
    static final float FREQ_BUST = 2.9f, DAMP_BUST = 0.22f, BUST_UP = 1.0f, BUST_FORWARD = 0.35f, MAX_LAG_BUST = 0.06f;
    /** a woman player's bust: 1.3 (was 1.0) - walking showed too little of it (user, 2026-11-07) */
    static final float BUST_PLAYER = 1.3f, BUST_ZOMBIE = 0.6f, BUST_MALE_PLAYER = 0.4f;
    /** a woman player's buttocks: their own spring, so walking bounces them at any weight (before, they only followed
     *  an overweight belly); a little stiffer than the bust. Gain by weight: x0.6 at 50 kg, x1 at 80, x1.3 from 110 */
    static final float FREQ_BUTT = 3.0f, DAMP_BUTT = 0.26f, BUTT_UP = 1.0f, BUTT_FORWARD = 0.3f, MAX_LAG_BUTT = 0.05f;
    static final float BUTT_PLAYER = 0.8f;
    /** the bust and the belly under torso armour or a chest rig (players): those do not move with them (RBSJiggleBag)
     *  and hold them - a chest rig can cover the bust like a plate (user, 2026-11-08); no underwear boost
     *  under them either. The buttocks, thighs and arms keep their bounce */
    static final float HOLD = 0.25f;
    /** bust / buttocks with nothing on them but underwear (or nothing): x1.1 - clothes hold them (user, 2026-11-09) */
    static final float UNDERWEAR_BOOST = 1.1f;           // 1.5 and 1.3 were too much (user, 2026-11-07, 2026-11-09)
    static final float[] BUST_MALE = { 0f, 0f, 0.3f, 0.6f };
    /** bags on the back: heavier and looser than flesh. Bounced a little too much in game with 0.16 / 1.0 / 0.7 and hit
     *  the 0.12 limit at once (2026-11-06): more damping, less of it, and a soft limit instead of a hard stop */
    static final float FREQ_BAG = 2.3f, DAMP_BAG = 0.24f, BAG_UP = 0.65f, BAG_FORWARD = 0.45f, MAX_LAG_BAG = 0.12f;
    static final float BAG_SHOWN = 0.07f;
    /** buttocks, thighs, underside of the upper arms: this much of the belly's up/down lag */
    static final float LIMB_GAIN = 0.8f;
    /** breathing (players): breaths per second and depth (mesh units) at rest .. out of breath; how fast it changes (s) */
    static final float BREATH_RATE_REST = 0.25f, BREATH_RATE_MAX = 0.7f, BREATH_REST = 0.0008f, BREATH_MAX = 0.0035f;
    static final float EXERT_SMOOTH = 2.0f;
    /** a hit / landing on the floor: the kick given to the springs (torso lengths / s) */
    static final float HIT_KICK = 1.2f, LAND_KICK = 1.5f;
    /** a few seconds of one fat zombie's spring go to the log, to see what it gets */
    static final int TRACE_LINES = 0;                  // 90 while tuning (2026-11-06)

    private static volatile boolean enabled = true, players = true;
    /** women players too; 0 while Hotsco's New Body replaces the female body (its EX add-on has its own physics) */
    private static volatile boolean femalePlayers = true;
    private static volatile float strength = 0.5f;          // 50 %: what looked right in game (2026-11-06)
    private static volatile float bustPct = 0.6f, bagsPct = 1f, limbsPct = 1f, breathPct = 1f, hitsPct = 1f;
    private static volatile float buttPct = 1f, underwearBoost = UNDERWEAR_BOOST;
    private static long cfgMtime = -1, cfgNextCheck;
    private static volatile boolean failed, ready;

    /** 0 = not tried, 2 = compiled and in use, 3 = missing or broken (never switched) */
    private static volatile int shaderState;
    private static volatile Object shader;
    private static Field fEffect, fModelMesh, fMeshSkinning, fBoneName, fBoneOffset;
    private static Method mCreateShader, mShaderName, mShaderProgram, mIsCompiled, mGetString;
    private static Method apCharacter, apSkinning, apCount, apModelAt, sdNumBones, sdBoneAt;
    private static Method chrX, chrY, chrForward, v2X, v2Y, chrFemale, chrAnimal, chrNutrition, nutWeight;
    private static Method chrHasHit, chrHitBehind, chrOnFloor, chrStats, statsGet, chrRunning, chrSprinting;
    private static Method chrWorn, wornSize, wornGet, wornLocation, chrSitGround, chrSitFurniture, chrActionState;
    private static Method wornItem, itemType;
    /** the action states of sitting (not the sitting down, not the getting up: in a stride the web held by the two
     *  thighs cut across between the legs - user, 2026-11-07); released faster than taken */
    static final java.util.Set<String> SIT_STATES = new java.util.HashSet<>(java.util.Arrays.asList(
            "sitonground-sitting", "sitonground-sitting-warmhands", "sitext", "sitonfurniture"));
    static final float SIT_RELEASE = 0.1f;
    /** sitting players' web between the thighs folds away (the shader, body mesh only); off for bodies with other UVs */
    private static volatile boolean sitWeb = true;
    static final float SIT_SMOOTH = 0.25f;
    private static final ThreadLocal<float[]> ROWS59 = ThreadLocal.withInitial(() -> new float[16]);
    private static int sitLogs;
    private static Object statEndurance;
    private static Class<?> playerClass;
    private static Field fFemaleBody, fMaleBody;
    private static Object modelManager;
    private static Method mStore, mLoad, mInvert;
    private static Constructor<?> matrixCtor;
    private static Object fileSystem, zero, identity;

    /** model (zombie body or zombie clothing) -> the effect it had before the switch */
    private static final Map<Object, Object> switched = Collections.synchronizedMap(new WeakHashMap<>());
    /** a mesh's skin transforms array -> the 60-slot array handed to the game instead */
    private static final Map<Object, Pad> padded = Collections.synchronizedMap(new WeakHashMap<>());
    /** AnimationPlayer -> spring state */
    private static final Map<Object, State> states = Collections.synchronizedMap(new WeakHashMap<>());
    /** character / animated visual owner -> {kind, variant, sex, expiry}: kind -1 not human, 0 human, 1 zombie, 2 player */
    private static final Map<Object, long[]> who = Collections.synchronizedMap(new WeakHashMap<>());
    /** SkinningData of a body -> bind-pose frame {pelvis xyz, L, forward xyz, up xyz, pelvis index}; null entry = unusable */
    private static final Map<Object, float[]> frames = Collections.synchronizedMap(new WeakHashMap<>());
    /** SkinningData of a mesh -> its {bag, L thigh, R thigh, L upper arm, R upper arm} bone indices (-1: none) */
    private static final Map<Object, float[]> meshBones = Collections.synchronizedMap(new WeakHashMap<>());
    /** player -> is the body the game hands them the shared vanilla one (another mod's body: no soft parts) */
    private static final Map<Object, Boolean> sharedBody = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<float[]> ROWS2 = ThreadLocal.withInitial(() -> new float[16]);
    /** SkinningData of an animation skeleton -> index of its pelvis bone */
    private static final Map<Object, Integer> pelvisOf = Collections.synchronizedMap(new WeakHashMap<>());
    /** character -> {bare chest, bare hips, expiry}: what it wears over the bust / the buttocks, looked at once a second */
    private static final Map<Object, long[]> bare = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<FloatBuffer> BUF = ThreadLocal.withInitial(() -> FloatBuffer.allocate(16));
    private static final ThreadLocal<float[]> POSE = ThreadLocal.withInitial(() -> new float[16]);

    private static int diag, traced, playerLogs;
    private static Object tracePlayer;
    private static long bounced, summaryAt, hits, landings;
    private static float maxLag;

    private Jiggle() {}

    static final class State {
        final float[] rows = new float[16];   // slot 59 as published
        final float[] rows2 = new float[16];  // slot 58 as published (the bone indices are the mesh's, set in palette)
        float[] frame;              // bind-pose frame of its body
        Object body, bodyData;      // its body model and that model's skinning data the frame came from
        boolean on;                 // anything soft at all (else both slots are still)
        float gain, bustGain, bagGain, limbGain, breathGain;
        long t;
        float aU, aF;               // anchor (pelvis) position: up, forward (torso lengths)
        float vU, vF;               // anchor velocity
        final float[] fat = new float[4], bust = new float[4], bag = new float[4];   // lag up, forward and their rates
        final float[] butt = new float[4];  // a woman player's buttocks, the same way
        float buttGain;
        boolean player;             // a player: the web between the thighs folds away while sitting
        float sit;                  // how far into a sit (0..1, eased)
        float exert, phase, breath; // breathing: how out of breath (0..1), where in the breath, the push (mesh units)
        boolean hit, floor;         // last time: in a hit reaction, on the floor
        double wx, wy;              // world position last time
        float dtS;                  // smoothed time between updates
        boolean started;
    }

    /** the 60-slot array handed out for one mesh of one character, with its own slot 59 matrix */
    static final class Pad {
        Object[] out;
        Object slot, slot2;
    }

    // ---------------- models: which ones draw with the shader ----------------

    /** a zombie body model (live or corpse) is about to be used: draw it with the shader */
    static void bodyModel(Object model) {
        if (model == null || failed) return;
        try {
            loadConfig();
            if (!enabled) return;
            if (!ready) init();
            if (failed || !shaderReady(model)) return;
            use(model);
        } catch (Throwable t) {
            RBSHook.warn("jiggle (body) failed: " + t);
        }
    }

    /** the body model the game hands a player: the shared vanilla one, switched once players' bellies are on */
    static void playerBody(Object chr, Object model) {
        if (model == null || failed || !players) return;
        try {
            if (!ready) init();
            if (failed || !playerClass.isInstance(chr)) return;
            // another mod's body (e.g. a child's): it and its clothes stay as they are
            Object female = fieldOf(fFemaleBody);
            boolean shared = (model == female && femalePlayers) || model == fieldOf(fMaleBody);
            sharedBody.put(chr, shared);
            if (!shared) return;
            bodyModel(model);
        } catch (Throwable t) {
            RBSHook.warn("jiggle (player body) failed: " + t);
        }
    }

    /** a player (not a zombie, not an animal): their clothes get the shader too */
    static boolean isPlayer(Object chr) {
        try {
            if (chr == null || failed || !players) return false;
            if (!ready) init();
            return !failed && playerClass.isInstance(chr) && !(Boolean) chrAnimal.invoke(chr)
                    && !Boolean.FALSE.equals(sharedBody.get(chr));
        } catch (Throwable t) {
            return false;
        }
    }

    /** shader for a clothing piece a zombie or a player puts on: ours instead of the plain one, otherwise as given */
    static Object clothingShader(Object shaderName, Object mesh, boolean wearer) {
        if (!wearer || shaderState != 2 || !enabled) return shaderName;
        if (shaderName != null && !shaderName.toString().isEmpty() && !shaderName.toString().equalsIgnoreCase("basicEffect"))
            return shaderName;                                  // a piece with its own shader keeps it
        String m = mesh == null ? "" : mesh.toString().toLowerCase(Locale.ROOT).replace('\\', '/');
        if (m.contains("hair") || m.contains("beard")) return shaderName;
        if (isGear(m) && bagShaderThere()) return SHADER_BAG;
        return SHADER;
    }

    /** what is stiff or hangs on the body rather than fitting it like cloth: the game's and mods' bag folder
     *  (Skinned/BackPacks: packs, satchels, duffels, the ALICE webbing, fanny packs, bags in hand), canteens, chest
     *  rigs, and the skinned armour that lies on a soft part (cuirasses, bullet / catcher vests, shoulder pads, the
     *  ammo strap, the codpiece; user, 2026-11-08 "there is armour too"). Torso armour and chest rigs also hold the
     *  bust and the belly (HOLD), which would push through them otherwise */
    static final String[] GEAR = { "backpacks/", "canteen", "cuirass", "bulletvest", "catchervest", "shoulderpad",
            "football_pads", "articshoulder", "ammostrap", "codpiece", "kneepad", "shinkneeguard", "shinpad",
            "elbowpad", "gorget", "scba", "shoulderholster" };  // static pieces (greaves, metal pads) never come here
    // checked against every skinned clothing model of the game and a big clothing mod (2026-11-08): what lies on a soft part
    // and is not cloth is here; necklaces, ties, scarves, tails, garters and vests stay with the body on purpose

    static boolean isGear(String m) {
        for (String g : GEAR) if (m.contains(g)) return true;
        return isChestRig(m);
    }

    /** worn there, the bust and the belly are held (HOLD): torso armour (B42 body location ids, lower case) */
    static final java.util.Set<String> HOLDS = new java.util.HashSet<>(java.util.Arrays.asList(
            "cuirass", "torsoextravestbullet", "sportshoulderpad", "sportshoulderpadontop"));

    /** "chestrig" in a mesh path or an item type (Bag_ChestRig and mods' rigs), not the zombie wound
     *  overlay ZedDmg_ShotgunChestRight */
    static boolean isChestRig(String s) {
        return s.contains("chestrig") && !s.contains("chestright");
    }

    private static boolean bagShaderThere() {
        return bagShaderState == 1;                             // compiled in shaderReady, with RBSJiggle
    }

    /** compiles RBSJiggleBag on that model (shaderReady puts its shader back afterwards): 1 ok, 2 missing / failed */
    private static int compileBagShader(Object model) {
        try {
            String vert = (String) mGetString.invoke(fileSystem, "media/shaders/" + SHADER_BAG + ".vert");
            String frag = (String) mGetString.invoke(fileSystem, "media/shaders/" + SHADER_BAG + ".frag");
            if (vert == null || frag == null || !new File(vert).isFile() || !new File(frag).isFile()) {
                RBSHook.log("jiggle: " + SHADER_BAG + " not found (Real Body Shape older than 0.3.6) - bags move like the clothes");
                return 2;
            }
            mCreateShader.invoke(model, SHADER_BAG);
            Object b = fEffect.get(model);
            Object prog = b == null ? null : mShaderProgram.invoke(b);
            if (b != null && SHADER_BAG.equalsIgnoreCase(String.valueOf(mShaderName.invoke(b)))
                    && prog != null && (Boolean) mIsCompiled.invoke(prog)) {
                RBSHook.log("jiggle: bags, packs, webbing, canteens, chest rigs and armour use " + SHADER_BAG + " - they sway, the bust, buttocks and thighs bounce under them");
                return 1;
            }
            RBSHook.warn("jiggle: " + SHADER_BAG + " did not compile (see the shader errors above) - bags move like the clothes");
        } catch (Throwable t) {
            RBSHook.warn("jiggle: " + SHADER_BAG + " failed: " + t + " - bags move like the clothes");
        }
        return 2;
    }

    private static boolean shaderReady(Object model) throws Exception {
        int st = shaderState;
        if (st == 2) return true;
        if (st == 3) return false;
        synchronized (Jiggle.class) {
            if (shaderState != 0) return shaderState == 2;
            String vert = (String) mGetString.invoke(fileSystem, "media/shaders/" + SHADER + ".vert");
            String frag = (String) mGetString.invoke(fileSystem, "media/shaders/" + SHADER + ".frag");
            if (vert == null || frag == null || !new File(vert).isFile() || !new File(frag).isFile()) {
                shaderState = 3;
                RBSHook.log("jiggle: shader " + SHADER + " not found - soft bellies need Real Body Shape 0.3.4 or newer");
                return false;
            }
            Object before = fEffect.get(model);
            mCreateShader.invoke(model, SHADER);                 // compiles on the render thread, waits for it
            Object s = fEffect.get(model);
            Object prog = s == null ? null : mShaderProgram.invoke(s);
            boolean ok = s != null && SHADER.equalsIgnoreCase(String.valueOf(mShaderName.invoke(s)))
                    && prog != null && (Boolean) mIsCompiled.invoke(prog);
            if (!ok) {
                fEffect.set(model, before);
                shaderState = 3;
                RBSHook.warn("jiggle: shader " + SHADER + " did not compile (see the shader errors above) - everyone keeps the game's shader");
                return false;
            }
            bagShaderState = compileBagShader(model);
            fEffect.set(model, before);                          // use() records it and switches
            shader = s;
            shaderState = 2;
            RBSHook.log("jiggle ready - shader " + SHADER + " compiled (" + vert + "); fat and obese zombies' bellies bounce, players' from "
                    + Math.round(playerFrom) + " kg" + (players ? "" : " (players switched off)") + "; bust, bags, thighs / arms, breathing, hits");
            return true;
        }
    }

    private static void use(Object model) throws Exception {
        Object s = shader;
        Object cur = fEffect.get(model);
        if (cur == s) return;
        switched.put(model, cur);
        fEffect.set(model, s);
    }

    /** config switched off: give every switched model its old shader back */
    private static void restoreAll() {
        synchronized (switched) {
            for (Map.Entry<Object, Object> e : switched.entrySet()) {
                try {
                    if (fEffect.get(e.getKey()) == shader && e.getValue() != null) fEffect.set(e.getKey(), e.getValue());
                } catch (Exception ignored) {}
            }
            switched.clear();
        }
    }

    // ---------------- palette (called from RBSHook.skinTransforms after Fit and Limbs) ----------------

    static Object palette(Object player, Object data, Object t) {
        if (shaderState != 2 || t == null || failed) return t;
        try {
            Object[] tr = (Object[]) t;
            if (tr.length >= SLOTS) return t;
            long[] z = whoOf(player);
            if (z[0] < 0) return t;                             // not a human (animals, vehicles): the game's own shaders
            Pad pad;
            synchronized (padded) {
                pad = padded.get(tr);
                if (pad == null) {
                    pad = new Pad();
                    // the same array type as the game's (Matrix4f[]): the patched method casts what it gets back
                    pad.out = (Object[]) java.lang.reflect.Array.newInstance(tr.getClass().getComponentType(), SLOTS);
                    for (int i = tr.length; i < SLOT; i++) pad.out[i] = identity;
                    pad.slot = matrixCtor.newInstance();
                    pad.slot2 = matrixCtor.newInstance();
                    padded.put(tr, pad);
                }
            }
            Object[] out = pad.out;
            System.arraycopy(tr, 0, out, 0, tr.length);
            State s = enabled ? states.get(player) : null;
            // a mesh in a space of its own (another mod's FBX, x100, Z up): the shader's rows are in the game's space and
            // threw its sleeves about (user, 2026-11-11) - they are converted to its space, so it bounces like the rest
            float[] space = s != null && s.on && data != null && data != s.bodyData && Fit.ensureReady() && Fit.ensureLayout(t)
                    ? MeshSpace.meshToGame(player, data) : null;
            if (s == null || !s.on) {
                out[SLOT] = zero;
                if (tr.length <= SLOT2) out[SLOT2] = identity;
                return out;
            }
            if (s.sit > 0f && data != null && data == s.bodyData) {
                float[] r59 = ROWS59.get();
                System.arraycopy(s.rows, 0, r59, 0, 16);
                r59[3] = 1f + s.sit;                            // the body mesh only: its web folds away
                write(pad.slot, r59);
                if (sitLogs++ == 0) RBSHook.log("jiggle: sitting - the web between the thighs folds away (body mesh recognised)");
            } else {
                write(pad.slot, space == null ? s.rows : MeshSpace.jiggleRows59(s.rows, space));
            }
            out[SLOT] = pad.slot;
            if (tr.length <= SLOT2) {                           // the extras, with this mesh's own bone numbers
                float[] b = bonesOf(data), r = ROWS2.get();
                System.arraycopy(s.rows2, 0, r, 0, 16);
                r[7] = b[0];
                r[12] = b[1];
                r[13] = b[2];
                r[14] = b[3];
                r[15] = b[4];
                write(pad.slot2, space == null ? r : MeshSpace.jiggleRows58(r, space));
                out[SLOT2] = pad.slot2;
            }
            return out;
        } catch (Throwable e) {
            RBSHook.warn("jiggle (palette) failed: " + e);
            return t;
        }
    }

    /** {bag, L thigh, R thigh, L upper arm, R upper arm}: the mesh's own numbers of those bones, -1 when it has none */
    private static float[] bonesOf(Object data) throws Exception {
        float[] b = data == null ? null : meshBones.get(data);
        if (b != null) return b;
        b = new float[]{ -1, -1, -1, -1, -1 };
        if (data != null) {
            int n = (Integer) sdNumBones.invoke(data);
            for (int i = 0; i < n; i++) {
                Object bone = sdBoneAt.invoke(data, i);
                Object nm = bone == null ? null : fBoneName.get(bone);
                String x = nm == null ? "" : nm.toString().toLowerCase(Locale.ROOT);
                if (x.endsWith("_backpack")) b[0] = i;
                else if (x.endsWith("_l_thigh")) b[1] = i;
                else if (x.endsWith("_r_thigh")) b[2] = i;
                else if (x.endsWith("_l_upperarm")) b[3] = i;
                else if (x.endsWith("_r_upperarm")) b[4] = i;
            }
            meshBones.put(data, b);
        }
        return b;
    }

    /** {kind, variant, female, expiry}: kind -1 not human, 0 other human (mannequin, corpse, preview), 1 zombie, 2 player;
     *  variant -1: a zombie whose body another mod picked (nothing soft) */
    private static long[] whoOf(Object player) throws Exception {
        Object chr = apCharacter.invoke(player);
        Object key = chr != null ? chr : player;
        long now = System.nanoTime();
        long[] e = who.get(key);
        if (e != null && now < e[3]) return e;
        long[] r = { -1, 0, 0, now + 2_000_000_000L };
        if (chr != null) {
            if (RBSHook.isLiveZombie(chr)) {
                Object v = RBSHook.visualOf(chr);
                if (v != null) {
                    r[0] = 1;
                    r[1] = RBSHook.wearsVariant(chr) ? RBSHook.variantOf(v) : -1;    // another mod's body: nothing soft
                    r[2] = RBSHook.isFemaleVisual(v) ? 1 : 0;
                } else r[0] = 0;
            } else if (!(Boolean) chrAnimal.invoke(chr)) {
                r[0] = playerClass.isInstance(chr) ? 2 : 0;
                r[2] = (Boolean) chrFemale.invoke(chr) ? 1 : 0;
            }
        } else {
            Object v = Limbs.animatedVisual(player);            // AnimatedModel: corpses, mannequins, previews
            if (v != null && RBSHook.isHumanVisual(v)) r[0] = 0;
        }
        who.put(key, r);
        return r;
    }

    // ---------------- spring (called from RBSHook.updateSkinTransforms after Height) ----------------

    static void update(Object player) {
        if (shaderState != 2 || failed || player == null) return;
        try {
            loadConfig();
            if (!enabled) return;
            Object chr = apCharacter.invoke(player);
            if (chr == null) return;
            long[] z = whoOf(player);
            boolean female = z[2] == 1;
            float gain, bust, bag = bagsPct, breath = 0f, butt = 0f, bellyHold = 1f;
            Object model;
            if (z[0] == 1 && z[1] >= 0) {
                if (!RBSHook.zombiesEnabled()) return;
                int var = (int) z[1];
                gain = GAIN[var];
                bust = female ? BUST_ZOMBIE : BUST_MALE[var];
                model = RBSHook.variantModelOf(female, var);
            } else if (z[0] == 2 && players && !Boolean.FALSE.equals(sharedBody.get(chr))) {
                gain = playerGain(chr);
                bust = female ? BUST_PLAYER : gain * BUST_MALE_PLAYER;
                breath = breathPct;
                long[] b = bareOf(chr);
                if (b[3] == 1) {                                // torso armour / a chest rig: still, and holds them
                    bust *= HOLD;
                    bellyHold = HOLD;
                } else if (female && b[0] == 1) bust *= underwearBoost;
                if (female) butt = BUTT_PLAYER * buttPct * buttWeight(chr) * (b[1] == 1 ? underwearBoost : 1f);
                model = fieldOf(female ? fFemaleBody : fMaleBody);
            } else {
                State old = states.get(player);
                if (old != null) old.on = false;
                return;
            }
            gain *= strength;
            bust *= bustPct;
            if ((gain <= 0 && bust <= 0 && bag <= 0 && breath <= 0 && butt <= 0) || model == null) {
                State old = states.get(player);
                if (old != null) old.on = false;                // lost weight / switched off: still again
                return;
            }
            Object mesh = fModelMesh.get(model);
            Object bodyData = mesh == null ? null : fMeshSkinning.get(mesh);
            if (bodyData == null) return;                       // body still loading
            State s = states.get(player);
            if (s == null || s.bodyData != bodyData) {
                float[] fr = frameOf(bodyData);                 // a new body (the player's is rebuilt as the weight changes)
                if (fr == null) return;
                if (s == null) {
                    s = new State();
                    states.put(player, s);
                }
                s.frame = fr;
                s.body = model;
                s.bodyData = bodyData;
                if (z[0] == 2 && playerLogs++ < 6) {
                    Object nut = chrNutrition.invoke(chr);
                    double kg = nut == null ? 0 : ((Number) nutWeight.invoke(nut)).doubleValue();
                    RBSHook.log(String.format(Locale.ROOT, "jiggle: player's soft parts on - %.1f kg, belly %.0f%% of a Fat zombie's (strength included), bust %.0f%%, buttocks %.0f%%, bags %.0f%%, breathing %.0f%%%s",
                            kg, gain * 100, bust * 100, butt * 100, bag * 100, breath * 100, fEffect.get(model) == shader ? "" : ", body switched to the shader now"));
                }
            }
            boolean relinked = fEffect.get(model) != shader;
            if (relinked) use(model);                           // a reloaded body can come back with the game's shader
            if (relinked && z[0] == 2 && playerLogs++ < 12) RBSHook.log("jiggle: player's body had the game's shader again (reloaded?) - switched back");
            s.gain = Math.max(0f, gain) * bellyHold;
            s.limbGain = Math.max(0f, gain) * limbsPct;     // not held: the buttocks, thighs and arms are not under it
            s.bustGain = bust;
            s.buttGain = butt;
            s.player = z[0] == 2;
            s.bagGain = bag;
            s.breathGain = breath;
            s.on = true;
            step(s, player, chr);
        } catch (Throwable e) {
            RBSHook.warn("jiggle (spring) failed: " + e);
        }
    }

    /** 0 up to playerFrom, 1 at 95 kg (the Fat zombie's body), 1.4 at 125 kg (the Obese one's) and above */
    private static float playerGain(Object chr) throws Exception {
        Object nut = chrNutrition.invoke(chr);
        if (nut == null) return 0f;
        float kg = (float) ((Number) nutWeight.invoke(nut)).doubleValue();
        float from = Math.min(playerFrom, 94f);
        if (kg <= from) return 0f;
        if (kg <= 95f) return (kg - from) / (95f - from);
        return Math.min(1.4f, 1f + 0.4f * (kg - 95f) / 30f);
    }

    /** a woman player's buttocks by weight: x0.6 at 50 kg, x1 at 80 kg, x1.3 from 110 kg */
    private static float buttWeight(Object chr) throws Exception {
        Object nut = chrNutrition.invoke(chr);
        float kg = nut == null ? 80f : (float) ((Number) nutWeight.invoke(nut)).doubleValue();
        if (kg <= 80f) return Math.max(0.6f, 0.6f + 0.4f * (kg - 50f) / 30f);
        return Math.min(1.3f, 1f + 0.3f * (kg - 80f) / 30f);
    }

    /** what covers the bust / the buttocks: worn-item locations (B42 ItemBodyLocation ids such as "BathRobe",
     *  "Jacket_Bulky", lower case, without a "module:" part). Underwear (Underwear, UnderwearTop, UnderwearBottom...),
     *  and nothing, count as bare; anything else on the trunk / hips holds them */
    static final java.util.Set<String> CHEST = new java.util.HashSet<>(java.util.Arrays.asList(
            "tshirt", "shirt", "shortsleeveshirt", "tanktop", "sweater", "sweaterhat", "jacket", "jacket_bulky",
            "jacket_down", "jackethat", "jackethat_bulky", "jacketsuit", "jersey", "fulltop", "fullsuit", "fullsuithead",
            "fullsuitheadscba", "boilersuit", "dress", "longdress", "bathrobe", "fullrobe", "torso1", "torso1legs1",
            "torsoextra", "torsoextravest", "torsoextravestbullet", "cuirass", "bodycostume", "vesttexture",
            "twjacket", "twtrenchcoat"));       // a clothing mod's own locations: a jacket, a long coat
    static final java.util.Set<String> HIPS = new java.util.HashSet<>(java.util.Arrays.asList(
            "pants", "pants_skinny", "pantsextra", "shortpants", "shortsshort", "skirt", "longskirt", "dress",
            "longdress", "legs1", "legs5", "torso1legs1", "boilersuit", "fullsuit", "fullsuithead", "fullsuitheadscba",
            "bathrobe", "fullrobe", "bodycostume", "twtrenchcoat"));

    /** {bare chest 1/0, bare hips 1/0, expiry, held 1/0 (torso armour or a chest rig)}: looked at again once a second */
    private static long[] bareOf(Object chr) throws Exception {
        long now = System.nanoTime();
        long[] e = bare.get(chr);
        if (e != null && now < e[2]) return e;
        long[] r = { 0, 0, now + 1_000_000_000L, 0 };
        if (chrWorn != null) {
            Object worn = chrWorn.invoke(chr);
            boolean chest = false, hips = false, rig = false;
            int n = worn == null ? 0 : (Integer) wornSize.invoke(worn);
            for (int i = 0; i < n; i++) {
                Object wi = wornGet.invoke(worn, i);
                if (wi == null) continue;
                if (itemType != null && !rig) {
                    Object item = wornItem.invoke(wi);
                    Object type = item == null ? null : itemType.invoke(item);
                    if (type != null && isChestRig(type.toString().toLowerCase(Locale.ROOT))) rig = true;
                }
                Object loc = wornLocation.invoke(wi);
                if (loc == null) continue;
                String id = loc.toString().toLowerCase(Locale.ROOT);
                int c = id.indexOf(':');
                if (c >= 0) id = id.substring(c + 1);
                if (CHEST.contains(id)) chest = true;
                if (HIPS.contains(id)) hips = true;
                if (HOLDS.contains(id)) rig = true;
            }
            r[0] = chest ? 0 : 1;
            r[1] = hips ? 0 : 1;
            r[3] = rig ? 1 : 0;
            if (e == null || e[0] != r[0] || e[1] != r[1] || e[3] != r[3]) {
                if (playerLogs++ < 30) RBSHook.log("jiggle: player wears " + (chest ? "something over the bust" : "only underwear (or nothing) over the bust")
                        + ", " + (hips ? "something over the hips" : "only underwear (or nothing) over the hips")
                        + (rig ? ", torso armour or a chest rig (holds the bust and the belly)" : ""));
            }
        }
        bare.put(chr, r);
        return r;
    }

    private static Object fieldOf(Field f) throws Exception {
        return f == null ? null : f.get(modelManager);
    }

    private static void step(State s, Object player, Object chr) throws Exception {
        long now = System.nanoTime();
        float[] fr = s.frame;
        // anchor: the pelvis in model space (the animation's bob and sway) ...
        Object data = apSkinning.invoke(player);
        if (data == null) return;
        int pelvis = animPelvis(data);
        if (pelvis < 0 || pelvis >= (Integer) apCount.invoke(player)) return;
        float[] m = POSE.get();
        Object pm = apModelAt.invoke(player, pelvis);
        if (pm == null) return;
        read(pm, m);
        boolean cv = Math.abs(m[3]) + Math.abs(m[7]) + Math.abs(m[11]) <= Math.abs(m[12]) + Math.abs(m[13]) + Math.abs(m[14]);
        float px = cv ? m[12] : m[3], py = cv ? m[13] : m[7], pz = cv ? m[14] : m[11];
        float L = fr[3];
        // up/down: the pelvis height in the animation; forward/back comes from the ground only (an animation can
        // carry the root forward and snap it back when it loops, which would kick the belly every loop)
        float aU = (px * fr[7] + py * fr[8] + pz * fr[9]) / L;
        float aF = 0f;
        // ... plus how far the zombie moved over the ground, along where it faces
        double wx = ((Number) chrX.invoke(chr)).doubleValue(), wy = ((Number) chrY.invoke(chr)).doubleValue();
        float dt = s.started ? (now - s.t) / 1e9f : 0f;
        if (!s.started || dt <= 0f || dt > 0.25f || Math.abs(wx - s.wx) + Math.abs(wy - s.wy) > 2.0) {
            s.started = true;
            s.t = now;
            s.aU = aU;
            s.aF = aF;
            s.vU = s.vF = 0f;
            java.util.Arrays.fill(s.fat, 0f);
            java.util.Arrays.fill(s.bust, 0f);
            java.util.Arrays.fill(s.bag, 0f);
            java.util.Arrays.fill(s.butt, 0f);
            s.dtS = 0f;
            s.wx = wx;
            s.wy = wy;
            s.hit = (Boolean) chrHasHit.invoke(chr);           // already lying / reeling when first seen: no kick
            s.floor = (Boolean) chrOnFloor.invoke(chr);
            publish(s);
            return;
        }
        if (dt < 0.004f) return;                                // several updates in one frame: wait for time to pass
        // the hook runs at a slightly different moment of each frame, so the measured time jitters while the pose and
        // the position moved by a whole frame: speeds from the raw time shake. Smooth the time and the speeds.
        float dtRaw = dt;
        s.dtS = s.dtS <= 0f ? dt : s.dtS + (dt - s.dtS) * 0.1f;
        dt = Math.max(0.005f, Math.min(0.1f, s.dtS));
        Object fwd = chrForward.invoke(chr);
        float fx = fwd == null ? 0f : ((Number) v2X.invoke(fwd)).floatValue(), fy = fwd == null ? 0f : ((Number) v2Y.invoke(fwd)).floatValue();
        float ground = (float) ((wx - s.wx) * fx + (wy - s.wy) * fy) * M_TO_L;
        float rawU = (aU - s.aU) / dt, rawF = (aF - s.aF + ground) / dt;
        float k = 1f - (float) Math.exp(-dt / SPEED_SMOOTH);
        float vU = s.vU + (rawU - s.vU) * k, vF = s.vF + (rawF - s.vF) * k;
        // the anchor changed speed: the belly keeps its own for a moment (that is the lag); a pop of the animation
        // (one pose to another) is capped so it gives one bounce, not a slap
        float dvU = clamp(vU - s.vU, MAX_KICK), dvF = clamp(vF - s.vF, MAX_KICK);
        push(s, -dvU, -dvF);
        kicks(s, chr);
        integrate(s.fat, FREQ, DAMP, dt, MAX_LAG);
        integrate(s.bust, FREQ_BUST, DAMP_BUST, dt, MAX_LAG_BUST / Math.max(0.1f, BUST_UP));
        integrate(s.bag, FREQ_BAG, DAMP_BAG, dt, MAX_LAG_BAG);
        integrate(s.butt, FREQ_BUTT, DAMP_BUTT, dt, MAX_LAG_BUTT / Math.max(0.1f, BUTT_UP));
        breathe(s, chr, dt);
        float sitting = 0f;
        if (s.player && sitWeb) {
            if (chrActionState != null) {
                Object st = chrActionState.invoke(chr);
                if (st != null && SIT_STATES.contains(st.toString().toLowerCase(Locale.ROOT))) sitting = 1f;
            } else if (chrSitGround != null
                    && ((Boolean) chrSitGround.invoke(chr) || (chrSitFurniture != null && (Boolean) chrSitFurniture.invoke(chr)))) {
                sitting = 1f;
            }
        }
        s.sit += (sitting - s.sit) * (1f - (float) Math.exp(-dt / (sitting > s.sit ? SIT_SMOOTH : SIT_RELEASE)));
        if (s.sit < 0.002f) s.sit = 0f;
        s.t = now;
        s.aU = aU;
        s.aF = aF;
        s.vU = vU;
        s.vF = vF;
        s.wx = wx;
        s.wy = wy;
        publish(s);
        trace(player, s, dtRaw, dt, rawU, rawF);
    }

    /** one damped spring, up and forward: p = {lag up, lag forward, rate up, rate forward}, in small steps */
    private static void integrate(float[] p, float freq, float damp, float dt, float lim) {
        float w0 = (float) (2 * Math.PI * freq);
        int n = Math.max(1, (int) Math.ceil(dt / (1f / 240f)));
        float h = dt / n;
        for (int i = 0; i < n; i++) {
            p[2] += (-w0 * w0 * p[0] - 2 * damp * w0 * p[2]) * h;
            p[3] += (-w0 * w0 * p[1] - 2 * damp * w0 * p[3]) * h;
            p[0] += p[2] * h;
            p[1] += p[3] * h;
        }
        p[0] = clamp(p[0], lim);
        p[1] = clamp(p[1], lim);
    }

    /** the same change of rate (up, forward) to every spring */
    private static void push(State s, float du, float df) {
        s.fat[2] += du;
        s.fat[3] += df;
        s.bust[2] += du;
        s.bust[3] += df;
        s.bag[2] += du;
        s.bag[3] += df;
        s.butt[2] += du;
        s.butt[3] += df;
    }

    /** a hit (the reaction starts) throws the soft parts the way the blow came from; landing on the floor shakes them */
    private static void kicks(State s, Object chr) throws Exception {
        boolean hit = (Boolean) chrHasHit.invoke(chr), floor = (Boolean) chrOnFloor.invoke(chr);
        if (hitsPct > 0) {
            if (hit && !s.hit) {
                // from the front the trunk is pushed back and what is soft keeps going: forward, relative to it
                float k = HIT_KICK * hitsPct * ((Boolean) chrHitBehind.invoke(chr) ? -1f : 1f);
                push(s, 0.4f * Math.abs(k), k);
                hits++;
            }
            if (floor && !s.floor) {
                float k = LAND_KICK * hitsPct;
                push(s, -0.5f * k, k);
                landings++;
            }
        }
        s.hit = hit;
        s.floor = floor;
    }

    /** breathing (players): faster and deeper the more out of breath (endurance), running or sprinting */
    private static void breathe(State s, Object chr, float dt) throws Exception {
        if (s.breathGain <= 0f) {
            s.breath = 0f;
            return;
        }
        float endurance = 1f;
        Object st = chrStats == null ? null : chrStats.invoke(chr);
        if (st != null && statEndurance != null) endurance = ((Number) statsGet.invoke(st, statEndurance)).floatValue();
        float run = (Boolean) chrSprinting.invoke(chr) ? 0.5f : ((Boolean) chrRunning.invoke(chr) ? 0.25f : 0f);
        float target = Math.max(0f, Math.min(1f, (1f - endurance) * 1.3f + run));
        s.exert += (target - s.exert) * (1f - (float) Math.exp(-dt / EXERT_SMOOTH));
        float rate = BREATH_RATE_REST + (BREATH_RATE_MAX - BREATH_RATE_REST) * s.exert;
        s.phase = (float) ((s.phase + 2 * Math.PI * rate * dt) % (2 * Math.PI));
        s.breath = (BREATH_REST + (BREATH_MAX - BREATH_REST) * s.exert) * s.breathGain * (float) Math.sin(s.phase);
    }

    /** the pelvis in the animation skeleton (its bones need not be numbered like the body mesh's) */
    private static int animPelvis(Object data) throws Exception {
        Integer c = pelvisOf.get(data);
        if (c != null) return c;
        int p = -1, n = (Integer) sdNumBones.invoke(data);
        for (int i = 0; i < n && p < 0; i++) {
            Object b = sdBoneAt.invoke(data, i);
            Object nm = b == null ? null : fBoneName.get(b);
            if (nm != null && nm.toString().toLowerCase(Locale.ROOT).endsWith("_pelvis")) p = i;
        }
        pelvisOf.put(data, p);
        return p;
    }

    /** a few seconds of the first fat zombie seen running, one line per update */
    private static void trace(Object player, State s, float dtRaw, float dt, float rawU, float rawF) {
        if (traced >= TRACE_LINES) return;
        if (tracePlayer == null) {
            if (Math.abs(s.vF) < 1.0f) return;                  // wait for one that moves (about 0.5 m/s)
            tracePlayer = player;
            RBSHook.log("jiggle trace: dtRaw dtUsed | speed up/fwd raw -> smoothed (torso lengths/s) | lag up/fwd (torso lengths)");
        }
        if (player != tracePlayer) return;
        traced++;
        RBSHook.log(String.format(Locale.ROOT, "jiggle trace %2d: %.4f %.4f | %+6.2f %+6.2f -> %+6.2f %+6.2f | %+.4f %+.4f",
                traced, dtRaw, dt, rawU, rawF, s.vU, s.vF, s.fat[0], s.fat[1]));
    }

    /** slot 59: belly displacement (mesh units) + on, pelvis + torso length, forward, up - rows of the shader's matrix;
     *  slot 58: bust displacement + marker, bag displacement + bag bone, thighs / buttocks / arms displacement + breath,
     *  the thigh and upper arm bones (the bone numbers are each mesh's own: palette puts them in) */
    private static void publish(State s) throws Exception {
        float[] fr = s.frame;
        float L = fr[3];
        float u = clamp(s.fat[0] * GAIN_UP * s.gain, MAX_LAG * 1.5f), f = clamp(s.fat[1] * GAIN_FORWARD * s.gain, MAX_LAG * 1.5f);
        float[] j = s.rows;
        for (int c = 0; c < 3; c++) j[c] = (fr[7 + c] * u + fr[4 + c] * f) * L;
        j[3] = s.on ? 1f : 0f;
        float[] k = s.rows2;
        float bu = clamp(s.bust[0] * BUST_UP * s.bustGain, MAX_LAG_BUST), bf = clamp(s.bust[1] * BUST_FORWARD * s.bustGain, MAX_LAG_BUST);
        float gu = soft(s.bag[0] * BAG_UP * s.bagGain, BAG_SHOWN), gf = soft(s.bag[1] * BAG_FORWARD * s.bagGain, BAG_SHOWN);
        float lu = clamp(s.fat[0] * LIMB_GAIN * s.limbGain, MAX_LAG * 1.5f);
        for (int c = 0; c < 3; c++) {
            k[c] = (fr[7 + c] * bu + fr[4 + c] * bf) * L;
            k[4 + c] = (fr[7 + c] * gu + fr[4 + c] * gf) * L;
            k[8 + c] = fr[7 + c] * lu * L;
        }
        k[3] = MAGIC;
        k[7] = -1f;
        k[11] = s.breath;
        k[12] = k[13] = k[14] = k[15] = -1f;
        j[4] = fr[0];
        j[5] = fr[1];
        j[6] = fr[2];
        j[7] = L;
        j[8] = fr[4];
        j[9] = fr[5];
        j[10] = fr[6];
        // the buttocks' own lag (women players): row 2 w up, row 3 w forward, mesh units (0: nothing)
        float tu = clamp(s.butt[0] * BUTT_UP * s.buttGain, MAX_LAG_BUTT), tf = clamp(s.butt[1] * BUTT_FORWARD * s.buttGain, MAX_LAG_BUTT);
        j[11] = tu * L;
        j[12] = fr[7];
        j[13] = fr[8];
        j[14] = fr[9];
        j[15] = tf * L;
        float lag = Math.max(Math.max(Math.abs(u), Math.abs(f)), Math.max(Math.max(Math.abs(bu), Math.abs(bf)), Math.max(Math.abs(gu), Math.abs(gf))));
        if (lag > 0.004f) {
            bounced++;
            if (lag > maxLag) maxLag = lag;
        }
        summary();
    }

    /** like clamp, but it eases into the limit (tanh) instead of stopping dead at it */
    private static float soft(float v, float lim) {
        return lim * (float) Math.tanh(v / lim);
    }

    private static float clamp(float v, float lim) {
        return v > lim ? lim : (v < -lim ? -lim : v);
    }

    // ---------------- bind-pose frame of a body ----------------

    @SuppressWarnings("unchecked")
    private static float[] frameOf(Object data) throws Exception {
        if (frames.containsKey(data)) return frames.get(data);
        float[] out = null;
        String why = "", sign = "";
        int n = (Integer) sdNumBones.invoke(data);
        int pelvis = -1, neck = -1, spine1 = -1, head = -1, lFoot = -1, rFoot = -1, lArm = -1, rArm = -1, lThigh = -1, rThigh = -1;
        int back = -1, dFront = -1, dBack = -1;
        for (int i = 0; i < n; i++) {
            Object b = sdBoneAt.invoke(data, i);
            Object nm = b == null ? null : fBoneName.get(b);
            String s = nm == null ? "" : nm.toString().toLowerCase(Locale.ROOT);
            if (s.endsWith("_pelvis")) pelvis = i;
            else if (s.endsWith("_neck")) neck = i;
            else if (s.endsWith("_spine1")) spine1 = i;
            else if (s.endsWith("_head")) head = i;
            else if (s.endsWith("_l_foot")) lFoot = i;
            else if (s.endsWith("_r_foot")) rFoot = i;
            else if (s.endsWith("_l_upperarm")) lArm = i;
            else if (s.endsWith("_r_upperarm")) rArm = i;
            else if (s.endsWith("_l_thigh")) lThigh = i;
            else if (s.endsWith("_r_thigh")) rThigh = i;
            else if (s.endsWith("_backpack")) back = i;
            else if (s.endsWith("_dressfront")) dFront = i;
            else if (s.endsWith("_dressback")) dBack = i;
        }
        List<Object> offsets = (List<Object>) fBoneOffset.get(data);
        if (pelvis < 0 || neck < 0 || head < 0 || lFoot < 0 || rFoot < 0) why = "bones missing";
        else if (offsets == null) why = "no bind pose";
        else {
            float[] P = bindPos(offsets, pelvis), N = bindPos(offsets, neck), H = bindPos(offsets, head);
            float[] lf = bindPos(offsets, lFoot), rf = bindPos(offsets, rFoot);
            // left-right from the upper arms (or the thighs); bones no vertex is weighted to have no bind pose in a
            // mesh (an identity offset, so they would all sit at the origin), so only bones bodies always use
            float[] la = bindPos(offsets, lArm), ra = bindPos(offsets, rArm);
            if (la == null || ra == null) {
                la = bindPos(offsets, lThigh);
                ra = bindPos(offsets, rThigh);
            }
            if (P == null || N == null || H == null || lf == null || rf == null || la == null || ra == null) why = "no bind pose";
            else {
                float[] up = norm(new float[]{ H[0] - (lf[0] + rf[0]) / 2, H[1] - (lf[1] + rf[1]) / 2, H[2] - (lf[2] + rf[2]) / 2 });
                float[] left = { la[0] - ra[0], la[1] - ra[1], la[2] - ra[2] };
                float[] fw = up == null ? null : norm(new float[]{   // up x left = forward for the game's skeleton
                        up[1] * left[2] - up[2] * left[1], up[2] * left[0] - up[0] * left[2], up[0] * left[1] - up[1] * left[0] });
                if (fw != null) {
                    // cross-check with bones that sit in front of / behind the trunk, when this mesh has them
                    float[] S1 = bindPos(offsets, spine1), B = bindPos(offsets, back), F = bindPos(offsets, dFront), K = bindPos(offsets, dBack);
                    float vote = 0;
                    if (S1 != null && B != null) vote += dot3(fw, S1[0] - B[0], S1[1] - B[1], S1[2] - B[2]);
                    if (F != null && K != null) vote += dot3(fw, F[0] - K[0], F[1] - K[1], F[2] - K[2]);
                    if (vote < 0) {
                        fw = new float[]{ -fw[0], -fw[1], -fw[2] };
                        sign = ", flipped by the backpack / skirt bones";
                    } else sign = vote > 0 ? ", the backpack / skirt bones agree" : ", no backpack / skirt bones to check";
                }
                float L = up == null ? 0 : (N[0] - P[0]) * up[0] + (N[1] - P[1]) * up[1] + (N[2] - P[2]) * up[2];
                if (up == null || fw == null || L <= 1e-5f) why = "degenerate bind pose";
                else out = new float[]{ P[0], P[1], P[2], L, fw[0], fw[1], fw[2], up[0], up[1], up[2], pelvis };
            }
        }
        frames.put(data, out);
        if (diag++ < 6) {
            RBSHook.log(out != null
                    ? String.format(Locale.ROOT, "jiggle: body frame - torso %.3f units, forward (%.2f, %.2f, %.2f), up (%.2f, %.2f, %.2f)%s",
                        out[3], out[4], out[5], out[6], out[7], out[8], out[9], sign)
                    : "jiggle: body skipped - " + why);
        }
        return out;
    }

    private static float dot3(float[] a, float x, float y, float z) {
        return a[0] * x + a[1] * y + a[2] * z;
    }

    private static float[] norm(float[] v) {
        if (v == null) return null;
        float l = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return l < 1e-6f ? null : new float[]{ v[0] / l, v[1] / l, v[2] / l };
    }

    private static float[] bindPos(List<Object> offsets, int i) throws Exception {
        if (i < 0 || i >= offsets.size() || offsets.get(i) == null) return null;
        Object inv = matrixCtor.newInstance();
        if (mInvert.invoke(null, offsets.get(i), inv) == null) return null;
        float[] m = new float[16];
        read(inv, m);
        boolean cv = Math.abs(m[3]) + Math.abs(m[7]) + Math.abs(m[11]) <= Math.abs(m[12]) + Math.abs(m[13]) + Math.abs(m[14]);
        float[] p = cv ? new float[]{ m[12], m[13], m[14] } : new float[]{ m[3], m[7], m[11] };
        if (Math.abs(p[0]) + Math.abs(p[1]) + Math.abs(p[2]) < 1e-6f) return null;      // identity offset: not in this mesh
        return p;
    }

    // ---------------- matrices ----------------

    private static void read(Object matrix, float[] out) throws Exception {
        FloatBuffer buf = BUF.get();
        buf.clear();
        mStore.invoke(matrix, buf);
        buf.rewind();
        buf.get(out, 0, 16);
    }

    private static void write(Object matrix, float[] m) throws Exception {
        FloatBuffer buf = BUF.get();
        buf.clear();
        buf.put(m, 0, 16);
        buf.rewind();
        mLoad.invoke(matrix, buf);
    }

    // ---------------- setup ----------------

    private static void summary() {
        long now = System.nanoTime();
        if (now < summaryAt) return;
        summaryAt = now + 60_000_000_000L;
        RBSHook.log(String.format(Locale.ROOT, "jiggle: %d bounce(s) so far, largest lag %.3f torso lengths, %d character(s) tracked, %d hit(s), %d landing(s)",
                bounced, maxLag, states.size(), hits, landings));
    }

    private static synchronized void init() {
        if (ready || failed) return;
        try {
            if (!RBSHook.ensureInit()) throw new IllegalStateException("RBSHook not ready");
            ClassLoader cl = Jiggle.class.getClassLoader();
            Class<?> model = Class.forName("zombie.core.skinnedmodel.model.Model", false, cl);
            Class<?> mesh = Class.forName("zombie.core.skinnedmodel.model.ModelMesh", false, cl);
            Class<?> shaderCls = Class.forName("zombie.core.skinnedmodel.shader.Shader", false, cl);
            Class<?> prog = Class.forName("zombie.core.opengl.ShaderProgram", false, cl);
            Class<?> zfs = Class.forName("zombie.ZomboidFileSystem", false, cl);
            Class<?> ap = Class.forName("zombie.core.skinnedmodel.animation.AnimationPlayer", false, cl);
            Class<?> sd = Class.forName("zombie.core.skinnedmodel.model.SkinningData", false, cl);
            Class<?> sb = Class.forName("zombie.core.skinnedmodel.model.SkinningBone", false, cl);
            Class<?> igc = Class.forName("zombie.characters.IsoGameCharacter", false, cl);
            Class<?> m4 = Class.forName("org.lwjgl.util.vector.Matrix4f", false, cl);
            fEffect = model.getField("effect");
            fModelMesh = model.getField("mesh");
            fMeshSkinning = mesh.getField("skinningData");
            mCreateShader = model.getMethod("CreateShader", String.class);
            mShaderName = shaderCls.getMethod("getName");
            mShaderProgram = shaderCls.getMethod("getShaderProgram");
            mIsCompiled = prog.getMethod("isCompiled");
            fileSystem = zfs.getField("instance").get(null);
            mGetString = zfs.getMethod("getString", String.class);
            apCharacter = ap.getMethod("getIsoGameCharacter");
            apSkinning = ap.getMethod("getSkinningData");
            apCount = ap.getMethod("getModelTransformsCount");
            apModelAt = ap.getMethod("getModelTransformAt", int.class);
            sdNumBones = sd.getMethod("numBones");
            sdBoneAt = sd.getMethod("getBoneAt", int.class);
            fBoneName = sb.getField("name");
            fBoneOffset = sd.getField("boneOffset");
            chrX = igc.getMethod("getX");
            chrY = igc.getMethod("getY");
            chrForward = igc.getMethod("getForwardDirection");
            chrFemale = igc.getMethod("isFemale");
            chrAnimal = igc.getMethod("isAnimal");
            playerClass = Class.forName("zombie.characters.IsoPlayer", false, cl);
            chrNutrition = playerClass.getMethod("getNutrition");              // players only, not IsoGameCharacter
            chrHasHit = igc.getMethod("hasHitReaction");
            chrHitBehind = igc.getMethod("isHitFromBehind");
            chrOnFloor = igc.getMethod("isOnFloor");
            chrRunning = igc.getMethod("isRunning");
            chrSprinting = igc.getMethod("isSprinting");
            try {                                                               // sitting: optional
                chrSitGround = igc.getMethod("isSitOnGround");
                chrSitFurniture = igc.getMethod("isSittingOnFurniture");
                try {
                    chrActionState = igc.getMethod("getCurrentActionContextStateName");
                } catch (Throwable t) {
                    chrActionState = null;
                }
            } catch (Throwable t) {
                RBSHook.log("jiggle: sitting not readable (" + t + ") - the web between the thighs stays");
            }
            try {                                                               // underwear only: optional
                chrWorn = igc.getMethod("getWornItems");
                wornSize = chrWorn.getReturnType().getMethod("size");
                wornGet = chrWorn.getReturnType().getMethod("get", int.class);
                wornLocation = wornGet.getReturnType().getMethod("getLocation");
            } catch (Throwable t) {
                chrWorn = null;
                RBSHook.log("jiggle: worn items not readable (" + t + ") - no underwear-only bounce");
            }
            if (chrWorn != null) {
                try {                                                           // chest rigs: optional
                    wornItem = wornGet.getReturnType().getMethod("getItem");
                    itemType = wornItem.getReturnType().getMethod("getType");
                } catch (Throwable t) {
                    itemType = null;
                    RBSHook.log("jiggle: worn item types not readable (" + t + ") - a chest rig does not hold the bust");
                }
            }
            try {                                                               // breathing only: optional
                chrStats = igc.getMethod("getStats");
                Class<?> stat = Class.forName("zombie.characters.CharacterStat", false, cl);
                statsGet = chrStats.getReturnType().getMethod("get", stat);
                statEndurance = stat.getField("ENDURANCE").get(null);
            } catch (Throwable t) {
                chrStats = null;
                RBSHook.log("jiggle: no endurance to read (" + t + ") - breathing stays at rest");
            }
            nutWeight = chrNutrition.getReturnType().getMethod("getWeight");
            Class<?> mm = Class.forName("zombie.core.skinnedmodel.ModelManager", false, cl);
            modelManager = mm.getField("instance").get(null);
            fFemaleBody = mm.getField("femaleModel");
            fMaleBody = mm.getField("maleModel");
            Class<?> v2 = chrForward.getReturnType();
            v2X = v2.getMethod("getX");
            v2Y = v2.getMethod("getY");
            mStore = m4.getMethod("store", FloatBuffer.class);
            mLoad = m4.getMethod("load", FloatBuffer.class);
            mInvert = m4.getMethod("invert", m4, m4);
            matrixCtor = m4.getConstructor();
            zero = matrixCtor.newInstance();
            write(zero, new float[16]);
            identity = matrixCtor.newInstance();                // new Matrix4f() is the identity
            ready = true;
        } catch (Throwable t) {
            failed = true;
            RBSHook.warn("jiggle init failed, bellies stay still: " + t);
        }
    }

    private static float pct(String v) {
        return (float) Math.max(0, Math.min(400, Double.parseDouble(v))) / 100f;
    }

    private static void loadConfig() {
        long now = System.nanoTime();
        if (now < cfgNextCheck) return;
        cfgNextCheck = now + 5_000_000_000L;
        try {
            Path p = Paths.get(System.getProperty("user.home"), "Zomboid", "Lua", CONFIG);
            if (!Files.exists(p)) return;
            long mt = Files.getLastModifiedTime(p).toMillis();
            if (mt == cfgMtime) return;
            cfgMtime = mt;
            boolean en = enabled, pl = players, fp = true;
            float st = strength, pf = playerFrom, bu = bustPct, ba = bagsPct, li = limbsPct, br = breathPct, hi = hitsPct;
            float bt = buttPct, uw = underwearBoost;
            boolean sw = true;
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                if (eq < 0 || line.trim().startsWith("#")) continue;
                String k = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim();
                try {
                    switch (k) {
                        case "enabled": en = !(v.equals("0") || v.equalsIgnoreCase("false")); break;
                        case "strength": st = (float) Math.max(0, Math.min(400, Double.parseDouble(v))) / 100f; break;
                        case "players": pl = !(v.equals("0") || v.equalsIgnoreCase("false")); break;
                        case "female_players": fp = !(v.equals("0") || v.equalsIgnoreCase("false")); break;
                        case "player_from": pf = (float) Math.max(30, Math.min(150, Double.parseDouble(v))); break;
                        case "bust": bu = pct(v); break;
                        case "bags": ba = pct(v); break;
                        case "limbs": li = pct(v); break;
                        case "breathing": br = pct(v); break;
                        case "hits": hi = pct(v); break;
                        case "butt": bt = pct(v); break;
                        case "underwear": uw = pct(v); break;
                        case "sit_web": sw = !(v.equals("0") || v.equalsIgnoreCase("false")); break;
                        default: break;
                    }
                } catch (NumberFormatException ignored) {}
            }
            boolean wasOn = enabled;
            enabled = en;
            strength = st;
            players = pl;
            femalePlayers = fp;
            playerFrom = pf;
            bustPct = bu;
            bagsPct = ba;
            limbsPct = li;
            breathPct = br;
            hitsPct = hi;
            buttPct = bt;
            underwearBoost = uw;
            sitWeb = sw;
            if (wasOn && !en && ready) restoreAll();
            RBSHook.log(String.format(Locale.ROOT, "jiggle config: enabled=%s strength=%.0f%% players=%s (from %.0f kg%s) bust=%.0f%% bags=%.0f%% limbs=%.0f%% breathing=%.0f%% hits=%.0f%% buttocks=%.0f%% underwear only x%.2f",
                    en, st * 100, pl, pf, fp ? "" : ", men only", bu * 100, ba * 100, li * 100, br * 100, hi * 100, bt * 100, uw));
        } catch (Exception e) {
            RBSHook.warn("jiggle config: " + e);
        }
    }
}
