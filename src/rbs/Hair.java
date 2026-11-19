package rbs;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Our hair (the sbj_ hair mod, model files named "sbj_hair..."): the loose pieces - a ponytail's clumps, strands by
 * the face - sway as the character moves, turns, turns the head, stops or bounces along.
 *
 * The skeleton has no hair bones, so the pieces are moved in the vertex shader the hair mod ships (sbj_HairSway): the
 * hair models get it through the same hook as the clothes (Jiggle.clothingShader), and every human mesh's palette gets
 * this character's two hair springs in slot 57 (Jiggle.palette; a human skeleton has 44 bones, so 57 is free).
 * The shader reads from each vertex's UV which piece it is and how far from the root (see sbj_HairSway.vert).
 *
 * The springs are worked out after each animation update: the point where the hair is tied (behind and above the
 * head joint) is followed in the world - the walk over the ground, the turns, the head turning (head look) and the
 * bob of the animation all move it - and the hair lags behind its changes of speed, overshoots and settles (two
 * springs, a slow one for the tips and a quicker one nearer the root, mixed differently per clump). Moving along, the
 * air keeps it a little behind. The lag is handed over in the head's bind pose, so a turned head swings it the right way.
 * Gravity (v6.8): the shader itself bends the tail towards down in whatever pose the animation gives (bending over,
 * lying, crawling), keeps it out of the head, the neck / upper back and the floor; this class hands it the tie and the
 * skull. Hits and landing on the floor jolt the springs.
 * Players and the zombies whose clothes get our shader (Clean.isZombieWearer), when their hair style is one of ours.
 * Config (RealBodyShape_jiggle.txt): hair = percent (0 off), hair_gravity = percent (0: no gravity), hair_vflip = 1 if
 * the root and the tip come out swapped.
 */
final class Hair {
    static final String SHADER = "sbj_HairSway";
    /** in the file name of every hair model of ours (skinned/hair/sbj_hair_...) */
    static final String MARK = "sbj_hair";
    static final int SLOT = 57;
    /** row 0 w of the slot: the shader trusts it only with this in it */
    static final float MAGIC = 57f;
    /** 0 not looked for yet, 1 compiled, 2 missing (no hair mod) or failed */
    static volatile int shaderState;

    // springs (metres): A the tips, B nearer the root - what looked right in the offline preview (sway_sim.py)
    static final float FREQ_A = 1.25f, DAMP_A = 0.22f, FREQ_B = 1.9f, DAMP_B = 0.28f;
    /** share of the tie's change of speed the hair keeps; the air's pull per m/s while moving */
    static final float GAIN = 0.45f, DRAG = 0.8f;
    /** the tie's movement that comes from the animation itself (a weapon swung, a lunge, a twist): this much more, so a
     *  swing throws the hair (user, 2026-11-10: the hair should act when swinging a weapon; hair_dyn.py) */
    static final float ANIM_EXTRA = 0.8f;
    /** running: 'down' tilts back by this much per m/s over WIND_FROM (up to WIND_MAX), so the tail's tip lifts back
     *  (user, 2026-11-10); the shader bends the hair to it like gravity */
    static final float WIND = 0.3f, WIND_FROM = 0.8f, WIND_MAX = 1.0f;
    /** the swing eases into this (m, tanh): a ponytail 16 cm, styles cut in strips 11 (wider and gaps open between
     *  them); up / down only half of it (hair hangs: it does not bounce like flesh) */
    static final float MAX_TIED = 0.16f, MAX_STRIPS = 0.11f, UP_SHARE = 0.5f;
    static final float SPEED_SMOOTH = 0.05f, MAX_KICK = 1.5f;
    /** the game's skeleton (mesh units, bind pose): where a ponytail is tied and the skull's centre, from the head
     *  joint; how far a piece may swing in towards the head / neck */
    static final float TIE_UP = 0.080f, TIE_BACK = 0.067f, SKULL_UP = 0.077f, SKULL_FWD = 0.013f, SKULL_R = 0.074f;
    /** the same for men (2026-11-16, men's styles): the same skeleton, but the male head is bigger and sits higher and
     *  further back (skull centre 0.9083 / z 0.025 against 0.9015 / 0.0177, the vanilla ponytail's root 1 cm higher);
     *  the shader is told by the slot's marker (MAGIC + 0.5) and keeps the hair behind the male neck / upper back */
    static final float TIE_UP_M = 0.093f, TIE_BACK_M = 0.072f, SKULL_UP_M = 0.084f, SKULL_FWD_M = 0.004f, SKULL_R_M = 0.078f;
    /** how much of the way the tail follows gravity at 100 % (gravity_sim.py) */
    static final float GRAVITY = 0.85f;
    /** a hit (the reaction starts) and landing on the floor: the change of speed given to the springs (m/s) */
    static final float HIT_KICK = 0.9f, LAND_KICK = 1.1f;
    /** torso (pelvis -> neck) in metres, as Jiggle */
    static final float TORSO_M = 0.55f;

