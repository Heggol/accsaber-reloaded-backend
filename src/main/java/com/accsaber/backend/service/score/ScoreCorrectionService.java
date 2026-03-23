package com.accsaber.backend.service.score;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.accsaber.backend.exception.ResourceNotFoundException;
import com.accsaber.backend.model.dto.APResult;
import com.accsaber.backend.model.entity.Modifier;
import com.accsaber.backend.model.entity.score.Score;
import com.accsaber.backend.model.entity.score.ScoreModifierLink;
import com.accsaber.backend.repository.score.ScoreModifierLinkRepository;
import com.accsaber.backend.repository.score.ScoreRepository;
import com.accsaber.backend.repository.user.UserRepository;
import com.accsaber.backend.service.map.MapDifficultyComplexityService;
import com.accsaber.backend.service.map.MapDifficultyStatisticsService;
import com.accsaber.backend.service.stats.OverallStatisticsService;
import com.accsaber.backend.service.stats.RankingService;
import com.accsaber.backend.service.stats.StatisticsService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScoreCorrectionService {

    private static final int ACCURACY_SCALE = 10;

    private final ScoreRepository scoreRepository;
    private final ScoreModifierLinkRepository modifierLinkRepository;
    private final UserRepository userRepository;
    private final APCalculationService apCalculationService;
    private final MapDifficultyComplexityService mapComplexityService;
    private final MapDifficultyStatisticsService mapDifficultyStatisticsService;
    private final StatisticsService statisticsService;
    private final OverallStatisticsService overallStatisticsService;
    private final RankingService rankingService;
    private final ScoreRankingService scoreRankingService;

    @Autowired
    @Qualifier("backfillExecutor")
    private Executor backfillExecutor;

    @Transactional
    @Async("taskExecutor")
    public void correctModifierScoresAsync() {
        log.info("Starting modifier score correction (DB-only, using scoreNoMods as source of truth)");

        List<Score> scores = scoreRepository.findActiveScoresWhereScoreDiffersFromScoreNoMods();
        log.info("Found {} active scores where score != scoreNoMods", scores.size());

        int corrected = 0;
        int failed = 0;
        ConcurrentHashMap<UUID, Set<Long>> affectedByCategory = new ConcurrentHashMap<>();
        Set<UUID> affectedDifficulties = new HashSet<>();

        for (int i = 0; i < scores.size(); i++) {
            Score score = scores.get(i);
            try {
                List<Modifier> modifiers = modifierLinkRepository.findByScore_Id(score.getId()).stream()
                        .map(ScoreModifierLink::getModifier)
                        .toList();

                Integer correctedScore = applyModifierMultiplier(score.getScoreNoMods(), modifiers);

                if (Objects.equals(score.getScore(), correctedScore)) {
                    continue;
                }

                Integer maxScore = score.getMapDifficulty().getMaxScore();
                if (maxScore == null || maxScore == 0) {
                    log.warn("Score {} has no maxScore on difficulty - skipping", score.getId());
                    continue;
                }

                BigDecimal accuracy = BigDecimal.valueOf(correctedScore)
                        .divide(BigDecimal.valueOf(maxScore), ACCURACY_SCALE, RoundingMode.HALF_UP);
                BigDecimal complexity = mapComplexityService.findActiveComplexity(score.getMapDifficulty().getId())
                        .orElse(null);
                if (complexity == null) {
                    log.warn("Score {} has no active complexity - skipping", score.getId());
                    continue;
                }

                APResult apResult = apCalculationService.calculateRawAP(
                        accuracy, complexity, score.getMapDifficulty().getCategory().getScoreCurve());

                log.info("Correcting score {} (user {}): score {} -> {}, ap {} -> {}",
                        score.getId(), score.getUser().getId(),
                        score.getScore(), correctedScore, score.getAp(), apResult.rawAP());

                score.setScore(correctedScore);
                score.setAp(apResult.rawAP());
                scoreRepository.save(score);

                corrected++;
                affectedByCategory.computeIfAbsent(
                        score.getMapDifficulty().getCategory().getId(),
                        k -> ConcurrentHashMap.newKeySet())
                        .add(score.getUser().getId());
                affectedDifficulties.add(score.getMapDifficulty().getId());
            } catch (Exception e) {
                failed++;
                log.error("Failed to correct score {}: {}", score.getId(), e.getMessage());
            }

            if ((i + 1) % 100 == 0) {
                log.info("Score correction progress: {}/{} checked, {} corrected so far",
                        i + 1, scores.size(), corrected);
            }
        }

        log.info("Score correction phase done: {} corrected, {} failed out of {} checked",
                corrected, failed, scores.size());

        if (corrected == 0) {
            log.info("No scores were corrected - skipping recalculations");
            return;
        }

        log.info("Reassigning ranks for {} affected difficulties", affectedDifficulties.size());
        for (UUID difficultyId : affectedDifficulties) {
            scoreRankingService.reassignRanks(difficultyId);
        }

        log.info("Recalculating stats for {} categories", affectedByCategory.size());
        for (var entry : affectedByCategory.entrySet()) {
            batchRecalculateStats(entry.getValue(), entry.getKey());
            rankingService.updateRankings(entry.getKey());
        }
        overallStatisticsService.updateOverallRankings();

        log.info("Modifier score correction complete: {} corrected, {} failed", corrected, failed);
    }

    private Integer applyModifierMultiplier(Integer baseScore, List<Modifier> modifiers) {
        if (modifiers.isEmpty())
            return baseScore;
        BigDecimal combined = modifiers.stream()
                .map(Modifier::getMultiplier)
                .reduce(BigDecimal.ONE, BigDecimal::multiply);
        return combined.multiply(BigDecimal.valueOf(baseScore))
                .setScale(0, RoundingMode.HALF_UP).intValue();
    }

    @Transactional
    public void removeScore(Long userId, UUID mapDifficultyId, String reason) {
        List<UUID> scoreIds = scoreRepository.findIdsByUserAndDifficulty(userId, mapDifficultyId);
        if (scoreIds.isEmpty()) {
            throw new ResourceNotFoundException(
                    "Scores for user " + userId + " on difficulty " + mapDifficultyId);
        }

        Score activeScore = scoreRepository.findByUser_IdAndMapDifficulty_IdAndActiveTrue(userId, mapDifficultyId)
                .orElse(null);
        UUID categoryId = activeScore != null
                ? activeScore.getMapDifficulty().getCategory().getId()
                : null;

        scoreRepository.nullifySupersedesReferences(scoreIds);
        scoreRepository.nullifyTopPlayReferences(scoreIds);
        scoreRepository.nullifyMilestoneScoreReferences(scoreIds);
        scoreRepository.deleteMergeScoreActions(scoreIds);
        scoreRepository.hardDeleteByIds(scoreIds);
        scoreRepository.flush();

        log.info("Hard-deleted {} score(s) for user {} on difficulty {} - reason: {}",
                scoreIds.size(), userId, mapDifficultyId, reason);

        userRepository.recalculateTotalXpForAllActiveUsers();
        scoreRankingService.reassignRanks(mapDifficultyId);

        if (categoryId != null) {
            mapDifficultyStatisticsService.recalculate(
                    activeScore.getMapDifficulty(), userId);
            statisticsService.recalculate(userId, categoryId);
            rankingService.updateRankingsAsync(categoryId);
        }
    }

    private void batchRecalculateStats(Set<Long> userIds, UUID categoryId) {
        List<CompletableFuture<Void>> futures = userIds.stream()
                .map(userId -> CompletableFuture.runAsync(() -> {
                    try {
                        statisticsService.recalculate(userId, categoryId, false);
                    } catch (Exception e) {
                        log.error("Stats recalc failed for user {} in category {}: {}",
                                userId, categoryId, e.getMessage());
                    }
                }, backfillExecutor))
                .toList();
        futures.forEach(CompletableFuture::join);
    }
}
