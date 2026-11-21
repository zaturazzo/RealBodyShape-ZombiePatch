package rbs;

/**
 * Project Viewpoint (another mod: first / third person views, ZombieBuddy) draws the characters with a renderer of its
 * own: its model.vert skins the vertices from the palette arrays the game hands out (all 60 slots, also ours) but keeps
 * three rows of each slot, and it never runs our shader - so the soft body (RBSJiggle: belly, bust, buttocks, thighs,
 * upper arms, breathing, what hangs from the back bag bone) did not move there (user, 2026-11-17; a local try
 * 2026-11-21: "ลองทำข้อ 2 ในเครื่องก่อนเลย").
 *
 * While Viewpoint reads its model.vert (viewpoint.platform.Shaders.read, a ZombieBuddy patch: Patches.ViewpointShaderRead),
 * the same push as RBSJiggle.vert's goes in before its skinning, read from its palette buffer: slot 59 rows 0-2 and slot
 * 58 rows 0-2 as the game's shader reads them, and slot 53 (Jiggle.SLOT_VP) with their row 3 (which Viewpoint drops),
 * whether the mesh is gear (RBSJiggleBag's rule: no bust / buttocks / thighs / arms) and a marker. Without the marker
 * (another palette: a pose Viewpoint baked itself, a shadow copy, a shorter skeleton) nothing moves. Not here: the web
 * between the thighs folding away when sitting, the eyeballs (their own shaders' work).
 *
 * Our hair (user, 2026-11-21: "ทรงผม ไม่ขยับคับ"): the same steps as the hair mod's sbj_HairSway.vert (sway, gravity, out
 * of the skull / neck / floor) on the vertices of our hair models - Hair.SLOT_VP marks those and carries the row of slot
 * 57 that Viewpoint drops. Viewpoint copies the game's vertex buffers as they are (floats), so the normal's length that
 * tells the pieces apart arrives unchanged.
 *
 * Only the text is changed, only when both anchor lines are found (else as it is, one line in the log); nothing of
 * Viewpoint's is copied or kept.
 *
 * The player (user, 2026-11-21: "เปิด viewpoint ละไม่มี physic เลย"): Viewpoint places the player's feet on the ground
 * (LegReach.apply) by multiplying every slot of the palette it copied - all 60, ours too - by the body's matrix, so the
 * marker no longer read 5353 / 53 and nothing moved (zombies are not placed that way). Slot 52 is identity (the padding,
 * Jiggle.SLOT_VP_REF) and gets the same matrix: the shader takes it from there and reads our slots back through its
 * inverse (as they were when there is none).
 */
final class ViewpointShader {
    private ViewpointShader() {}

    /** our push went into Viewpoint's model shader at least once: Jiggle then writes slot 53 */
    static volatile boolean injected;
    private static volatile boolean warned;

    static final String MARK = "RBS_JIGGLE_VIEWPOINT";
    static final String MAIN = "void main() {";
    static final String AFTER = "  vec3 n = aNormal;\n";
    /** our hair's vertices sway (rbsHair), every other one gets the soft body's push */
    static final String CALL = "  if (palette >= 0 && skinned && !rbsHair(palette, p, n)) p.xyz += rbsJiggle(palette, aPos);   // " + MARK + "\n";
    /** its motion pass (motion blur / upscaling) places the vertex once more, for the frame before: the same push there,
     *  or the soft parts would smear (optional: left out when not found) */
    static final String MOTION = "    vec4 q = vec4(aPos, 1.0);\n";
    static final String CALL_MOTION = "    vec3 rbsN = aNormal;\n"
            + "    if (palette >= 0 && skinned && !rbsHair(palette, q, rbsN)) q.xyz += rbsJiggle(palette, aPos);   // " + MARK + "\n";