    private static final float[] E0 = { 1, 0, 0 }, E1 = { 0, 1, 0 }, E2 = { 0, 0, 1 };

    static final class State {
        final float[] rows = new float[16];
        final float[] a = new float[6], b = new float[6];   // lag forward, left, up (m) and their rates
        boolean on, started;
        long t;
        double wx, wy;              // the tie in the world (m), up from the model
        float wz, vx, vy, vz, dtS;
        float mF, mL, mU, mvF, mvL, mvU;   // the tie in the character's own frame (the animation's movement) and its speed
        float wind;                 // the air's tilt of 'down' now
        boolean hit, floor;         // last time: in a hit reaction, on the floor
        boolean tied;               // a ponytail: its hair bends about the tie, other styles about the skull's centre
        boolean male;               // a man's head: his tie, skull and neck (the shader's marker)
    }

    /** the animation skeleton's head, offset and bind-pose frame */
    static final class Frame {
        int head;
        Object offset;
        float L;
        float[] fw, up, left, tie, skull, tieM, skullM;

        float[] tie(State s) { return s.male ? tieM : tie; }
        float[] skull(State s) { return s.male ? skullM : skull; }
    }

    private static final Map<Object, State> states = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, Frame> frames = Collections.synchronizedMap(new WeakHashMap<>());
    /** character -> {1 ours / 0 not, expiry} */
    private static final Map<Object, long[]> wears = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Class<?>, Method> visualGetter = Collections.synchronizedMap(new HashMap<>());
    private static final Map<Class<?>, Method> femaleGetter = Collections.synchronizedMap(new HashMap<>());

    // ---------------- UI previews (user, 2026-11-19: the hair should sway when the character is turned on the
    // character creation screen, a new game or after a death) ----------------
    // The preview is an AnimatedModel with no character (PlayerFit.previewModel); it never walks, and its turns are
    // jumps of 45 degrees (ISUI3DModel.setDirection: the buttons, or a drag of 40 pixels). Its facing is the model's
    // angle; the tie is followed as if it were PREVIEW_LEVER further behind the turning axis, so a turn swings the hair
    // of every style (the skull's centre, the pivot of loose styles, is almost on that axis). The corpse atlas and
    // mannequins also draw through AnimatedModels, but those never animate (setAnimate(false)): left alone.
    static final float PREVIEW_LEVER = 0.10f;
    private static java.lang.reflect.Field amAngle;
    private static Method amAnimate, amZombie, amIsometric;

    /**
     * the AnimatedModel of a UI preview this AnimationPlayer animates, or null: not a zombie, and animating or drawn
     * flat (not isometric). The corpse atlas and mannequins never animate and keep the default isometric view; the
     * character creation preview is flat (CharacterCreationAvatar: setIsometric(false)) and animates only while the
     * game is not paused (ISUI3DModel.prerender) - after a death it can be paused.
     */
    private static Object previewOf(Object player) {
        Object am = PlayerFit.previewModel(player);
        if (am == null) return null;
        try {
            if (amAngle == null) {
                Class<?> c = am.getClass();
                amAnimate = c.getMethod("isAnimate");
                amZombie = c.getMethod("isZombie");
                amIsometric = c.getMethod("isIsometric");
                java.lang.reflect.Field fa = c.getDeclaredField("angle");
                fa.setAccessible(true);
                amAngle = fa;
            }
            if ((Boolean) amZombie.invoke(am)) return null;
            if (!(Boolean) amAnimate.invoke(am) && (Boolean) amIsometric.invoke(am)) return null;
            return am;
        } catch (Throwable t) {
            if (warns++ < 5) RBSHook.warn("hair: UI previews stay still: " + t);
            return null;
        }
    }
    private static volatile Method hairModel;
    private static final ThreadLocal<float[]> M = ThreadLocal.withInitial(() -> new float[16]);
    private static final ThreadLocal<float[]> O = ThreadLocal.withInitial(() -> new float[16]);
    private static int warns, logs;
    private static long summaryAt;
    private static float maxSwing;

