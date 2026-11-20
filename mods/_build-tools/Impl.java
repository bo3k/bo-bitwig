import java.util.*; import java.util.zip.*;
/** Impl <jar> <internal name> : classes that extend/implement it (transitively), with string constants matching an optional regex */
public class Impl {
  public static void main(String[] a) throws Exception {
    Map<String,List<String>> sup=new HashMap<>(); Map<String,List<String>> strs=new HashMap<>();
    try(ZipFile z=new ZipFile(a[0])){ for(var e:Collections.list(z.entries())){ String n=e.getName(); if(!n.endsWith(".class"))continue;
      byte[] b=z.getInputStream(e).readAllBytes(); b[6]=0;b[7]=65;
      var r=new jdk.internal.org.objectweb.asm.ClassReader(b); List<String> s=new ArrayList<>(); if(r.getSuperName()!=null)s.add(r.getSuperName()); s.addAll(Arrays.asList(r.getInterfaces()));
      sup.put(r.getClassName(),s);
      List<String> st=new ArrayList<>(); char[] buf=new char[r.getMaxStringLength()];
      for(int i=1;i<r.getItemCount();i++){ int off=r.getItem(i); if(off>0 && b[off-1]==8){ st.add(r.readUTF8(off,buf)); } }
      strs.put(r.getClassName(),st);}}
    Set<String> out=new TreeSet<>(); Deque<String> q=new ArrayDeque<>(List.of(a[1]));
    while(!q.isEmpty()){String t=q.poll(); for(var en:sup.entrySet()) if(en.getValue().contains(t)&&out.add(en.getKey())) q.add(en.getKey());}
    for(String c:out){ List<String> s=strs.get(c); if(a.length>2) s=s.stream().filter(x->x.matches(a[2])).toList(); System.out.println(c+"  "+(s.size()>8?s.subList(0,8):s)); }
  }
}