    static final String FUNCS = String.join("\n",
        "// " + MARK + ": Real Body Shape's soft body (zombie patch), the same push as its RBSJiggle.vert, before the skinning.",
        "// Slots of this palette: 59 rows 0-2, 58 rows 0-2, 53 = their rows 3, gear, marker; 52 = identity (padding).",
        "// The player's palette is moved slot by slot by the feet's placement on the ground (each slot's rows times one",
        "// matrix, ours too): slot 52 then holds that matrix, and the others are read back through it.",
        "mat3 rbsAi = mat3(1.0);",
        "vec3 rbsT = vec3(0.0);",
        "vec4 rbsRaw(int pal, int slot, int r) { return uPalettes[(pal + slot) * 3 + r]; }",
        "vec4 rbsRow(int pal, int slot, int r) {",
        "  vec4 q0 = rbsRaw(pal, slot, 0), q1 = rbsRaw(pal, slot, 1), q2 = rbsRaw(pal, slot, 2);",
        "  vec3 c0 = rbsAi * vec3(q0.x, q1.x, q2.x), c1 = rbsAi * vec3(q0.y, q1.y, q2.y);",
        "  vec3 c2 = rbsAi * vec3(q0.z, q1.z, q2.z), c3 = rbsAi * (vec3(q0.w, q1.w, q2.w) - rbsT);",
        "  return vec4(c0[r], c1[r], c2[r], c3[r]);",
        "}",
        "float rbsShare(float bone) {",
        "  if (bone < 0.0) return 0.0;",
        "  float w = 0.0;",
        "  if (abs(aBones.x - bone) < 0.5) w += aWeights.x;",
        "  if (abs(aBones.y - bone) < 0.5) w += aWeights.y;",
        "  if (abs(aBones.z - bone) < 0.5) w += aWeights.z;",
        "  if (abs(aBones.w - bone) < 0.5) w += aWeights.w;",
        "  return w;",
        "}",
        "bool rbsRef(int pal) {",
        "  if (pal < 0 || (pal + 60) * 3 > uPalettes.length()) return false;",
        "  vec4 a0 = rbsRaw(pal, 52, 0), a1 = rbsRaw(pal, 52, 1), a2 = rbsRaw(pal, 52, 2);",
        "  mat3 A = transpose(mat3(a0.xyz, a1.xyz, a2.xyz));",
        "  if (abs(determinant(A)) < 1.0e-6) return false;",
        "  rbsAi = inverse(A);",
        "  rbsT = vec3(a0.w, a1.w, a2.w);",
        "  return true;",
        "}",
        "vec3 rbsJiggle(int pal, vec3 pos) {",
        "  if (!rbsRef(pal)) return vec3(0.0);",
        "  vec4 v = rbsRow(pal, 53, 2);",
        "  if (abs(v.w - 53.0) > 0.01 || abs(v.z - 5353.0) > 0.5) return vec3(0.0);",
        "  vec4 d = rbsRow(pal, 59, 0);",
        "  if (d.w <= 0.5) return vec3(0.0);",
        "  vec4 pelvis = rbsRow(pal, 59, 1);",
        "  vec4 r2 = rbsRow(pal, 59, 2);",
        "  vec4 r3 = rbsRow(pal, 53, 0);",
        "  vec3 fw = r2.xyz, up = r3.xyz;",
        "  if (pelvis.w <= 0.0) return vec3(0.0);",
        "  vec3 rel = (pos - pelvis.xyz) / pelvis.w;",
        "  float h = dot(rel, up), f = dot(rel, fw), s = dot(rel, cross(up, fw));",
        "  float w = smoothstep(0.02, 0.14, f) * smoothstep(-0.12, 0.05, h) * (1.0 - smoothstep(0.08, 0.55, h))",
        "      * (1.0 - smoothstep(0.22, 0.42, abs(s)));",
        "  vec3 o = d.xyz * w;",
        "  vec4 k0 = rbsRow(pal, 58, 0);",
        "  if (abs(k0.w - 58.0) < 0.01) {",
        "    vec4 k1 = rbsRow(pal, 58, 1), k2 = rbsRow(pal, 58, 2), k3 = rbsRow(pal, 53, 1);",
        "    bool gear = v.x > 0.5;",
        "    float fc = f - 0.12 * clamp(h, 0.0, 1.0);",
        "    if (!gear) {",
        "      float wb = smoothstep(0.04, 0.12, fc) * smoothstep(0.50, 0.64, h) * (1.0 - smoothstep(0.88, 0.98, h))",
        "          * (1.0 - smoothstep(0.24, 0.34, abs(s)));",
        "      o += k0.xyz * wb;",
        "    }",
        "    float chest = smoothstep(0.0, 0.08, fc) * smoothstep(0.40, 0.55, h) * (1.0 - smoothstep(0.92, 1.0, h))",
        "        * (1.0 - smoothstep(0.30, 0.42, abs(s)));",
        "    o += (fw * (chest + 0.5 * w) + up * (0.3 * chest)) * k2.w;",
        "    if (!gear) {",
        "      float butt = (1.0 - smoothstep(-0.14, -0.04, fc)) * smoothstep(-0.32, -0.18, h) * (1.0 - smoothstep(0.0, 0.12, h))",
        "          * (1.0 - smoothstep(0.30, 0.40, abs(s)));",
        "      float thigh = smoothstep(-0.78, -0.50, h) * (1.0 - smoothstep(-0.12, 0.0, h)) * (1.0 - smoothstep(0.42, 0.55, abs(s)));",
        "      float under = 0.91 - 0.1 * (abs(s) - 0.36);",
        "      float arm = (rbsShare(k3.z) + rbsShare(k3.w)) * smoothstep(0.0, 0.05, under - h);",
        "      o += k2.xyz * clamp(0.8 * butt + 0.6 * thigh + arm, 0.0, 1.0);",
        "      o += (up * r2.w + fw * r3.w) * butt;",
        "    }",
        "    o += k1.xyz * rbsShare(k1.w);",
        "  }",
        "  return o;",
        "}",
        "// Our hair (the hair mod's sbj_HairSway.vert, the same steps): slot 57 rows 0-2 = the two springs (marker 57 / 57.5),",
        "// the skull; slot 51 (our hair meshes only) row 0 = 57's row 3 (pivot, gravity), row 1 = marker 5151 / 51. The vertex's",
        "// normal length says which piece and how far along it. Not here: the weather's wind (off in that shader too).",
        "const float RBS_BEND0 = 0.08, RBS_BEND1 = 0.55, RBS_STRAND = 0.05, RBS_BACK_MARGIN = 0.014, RBS_FLOOR = 0.006;",
        "const vec3 RBS_HANG = vec3(0.0, -1.0, 0.0);",
        "float rbsBackZ(float y, bool male) {",
        "  if (male) return 0.086 + 0.016 * smoothstep(0.66, 0.70, y) * (1.0 - smoothstep(0.77, 0.80, y)) - 0.011 * smoothstep(0.78, 0.80, y);",
        "  return 0.063 + 0.016 * smoothstep(0.675, 0.72, y) * (1.0 - smoothstep(0.775, 0.80, y));",
        "}",
        "vec3 rbsTurn(vec3 v, vec3 axis, float ang) {",
        "  float c = cos(ang), s = sin(ang);",
        "  return v * c + cross(axis, v) * s + axis * dot(axis, v) * (1.0 - c);",
        "}",
        "bool rbsHair(int pal, inout vec4 pos, inout vec3 nrm) {",
        "  float nl = length(nrm);",
        "  if (nl <= 1.5 || !rbsRef(pal)) return false;",
        "  vec4 m1 = rbsRow(pal, 51, 1);",
        "  if (abs(m1.w - 51.0) > 0.01 || abs(m1.z - 5151.0) > 0.5) return false;",
        "  vec4 h0 = rbsRow(pal, 57, 0);",
        "  bool male = abs(h0.w - 57.5) < 0.01;",
        "  if (!male && abs(h0.w - 57.0) > 0.01) return false;",
        "  vec4 h1 = rbsRow(pal, 57, 1), h2 = rbsRow(pal, 57, 2), h3 = rbsRow(pal, 51, 0);",
        // the vertex's skinning as the game's shader has it: the rows as this renderer sums them, then the feet's
        // placement taken back out (slot 52) - 'down' and the floor are the game's model space's
        "  vec4 s0 = vec4(0.0), s1 = vec4(0.0), s2 = vec4(0.0);",
        "  float ws = 0.0;",
        "  for (int i = 0; i < 4; i++) {",
        "    float w = aWeights[i];",
        "    if (w > 0.0) {",
        "      int b = int(aBones[i]);",
        "      s0 += rbsRaw(pal, b, 0) * w; s1 += rbsRaw(pal, b, 1) * w; s2 += rbsRaw(pal, b, 2) * w;",
        "      ws += w;",
        "    }",
        "  }",
        "  mat3 L = rbsAi * transpose(mat3(s0.xyz, s1.xyz, s2.xyz));",
        "  vec3 tg = rbsAi * (vec3(s0.w, s1.w, s2.w) - rbsT * ws);",
        "  float q = (nl - 2.0) * 0.5;",
        "  float j = floor(q + 0.0001);",
        "  float s = clamp((q - j) / 0.49, 0.0, 1.0);",
        "  float kind = floor(j / 7.0 + 0.0001);",
        "  float k = j - kind * 7.0;",
        "  bool tail = kind < 0.5;",
        "  vec3 nn = nrm / nl;",
        "  vec3 p0 = pos.xyz, p = p0;",
        "  float pressW = 0.0;",
        "  if (h3.w > 0.0) {",
        "    vec3 dn = normalize(vec3(0.0, -1.0, h1.w));",
        "    vec3 g = normalize(transpose(L) * dn);",   // down in the vertex's bind pose
        "    if (tail) {",
        "      float press = max(-g.z, 0.0);",
        "      vec3 gr = vec3(g.x, g.y, max(g.z, 0.0));",
        "      float gl = length(gr);",
        "      gr = gl > 0.25 ? gr / gl : RBS_HANG;",
        "      vec3 ax = cross(RBS_HANG, gr);",
        "      float sn = length(ax);",
        "      float t = smoothstep(RBS_BEND0, RBS_BEND1, s);",
        "      if (sn > 1e-5) {",
        "        float ang = atan(sn, dot(gr, RBS_HANG)) * t * h3.w;",
        "        p = h3.xyz + rbsTurn(p - h3.xyz, ax / sn, ang);",
        "        nn = rbsTurn(nn, ax / sn, ang);",
        "      }",
        "      pressW = press * t;",
        "    } else {",
        "      p += (g - RBS_HANG) * RBS_STRAND * s;",
        "    }",
        "  }",
        "  float m = 0.5 + 0.35 * sin(k * 2.4 + 0.7);",
        "  p += mix(h1.xyz, h0.xyz, m) * pow(s, 1.5);",
        "  vec3 d = p - h2.xyz;",
        "  float dl = length(d);",
        "  float rmin = min(length(p0 - h2.xyz), h2.w);",
        "  if (dl < rmin && dl > 1e-6) p = h2.xyz + d / dl * rmin;",
        "  if (tail && p.y < 0.86 && abs(p.x) < 0.10 && p0.z > 0.03) {",
        "    float zmin = min(rbsBackZ(p.y, male) + RBS_BACK_MARGIN, p0.z);",
        "    p.z = max(p.z, zmin);",
        "    p.z -= (p.z - zmin) * clamp(pressW, 0.0, 1.0);",
        "  }",
        // above the floor (the game's shader sets the skinned y; here the bind position is moved so it skins to that)
        "  float y = (L * p + tg).y;",
        "  if (y < RBS_FLOOR && abs(determinant(L)) > 1.0e-6) p += inverse(L) * vec3(0.0, RBS_FLOOR - y, 0.0);",
        "  pos.xyz = p;",
        "  nrm = nn;",
        "  return true;",
        "}",
        "");