    private Hair() {}

    // ---------------- shader ----------------

    /** compiled once, with Jiggle's shader (Jiggle.shaderReady puts the model's own shader back afterwards) */
    static void compile(Object model) {
        try {
            String vert = (String) Jiggle.mGetString.invoke(Jiggle.fileSystem, "media/shaders/" + SHADER + ".vert");
            String frag = (String) Jiggle.mGetString.invoke(Jiggle.fileSystem, "media/shaders/" + SHADER + ".frag");
            if (vert == null || frag == null || !new File(vert).isFile() || !new File(frag).isFile()) {
                shaderState = 2;
                RBSHook.log("hair: " + SHADER + " not found (the hair mod is off) - nothing to sway");
                return;
            }
            Jiggle.mCreateShader.invoke(model, SHADER);
            Object s = Jiggle.fEffect.get(model);
            Object prog = s == null ? null : Jiggle.mShaderProgram.invoke(s);
            if (s != null && SHADER.equalsIgnoreCase(String.valueOf(Jiggle.mShaderName.invoke(s)))
                    && prog != null && (Boolean) Jiggle.mIsCompiled.invoke(prog)) {
                shaderState = 1;
                RBSHook.log("hair: our hair models use " + SHADER + " (" + vert + ") - their loose pieces sway");
                return;
            }
            RBSHook.warn("hair: " + SHADER + " did not compile (see the shader errors above) - our hair stays still");
        } catch (Throwable t) {
            RBSHook.warn("hair: " + SHADER + " failed: " + t + " - our hair stays still");
        }
        shaderState = 2;
    }

    /** the shader of a hair / beard model (path lower case, '/'): ours for our hair, the rest as given */
    static Object shaderFor(String mesh, Object shaderName) {
        if (shaderState != 1 || !mesh.contains(MARK)) return shaderName;
        return SHADER;
    }

    // ---------------- palette (Jiggle.palette, every human mesh) ----------------

    static Object slot(Object player, Jiggle.Pad pad) throws Exception {
        State s = states.get(player);
        if (s == null || !s.on) return Jiggle.identity;
        if (pad.slot3 == null) pad.slot3 = Jiggle.matrixCtor.newInstance();
        Jiggle.write(pad.slot3, s.rows);
        return pad.slot3;
    }

    // ---------------- springs (RBSHook.afterUpdateSkinTransforms, after Jiggle) ----------------

    static void update(Object player) {
        if (shaderState != 1 || Jiggle.shaderState != 2 || Jiggle.failed || player == null) return;
        try {
            State s = states.get(player);
            Object chr = Jiggle.apCharacter.invoke(player);
            // no character: a UI preview (character creation, after a death too; the info window) sways as well
            Object preview = chr == null ? previewOf(player) : null;
            boolean on = Jiggle.enabled && Jiggle.hairPct > 0f && (chr != null
                    ? wearsOurs(chr) && ((RBSHook.zombiesEnabled() && Clean.isZombieWearer(chr)) || Jiggle.isPlayer(chr))
                    : preview != null && wearsOurs(preview));
            if (!on) {
                if (s != null) s.on = false;
                return;
            }
            Object data = Jiggle.apSkinning.invoke(player);
            Frame f = data == null ? null : frameFor(data);
            if (f == null || f.head >= (Integer) Jiggle.apCount.invoke(player)) {
                if (s != null) s.on = false;
                return;
            }
            if (s == null) {
                s = new State();
                states.put(player, s);
                if (logs++ < 6) RBSHook.log(String.format(Locale.ROOT, "hair: a %s with our hair - it sways (tie at %.3f, %.3f, %.3f in the bind pose, torso %.3f units%s)",
                        chr == null ? "UI preview (turning it swings the hair)" : Jiggle.isPlayer(chr) ? "player" : "zombie",
                        f.tie[0], f.tie[1], f.tie[2], f.L, Jiggle.hairVFlip ? ", v flipped" : ""));
            }
            long[] we = wears.get(chr != null ? chr : preview);
            s.tied = we != null && we.length > 2 && we[2] == 1;
            s.male = we != null && we.length > 3 && we[3] == 1;
            step(s, f, player, chr, preview);
        } catch (Throwable t) {
            if (warns++ < 5) RBSHook.warn("hair (spring) failed: " + t);
        }
    }

