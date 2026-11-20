import jdk.internal.org.objectweb.asm.*;
import jdk.internal.org.objectweb.asm.commons.*;
import java.util.*; import java.util.zip.*; import java.io.*; import java.nio.file.*;

/** JDK-21 stand-in for `bml-modkit finish` for mods WITHOUT @Hook/@AddField:
 *  rewrites bitwig/obf/X -> X in place, strips bml/api annotations, lists non-public Bitwig
 *  classes/members used (and package-private overrides) as "access", and writes uses.tsv.
 *  args: <classesDir> <hooks.json> <uses.tsv> <bitwig.jar> [<more jars>...] */
public class Finish {
    static final Map<String, byte[]> bitwig = new HashMap<>();
    static final Map<String, Info> infos = new HashMap<>();
    record M(String name, String desc, int access) {}
    static final class Info { String name, sup; String[] itf; int access; List<M> methods = new ArrayList<>(), fields = new ArrayList<>(); }

    static Info info(String n) {
        if (infos.containsKey(n)) return infos.get(n);
        byte[] b = bitwig.get(n);
        if (b == null) { infos.put(n, null); return null; }
        b = b.clone(); if ((b[6] & 0xff) * 256 + (b[7] & 0xff) > 65) { b[6] = 0; b[7] = 65; }
        Info i = new Info();
        new ClassReader(b).accept(new ClassVisitor(Opcodes.ASM9) {
            public void visit(int v, int a, String nm, String s, String sup, String[] itf) { i.name = nm; i.access = a; i.sup = sup; i.itf = itf; }
            public FieldVisitor visitField(int a, String nm, String d, String s, Object v) { i.fields.add(new M(nm, d, a)); return null; }
            public MethodVisitor visitMethod(int a, String nm, String d, String s, String[] e) { i.methods.add(new M(nm, d, a)); return null; }
        }, ClassReader.SKIP_CODE);
        infos.put(n, i); return i;
    }
    /** [declaring class, member] or null */
    static Object[] resolve(String owner, String name, String desc, boolean field) {
        Deque<String> q = new ArrayDeque<>(); q.add(owner); Set<String> seen = new HashSet<>();
        // superclass chain first, then interfaces
        String c = owner;
        while (c != null) { Info i = info(c); if (i == null) break;
            for (M m : field ? i.fields : i.methods) if (m.name.equals(name) && m.desc.equals(desc)) return new Object[]{c, m, c};
            c = i.sup; }
        while (!q.isEmpty()) { String x = q.poll(); if (!seen.add(x)) continue; Info i = info(x); if (i == null) continue;
            for (M m : field ? i.fields : i.methods) if (m.name.equals(name) && m.desc.equals(desc)) return new Object[]{(i.access & Opcodes.ACC_INTERFACE) != 0 ? "-" : x, m, x};
            if (i.itf != null) q.addAll(Arrays.asList(i.itf)); if (i.sup != null) q.add(i.sup); }
        return null;
    }
    static boolean isObjectMethod(String name, String desc) {
        for (java.lang.reflect.Method m : Object.class.getDeclaredMethods())
            if (m.getName().equals(name) && Type.getMethodDescriptor(m).equals(desc)) return true;
        return false;
    }
    static boolean isPublic(int a) { return (a & Opcodes.ACC_PUBLIC) != 0; }
    static String j(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }

