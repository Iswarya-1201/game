package com.playcode.app;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final QuizService service; private final ScoreRepository scores;
    public ApiController(QuizService service, ScoreRepository scores){this.service=service;this.scores=scores;}

    @GetMapping("/languages") public List<String> languages(){return service.languages();}
    @GetMapping("/topics") public List<String> topics(@RequestParam String language){return service.topics(language);}
    @PostMapping("/login") public Map<String,Object> login(@RequestBody Map<String,String> body){
        String u=clean(body.get("username")); if(u.length()<2) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Username must contain at least 2 characters.");
        service.online.compute(u.toLowerCase(),(k,v)->{if(v==null)v=new QuizService.Player(u);v.touch();v.username=u;return v;});
        return Map.of("username",u,"message","Welcome to PLAYCODE");
    }
    @PostMapping("/heartbeat") public Map<String,Object> heartbeat(@RequestBody Map<String,String> body){
        String u=clean(body.get("username")); service.online.computeIfPresent(u.toLowerCase(),(k,v)->{v.touch();return v;});
        return Map.of("ok",true);
    }
    @GetMapping("/online") public List<String> online(@RequestParam String username){
        cleanup(); return service.online.values().stream().map(p->p.username).filter(u->!u.equalsIgnoreCase(username)).sorted().collect(Collectors.toList());
    }
    @PostMapping("/solo/questions") public List<Map<String,Object>> soloQuestions(@RequestBody Map<String,String> b){
        String lang=req(b,"language"), topic=req(b,"topic"), username=req(b,"username");
        List<Map<String,Object>> qs=service.publicQuestions(lang,topic,20,username);
        if(qs.size()<20) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"This topic has only "+qs.size()+" questions. A 20-question quiz needs at least 20.");
        return qs;
    }
    @PostMapping("/solo/submit") public Map<String,Object> soloSubmit(@RequestBody Map<String,Object> b){
        String user=required(b,"username"), lang=required(b,"language"), topic=required(b,"topic");
        @SuppressWarnings("unchecked") List<Map<String,Object>> qs=(List<Map<String,Object>>)b.get("questions");
        @SuppressWarnings("unchecked") Map<String,String> answers=(Map<String,String>)b.get("answers");
        int score=grade(qs,answers); scores.save(new Score(user,lang,"SOLO",topic,score,qs.size()));
        return Map.of("score",score,"total",qs.size(),"message","Quiz submitted");
    }
    @GetMapping("/leaderboard") public List<Map<String,Object>> leaderboard(){
        return scores.findAllByOrderByScoreDescPlayedAtAsc().stream().map(s->{
            Map<String,Object> m=new LinkedHashMap<>();m.put("username",s.getUsername());m.put("language",s.getLanguage());m.put("mode",s.getMode());
            m.put("topic",s.getTopic());m.put("score",s.getScore());m.put("total",s.getTotal());m.put("playedAt",s.getPlayedAt());return m;
        }).toList();
    }
    @GetMapping("/history") public List<Score> history(@RequestParam String username){return scores.findByUsernameOrderByPlayedAtDesc(username);}
    @PostMapping("/challenge") public Map<String,Object> challenge(@RequestBody Map<String,String>b){
        String from=required(b,"from"), to=required(b,"to");
        if(from.equalsIgnoreCase(to)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose another player.");
        if(!service.online.containsKey(to.toLowerCase())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"That player is not online.");
        String id=UUID.randomUUID().toString().substring(0,8);
        service.challenges.put(id,new QuizService.Challenge(id,from,to));
        return Map.of("id",id,"status","PENDING","message","Challenge sent");
    }
    @GetMapping("/challenges") public List<Map<String,Object>> challenges(@RequestParam String username){
        return service.challenges.values().stream().filter(c->c.to.equalsIgnoreCase(username)||c.from.equalsIgnoreCase(username))
                .sorted(Comparator.comparingLong(c->-c.created)).map(c->{
                    Map<String,Object> item=new LinkedHashMap<>(); item.put("id",c.id);item.put("from",c.from);item.put("to",c.to);item.put("status",c.status);
                    if(c.matchId!=null)item.put("matchId",c.matchId);if(c.language!=null)item.put("language",c.language);if(c.topic!=null)item.put("topic",c.topic);
                    return item;
                }).toList();
    }
    @PostMapping("/challenge/respond") public Map<String,Object> respond(@RequestBody Map<String,String>b){
        QuizService.Challenge c=service.challenges.get(required(b,"id")); if(c==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Challenge not found.");
        if(!c.to.equalsIgnoreCase(required(b,"username")))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Only the invited player can respond.");
        String response=required(b,"response").toUpperCase();
        if(!List.of("ACCEPT","REJECT").contains(response))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid response.");
        c.status=response.equals("ACCEPT")?"ACCEPTED":"REJECTED";
        return Map.of("id",c.id,"status",c.status,"from",c.from,"to",c.to);
    }
    @PostMapping("/match/start") public Map<String,Object> startMatch(@RequestBody Map<String,String>b){
        String challengeId=required(b,"challengeId"), user=required(b,"username"), lang=required(b,"language"), topic=required(b,"topic");
        QuizService.Challenge c=service.challenges.get(challengeId);
        if(c==null||!c.status.equals("ACCEPTED"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Challenge must be accepted first.");
        if(!c.from.equalsIgnoreCase(user))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Only the player who sent the challenge can choose the language/topic and start the match.");
        synchronized(c) {
            if(c.matchId != null) {
                QuizService.Match existing=service.matches.get(c.matchId);
                if(existing != null) return Map.of("matchId",existing.id,"player1",existing.player1,"player2",existing.player2,"language",existing.language,"topic",existing.topic,"status",existing.status,"startAt",existing.startAt);
            }
            List<Question> qs=service.freshQuestions(lang,topic,20,List.of(c.from,c.to));
            if(qs.size()<20)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"This topic has fewer than 20 questions.");
            String id=UUID.randomUUID().toString().substring(0,8);
            QuizService.Match m=new QuizService.Match(id,c.from,c.to,lang,topic,qs);service.matches.put(id,m);
            c.matchId=id;c.language=lang;c.topic=topic;
            return Map.of("matchId",m.id,"player1",m.player1,"player2",m.player2,"language",m.language,"topic",m.topic,"status",m.status,"startAt",m.startAt);
        }
    }
    @GetMapping("/match/{id}") public Map<String,Object> match(@PathVariable String id,@RequestParam String username){
        QuizService.Match m=getMatch(id); authorize(m,username);
        synchronized(m) { if(m.status.equals("COUNTDOWN") && System.currentTimeMillis()>=m.startAt)m.status="RUNNING"; }
        Map<String,Object> out=new LinkedHashMap<>();out.put("matchId",m.id);out.put("player1",m.player1);out.put("player2",m.player2);
        out.put("language",m.language);out.put("topic",m.topic);out.put("status",m.status);out.put("startAt",m.startAt);out.put("submitted",m.submitted);
        if(!m.status.equals("FINISHED"))out.put("questions",m.questions.stream().map(q->{Map<String,Object>x=new LinkedHashMap<>();x.put("id",q.id());x.put("question",q.question());x.put("optionA",q.optionA());x.put("optionB",q.optionB());x.put("optionC",q.optionC());x.put("optionD",q.optionD());return x;}).toList());
        if(m.status.equals("FINISHED")){out.put("scores",m.scores);out.put("winner",m.scores.getOrDefault(m.player1,0)==m.scores.getOrDefault(m.player2,0)?"DRAW":m.scores.getOrDefault(m.player1,0)>m.scores.getOrDefault(m.player2,0)?m.player1:m.player2);}
        return out;
    }
    @PostMapping("/match/{id}/submit") public Map<String,Object> matchSubmit(@PathVariable String id,@RequestBody Map<String,Object>b){
        QuizService.Match m=getMatch(id);String user=required(b,"username");authorize(m,user);
        synchronized(m) { if(System.currentTimeMillis()<m.startAt)throw new ResponseStatusException(HttpStatus.CONFLICT,"The match has not started yet. Wait for the countdown."); if(m.status.equals("FINISHED"))throw new ResponseStatusException(HttpStatus.CONFLICT,"This match is already finished."); }
        if(m.submitted.contains(user))throw new ResponseStatusException(HttpStatus.CONFLICT,"You already submitted.");
        @SuppressWarnings("unchecked") Map<String,String> answers=(Map<String,String>)b.get("answers");
        int score=0; for(Question q:m.questions){String a=answers==null?null:answers.get(String.valueOf(q.id()));if(a!=null&&a.equalsIgnoreCase(q.correctAnswer()))score++;}
        m.scores.put(user,score);m.submitted.add(user);
        scores.save(new Score(user,m.language,"MULTIPLAYER",m.topic,score,m.questions.size()));
        if(m.submitted.contains(m.player1)&&m.submitted.contains(m.player2))m.status="FINISHED";
        return Map.of("score",score,"total",m.questions.size(),"submitted",true);
    }
    private int grade(List<Map<String,Object>> qs,Map<String,String> answers){
        int score=0;if(qs==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Questions missing.");
        for(Map<String,Object> q:qs){String ans=answers==null?null:answers.get(String.valueOf(q.get("id")));if(ans==null)continue;
            // Correct answer is looked up server-side by question id; clients cannot choose the answer key.
            long id=Long.parseLong(String.valueOf(q.get("id")));
            // Match against loaded CSV records.
            List<Question> found=service.rawQuestionsForGrade(id);
            if(!found.isEmpty()&&ans.equalsIgnoreCase(found.get(0).correctAnswer()))score++;
        } return score;
    }
    private QuizService.Match getMatch(String id){QuizService.Match m=service.matches.get(id);if(m==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Match not found.");return m;}
    private void authorize(QuizService.Match m,String u){if(!m.player1.equalsIgnoreCase(u)&&!m.player2.equalsIgnoreCase(u))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Not in this match.");}
    private String req(Map<String,String>b,String k){return required(b,k);}
    private String required(Map<?,?> b,String k){String v=String.valueOf(b.get(k));if(v.equals("null")||v.isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,k+" is required.");return v.trim();}
    private String clean(String s){return s==null?"":s.trim().replaceAll("[<>]","");}
    private void cleanup(){long cutoff=System.currentTimeMillis()-45000;service.online.entrySet().removeIf(e->e.getValue().lastSeen<cutoff);}
}
