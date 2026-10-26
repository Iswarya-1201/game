package com.playcode.app;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/coding")
public class CodingController {
  private final ObjectMapper mapper = new ObjectMapper();
  private final List<Map<String,Object>> problems = new ArrayList<>();
  private final Map<String, Duel> duels = new ConcurrentHashMap<>();
  public CodingController(){try(InputStream in=getClass().getResourceAsStream("/coding-problems.json")){if(in==null)throw new IllegalStateException("coding-problems.json missing");problems.addAll(mapper.readValue(in,new TypeReference<List<Map<String,Object>>>(){}));}catch(Exception e){throw new RuntimeException("Unable to load coding problems",e);}}
  @GetMapping("/topics") public List<String> topics(@RequestParam String language){return problems.stream().filter(p->supports(p,language)).map(p->String.valueOf(p.get("topic"))).distinct().sorted().toList();}
  @GetMapping("/problem") public Map<String,Object> problem(@RequestParam String language,@RequestParam String topic){List<Map<String,Object>> pool=problems.stream().filter(p->supports(p,language)&&String.valueOf(p.get("topic")).equalsIgnoreCase(topic)).toList();if(pool.isEmpty())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"No coding missions found for this concept yet.");return publicProblem(pool.get(new Random().nextInt(pool.size())),language);}
  @PostMapping("/submit") public Map<String,Object> submit(@RequestBody Map<String,Object> b){String lang=required(b,"language"),id=required(b,"problemId"),code=required(b,"code"),user=String.valueOf(b.getOrDefault("username","Player"));Map<String,Object> p=find(id,lang);Map<String,Object> result=grade(p,lang,code);result.put("username",user);return result;}
  @PostMapping("/challenge") public Map<String,Object> challenge(@RequestBody Map<String,String>b){String from=required(b,"from"),to=required(b,"to");if(from.equalsIgnoreCase(to))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose another player.");QuizService.Player player=null; // shared online registry is injected through static access below
    if(!OnlineBridge.isOnline(to))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"That player is not online.");String id=UUID.randomUUID().toString().substring(0,8);duels.put(id,new Duel(id,from,to));return Map.of("id",id,"status","PENDING");}
  @GetMapping("/challenges") public List<Map<String,Object>> challenges(@RequestParam String username){return duels.values().stream().filter(d->d.from.equalsIgnoreCase(username)||d.to.equalsIgnoreCase(username)).sorted(Comparator.comparingLong(d->-d.created)).map(d->{Map<String,Object>x=new LinkedHashMap<>();x.put("id",d.id);x.put("from",d.from);x.put("to",d.to);x.put("status",d.status);if(d.matchId!=null){x.put("matchId",d.matchId);x.put("language",d.language);x.put("topic",d.topic);}return x;}).toList();}
  @PostMapping("/challenge/respond") public Map<String,Object> respond(@RequestBody Map<String,String>b){Duel d=duels.get(required(b,"id"));if(d==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Coding challenge not found.");if(!d.to.equalsIgnoreCase(required(b,"username")))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Only the invited player can respond.");String r=required(b,"response").toUpperCase();if(!List.of("ACCEPT","REJECT").contains(r))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid response.");d.status=r.equals("ACCEPT")?"ACCEPTED":"REJECTED";return Map.of("id",d.id,"status",d.status);}
  @PostMapping("/match/start") public Map<String,Object> start(@RequestBody Map<String,String>b){String cid=required(b,"challengeId"),user=required(b,"username"),lang=required(b,"language"),topic=required(b,"topic");Duel d=duels.get(cid);if(d==null||!d.status.equals("ACCEPTED"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Challenge must be accepted first.");if(!d.from.equalsIgnoreCase(user))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Only the challenge sender chooses the coding mission.");synchronized(d){if(d.matchId==null){Map<String,Object> p=choose(lang,topic);d.matchId=UUID.randomUUID().toString().substring(0,8);d.language=lang;d.topic=topic;matches.put(d.matchId,new CodeMatch(d.matchId,d.from,d.to,lang,topic,p));}CodeMatch m=matches.get(d.matchId);return Map.of("matchId",m.id,"startAt",m.startAt,"language",m.language,"topic",m.topic);}}
  private final Map<String,CodeMatch> matches=new ConcurrentHashMap<>();
  @GetMapping("/match/{id}") public Map<String,Object> getMatch(@PathVariable String id,@RequestParam String username){CodeMatch m=match(id);authorize(m,username);synchronized(m){if(m.status.equals("COUNTDOWN")&&System.currentTimeMillis()>=m.startAt)m.status="RUNNING";}Map<String,Object>x=new LinkedHashMap<>();x.put("matchId",m.id);x.put("player1",m.p1);x.put("player2",m.p2);x.put("language",m.language);x.put("topic",m.topic);x.put("startAt",m.startAt);x.put("status",m.status);x.put("problem",publicProblem(m.problem,m.language));x.put("submittedPlayers",new ArrayList<>(m.results.keySet()));if(m.status.equals("FINISHED")){x.put("results",m.results);x.put("winner",m.winner);x.put("problemTitle",m.problem.get("title"));}return x;}
  @PostMapping("/match/{id}/submit") public Map<String,Object> submitMatch(@PathVariable String id,@RequestBody Map<String,Object>b){CodeMatch m=match(id);String user=required(b,"username");authorize(m,user);if(System.currentTimeMillis()<m.startAt)throw new ResponseStatusException(HttpStatus.CONFLICT,"Wait for the synchronized start.");synchronized(m){if(m.results.containsKey(user))throw new ResponseStatusException(HttpStatus.CONFLICT,"You already submitted this coding mission.");String code=required(b,"code");Map<String,Object> r=grade(m.problem,m.language,code);r.put("submittedAt",System.currentTimeMillis());m.results.put(user,r);if(m.results.size()==2){m.status="FINISHED";Map<String,Object>a=m.results.get(m.p1),z=m.results.get(m.p2);int as=num(a.get("score")),zs=num(z.get("score"));long at=numLong(a.get("submittedAt")),zt=numLong(z.get("submittedAt"));m.winner=as==zs?(at==zt?"DRAW":at<zt?m.p1:m.p2):(as>zs?m.p1:m.p2);}Map<String,Object> out=new LinkedHashMap<>(r);out.put("status",m.status);return out;}}
  private Map<String,Object> grade(Map<String,Object> p,String lang,String code){
    List<Map<String,Object>> cases=testCases(p);
    List<Map<String,Object>> results=new ArrayList<>(); int passed=0;
    if(cases.isEmpty()) throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"This coding problem has no valid test cases configured.");
    for(int i=0;i<cases.size();i++){
      Map<String,Object> tc=cases.get(i);
      String input=String.valueOf(tc.getOrDefault("input",""));
      String expected=normalize(String.valueOf(tc.getOrDefault("output","")));
      try {
        String output=execute(lang,code,input); boolean ok=normalize(output).equals(expected);
        if(ok) passed++;
        Map<String,Object> tr=new LinkedHashMap<>(); tr.put("passed",ok);
        tr.put("message",ok?"Output matched":"Output did not match expected output");
        tr.put("expected",expected); tr.put("actual",normalize(output)); results.add(tr);
      } catch(Exception e) {
        Map<String,Object> tr=new LinkedHashMap<>(); tr.put("passed",false);
        tr.put("message",safeMessage(e.getMessage())); tr.put("expected",expected); tr.put("actual","");
        results.add(tr); break;
      }
    }
    int total=cases.size(); Map<String,Object> out=new LinkedHashMap<>();
    out.put("problemId",p.get("id")); out.put("passed",passed); out.put("total",total);
    out.put("score",passed*10); out.put("allPassed",passed==total); out.put("tests",results);
    out.put("message",passed==total?"All test cases passed!":"Passed "+passed+" of "+total+" test cases."); return out;
  }
  // coding-problems.json stores tests as [input, output] pairs. Also accept object-shaped tests.
  private List<Map<String,Object>> testCases(Map<String,Object> problem){
    Object raw=problem.get("tests"); List<Map<String,Object>> out=new ArrayList<>();
    if(!(raw instanceof List<?> list)) return out;
    for(Object item:list){
      Map<String,Object> test=new LinkedHashMap<>();
      if(item instanceof Map<?,?> m){
        if(m.containsKey("input")) test.put("input",m.get("input"));
        if(m.containsKey("output")) test.put("output",m.get("output"));
      } else if(item instanceof List<?> pair && pair.size()>=2){
        test.put("input",pair.get(0)); test.put("output",pair.get(1));
      }
      if(test.containsKey("input") && test.containsKey("output")) out.add(test);
    }
    return out;
  }
  /**
   * Runs code locally, so the arena does not depend on the retired public Piston API.
   * IMPORTANT: this is for a trusted, localhost student project only. It is not a secure
   * sandbox for untrusted users; arbitrary submitted code runs with this computer's permissions.
   */
  private String execute(String lang, String code, String input) {
    Path work = null;
    try {
      work = Files.createTempDirectory("playverse-code-");
      boolean javaCode = lang.equalsIgnoreCase("Java");
      String sourceName = javaCode ? "Main.java" : "main.py";
      Files.writeString(work.resolve(sourceName), code, StandardCharsets.UTF_8);

      if (javaCode) {
        ProcessResult compile = runProcess(List.of("javac", sourceName), work, "", 12);
        if (compile.exitCode != 0) {
          throw new RuntimeException("Compile error:\n" + nonEmpty(compile.stderr, compile.stdout, "Please check your Java syntax."));
        }
        ProcessResult run = runProcess(List.of("java", "-cp", work.toAbsolutePath().toString(), "Main"), work, input, 5);
        if (run.timedOut) throw new RuntimeException("Time limit exceeded (5 seconds).");
        if (run.exitCode != 0) throw new RuntimeException("Runtime error:\n" + nonEmpty(run.stderr, run.stdout, "Check your code and input."));
        return run.stdout;
      }

      List<String> command = isWindows()
          ? List.of("python", sourceName)
          : List.of("python3", sourceName);
      ProcessResult run;
      try {
        run = runProcess(command, work, input, 5);
      } catch (RuntimeException first) {
        if (!isWindows()) throw first;
        run = runProcess(List.of("py", "-3", sourceName), work, input, 5);
      }
      if (run.timedOut) throw new RuntimeException("Time limit exceeded (5 seconds).");
      if (run.exitCode != 0) throw new RuntimeException("Runtime error:\n" + nonEmpty(run.stderr, run.stdout, "Check your Python code and input."));
      return run.stdout;
    } catch (IOException e) {
      throw new RuntimeException("Could not create the temporary code workspace: " + e.getMessage());
    } finally {
      if (work != null) {
        try (var paths = Files.walk(work)) {
          paths.sorted(Comparator.reverseOrder()).forEach(path -> {
            try { Files.deleteIfExists(path); } catch (IOException ignored) { }
          });
        } catch (IOException ignored) { }
      }
    }
  }

  private ProcessResult runProcess(List<String> command, Path directory, String input, int timeoutSeconds) {
    Process process = null;
    try {
      process = new ProcessBuilder(command).directory(directory.toFile()).start();
      Process running = process;
      CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(() -> readStream(running.getInputStream()));
      CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(() -> readStream(running.getErrorStream()));
      try (var stdin = process.getOutputStream()) {
        stdin.write(input.getBytes(StandardCharsets.UTF_8));
      }
      boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
      if (!finished) {
        process.destroyForcibly();
        process.waitFor(1, TimeUnit.SECONDS);
        return new ProcessResult(-1, stdoutFuture.get(2, TimeUnit.SECONDS), stderrFuture.get(2, TimeUnit.SECONDS), true);
      }
      return new ProcessResult(process.exitValue(), stdoutFuture.get(2, TimeUnit.SECONDS), stderrFuture.get(2, TimeUnit.SECONDS), false);
    } catch (IOException e) {
      throw new RuntimeException("Cannot start " + command.get(0) + ". Install the JDK for Java or Python 3 for Python, and ensure it is on PATH.");
    } catch (Exception e) {
      if (process != null) process.destroyForcibly();
      if (e instanceof RuntimeException runtime) throw runtime;
      throw new RuntimeException("Code execution failed: " + e.getMessage());
    }
  }

  private String readStream(java.io.InputStream stream) {
    try (stream) { return new String(stream.readAllBytes(), StandardCharsets.UTF_8); }
    catch (IOException e) { return ""; }
  }

  private String nonEmpty(String first, String second, String fallback) {
    if (first != null && !first.isBlank()) return first.trim();
    if (second != null && !second.isBlank()) return second.trim();
    return fallback;
  }

  private boolean isWindows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"); }

  private record ProcessResult(int exitCode, String stdout, String stderr, boolean timedOut) { }
  private Map<String,Object> choose(String lang,String topic){List<Map<String,Object>> p=problems.stream().filter(x->supports(x,lang)&&String.valueOf(x.get("topic")).equalsIgnoreCase(topic)).toList();if(p.isEmpty())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"No coding missions for this concept.");return p.get(new Random().nextInt(p.size()));}
  private Map<String,Object> find(String id,String lang){return problems.stream().filter(p->String.valueOf(p.get("id")).equals(id)&&supports(p,lang)).findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Coding problem not found."));}
  private Map<String,Object> publicProblem(Map<String,Object> p,String lang){
    Map<String,Object>x=new LinkedHashMap<>();
    for(String k:List.of("id","title","topic","difficulty","story","input","output")) x.put(k,p.get(k));
    x.put("javaStarter",p.get("java")); x.put("pythonStarter",p.get("python"));
    x.put("samples",testCases(p).stream().limit(2).map(t->{Map<String,Object> sample=new LinkedHashMap<>();sample.put("input",t.get("input"));sample.put("output",t.get("output"));return sample;}).toList());
    return x;
  }
  private boolean supports(Map<String,Object>p,String lang){return lang.equalsIgnoreCase("Java")||lang.equalsIgnoreCase("Python");} private CodeMatch match(String id){CodeMatch m=matches.get(id);if(m==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Coding match not found.");return m;}private void authorize(CodeMatch m,String u){if(!m.p1.equalsIgnoreCase(u)&&!m.p2.equalsIgnoreCase(u))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"You are not in this coding duel.");}private String required(Map<?,?>b,String k){String v=String.valueOf(b.get(k));if(v.equals("null")||v.isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,k+" is required.");return v.trim();}private int num(Object x){try{return Integer.parseInt(String.valueOf(x));}catch(Exception e){return 0;}}private long numLong(Object x){try{return Long.parseLong(String.valueOf(x));}catch(Exception e){return 0;}}private String normalize(String s){return s==null?"":s.trim().replaceAll("\\s+"," ");}private String safeMessage(String s){if(s==null)return "Execution failed";return s.length()>160?s.substring(0,160):s;}
  static class Duel {String id,from,to,status="PENDING",matchId,language,topic;long created=System.currentTimeMillis();Duel(String i,String f,String t){id=i;from=f;to=t;}}
  static class CodeMatch {String id,p1,p2,language,topic,status="COUNTDOWN",winner;long startAt=System.currentTimeMillis()+7000;Map<String,Object>problem;Map<String,Map<String,Object>>results=new ConcurrentHashMap<>();CodeMatch(String i,String a,String b,String l,String t,Map<String,Object>p){id=i;p1=a;p2=b;language=l;topic=t;problem=p;}}
  // Static bridge is initialized by CodingController constructor using the quiz service bean.
  @org.springframework.beans.factory.annotation.Autowired void setQuizService(QuizService service){OnlineBridge.registry=service.online;}
  static class OnlineBridge {static Map<String,QuizService.Player> registry=Map.of();static boolean isOnline(String u){return registry.containsKey(u.toLowerCase());}}
}