    // ---------------- a check in game (user, 2026-11-21: no soft body in Viewpoint's views) ----------------
    // The first few of the player's models Viewpoint queues (ModelDraws.add, after its foot placement moved the
    // palette): our slots as its shader will read them - straight, and back through slot 52 - then a count a minute
    // later, then nothing more.
    private static volatile boolean drawDone;
    private static int drawLogs, drawSeen, drawStraight, drawDecoded;
    private static long drawUntil;
    private static java.lang.reflect.Field fPalettes, fBones, fCharacter;
    private static Class<?> playerType;

    static void draw(Object draws, Object instance, int palette) {
        if (drawDone || draws == null || instance == null || palette < 0) return;
        try {
            if (fPalettes == null) {
                fPalettes = draws.getClass().getDeclaredField("palettes");
                fPalettes.setAccessible(true);
                fBones = draws.getClass().getDeclaredField("bones");
                fBones.setAccessible(true);
                fCharacter = instance.getClass().getField("character");
                playerType = Class.forName("zombie.characters.IsoPlayer", false, instance.getClass().getClassLoader());
                drawUntil = System.nanoTime() + 60_000_000_000L;
            }
            Object chr = fCharacter.get(instance);
            if (chr == null || !playerType.isInstance(chr)) return;
            float[] p = (float[]) fPalettes.get(draws);
            int bones = fBones.getInt(draws);
            drawSeen++;
            String what;
            if (palette + 60 > bones) {
                what = "its palette has " + (bones - palette) + " slot(s), not 60 - no room for ours";
            } else {
                int b53 = (palette + 53) * 12, b52 = (palette + 52) * 12, b59 = (palette + 59) * 12;
                boolean straight = Math.abs(p[b53 + 11] - 53f) <= 0.01f && Math.abs(p[b53 + 10] - 5353f) <= 0.5f;
                float[] row2 = decodedRow2(p, b52, b53);
                boolean decoded = row2 != null && Math.abs(row2[3] - 53f) <= 0.01f && Math.abs(row2[2] - 5353f) <= 0.5f;
                if (straight) drawStraight++;
                if (decoded) drawDecoded++;
                what = String.format(java.util.Locale.ROOT,
                        "slot 53 row 2 (%.3f, %.3f, %.3f, %.3f) - marker %s straight, %s through slot 52 [%.3f %.3f %.3f %.3f | %.3f %.3f %.3f %.3f | %.3f %.3f %.3f %.3f]; slot 59 row 0 (%.4f, %.4f, %.4f, %.2f)",
                        p[b53 + 8], p[b53 + 9], p[b53 + 10], p[b53 + 11], straight ? "ok" : "MISSING", decoded ? "ok" : "MISSING",
                        p[b52], p[b52 + 1], p[b52 + 2], p[b52 + 3], p[b52 + 4], p[b52 + 5], p[b52 + 6], p[b52 + 7],
                        p[b52 + 8], p[b52 + 9], p[b52 + 10], p[b52 + 11], p[b59], p[b59 + 1], p[b59 + 2], p[b59 + 3]);
            }
            if (drawLogs < 4) {
                drawLogs++;
                RBSHook.log("viewpoint check: a model of the player at palette " + palette + " of " + bones + ": " + what
                        + (injected ? "" : " (our push is not in its shader)"));
            }
            if (System.nanoTime() > drawUntil) {
                drawDone = true;
                RBSHook.log("viewpoint check: " + drawSeen + " model(s) of the player in a minute, our marker straight in " + drawStraight
                        + ", through slot 52 in " + drawDecoded + " - checks stop here");
            }
        } catch (Throwable t) {
            drawDone = true;
            RBSHook.warn("viewpoint check: " + t);
        }
    }