    /** is the character's hair style one of ours (its name starts with sbj_), looked at once a second */
    private static boolean wearsOurs(Object chr) throws Exception {
        long now = System.nanoTime();
        long[] e = wears.get(chr);
        if (e != null && now < e[1]) return e[0] == 1;
        Class<?> c = chr.getClass();
        Method g;
        if (visualGetter.containsKey(c)) g = visualGetter.get(c);
        else {
            try {
                g = c.getMethod("getHumanVisual");
            } catch (NoSuchMethodException x) {
                g = null;
            }
            visualGetter.put(c, g);
        }
        boolean ours = false, tied = false;
        Object v = g == null ? null : g.invoke(chr);
        if (v != null) {
            if (hairModel == null) hairModel = v.getClass().getMethod("getHairModel");
            Object h = hairModel.invoke(v);
            String hs = h == null ? "" : h.toString().toLowerCase(Locale.ROOT);
            ours = hs.startsWith("sbj_");
            tied = hs.contains("ponytail") || hs.contains("bun");
        }
        boolean male = false;
        try {
            Method fm = femaleGetter.get(c);                    // a character's isFemale, or an AnimatedModel's
            if (fm == null) {
                fm = c.getMethod("isFemale");
                femaleGetter.put(c, fm);
            }
            male = !(Boolean) fm.invoke(chr);
        } catch (Throwable t) {
            male = false;
        }
        wears.put(chr, new long[]{ ours ? 1 : 0, now + 1_000_000_000L, tied ? 1 : 0, male ? 1 : 0 });
        return ours;
    }

    @SuppressWarnings("unchecked")
    private static Frame frameFor(Object data) throws Exception {
        if (frames.containsKey(data)) return frames.get(data);
        Frame f = null;
        float[] fr = Jiggle.frameOf(data);
        if (fr != null) {
            int head = -1, n = (Integer) Jiggle.sdNumBones.invoke(data);
            for (int i = 0; i < n && head < 0; i++) {
                Object b = Jiggle.sdBoneAt.invoke(data, i);
                Object nm = b == null ? null : Jiggle.fBoneName.get(b);
                if (nm != null && nm.toString().toLowerCase(Locale.ROOT).endsWith("_head")) head = i;
            }
            List<Object> offsets = (List<Object>) Jiggle.fBoneOffset.get(data);
            float[] H = head < 0 || offsets == null ? null : Jiggle.bindPos(offsets, head);
            if (H != null) {
                f = new Frame();
                f.head = head;
                f.offset = offsets.get(head);
                f.L = fr[3];
                f.fw = new float[]{ fr[4], fr[5], fr[6] };
                f.up = new float[]{ fr[7], fr[8], fr[9] };
                f.left = cross(f.fw, f.up);                     // forward = up x left
                f.tie = new float[3];
                f.skull = new float[3];
                f.tieM = new float[3];
                f.skullM = new float[3];
                for (int c = 0; c < 3; c++) {
                    f.tie[c] = H[c] + f.up[c] * TIE_UP - f.fw[c] * TIE_BACK;
                    f.skull[c] = H[c] + f.up[c] * SKULL_UP + f.fw[c] * SKULL_FWD;
                    f.tieM[c] = H[c] + f.up[c] * TIE_UP_M - f.fw[c] * TIE_BACK_M;
                    f.skullM[c] = H[c] + f.up[c] * SKULL_UP_M + f.fw[c] * SKULL_FWD_M;
                }
            }
        }
        frames.put(data, f);
        if (f == null) RBSHook.log("hair: a skeleton without a usable head - its hair stays still");
        return f;
    }

