import java.net.*; import java.util.*; import java.util.zip.*; import java.io.*;
public class Verify {
  public static void main(String[] a) throws Exception {
    List<URL> urls = new ArrayList<>(); for (int i = 1; i < a.length; i++) urls.add(new File(a[i]).toURI().toURL());
    urls.add(0, new File(a[0]).toURI().toURL());
    URLClassLoader l = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
    int ok = 0, bad = 0;
    try (ZipFile z = new ZipFile(a[0])) { for (var e : Collections.list(z.entries())) { String n = e.getName(); if (!n.endsWith(".class")) continue;
      String c = n.substring(0, n.length() - 6).replace('/', '.');
      try { Class<?> k = Class.forName(c, false, l); k.getDeclaredMethods(); java.lang.invoke.MethodHandles.privateLookupIn(k, java.lang.invoke.MethodHandles.lookup()).ensureInitialized(k); System.out.println("VERIFIED+INIT " + c); ok++; }
      catch (VerifyError | ClassFormatError | IncompatibleClassChangeError v) { System.out.println("FAIL " + c + " " + v); bad++; }
      catch (Throwable t) { Throwable r = t; while (r.getCause() != null) r = r.getCause(); System.out.println("LINKED (init stopped: " + r.getClass().getSimpleName() + ": " + String.valueOf(r.getMessage()).lines().findFirst().orElse("") + ") " + c); ok++; }
    }}
    System.out.println(bad == 0 ? "VERIFY_OK classes=" + ok : "VERIFY_FAILED " + bad);
  }
}
