package com.playcode.app;

import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class QuizService {
    private final List<Question> questions = new ArrayList<>();
    // Tracks recently served questions per player and topic so practice attempts rotate through the bank.
    private final Map<String, Deque<Long>> recentQuestionIds = new ConcurrentHashMap<>();
    private static final int RECENT_QUESTION_MEMORY = 35;
    // Concurrent maps make simultaneous online users/challenges safe to access across request threads.
    final Map<String, Player> online = new ConcurrentHashMap<>();
    final Map<String, Challenge> challenges = new ConcurrentHashMap<>();
    final Map<String, Match> matches = new ConcurrentHashMap<>();

    @PostConstruct
    public void loadQuestions() {
        try (InputStream in = getClass().getResourceAsStream("/questions.csv")) {
            if (in == null) throw new IllegalStateException("questions.csv was not found in resources");
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line = br.readLine(); // header
                while ((line = br.readLine()) != null) {
                    List<String> c = parseCsv(line);
                    if (c.size() < 10) continue;
                    questions.add(new Question(Long.parseLong(c.get(0)), c.get(1), c.get(2), c.get(3), c.get(4),
                            c.get(5), c.get(6), c.get(7), c.get(8), c.get(9).trim().toUpperCase()));
                }
            }
        } catch (Exception e) { throw new RuntimeException("Could not load question bank: " + e.getMessage(), e); }
        System.out.println("PLAYCODE loaded " + questions.size() + " questions.");
    }
    private List<String> parseCsv(String line) {
        List<String> out = new ArrayList<>(); StringBuilder cur = new StringBuilder(); boolean quoted=false;
        for (int i=0;i<line.length();i++) {
            char ch=line.charAt(i);
            if(ch=='"' && quoted && i+1<line.length() && line.charAt(i+1)=='"'){cur.append('"');i++;}
            else if(ch=='"') quoted=!quoted;
            else if(ch==',' && !quoted){out.add(cur.toString());cur.setLength(0);}
            else cur.append(ch);
        }
        out.add(cur.toString()); return out;
    }
    public List<String> languages() {
        return questions.stream().map(Question::subject).distinct().sorted().collect(Collectors.toList());
    }
    public List<String> topics(String language) {
        return questions.stream().filter(q->q.subject().equalsIgnoreCase(language))
                .map(Question::topic).distinct().sorted().collect(Collectors.toList());
    }
    public List<Map<String,Object>> publicQuestions(String language, String topic, int count, String username) {
        List<Question> selected = freshQuestions(language, topic, count, List.of(username));
        return selected.stream().map(q->{
            Map<String,Object> m=new LinkedHashMap<>(); m.put("id",q.id());m.put("subject",q.subject());m.put("topic",q.topic());
            m.put("difficulty",q.difficulty());m.put("question",q.question());m.put("optionA",q.optionA());
            m.put("optionB",q.optionB());m.put("optionC",q.optionC());m.put("optionD",q.optionD());return m;
        }).collect(Collectors.toList());
    }
    public List<Question> freshQuestions(String language, String topic, int count, List<String> players) {
        List<Question> pool=questions.stream()
                .filter(q->q.subject().equalsIgnoreCase(language)&&q.topic().equalsIgnoreCase(topic))
                .collect(Collectors.toCollection(ArrayList::new));
        if(pool.isEmpty()) return List.of();
        Collections.shuffle(pool);
        Set<Long> selectedIds=new HashSet<>();
        List<Question> selected=new ArrayList<>();
        // Prefer questions not seen recently by any player in this match.
        Set<Long> recentlySeen=new HashSet<>();
        for(String player:players) {
            Deque<Long> history=recentQuestionIds.get(historyKey(player,language,topic));
            if(history!=null) synchronized(history) { recentlySeen.addAll(history); }
        }
        for(Question q:pool) {
            if(!recentlySeen.contains(q.id()) && selected.size()<count) { selected.add(q); selectedIds.add(q.id()); }
        }
        // If a topic is nearly exhausted, fill from the remaining pool without duplicates in this quiz.
        if(selected.size()<count) {
            for(Question q:pool) {
                if(selected.size()>=count) break;
                if(selectedIds.add(q.id())) selected.add(q);
            }
        }
        for(String player:players) {
            String key=historyKey(player,language,topic);
            Deque<Long> history=recentQuestionIds.computeIfAbsent(key,k->new ArrayDeque<>());
            synchronized(history) {
                for(Question q:selected) { history.remove(q.id()); history.addLast(q.id()); }
                while(history.size()>RECENT_QUESTION_MEMORY) history.removeFirst();
            }
        }
        return selected;
    }
    private String historyKey(String username,String language,String topic) {
        return (username==null?"":username.trim().toLowerCase(Locale.ROOT))+"|"+language.toLowerCase(Locale.ROOT)+"|"+topic.toLowerCase(Locale.ROOT);
    }
    public List<Question> rawQuestionsForGrade(long id) { return questions.stream().filter(q -> q.id() == id).toList(); }
    public List<Question> rawQuestions(String language, String topic, int count) {
        return freshQuestions(language, topic, Math.min(count,20), List.of("__general__"));
    }
    static class Player { String username; long lastSeen; Player(String u){username=u;touch();} void touch(){lastSeen=System.currentTimeMillis();} }
    static class Challenge { String id, from, to, status="PENDING", matchId, language, topic; long created=System.currentTimeMillis(); Challenge(String i,String f,String t){id=i;from=f;to=t;} }
    static class Match {
        String id, player1, player2, language, topic, status="COUNTDOWN";
        long startAt;
        List<Question> questions; Map<String,Map<Long,String>> answers=new ConcurrentHashMap<>();
        Map<String,Integer> scores=new ConcurrentHashMap<>(); Set<String> submitted=ConcurrentHashMap.newKeySet();
        Match(String id,String p1,String p2,String lang,String topic,List<Question> qs){this.id=id;player1=p1;player2=p2;language=lang;this.topic=topic;questions=qs;startAt=System.currentTimeMillis()+5000L;}
    }
}