    private static void step(State s, Frame f, Object player, Object chr, Object preview) throws Exception {
        float[] m = M.get(), o = O.get();
        Object hm = Jiggle.apModelAt.invoke(player, f.head);
        if (hm == null) return;
        Jiggle.read(hm, m);
        Jiggle.read(f.offset, o);
        boolean cm = cv(m), co = cv(o);
        // the tie in model space (the head's pose now: head look and animation included), then in the world
        float[] tm = xf(m, cm, xf(o, co, f.tie(s), 1f), 1f);
        float k = TORSO_M / f.L;
        float lf = dot(tm, f.fw) * k, ll = dot(tm, f.left) * k, lu = dot(tm, f.up) * k;
        // the facing and the place: the character's, or a UI preview's angle on the spot (with the lever: see above)
        Object fwd = chr != null ? Jiggle.chrForward.invoke(chr) : amAngle.get(preview);
        float fx = fwd == null ? 1f : ((Number) Jiggle.v2X.invoke(fwd)).floatValue();
        float fy = fwd == null ? 0f : ((Number) Jiggle.v2Y.invoke(fwd)).floatValue();
        float fl = (float) Math.sqrt(fx * fx + fy * fy);
        if (fl < 1e-4f) return;
        fx /= fl;
        fy /= fl;
        double px = chr != null ? ((Number) Jiggle.chrX.invoke(chr)).doubleValue() : 0.0;
        double py = chr != null ? ((Number) Jiggle.chrY.invoke(chr)).doubleValue() : 0.0;
        float lw = chr != null ? lf : lf - PREVIEW_LEVER;
        // world left of the facing is (fy, -fx) (HeadLook.YAW_SIGN, checked in game), the model's left too
        double wx = px + fx * lw + fy * ll;
        double wy = py + fy * lw - fx * ll;
        float wz = lu;
        long now = System.nanoTime();
        float dt = s.started ? (now - s.t) / 1e9f : 0f;
        if (!s.started || dt <= 0f || dt > 0.25f || Math.abs(wx - s.wx) + Math.abs(wy - s.wy) > 2.0) {
            s.started = true;
            s.t = now;
            s.wx = wx;
            s.wy = wy;
            s.wz = wz;
            s.vx = s.vy = s.vz = 0f;
            s.mF = lf;
            s.mL = ll;
            s.mU = lu;
            s.mvF = s.mvL = s.mvU = 0f;
            s.wind = 0f;
            s.dtS = 0f;
            java.util.Arrays.fill(s.a, 0f);
            java.util.Arrays.fill(s.b, 0f);
            // already reeling / lying when first seen: no jolt (a preview is never hit)
            s.hit = chr != null && (Boolean) Jiggle.chrHasHit.invoke(chr);
            s.floor = chr != null && (Boolean) Jiggle.chrOnFloor.invoke(chr);
            s.on = true;
            publish(s, f, m, cm, o, co);
            return;
        }
        if (dt < 0.004f) return;                                // several updates in one frame
        s.dtS = s.dtS <= 0f ? dt : s.dtS + (dt - s.dtS) * 0.1f; // the hook's timing jitters: smooth it (as Jiggle)
        dt = Math.max(0.005f, Math.min(0.1f, s.dtS));
        float rx = (float) ((wx - s.wx) / dt), ry = (float) ((wy - s.wy) / dt), rz = (wz - s.wz) / dt;
        float q = 1f - (float) Math.exp(-dt / SPEED_SMOOTH);
        float vx = s.vx + (rx - s.vx) * q, vy = s.vy + (ry - s.vy) * q, vz = s.vz + (rz - s.vz) * q;
        float dx = clamp(vx - s.vx, MAX_KICK), dy = clamp(vy - s.vy, MAX_KICK), dz = clamp(vz - s.vz, MAX_KICK);
        // the change of speed in the character's frame now: the hair keeps its old speed (that is the lag)
        float dF = dx * fx + dy * fy, dL = dx * fy - dy * fx;
        float vF = vx * fx + vy * fy, vL = vx * fy - vy * fx;
        // the animation's own movement of the tie, in the character's frame: a swing, a lunge, a twist
        float mvF = s.mvF + ((lf - s.mF) / dt - s.mvF) * q, mvL = s.mvL + ((ll - s.mL) / dt - s.mvL) * q, mvU = s.mvU + ((lu - s.mU) / dt - s.mvU) * q;
        float aF = clamp(mvF - s.mvF, MAX_KICK), aL = clamp(mvL - s.mvL, MAX_KICK), aU = clamp(mvU - s.mvU, MAX_KICK);
        for (float[] p : new float[][]{ s.a, s.b }) {
            p[3] -= GAIN * dF + ANIM_EXTRA * aF;
            p[4] -= GAIN * dL + ANIM_EXTRA * aL;
            p[5] -= GAIN * dz + ANIM_EXTRA * aU;
        }
        s.wind = Math.min(WIND_MAX, Math.max(0f, WIND * (vF - WIND_FROM)));
        if (chr != null) kicks(s, chr);
        float lim = s.tied ? MAX_TIED : MAX_STRIPS;
        integrate(s.a, FREQ_A, DAMP_A, dt, -DRAG * GAIN * vF, -DRAG * GAIN * vL, lim);
        integrate(s.b, FREQ_B, DAMP_B, dt, -DRAG * GAIN * vF, -DRAG * GAIN * vL, lim);
        s.t = now;
        s.wx = wx;
        s.wy = wy;
        s.wz = wz;
        s.vx = vx;
        s.vy = vy;
        s.vz = vz;
        s.mF = lf;
        s.mL = ll;
        s.mU = lu;
        s.mvF = mvF;
        s.mvL = mvL;
        s.mvU = mvU;
        s.on = true;
        publish(s, f, m, cm, o, co);
    }