    public static void main(String[] a) throws Exception {
        Path dir = Path.of(a[0]);
        for (int k = 3; k < a.length; k++) try (ZipFile z = new ZipFile(a[k])) {
            for (var e : Collections.list(z.entries())) { String n = e.getName();
                if (n.endsWith(".class") && !n.startsWith("META-INF/")) bitwig.putIfAbsent(n.substring(0, n.length() - 6), z.getInputStream(e).readAllBytes()); }
        }
        Remapper rm = new Remapper() { public String map(String n) { return n.startsWith("bitwig/obf/") ? n.substring(11) : n; } };
        TreeSet<String> uses = new TreeSet<>();
        LinkedHashMap<String, String> access = new LinkedHashMap<>(); // key -> json
        List<String> problems = new ArrayList<>();
        int rewritten = 0;
        List<Path> files; try (var s = Files.walk(dir)) { files = s.filter(p -> p.toString().endsWith(".class")).sorted().toList(); }
        for (Path f : files) {
            byte[] in = Files.readAllBytes(f);
            ClassWriter w = new ClassWriter(0);
            ClassVisitor strip = new ClassVisitor(Opcodes.ASM9, w) {
                public AnnotationVisitor visitAnnotation(String d, boolean v) { return d.startsWith("Lbml/api/") ? null : super.visitAnnotation(d, v); }
                public MethodVisitor visitMethod(int ac, String nm, String d, String s, String[] e) {
                    return new MethodVisitor(Opcodes.ASM9, super.visitMethod(ac, nm, d, s, e)) {
                        public AnnotationVisitor visitAnnotation(String ad, boolean v) { return ad.startsWith("Lbml/api/") ? null : super.visitAnnotation(ad, v); }
                        public AnnotationVisitor visitParameterAnnotation(int p, String ad, boolean v) { return ad.startsWith("Lbml/api/") ? null : super.visitParameterAnnotation(p, ad, v); }
                    };
                }
            };
            new ClassReader(in).accept(new ClassRemapper(strip, rm), 0);
            byte[] out = w.toByteArray();
            if (!Arrays.equals(in, out)) { Files.write(f, out); rewritten++; }
            // scan references
            final String[] self = new String[1]; final String[] sup = new String[1];
            List<M> own = new ArrayList<>();
            new ClassReader(out).accept(new ClassVisitor(Opcodes.ASM9) {
                void cls(String n) { if (n == null) return; while (n.startsWith("[")) n = n.substring(1);
                    if (n.startsWith("L") && n.endsWith(";")) n = n.substring(1, n.length() - 1);
                    if (n.length() == 1) return; Info i = info(n); if (i == null) return;
                    uses.add("C\t" + n);
                    if (!isPublic(i.access)) access.put("class:" + n, "{ \"kind\": \"class\", \"class\": " + j(n) + " }"); }
                void desc(String d) { for (Type t : d.startsWith("(") ? concat(Type.getArgumentTypes(d), Type.getReturnType(d)) : new Type[]{Type.getType(d)})
                    { Type e = t.getSort() == Type.ARRAY ? t.getElementType() : t; if (e.getSort() == Type.OBJECT) cls(e.getInternalName()); } }
                Type[] concat(Type[] x, Type y) { Type[] r = Arrays.copyOf(x, x.length + 1); r[x.length] = y; return r; }
                void member(String owner, String name, String d, boolean field) {
                    while (owner.startsWith("[")) return; if (info(owner) == null) return;
                    cls(owner); desc(d);
                    Object[] r = resolve(owner, name, d, field);
                    String decl = r == null ? "-" : (String) r[0];
                    uses.add((field ? "F\t" : "M\t") + owner + "\t" + name + "\t" + d + "\t" + decl);
                    if (r != null && !isPublic(((M) r[1]).access())) {
                        String kind = field ? "field" : "method";
                        String realDecl = r.length > 2 ? (String) r[2] : decl;
                        access.put(kind + ":" + realDecl + "." + name + d, "{ \"kind\": " + j(kind) + ", \"owner\": " + j(realDecl) + ", \"name\": " + j(name) + ", \"descriptor\": " + j(d) + " }");
                        if (!field && (((M) r[1]).access() & Opcodes.ACC_STATIC) == 0 && !name.equals("<init>")) problems.add("WARN non-public instance method " + decl + "." + name + d + ": check it isn't also in a super/subclass");
                    }
                    if (r == null && !field && isObjectMethod(name, d)) { uses.remove((field ? "F\t" : "M\t") + owner + "\t" + name + "\t" + d + "\t" + decl); return; } // java.lang.Object's, through an interface
                    if (r == null) problems.add("ERROR unresolved " + owner + "." + name + d);
                }
                public void visit(int v, int ac, String nm, String s, String su, String[] itf) { self[0] = nm; sup[0] = su; cls(su); if (itf != null) for (String i : itf) cls(i); }
                public FieldVisitor visitField(int ac, String nm, String d, String s, Object v) { desc(d); return null; }
                public MethodVisitor visitMethod(int ac, String nm, String d, String s, String[] e) {
                    desc(d); own.add(new M(nm, d, ac));
                    return new MethodVisitor(Opcodes.ASM9) {
                        public void visitMethodInsn(int op, String o, String n, String dd, boolean itf) { member(o, n, dd, false); }
                        public void visitFieldInsn(int op, String o, String n, String dd) { member(o, n, dd, true); }
                        public void visitTypeInsn(int op, String t) { cls(t); }
                        public void visitLdcInsn(Object c) { if (c instanceof Type t && t.getSort() == Type.OBJECT) cls(t.getInternalName()); }
                        public void visitMultiANewArrayInsn(String d, int n) { desc(d); }
                        public void visitInvokeDynamicInsn(String n, String d, Handle bsm, Object... args) {
                            desc(d); for (Object x : args) if (x instanceof Handle h) member(h.getOwner(), h.getName(), h.getDesc(), h.getTag() <= Opcodes.H_PUTSTATIC); }
                        public void visitLocalVariable(String n, String d, String s, Label st, Label en, int i) { desc(d); }
                    };
                }
            }, 0);
            // overrides of package-private Bitwig methods: they only override if opened
            if (sup[0] != null && info(sup[0]) != null) for (M m : own) {
                if (m.name.startsWith("<") || (m.access & Opcodes.ACC_STATIC) != 0) continue;
                String c = sup[0];
                while (c != null && info(c) != null) { Info i = info(c);
                    for (M bm : i.methods) if (bm.name.equals(m.name) && bm.desc.equals(m.desc)
                        && (bm.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE)) == 0)
                        { access.put("method:" + c + "." + m.name + m.desc, "{ \"kind\": \"method\", \"owner\": " + j(c) + ", \"name\": " + j(m.name) + ", \"descriptor\": " + j(m.desc) + " }");
                          problems.add("NOTE " + self[0] + " overrides package-private " + c + "." + m.name + m.desc + " (opened)"); }
                    c = i.sup; }
            }
        }
        try (PrintWriter p = new PrintWriter(a[1], "UTF-8")) {
            p.println("{\n  \"modkit\": \"jdk21-standin\",\n  \"hook_classes\": [],\n  \"hooks\": [],\n  \"access\": [");
            p.println(String.join(",\n", access.values().stream().map(s -> "    " + s).toList()));
            p.println("  ]\n}");
        }
        Files.write(Path.of(a[2]), (String.join("\n", uses) + "\n").getBytes("UTF-8"));
        problems.forEach(System.out::println);
        System.out.println((problems.stream().anyMatch(s -> s.startsWith("ERROR")) ? "FINISH_ERRORS" : "FINISH_OK") + " access=" + access.size() + " uses=" + uses.size() + " classes_rewritten=" + rewritten);
    }
}
