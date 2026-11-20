package rbs;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Eyes that turn and blink, for a head with real eyeballs (a local face mod that gives the HD body its own head, see
 * RealBodyShape_HD's RBS_HEAD_MODS). Its file media/textures/RBSHeadFace/eyes.txt (with several heads: in the chosen
 * one's sub-folder, Head) says where the eyes are; without it nothing here runs - nor with the game's face chosen. Two palette slots of every human mesh whose palette leaves them free (Jiggle.palette):
 *   slot 54 (the head, the same for everyone): row 0 = the +x eye's centre in the bind pose (model space), the eye
 *     unit (model units); row 1 = the lids' shape: middle x, half width, corner height, upper lid top; row 2 = lower
 *     lid bottom, the +x eyeball's texture square (u, v, size); row 3 = a point of the upper lid's skin (u, v), the
 *     eyeball's radius, 54 (all lengths in eye units from the eye's centre; the -x eye is the mirror image)
 *   slot 55 (this character): row 0 = gaze yaw (+ = towards model +x), pitch (+ = up), how far the upper lids are
 *     down (0 open .. 1 shut), 55
 * RBSJiggle.vert turns the eyeball's vertices (texture inside the square, near the centre) about the centre;
 * RBSJiggle.frag draws the upper lid's skin over the eyeball above the blink line (the lids are not moved).
 * What the eyes do (user, 2026-11-16: "blink, also with tiredness / sleep / death / fright; let the eyes glance
 * about by themselves"):
 *   - a blink every 2-6 s (tired: more often, slower, the lids partly down all the time)
 *   - asleep or dead: shut
 *   - frightened (panic jumps up, a zombie right next to them): wide open, no blinking for a while
 *   - looking at something (HeadLook: a zombie in sight, the camera when standing still): the eyes get there first
 *     and the head follows; aiming with nothing to look at: straight ahead; otherwise a glance now and then
 *   - looking down, the upper lids follow a little
 *   - the game paused (single player, or the server in multiplayer): the eyes stop with the world, a blink under way
 *     still opens; the game sped up (single player's fast forward): no blinking (user, 2026-11-16)
 *   - other players in multiplayer wear the variant bodies (Others), which have no eyeballs: nothing is worked out
 */
final class Eyes {
    static final int SLOT = 55, SLOT_HEAD = 54;
    static final float MAGIC = 55f, MAGIC_HEAD = 54f;
    static final String DIR = "media/textures/RBSHeadFace/", NAME = "eyes.txt";
    /** the chosen head's eyes file (Head: its sub-folder, or the top one) */
    static String file() { return DIR + Head.sub() + NAME; }
    /** the eyes move this fast (a saccade, s), the head's look settles over HeadLook's own time */
    static final float TAU_EYE = 0.035f;
    /** a blink: closing, shut, opening (s); tired ones are slower */
    static final float BLINK_CLOSE = 0.07f, BLINK_HOLD = 0.04f, BLINK_OPEN = 0.11f;
    /** looking down, the upper lids come down this share of the way at the lowest gaze */
    static final float LID_FOLLOW = 0.35f;

    private static volatile boolean failed, ready, absent;
    private static float[] head;                                 // slot 54 as published
    private static float yawSign = 1f, maxYaw, maxUp, maxDown;
    private static Method chrStats, statsGet, asleep, dead, veryClose, aiming;
    private static Method gamePaused, speedControls, gameSpeed;            // optional: without them, always running
    private static volatile boolean pausedNow, fastNow;
    private static volatile long gameStateAt;
    private static Object FATIGUE, PANIC;
    private static final Map<Object, St> perChar = Collections.synchronizedMap(new WeakHashMap<>());
    private static int warns, logs;
    private static volatile int headGen = -1;

    private Eyes() {}

    static final class St {
        final float[] rows = new float[16];
        long t, nextBlink, blinkAt, nextGlance, startleUntil;
        float yaw, pitch, gYaw, gPitch, lastPanic = -1f, lastClose;
        int lastVeryClose;
    }

    // ---------------- palette ----------------

    /** the head was switched (Head): its file read again; "the game's face": no eyeballs */
    private static void headChanged() {
        int g = Head.generation();
        if (g == headGen) return;
        synchronized (Eyes.class) {
            if (g == headGen) return;
            headGen = g;
            if (failed) return;
            ready = false;
            absent = Head.none();
            head = null;
            perChar.clear();
            if (absent) RBSHook.log("eyes: the game's own face chosen - no eyeballs");
        }
    }

    /** slot 54: the head's eyes (identity without the file) */
    static Object headSlot(Jiggle.Pad pad) {
        headChanged();
        if (failed || absent) return Jiggle.identity;
        try {
            if (!ready) init();
            float[] h = head;
            if (!ready || h == null) return Jiggle.identity;
            if (pad.slot6 == null) pad.slot6 = Jiggle.matrixCtor.newInstance();
            Jiggle.write(pad.slot6, h);                   // every time: the head can be switched
            return pad.slot6;
        } catch (Throwable t) {
            if (warns++ < 5) RBSHook.warn("eyes (head slot) failed: " + t);
            return Jiggle.identity;
        }
    }

    /** slot 55: this character's gaze and lids */
    static Object slot(Object player, Jiggle.Pad pad) {
        headChanged();
        if (failed || absent) return Jiggle.identity;
        try {
            if (!ready) init();
            if (!ready || head == null) return Jiggle.identity;
            Object chr = Jiggle.apCharacter.invoke(player);
            if (chr == null) return previewSlot(player, pad);
            St s = perChar.get(chr);
            if (s == null) {
                s = new St();
                perChar.put(chr, s);
            }
            long now = System.nanoTime();
            if (now - s.t > 5_000_000L) update(player, chr, s, now);       // once a frame for all its meshes
            if (pad.slot5 == null) pad.slot5 = Jiggle.matrixCtor.newInstance();
            Jiggle.write(pad.slot5, s.rows);
            return pad.slot5;
        } catch (Throwable t) {
            if (warns++ < 5) RBSHook.warn("eyes failed: " + t);
            return Jiggle.identity;
        }
    }

    // ---------------- UI previews (user, 2026-11-19: the eyes blink on the character creation screen too) ----------------
    private static Method amVisual;

    /**
     * slot 55 of a UI preview (character creation, a new game or after a death; the info window): it blinks and its eyes
     * glance about now and then - nothing else (no stats, no head look). Held while the preview does not animate (the
     * game paused after a death). A preview of another mod's NPC wearing another head than the chosen one keeps still
     * (slot 54 is the chosen head's).
     */
    private static Object previewSlot(Object player, Jiggle.Pad pad) throws Exception {
        Object am = Hair.previewOf(player);
        if (am == null) return Jiggle.identity;
        if (amVisual == null || amVisual.getDeclaringClass() != am.getClass()) amVisual = am.getClass().getMethod("getHumanVisual");
        Object[] npc = NpcHeads.previewOf(amVisual.invoke(am));
        if (npc != null) {
            String h = (String) npc[2];
            if (!(h.isEmpty() ? Head.none() : (h + "/").equals(Head.sub()))) return Jiggle.identity;
        }
        St s = perChar.get(player);
        if (s == null) {
            s = new St();
            perChar.put(player, s);
            if (logs < 4) {
                logs++;
                RBSHook.log("eyes: a UI preview (character creation, the info window) - it blinks and glances about");
            }
        }
        long now = System.nanoTime();
        if (now - s.t > 5_000_000L) updatePreview(s, now, Hair.animating(am));
        if (pad.slot5 == null) pad.slot5 = Jiggle.matrixCtor.newInstance();
        Jiggle.write(pad.slot5, s.rows);
        return pad.slot5;
    }

    /** a preview's blinks and glances (the same timing as a character's, never tired, frightened or looking at anything) */
    static void updatePreview(St s, long now, boolean running) {
        float dt = s.t == 0 || !running ? 0f : Math.min(0.1f, (now - s.t) / 1e9f);
        s.t = now;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        if (!running) {                                              // held: nothing starts, nor right after
            s.nextBlink = Math.max(s.nextBlink, now + (long) (rnd.nextDouble(0.5, 2.5) * 1e9));
            s.nextGlance = Math.max(s.nextGlance, now + (long) (rnd.nextDouble(0.5, 2.0) * 1e9));
        }
        if (s.nextBlink == 0) s.nextBlink = now + (long) (rnd.nextDouble(0.5, 3.0) * 1e9);
        if (running && now >= s.nextBlink && s.blinkAt == 0) {
            s.blinkAt = now;
            s.nextBlink = now + (long) (rnd.nextDouble(2.0, 6.0) * 1e9);
        }
        float b = 0f;
        if (s.blinkAt != 0) {
            float a = (now - s.blinkAt) / 1e9f;
            if (a < BLINK_CLOSE) b = a / BLINK_CLOSE;
            else if (a < BLINK_CLOSE + BLINK_HOLD) b = 1f;
            else if (a < BLINK_CLOSE + BLINK_HOLD + BLINK_OPEN) b = 1f - (a - BLINK_CLOSE - BLINK_HOLD) / BLINK_OPEN;
            else s.blinkAt = 0;
        }
        if (running && now >= s.nextGlance) {                        // a glance now and then, back ahead in between
            boolean ahead = Math.abs(s.gYaw) > 0.01f || rnd.nextDouble() < 0.35;
            if (ahead) {
                s.gYaw = 0f;
                s.gPitch = 0f;
                s.nextGlance = now + (long) (rnd.nextDouble(1.5, 5.0) * 1e9);
            } else {
                s.gYaw = (float) Math.toRadians(rnd.nextDouble(-14, 14));
                s.gPitch = (float) Math.toRadians(rnd.nextDouble(-6, 4));
                s.nextGlance = now + (long) (rnd.nextDouble(0.6, 2.0) * 1e9);
            }
        }
        float wantYaw = Math.max(-maxYaw, Math.min(maxYaw, s.gYaw));
        float wantPitch = Math.max(-maxDown, Math.min(maxUp, s.gPitch));
        if (dt > 0f) {
            float k = 1f - (float) Math.exp(-dt / TAU_EYE);
            s.yaw += (wantYaw - s.yaw) * k;
            s.pitch += (wantPitch - s.pitch) * k;
        }
        float follow = maxDown > 0f ? LID_FOLLOW * Math.max(0f, -s.pitch) / maxDown : 0f;
        float[] r = s.rows;
        r[0] = yawSign * s.yaw;
        r[1] = s.pitch;
        r[2] = Math.min(1f, Math.max(b, follow));
        r[3] = MAGIC;
        for (int i = 4; i < 16; i++) r[i] = 0f;
    }

    // ---------------- what the eyes do ----------------

    /** the game paused / sped up, checked 20 times a second: GameTime.isGamePaused (single player's pause button, a
     *  multiplayer server's pause) and the speed buttons (2 and up = faster; always 1 in multiplayer) */
    private static void gameState(long now) {
        if (now - gameStateAt < 50_000_000L) return;
        gameStateAt = now;
        boolean p = false, f = false;
        try {
            if (gamePaused != null) p = (Boolean) gamePaused.invoke(null);
            Object sc = speedControls != null ? speedControls.invoke(null) : null;
            if (sc != null) {
                int v = ((Number) gameSpeed.invoke(sc)).intValue();
                if (v == 0) p = true;
                f = v >= 2;
            }
        } catch (Throwable t) {
            if (warns++ < 5) RBSHook.warn("eyes: game speed unknown: " + t);
        }
        pausedNow = p;
        fastNow = f && !p;
    }

    private static void update(Object player, Object chr, St s, long now) throws Exception {
        gameState(now);
        boolean paused = pausedNow, fast = fastNow;
        // paused: the eyes hold where they are (a blink under way still opens: it runs on its own clock)
        float dt = s.t == 0 || paused ? 0f : Math.min(0.1f, (now - s.t) / 1e9f);
        s.t = now;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        if (paused || fast) {                       // no blink starts now, nor right after (else one on every unpause)
            s.nextBlink = Math.max(s.nextBlink, now + (long) (rnd.nextDouble(0.5, 2.5) * 1e9));
        }
        if (paused) s.nextGlance = Math.max(s.nextGlance, now + (long) (rnd.nextDouble(0.5, 2.0) * 1e9));
        boolean shut = (Boolean) dead.invoke(chr) || (Boolean) asleep.invoke(chr);
        float fatigue = 0f, panic = 0f;
        int close = 0;
        Object st = chrStats.invoke(chr);
        if (st != null) {
            fatigue = ((Number) statsGet.invoke(st, FATIGUE)).floatValue();
            panic = ((Number) statsGet.invoke(st, PANIC)).floatValue();
            if (veryClose != null) close = ((Number) veryClose.invoke(st)).intValue();
        }
        // frightened: the panic jumps, or a zombie comes right up
        if ((s.lastPanic >= 0f && panic - s.lastPanic > 8f) || close > s.lastVeryClose) {
            s.startleUntil = now + 1_500_000_000L;
            s.blinkAt = 0;
        }
        s.lastPanic = panic;
        s.lastVeryClose = close;
        boolean startled = now < s.startleUntil;
        float tired = smooth(0.55f, 1.0f, fatigue);
        // blinking
        if (s.nextBlink == 0) s.nextBlink = now + (long) (rnd.nextDouble(0.5, 4.0) * 1e9);
        if (!startled && !shut && !paused && !fast && now >= s.nextBlink && s.blinkAt == 0) {
            s.blinkAt = now;
            double gap = tired > 0.3f ? rnd.nextDouble(1.0, 3.5) : rnd.nextDouble(2.0, 6.0);
            s.nextBlink = now + (long) (gap * 1e9);
        }
        float b = 0f;
        if (s.blinkAt != 0) {
            float slow = 1f + 0.6f * tired;
            float a = (now - s.blinkAt) / 1e9f / slow;
            if (a < BLINK_CLOSE) b = a / BLINK_CLOSE;
            else if (a < BLINK_CLOSE + BLINK_HOLD) b = 1f;
            else if (a < BLINK_CLOSE + BLINK_HOLD + BLINK_OPEN) b = 1f - (a - BLINK_CLOSE - BLINK_HOLD) / BLINK_OPEN;
            else s.blinkAt = 0;
        }
        float droop = startled ? 0f : 0.45f * tired;
        // where to look: HeadLook's target (the eyes take what the head has not turned yet)
        float[] look = HeadLook.lookOf(player);
        float wantYaw, wantPitch;
        if (look != null && look[4] > 0.5f) {
            wantYaw = look[2] - look[0];
            wantPitch = look[3] - look[1];
            s.nextGlance = now + (long) (rnd.nextDouble(0.8, 2.5) * 1e9);
            s.gYaw = s.gPitch = 0f;
        } else if ((look != null && look[5] > 0.5f) || (Boolean) aiming.invoke(chr)) {
            wantYaw = wantPitch = 0f;                                   // aiming: along the aim
            s.gYaw = s.gPitch = 0f;
        } else {
            if (now >= s.nextGlance) {                                  // a glance now and then, back ahead in between
                boolean ahead = Math.abs(s.gYaw) > 0.01f || rnd.nextDouble() < 0.35;
                if (ahead) {
                    s.gYaw = 0f;
                    s.gPitch = 0f;
                    s.nextGlance = now + (long) (rnd.nextDouble(1.5, 5.0) * 1e9);
                } else {
                    s.gYaw = (float) Math.toRadians(rnd.nextDouble(-14, 14));
                    s.gPitch = (float) Math.toRadians(rnd.nextDouble(-6, 4));
                    s.nextGlance = now + (long) (rnd.nextDouble(0.6, 2.0) * 1e9);
                }
            }
            wantYaw = s.gYaw;
            wantPitch = s.gPitch;
        }
        if (shut) wantYaw = wantPitch = 0f;
        wantYaw = Math.max(-maxYaw, Math.min(maxYaw, wantYaw));
        wantPitch = Math.max(-maxDown, Math.min(maxUp, wantPitch));
        if (dt > 0f) {
            float k = 1f - (float) Math.exp(-dt / TAU_EYE);
            s.yaw += (wantYaw - s.yaw) * k;
            s.pitch += (wantPitch - s.pitch) * k;
        }
        float follow = maxDown > 0f ? LID_FOLLOW * Math.max(0f, -s.pitch) / maxDown : 0f;
        float lids = shut ? 1f : Math.min(1f, Math.max(Math.max(b, droop), follow));
        float[] r = s.rows;
        r[0] = yawSign * s.yaw;
        r[1] = s.pitch;
        r[2] = lids;
        r[3] = MAGIC;
        for (int i = 4; i < 16; i++) r[i] = 0f;
        if (logs < 2 && lids > 0.9f) {
            logs++;
            RBSHook.log(String.format(Locale.ROOT, "eyes: lids shut (%s), gaze %.0f / %.0f deg", shut ? "asleep or dead" : "a blink",
                    Math.toDegrees(s.yaw), Math.toDegrees(s.pitch)));
        }
    }

    private static float smooth(float a, float b, float x) {
        float t = Math.max(0f, Math.min(1f, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

    // ---------------- setup ----------------

    private static synchronized void init() {
        if (ready || failed || absent) return;
        try {
            if (Jiggle.identity == null) return;                        // the palette is not set up yet
            ClassLoader cl = Eyes.class.getClassLoader();
            Class<?> zfs = Class.forName("zombie.ZomboidFileSystem", false, cl);
            Object fs = zfs.getField("instance").get(null);
            String file = file();
            Object at = zfs.getMethod("getString", String.class).invoke(fs, file);
            File f = at instanceof String ? new File((String) at) : null;
            if (f == null || !f.isFile()) {
                absent = true;
                RBSHook.log("eyes: no head with eyeballs (" + file + ") - nothing to do");
                return;
            }
            float[] h = new float[16];
            float gy = 20, gu = 8, gd = 10;
            for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                String[] p = line.trim().split("\\s+");
                if (p.length < 2) continue;
                float[] v = new float[p.length - 1];
                for (int i = 1; i < p.length; i++) v[i - 1] = Float.parseFloat(p[i]);
                switch (p[0]) {
                    case "centre": h[0] = v[0]; h[1] = v[1]; h[2] = v[2]; break;
                    case "unit": h[3] = v[0]; break;
                    case "lids": h[4] = v[0]; h[5] = v[1]; h[6] = v[2]; h[7] = v[3]; h[8] = v[4]; break;
                    case "square": h[9] = v[0]; h[10] = v[1]; h[11] = v[2]; break;
                    case "lidskin": h[12] = v[0]; h[13] = v[1]; break;
                    case "radius": h[14] = v[0]; break;
                    case "yaw_sign": yawSign = v[0] < 0 ? -1f : 1f; break;
                    case "gaze_max": gy = v[0]; gu = v[1]; gd = v.length > 2 ? v[2] : v[1]; break;
                    default: break;
                }
            }
            h[15] = MAGIC_HEAD;
            if (h[3] <= 0f || h[5] <= 0f || h[11] <= 0f || h[14] <= 0f) {
                absent = true;
                RBSHook.warn("eyes: " + f + " is missing values - nothing to do");
                return;
            }
            maxYaw = (float) Math.toRadians(gy);
            maxUp = (float) Math.toRadians(gu);
            maxDown = (float) Math.toRadians(gd);
            Class<?> igc = Class.forName("zombie.characters.IsoGameCharacter", false, cl);
            Class<?> stats = Class.forName("zombie.characters.Stats", false, cl);
            Class<?> cs = Class.forName("zombie.characters.CharacterStat", false, cl);
            chrStats = igc.getMethod("getStats");
            statsGet = stats.getMethod("get", cs);
            FATIGUE = cs.getField("FATIGUE").get(null);
            PANIC = cs.getField("PANIC").get(null);
            try {
                veryClose = stats.getMethod("getNumVeryCloseZombies");
            } catch (NoSuchMethodException e) {
                veryClose = null;
            }
            asleep = igc.getMethod("isAsleep");
            dead = igc.getMethod("isDead");
            aiming = igc.getMethod("isAiming");
            try {
                gamePaused = Class.forName("zombie.GameTime", false, cl).getMethod("isGamePaused");
                speedControls = Class.forName("zombie.ui.UIManager", false, cl).getMethod("getSpeedControls");
                gameSpeed = speedControls.getReturnType().getMethod("getCurrentGameSpeed");
            } catch (Throwable t) {
                gamePaused = speedControls = gameSpeed = null;
                RBSHook.warn("eyes: no game speed (" + t + ") - they blink as if the game always ran at normal speed");
            }
            head = h;
            ready = true;
            RBSHook.log(String.format(Locale.ROOT, "eyes ready (%s): they turn up to %.0f deg aside, %.0f up, %.0f down, and blink",
                    f, gy, gu, gd));
        } catch (Throwable t) {
            failed = true;
            RBSHook.warn("eyes off: " + t);
        }
    }
}