    /** slot 53 row 2 as the shader reads it back: rows' = A rows + t (column 3), A and t from slot 52; null if A is singular */
    static float[] decodedRow2(float[] p, int b52, int b53) {
        double a00 = p[b52], a01 = p[b52 + 1], a02 = p[b52 + 2], t0 = p[b52 + 3];
        double a10 = p[b52 + 4], a11 = p[b52 + 5], a12 = p[b52 + 6], t1 = p[b52 + 7];
        double a20 = p[b52 + 8], a21 = p[b52 + 9], a22 = p[b52 + 10], t2 = p[b52 + 11];
        double det = a00 * (a11 * a22 - a12 * a21) - a01 * (a10 * a22 - a12 * a20) + a02 * (a10 * a21 - a11 * a20);
        if (Math.abs(det) < 1e-6) return null;
        // the inverse's row 2 (only row 2 of the slot is needed)
        double i20 = (a10 * a21 - a11 * a20) / det, i21 = -(a00 * a21 - a01 * a20) / det, i22 = (a00 * a11 - a01 * a10) / det;
        float[] out = new float[4];
        for (int c = 0; c < 4; c++) {
            double q0 = p[b53 + c], q1 = p[b53 + 4 + c], q2 = p[b53 + 8 + c];
            if (c == 3) { q0 -= t0; q1 -= t1; q2 -= t2; }
            out[c] = (float) (i20 * q0 + i21 * q1 + i22 * q2);
        }
        return out;
    }

    /** Viewpoint's shader file text: model.vert with our push in it, anything else as it is */
    static String source(String name, String src) {
        try {
            if (src == null || !"model.vert".equals(name) || src.contains(MARK)) return src;
            int main = src.indexOf(MAIN);
            int at = main < 0 ? -1 : src.indexOf(AFTER, main);
            if (at < 0) {
                if (!warned) {
                    warned = true;
                    RBSHook.log("viewpoint: its model shader is not as this patch knows it (another Viewpoint version?)"
                            + " - the soft body stays still in its views");
                }
                return src;
            }
            int end = at + AFTER.length();
            String rest = src.substring(end);
            int mq = rest.indexOf(MOTION);
            if (mq >= 0) rest = rest.substring(0, mq + MOTION.length()) + CALL_MOTION + rest.substring(mq + MOTION.length());
            String out = src.substring(0, main) + FUNCS + src.substring(main, end) + CALL + rest;
            if (!injected) RBSHook.log("viewpoint: the soft body goes into its model shader (first / third person views)");
            injected = true;
            return out;
        } catch (Throwable t) {
            RBSHook.warn("viewpoint shader: " + t);
            return src;
        }
    }
}