    /** a hit throws the hair the way the blow came from (from the front the head is pushed back, the hair keeps
     *  going: forward relative to it); landing on the floor throws it down and forward */
    private static void kicks(State s, Object chr) throws Exception {
        boolean hit = (Boolean) Jiggle.chrHasHit.invoke(chr), floor = (Boolean) Jiggle.chrOnFloor.invoke(chr);
        if (hit && !s.hit) {
            float k = HIT_KICK * ((Boolean) Jiggle.chrHitBehind.invoke(chr) ? -1f : 1f);
            s.a[3] += k;
            s.b[3] += 0.8f * k;
            s.a[5] += 0.3f * Math.abs(k);
        }
        if (floor && !s.floor) {
            s.a[5] -= LAND_KICK;
            s.b[5] -= 0.8f * LAND_KICK;
            s.a[3] += 0.4f * LAND_KICK;
        }
        s.hit = hit;
        s.floor = floor;
    }

    /** a damped spring in 3 (forward, left, up), the air's pull on the first two, in small steps */
    private static void integrate(float[] p, float freq, float damp, float dt, float pullF, float pullL, float max) {
        float w0 = (float) (2 * Math.PI * freq);
        int n = Math.max(1, (int) Math.ceil(dt / (1f / 240f)));
        float h = dt / n;
        for (int i = 0; i < n; i++) {
            p[3] += (-w0 * w0 * p[0] - 2 * damp * w0 * p[3] + pullF) * h;
            p[4] += (-w0 * w0 * p[1] - 2 * damp * w0 * p[4] + pullL) * h;
            p[5] += (-w0 * w0 * p[2] - 2 * damp * w0 * p[5]) * h;
            p[0] += p[3] * h;
            p[1] += p[4] * h;
            p[2] += p[5] * h;
        }
        for (int i = 0; i < 3; i++) p[i] = clamp(p[i], 2 * max);
    }

