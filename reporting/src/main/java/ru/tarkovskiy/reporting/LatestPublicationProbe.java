package ru.tarkovskiy.reporting;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import static java.nio.file.StandardOpenOption.*;

/** Explicit main only: local JSON fixtures and report generation, never test execution. */
public final class LatestPublicationProbe {
 private static final ObjectMapper JSON=new ObjectMapper();
 public static void main(String[] args)throws Exception {
  Path root=Path.of(args[0]); Files.createDirectories(root);
  for(String layer:List.of("api","ui")) {
   Path source=root.resolve(layer+"-tests/src/test/java/ru/tarkovskiy/"+layer+"/tests/SyntheticTest.java");Files.createDirectories(source.getParent());
   Files.writeString(source,"@Test public void scenario() {}",CREATE_NEW);
  }
  fixture(root,"first",1000,true,true); fixture(root,"second",2000,true,true);
  LatestAllurePublisher.publish(root,"first","All");
  LatestAllurePublisher.publish(root,"second","All");
  Path snapshot=root.resolve("test-runs/second/prepared/combined-All");
  JsonNode summary=JSON.readTree(snapshot.resolve("html/widgets/summary.json").toFile());
  require(summary.path("statistic").path("total").asInt()==10,"combined count");
  for(String status:List.of("passed","failed","broken","skipped","unknown"))require(summary.path("statistic").path(status).asInt()==2,"status "+status);
  JsonNode trend=JSON.readTree(snapshot.resolve("html/history/history-trend.json").toFile());
  require(trend.size()==2,"two runs in trend");
  long files;try(var all=Files.walk(root)){files=all.count();}
  byte[] config=Files.readAllBytes(root.resolve(".mvn/maven.config"));
  LatestAllurePublisher.publish(root,"second","All");
  try(var all=Files.walk(root)){require(files==all.count(),"idempotence: new files");}
  require(Arrays.equals(config,Files.readAllBytes(root.resolve(".mvn/maven.config"))),"idempotence: changed selection");
  fixture(root,"incomplete",3000,true,false);LatestAllurePublisher.publish(root,"incomplete","All");
  require(Files.readString(root.resolve(".mvn/maven.config")).contains("second/prepared/combined-All/input"),"incomplete replaced combined");
  fixture(root,"ui-only",4000,false,true);LatestAllurePublisher.publish(root,"ui-only","All");
  require(Files.readString(root.resolve(".mvn/maven.config")).contains("second/prepared/combined-All/input"),"UI replaced combined");
  fixture(root,"invalid",5000,true,true);
  Files.writeString(root.resolve("test-runs/invalid/ui-tests/All/run.properties"),"runId=wrong\nmodule=ui-tests\nsuite=All\n");
  config=Files.readAllBytes(root.resolve(".mvn/maven.config"));
  boolean refused=false;try{LatestAllurePublisher.publish(root,"invalid","All");}catch(Exception expected){refused=true;}
  require(refused,"wrong Run ID accepted");require(Arrays.equals(config,Files.readAllBytes(root.resolve(".mvn/maven.config"))),"failure changed atomic selection");
  System.out.println("PASS: all statuses, complete combined run, history, attachment/step retention, idempotence, incomplete/UI-only isolation, invalid-ID atomic refusal. Fixtures retained at "+root);
 }
 private static void fixture(Path root,String id,long time,boolean api,boolean ui)throws Exception {
  Path run=root.resolve("test-runs/"+id);Files.createDirectories(run);
  Files.writeString(run.resolve("run.properties"),"runId="+id+"\nenvironment=TEST\ncreatedEpoch="+time+"\n",CREATE_NEW);
  for(String layer:List.of("api","ui")) {
   Path part=run.resolve(layer+"-tests/All");Path input=part.resolve("allure-results");Files.createDirectories(input);
   Files.writeString(part.resolve("run.properties"),"runId="+id+"\nmodule="+layer+"-tests\nsuite=All\n",CREATE_NEW);
   if(layer.equals("api")?api:ui)Files.writeString(part.resolve("completed.marker"),"done",CREATE_NEW);
   int n=0;for(String status:List.of("passed","failed","broken","skipped","unknown")) {
    String uuid=UUID.randomUUID().toString(),attachment=uuid+".txt";
    Files.writeString(input.resolve(attachment),"safe diagnostic",CREATE_NEW);
    Map<String,Object> test=new LinkedHashMap<>();test.put("uuid",uuid);test.put("historyId",layer+"-"+n);test.put("fullName","ru.tarkovskiy."+layer+".tests.SyntheticTest.scenario");test.put("name","Synthetic case "+n);test.put("status",status);test.put("start",time);test.put("stop",time+10);
    test.put("labels",List.of(Map.of("name","runId","value",id)));test.put("parameters",List.of(Map.of("name","variant","value",Integer.toString(n))));
    test.put("steps",List.of(Map.of("name","Synthetic action","status",status)));test.put("attachments",List.of(Map.of("name","Diagnostic","source",attachment,"type","text/plain")));
    JSON.writeValue(input.resolve(uuid+"-result.json").toFile(),test);n++;
   }
  }
 }
 private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
