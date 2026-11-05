package rbs;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal class-file rewriter (no external libraries): renames one method and adds a new method under
 * the old name whose body is
 *
 *     return (CastClass) HookClass.hookMethod(this, arg0, ...);     // every parameter must be a reference
 *     HookClass.hookMethod(this, arg0, ...); return;                 // methods returning void (castClass null)
 *
 * The new body has no branches, so no StackMapTable is needed and the JVM verifier accepts it as-is.
 * The added method is public; the renamed original keeps its own access flags.
 * Everything else (fields, other methods, attributes) is copied byte for byte.
 */
public final class ClassPatcher {
    private ClassPatcher() {}

    static final class Member {
        int access, name, desc;
        byte[] attrs;   // attributes_count + attributes, raw
    }

    static final class Cp {
        final List<byte[]> entries = new ArrayList<>();   // index 0 unused; null = second slot of long/double
        final List<String> utf8 = new ArrayList<>();

        int add(byte[] e, String s) { entries.add(e); utf8.add(s); return entries.size() - 1; }

        int utf8(String s) throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(b);
            d.writeByte(1);
            d.writeUTF(s);
            return add(b.toByteArray(), s);
        }

        int ref(int tag, int a) {
            return add(new byte[]{(byte) tag, (byte) (a >> 8), (byte) a}, null);
        }