    /** row 0 spring A, row 1 spring B + the air's tilt (bind pose of the head, mesh units), row 2 the skull's centre +
     *  radius, row 3 the pivot + how much of the way the hair follows gravity */
    private static void publish(State s, Frame f, float[] m, boolean cm, float[] o, boolean co) {
        float[] r = s.rows;
        float mesh = f.L / TORSO_M * Jiggle.hairPct;
        // the head's turn (bind -> model) per axis: the lag found in the character's frame goes into the head's bind pose
        float[] r0 = xf(m, cm, xf(o, co, E0, 0f), 0f), r1 = xf(m, cm, xf(o, co, E1, 0f), 0f), r2 = xf(m, cm, xf(o, co, E2, 0f), 0f);
        float swing = 0f, max = s.tied ? MAX_TIED : MAX_STRIPS;
        for (int sp = 0; sp < 2; sp++) {
            float[] p = sp == 0 ? s.a : s.b;
            float lf = soft(p[0], max), ll = soft(p[1], max), lu = soft(p[2], max) * UP_SHARE;
            swing = Math.max(swing, Math.max(Math.abs(lf), Math.max(Math.abs(ll), Math.abs(lu))));
            float[] d = new float[3];
            for (int c = 0; c < 3; c++) d[c] = (f.fw[c] * lf + f.left[c] * ll + f.up[c] * lu) * mesh;
            int base = sp * 4;
            r[base] = dot(r0, d) / Math.max(dot(r0, r0), 1e-8f);
            r[base + 1] = dot(r1, d) / Math.max(dot(r1, r1), 1e-8f);
            r[base + 2] = dot(r2, d) / Math.max(dot(r2, r2), 1e-8f);
        }
        r[3] = s.male ? MAGIC + 0.5f : MAGIC;              // the shader: 57 a woman's head and neck, 57.5 a man's
        r[7] = s.wind;                                     // the air: 'down' tilts back this much
        float[] skull = f.skull(s);
        r[8] = skull[0];
        r[9] = skull[1];
        r[10] = skull[2];
        r[11] = s.male ? SKULL_R_M : SKULL_R;
        float[] pivot = s.tied ? f.tie(s) : skull;         // what the hanging pieces bend about with gravity
        r[12] = pivot[0];
        r[13] = pivot[1];
        r[14] = pivot[2];
        r[15] = Math.min(1f, GRAVITY * Jiggle.hairGravity);
        if (swing > maxSwing) maxSwing = swing;
        long now = System.nanoTime();
        if (now >= summaryAt) {
            summaryAt = now + 60_000_000_000L;
            RBSHook.log(String.format(Locale.ROOT, "hair: %d character(s) with our hair tracked, largest swing so far %.0f cm", states.size(), maxSwing * 100));
        }
    }

    // ---------------- small maths ----------------

    /** translation at 12..14 (column vectors) or at 3, 7, 11 (row vectors, the game's), as Jiggle tells them apart */
    private static boolean cv(float[] m) {
        return Math.abs(m[3]) + Math.abs(m[7]) + Math.abs(m[11]) <= Math.abs(m[12]) + Math.abs(m[13]) + Math.abs(m[14]);
    }

    /** a point (w 1) or a direction (w 0) through the matrix */
    private static float[] xf(float[] m, boolean cv, float[] p, float w) {
        if (cv) return new float[]{ m[0] * p[0] + m[4] * p[1] + m[8] * p[2] + m[12] * w,
                m[1] * p[0] + m[5] * p[1] + m[9] * p[2] + m[13] * w,
                m[2] * p[0] + m[6] * p[1] + m[10] * p[2] + m[14] * w };
        return new float[]{ m[0] * p[0] + m[1] * p[1] + m[2] * p[2] + m[3] * w,
                m[4] * p[0] + m[5] * p[1] + m[6] * p[2] + m[7] * w,
                m[8] * p[0] + m[9] * p[1] + m[10] * p[2] + m[11] * w };
    }

    private static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static float[] cross(float[] a, float[] b) {
        return new float[]{ a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0] };
    }

    private static float soft(float v, float lim) {
        return lim * (float) Math.tanh(v / lim);
    }

    private static float clamp(float v, float lim) {
        return v > lim ? lim : (v < -lim ? -lim : v);
    }
}
