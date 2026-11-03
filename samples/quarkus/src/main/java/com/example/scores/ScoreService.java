package com.example.scores;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

@ApplicationScoped
public class ScoreService {
  /** The first {@code n} distinct scores at or above {@code minimum}, in arrival order. */
  public Uni<List<Integer>> top(Multi<Integer> scores, int minimum, int n) {
    return scores
        .select()
        .where(score -> score >= minimum)
        .select()
        .distinct()
        .select()
        .first(n)
        .collect()
        .asList();
  }

  /** A score that failed to load counts as zero. */
  public Uni<Integer> scoreOrZero(Uni<Integer> score) {
    return score.onFailure().recoverWithItem(0);
  }

  public boolean isHigh(int score) {
    return score >= 90;
  }
}
