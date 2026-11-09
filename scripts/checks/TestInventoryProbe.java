import org.testng.annotations.*;
import java.lang.reflect.*;
import java.util.*;
/** Reflects annotations and invokes ONLY the project's pure, array-returning DataProviders. */
public class TestInventoryProbe {
 public static void main(String[] args) throws Exception {
  int total=0, methods=0;
  for(int i=1;i<args.length;i++) {
   Class<?> type=Class.forName(args[i]); Object instance=type.getConstructor().newInstance();
   Map<String,Method> providers=new HashMap<>();
   for(Method m:type.getDeclaredMethods()) {DataProvider dp=m.getAnnotation(DataProvider.class);if(dp!=null)providers.put(dp.name().isEmpty()?m.getName():dp.name(),m);}
   int cases=0;
   for(Method m:type.getDeclaredMethods()) {
    Test t=m.getAnnotation(Test.class);if(t==null)continue;
    if(!t.enabled() || t.invocationCount()!=1)throw new AssertionError("Unexpected disabled/repeated method "+m.getName());
    int n=t.dataProvider().isEmpty()?1:((Object[][])providers.get(t.dataProvider()).invoke(instance)).length;
    cases+=n;methods++;
   }
   total+=cases;System.out.println(type.getSimpleName()+": "+cases);
  }
  if(total!=Integer.parseInt(args[0]))throw new AssertionError("Unexpected inventory "+total);
  System.out.println("PASS: "+methods+" methods, "+total+" invocations; no tests/fixtures executed");
 }
}
