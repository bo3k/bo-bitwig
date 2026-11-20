import jdk.internal.org.objectweb.asm.*;
import jdk.internal.org.objectweb.asm.commons.*;
import java.util.*; import java.util.zip.*; import java.io.*; import java.nio.file.*;

/** Makes a compile-only jar from Bitwig's jars: class version lowered to 65 for javac 21, method
 *  bodies removed, everything public, and (for bitwig.jar) default-package classes moved to bitwig.obf.
 *  args: <in.jar> <out.jar> <remapDefaultPackage:true|false> */
public class CompileJar {
    public static void main(String[] a) throws Exception {
        boolean remap = Boolean.parseBoolean(a[2]);
        Set<String> defaults = new HashSet<>();
        try (ZipFile z = new ZipFile(a[0])) {
            for (var e : Collections.list(z.entries())) {
                String n = e.getName();
                if (n.endsWith(".class") && !n.contains("/")) defaults.add(n.substring(0, n.length() - 6));
            }
            Remapper rm = new Remapper() {
                @Override public String map(String internal) {
                    if (!remap) return internal;
                    if (internal.indexOf('/') < 0 && defaults.contains(internal)) return "bitwig/obf/" + internal;
                    // nested classes of default-package classes: Outer$Inner
                    int d = internal.indexOf('$');
                    if (internal.indexOf('/') < 0 && d > 0 && defaults.contains(internal)) return "bitwig/obf/" + internal;
                    return internal;
                }
            };
            int n = 0;
            try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(a[1]))) {
                for (var e : Collections.list(z.entries())) {
                    String name = e.getName();
                    if (!name.endsWith(".class") || name.startsWith("META-INF/")) continue;
                    byte[] b = z.getInputStream(e).readAllBytes();
                    if ((b[6] & 0xff) * 256 + (b[7] & 0xff) > 65) { b[6] = 0; b[7] = 65; }
                    ClassReader r = new ClassReader(b);
                    ClassWriter w = new ClassWriter(0);
                    ClassVisitor pub = new ClassVisitor(Opcodes.ASM9, w) {
                        int acc(int x) { return (x & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC; }
                        int macc(int x) { return acc(x) & ~(Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE); }
                        @Override public void visit(int v, int access, String nm, String sig, String sup, String[] itf) {
                            super.visit(65, acc(access), nm, sig, sup, itf);
                        }
                        @Override public void visitInnerClass(String nm, String outer, String inner, int access) {
                            super.visitInnerClass(nm, outer, inner, acc(access));
                        }
                        @Override public FieldVisitor visitField(int access, String nm, String d, String s, Object v) {
                            return super.visitField(acc(access) & ~Opcodes.ACC_SYNTHETIC, nm, d, s, v);
                        }
                        @Override public MethodVisitor visitMethod(int access, String nm, String d, String s, String[] ex) {
                            return super.visitMethod(macc(access), nm, d, s, ex);
                        }
                        @Override public void visitNestHost(String h) {}
                        @Override public void visitNestMember(String m) {}
                        @Override public void visitPermittedSubclass(String p) {}
                    };
                    r.accept(new ClassRemapper(pub, rm), ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    String outName = rm.map(name.substring(0, name.length() - 6)) + ".class";
                    out.putNextEntry(new ZipEntry(outName));
                    out.write(w.toByteArray());
                    out.closeEntry();
                    n++;
                }
            }
            System.out.println("COMPILE_JAR_OK classes=" + n + " out=" + a[1]);
        }
    }
}
