package rbs;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Where a character is, for the springs (Jiggle: the soft body; Hair): its position and facing, followed smoothly
 * between the game's changes of them.
 *
 * Project Viewpoint's first / third person views run the game's logic at a fixed 30 ticks a second and update the poses
 * in every frame they draw between (its "fixed tick"). The position the springs take their speed from then moved in
 * steps - a whole tick's way in one update, none in the next - so the speed read every update jumped between more than
 * twice the real one and nothing, and the springs were kicked back and forth (user, 2026-11-21: "มันกระตุกมากๆ", the hair
 * in its views).
 *
 * Seen like that (changes with updates without one between, several ticks running), the place follows the ticks' line a
 * tick and an update behind: each tick's time is put on the steady beat of the ticks (between the update that saw it and
 * the one before), and the place is that line at that delay - a steady speed, it starts and stops with the character.
 * Otherwise (the game's own views: a new position every update) it is the position as the game has it, as before.
 */
final class Place {
    private Place() {}

    /** changes further apart than this are not ticks of a moving character (it stood still): not timed */
    static final float MAX_PERIOD = 0.12f;
    /** a jump this far (squares) is a teleport, not a move: no glide */
    static final double JUMP = 2.0;
    /** the share of ticks with updates between them: the line is followed above ON, left below OFF (at about 33 fps
     *  and less a tick now and then has one - not followed: the raw position jumps a little only then) */
    static final float GAPS_ON = 0.25f, GAPS_OFF = 0.03f, GAPS_RATE = 0.2f;
    static final int RING = 6;
    /** a switch between the raw position and the ticks' line is eased over about this (s) */
    static final float EASE = 0.15f;
    /** the delay behind the ticks follows their timing over about this (s) */
    static final float DELAY_EASE = 0.5f;
    /** updates closer than this are one (the soft body and the hair both ask; the springs skip such repeats too) */
    static final long ONE_UPDATE = 4_000_000L;

    static final class Glide {
        final long[] t = new long[RING];            // the ticks: their times (ns, on the beat) ...
        final double[] x = new double[RING], y = new double[RING];
        final float[] a = new float[RING];          // ... and facing angle (unwrapped)
        int n, head;                                // ticks kept, the newest's index
        double rx, ry;                              // the last raw values
        float ra;
        float period, update;                       // between ticks, between updates (s, smoothed; 0 unknown)
        long lastCall, prevUpdate;                  // this update's call, the update before's
        int same;                                   // updates without a change since the last change
        float gaps;                                 // the share of ticks with updates between (smoothed)
        boolean started, glide, outSet;             // following the ticks' line now; ox.. hold a place
        double ox, oy, offX, offY;                  // the place handed out last, the gap eased away since offT
        float oa, offA;
        long offT;
        float delay;                                // how far behind the ticks' line is followed (s)
        long delayAt;

        void push(long time, double px, double py, float ang) {
            head = (head + 1) % RING;
            t[head] = time;
            x[head] = px;
            y[head] = py;
            a[head] = ang;
            if (n < RING) n++;
        }
    }

    private static final Map<Object, Glide> glides = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<double[]> OUT = ThreadLocal.withInitial(() -> new double[4]);
    private static volatile boolean stepsSaid;

    /** {x, y, forward x, forward y} of the character now, from its position and forward direction as the game has them */
    static double[] at(Object chr, double x, double y, float fx, float fy, long now) {
        double[] out = OUT.get();
        float a = (float) Math.atan2(fy, fx);
        Glide g;
        synchronized (glides) {
            g = glides.get(chr);
            if (g == null) {
                g = new Glide();
                glides.put(chr, g);
            }
        }
        synchronized (g) {
            // a new update
            if (now - g.lastCall > ONE_UPDATE) {
                if (g.lastCall > 0) {
                    g.prevUpdate = g.lastCall;
                    float u = (now - g.lastCall) / 1e9f;
                    if (u < MAX_PERIOD) g.update = g.update <= 0f ? u : g.update + (u - g.update) * 0.2f;
                }
                g.lastCall = now;
            }
            boolean reset = !g.started || Math.abs(x - g.rx) + Math.abs(y - g.ry) > JUMP;
            if (reset) {
                g.started = true;
                g.n = 0;
                g.push(now, x, y, a);
                g.rx = x;
                g.ry = y;
                g.ra = a;
                g.same = 0;
            } else if (x != g.rx || y != g.ry || a != g.ra) {
                long last = g.t[g.head];
                boolean between = g.same > 0;
                // on the beat: where the next tick was due, kept between the update before (it had not come then) and
                // now - in the game's views (each update its own tick, sooner than the beat) that is now
                long due = g.period > 0f ? last + (long) (g.period * 1e9f) : now;
                long lo = Math.max(g.prevUpdate, last + 1);
                long tick = Math.max(lo, Math.min(now, due));
                float since = (tick - last) / 1e9f;
                if (since < MAX_PERIOD) {
                    // following the ticks, a change without an update between (back in the game's views, or a late
                    // frame) does not set the beat: the delay would shrink and the place run ahead to catch up
                    if (between || !g.glide) g.period = g.period <= 0f ? since : g.period + (since - g.period) * 0.2f;
                    // how many of the ticks come with updates between: all at a high frame rate in Viewpoint's views,
                    // some at a low one, none in the game's views
                    g.gaps += ((between ? 1f : 0f) - g.gaps) * GAPS_RATE;
                } else if (g.period > 0f) {
                    // moving off after standing still: it set off at most a tick before this one
                    g.push(tick - (long) (g.period * 1e9f), g.x[g.head], g.y[g.head], g.a[g.head]);
                }
                float ang = g.a[g.head] + wrap(a - g.a[g.head]);
                g.push(tick, x, y, ang);
                g.rx = x;
                g.ry = y;
                g.ra = a;
                g.same = 0;
                if (g.glide && g.gaps > 0.5f && !stepsSaid) {
                    stepsSaid = true;
                    RBSHook.log(String.format(java.util.Locale.ROOT, "place: a character's position changes in steps every %.0f ms, with pose updates every %.0f ms between"
                            + " (Project Viewpoint's fixed tick) - the soft body and the hair follow it smoothly", g.period * 1000f, g.update * 1000f));
                }
            } else if (now == g.lastCall) {
                g.same++;
            }
            boolean can = g.period > 0f && g.n >= 2, glide = can && (g.glide ? g.gaps > GAPS_OFF : g.gaps > GAPS_ON);
            double lx = x, ly = y;                          // the ticks' line now (the raw position if none)
            float la = a;
            if (can) {
                // the ticks' line, a tick and an update behind (that delay eased as the timing drifts: a change
                // of it is a change of the place's speed)
                float d = g.period + Math.min(g.update, g.period);
                if (g.delay <= 0f || !g.glide) g.delay = d;
                else if (now != g.delayAt) g.delay += (d - g.delay) * (1f - (float) Math.exp(-(now - g.delayAt) / 1e9f / DELAY_EASE));
                g.delayAt = now;
                long when = now - (long) (g.delay * 1e9f);
                int j = g.head, k = -1;
                for (int i = 0; i < g.n; i++) {             // the newest tick at or before 'when'
                    if (g.t[j] <= when) {
                        k = j;
                        break;
                    }
                    j = (j - 1 + RING) % RING;
                }
                if (k < 0) {                                // before every tick kept: the oldest
                    int o = (g.head - g.n + 1 + RING) % RING;
                    lx = g.x[o];
                    ly = g.y[o];
                    la = g.a[o];
                } else if (k == g.head) {                   // past the newest: there (it stopped)
                    lx = g.x[k];
                    ly = g.y[k];
                    la = g.a[k];
                } else {
                    int m = (k + 1) % RING;
                    double f = (double) (when - g.t[k]) / Math.max(1L, g.t[m] - g.t[k]);
                    lx = g.x[k] + (g.x[m] - g.x[k]) * f;
                    ly = g.y[k] + (g.y[m] - g.y[k]) * f;
                    la = (float) (g.a[k] + (g.a[m] - g.a[k]) * f);
                }
            }
            // the gap being eased away (a switch before), as it is now
            double ex = 0, ey = 0;
            float ea = 0f;
            if (g.offT != 0L) {
                float e = Math.max(0f, (now - g.offT) / 1e9f / EASE);
                if (e >= 8f) {
                    g.offT = 0L;
                } else {
                    float h = (1f + e) * (float) Math.exp(-e);
                    ex = g.offX * h;
                    ey = g.offY * h;
                    ea = g.offA * h;
                }
            }
            // into Viewpoint's views and back (user, 2026-11-21: the soft body when back in the isometric view): the
            // line is behind the raw position, a jump between them would kick the springs. From where the way before
            // would be now (so the speed goes on), the gap is eased away (critically damped: it starts slowly)
            if (reset) {
                g.offT = 0L;
                ex = ey = 0;
                ea = 0f;
            } else if (glide != g.glide && g.outSet) {
                double bx = g.glide ? lx : x, by = g.glide ? ly : y;
                float ba = g.glide ? la : a;
                double nx = glide ? lx : x, ny = glide ? ly : y;
                float na = glide ? la : a;
                g.offX = bx + ex - nx;
                g.offY = by + ey - ny;
                g.offA = wrap(ba + ea - na);
                g.offT = now;
                ex = g.offX;
                ey = g.offY;
                ea = g.offA;
            }
            g.glide = glide;
            double px = (glide ? lx : x) + ex, py = (glide ? ly : y) + ey;
            float pa = (glide ? la : a) + ea;
            g.ox = px;
            g.oy = py;
            g.oa = pa;
            g.outSet = true;
            out[0] = px;
            out[1] = py;
            out[2] = Math.cos(pa);
            out[3] = Math.sin(pa);
        }
        return out;
    }

    /** an angle's difference into -pi .. pi */
    private static float wrap(float d) {
        while (d > Math.PI) d -= (float) (2 * Math.PI);
        while (d < -Math.PI) d += (float) (2 * Math.PI);
        return d;
    }
}
