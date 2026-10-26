package com.playcode.app;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface ScoreRepository extends JpaRepository<Score,Long> {
    List<Score> findTop50ByOrderByScoreDescPlayedAtAsc();
    List<Score> findAllByOrderByScoreDescPlayedAtAsc();
    List<Score> findByUsernameOrderByPlayedAtDesc(String username);
}