        int ref(int tag, int a, int b) {
            return add(new byte[]{(byte) tag, (byte) (a >> 8), (byte) a, (byte) (b >> 8), (byte) b}, null);
        }
    }

    /** number of parameters in a method descriptor; throws if any is not a reference type */
    static int countRefParams(String desc) throws IOException {
        String p = desc.substring(desc.indexOf('(') + 1, desc.indexOf(')'));
        int n = 0, i = 0;
        while (i < p.length()) {
            char c = p.charAt(i);
            if (c == 'L') { i = p.indexOf(';', i) + 1; n++; }
            else throw new IOException("only reference parameters are supported: " + desc);
        }
        return n;
    }

    public static byte[] patch(byte[] cls, String methodName, String methodDesc, String renamedTo,
                               String hookClass, String hookMethod, String hookDesc, String castClass) throws IOException {
        int nparams = countRefParams(methodDesc);
        if (nparams > 250) throw new IOException("too many parameters: " + methodDesc);
        boolean isVoid = methodDesc.endsWith(")V");
        if (isVoid != (castClass == null)) throw new IOException("a void method takes no cast class (and only it): " + methodDesc);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(cls));
        int magic = in.readInt();
        if (magic != 0xCAFEBABE) throw new IOException("not a class file");
        int minor = in.readUnsignedShort(), major = in.readUnsignedShort();
        int cpCount = in.readUnsignedShort();
        Cp cp = new Cp();
        cp.add(null, null);
        for (int i = 1; i < cpCount; i++) {
            int tag = in.readUnsignedByte();
            byte[] e;
            String s = null;
            switch (tag) {
                case 1: {
                    int len = in.readUnsignedShort();
                    e = new byte[3 + len];
                    e[0] = 1; e[1] = (byte) (len >> 8); e[2] = (byte) len;
                    in.readFully(e, 3, len);
                    s = new DataInputStream(new ByteArrayInputStream(e, 1, len + 2)).readUTF();
                    break;
                }
                case 3: case 4: e = read(in, tag, 4); break;                       // Integer, Float
                case 5: case 6: e = read(in, tag, 8); break;                       // Long, Double (2 slots)
                case 7: case 8: case 16: case 19: case 20: e = read(in, tag, 2); break;   // Class, String, MethodType, Module, Package
                case 9: case 10: case 11: case 12: case 17: case 18: e = read(in, tag, 4); break;  // refs, NameAndType, Dynamic, InvokeDynamic
                case 15: e = read(in, tag, 3); break;                              // MethodHandle
                default: throw new IOException("unknown constant pool tag " + tag + " at #" + i);
            }
            cp.add(e, s);
            if (tag == 5 || tag == 6) { cp.add(null, null); i++; }
        }
        int access = in.readUnsignedShort(), thisC = in.readUnsignedShort(), superC = in.readUnsignedShort();
        int[] ifaces = new int[in.readUnsignedShort()];
        for (int i = 0; i < ifaces.length; i++) ifaces[i] = in.readUnsignedShort();
        List<Member> fields = readMembers(in);
        List<Member> methods = readMembers(in);
        byte[] classAttrs = in.readAllBytes();

        Member target = null;
        for (Member m : methods) {
            if (methodName.equals(cp.utf8.get(m.name)) && methodDesc.equals(cp.utf8.get(m.desc))) { target = m; break; }
        }
        if (target == null) throw new IOException("method " + methodName + methodDesc + " not found");
        for (Member m : methods) {
            if (renamedTo.equals(cp.utf8.get(m.name))) throw new IOException("already patched (" + renamedTo + " exists)");
        }

        // new constant-pool entries (duplicates of existing Utf8/Class entries are allowed by the JVM spec)
        int renamedIdx = cp.utf8(renamedTo);
        int hookClassIdx = cp.ref(7, cp.utf8(hookClass));
        int natIdx = cp.ref(12, cp.utf8(hookMethod), cp.utf8(hookDesc));
        int hookRef = cp.ref(10, hookClassIdx, natIdx);
        int castIdx = isVoid ? 0 : cp.ref(7, cp.utf8(castClass));
        int codeIdx = cp.utf8("Code");
        target.name = renamedIdx;

        Member added = new Member();
        added.access = 0x0001;                 // public
        added.name = cp.utf8(methodName);
        added.desc = cp.utf8(methodDesc);
        ByteArrayOutputStream cb = new ByteArrayOutputStream();
        for (int i = 0; i <= nparams; i++) {
            if (i <= 3) cb.write(0x2A + i);                                          // aload_0 .. aload_3
            else { cb.write(0x19); cb.write(i); }                                     // aload <i>
        }
        cb.write(0xB8); cb.write(hookRef >> 8); cb.write(hookRef);                    // invokestatic hook
        if (isVoid) {
            cb.write(0xB1);                                                           // return
        } else {
            cb.write(0xC0); cb.write(castIdx >> 8); cb.write(castIdx);                // checkcast CastClass
            cb.write(0xB0);                                                           // areturn
        }
        byte[] code = cb.toByteArray();
        ByteArrayOutputStream ab = new ByteArrayOutputStream();
        DataOutputStream ad = new DataOutputStream(ab);
        ad.writeShort(1);                                   // attributes_count
        ad.writeShort(codeIdx);
        ad.writeInt(2 + 2 + 4 + code.length + 2 + 2);
        ad.writeShort(nparams + 1);                         // max_stack
        ad.writeShort(nparams + 1);                         // max_locals (this + reference arguments)
        ad.writeInt(code.length);
        ad.write(code);
        ad.writeShort(0);                                   // exception_table_length
        ad.writeShort(0);                                   // code attributes_count
        added.attrs = ab.toByteArray();
        methods.add(added);

        ByteArrayOutputStream ob = new ByteArrayOutputStream(cls.length + 512);
        DataOutputStream out = new DataOutputStream(ob);
        out.writeInt(magic);
        out.writeShort(minor);
        out.writeShort(major);
        out.writeShort(cp.entries.size());
        for (int i = 1; i < cp.entries.size(); i++) {
            byte[] e = cp.entries.get(i);
            if (e != null) out.write(e);
        }
        out.writeShort(access);
        out.writeShort(thisC);
        out.writeShort(superC);
        out.writeShort(ifaces.length);
        for (int x : ifaces) out.writeShort(x);
        writeMembers(out, fields);
        writeMembers(out, methods);
        out.write(classAttrs);
        return ob.toByteArray();
    }

    private static byte[] read(DataInputStream in, int tag, int n) throws IOException {
        byte[] e = new byte[1 + n];
        e[0] = (byte) tag;
        in.readFully(e, 1, n);
        return e;
    }

    private static List<Member> readMembers(DataInputStream in) throws IOException {
        int n = in.readUnsignedShort();
        List<Member> list = new ArrayList<>(n + 1);
        for (int i = 0; i < n; i++) {
            Member m = new Member();
            m.access = in.readUnsignedShort();
            m.name = in.readUnsignedShort();
            m.desc = in.readUnsignedShort();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(b);
            int ac = in.readUnsignedShort();
            d.writeShort(ac);
            for (int j = 0; j < ac; j++) {
                int nameIdx = in.readUnsignedShort();
                int len = in.readInt();
                byte[] body = new byte[len];
                in.readFully(body);
                d.writeShort(nameIdx);
                d.writeInt(len);
                d.write(body);
            }
            m.attrs = b.toByteArray();
            list.add(m);
        }
        return list;
    }

    private static void writeMembers(DataOutputStream out, List<Member> list) throws IOException {
        out.writeShort(list.size());
        for (Member m : list) {
            out.writeShort(m.access);
            out.writeShort(m.name);
            out.writeShort(m.desc);
            out.write(m.attrs);
        }
    }
}
