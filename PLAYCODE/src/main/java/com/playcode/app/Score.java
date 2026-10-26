package com.playcode.app;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name="quiz_scores")
public class Score {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    private String username;
    private String language;
    private String mode;
    private String topic;
    private int score;
    private int total;
    private LocalDateTime playedAt = LocalDateTime.now();

    public Score() {}
    public Score(String username,String language,String mode,String topic,int score,int total) {
        this.username=username; this.language=language; this.mode=mode; this.topic=topic; this.score=score; this.total=total; this.playedAt=LocalDateTime.now();
    }
    public Long getId(){return id;} public String getUsername(){return username;}
    public String getLanguage(){return language;} public String getMode(){return mode;}
    public String getTopic(){return topic;} public int getScore(){return score;}
    public int getTotal(){return total;} public LocalDateTime getPlayedAt(){return playedAt;}
}
