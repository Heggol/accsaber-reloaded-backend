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

    @Async("taskExecutor")
    public void correctModifierScoresAsync() {
        log.info("Starting modifier score correction (DB-only, using scoreNoMods as source of truth)");

        int corrected = 0;
        int failed = 0;
        ConcurrentHashMap<UUID, Set<Long>> affectedByCategory = new ConcurrentHashMap<>();
        Set<UUID> affectedDifficulties = new HashSet<>();

        List<Score> withModifiers = scoreRepository.findActiveScoresWithModifierLinks();
        log.info("Pass 1: {} active scores with modifier links to check", withModifiers.size());

        for (int i = 0; i < withModifiers.size(); i++) {
            Score score = withModifiers.get(i);
            try {
                List<Modifier> modifiers = modifierLinkRepository.findByScoreIdWithModifier(score.getId()).stream()
                        .map(ScoreModifierLink::getModifier)
                        .toList();

                Integer expectedScore = applyModifierMultiplier(score.getScoreNoMods(), modifiers);

                if (Objects.equals(score.getScore(), expectedScore)) {
                    continue;
                }

                if (correctScore(score, expectedScore, affectedByCategory, affectedDifficulties)) {
                    corrected++;
                }
            } catch (Exception e) {
                failed++;
                log.error("Pass 1 - Failed to correct score {}: {}", score.getId(), e.getMessage());
            }

            if ((i + 1) % 500 == 0) {
                log.info("Pass 1 progress: {}/{} checked, {} corrected so far",
                        i + 1, withModifiers.size(), corrected);
            }
        }

        log.info("Pass 1 done: {} corrected, {} failed out of {} checked", corrected, failed, withModifiers.size());
        
        int pass2Corrected = 0;
        int pass2Failed = 0;
        List<Score> inflatedNoLinks = scoreRepository.findActiveScoresInflatedWithoutModifierLinks();
        log.info("Pass 2: {} active scores inflated without modifier links", inflatedNoLinks.size());

        for (int i = 0; i < inflatedNoLinks.size(); i++) {
            Score score = inflatedNoLinks.get(i);
            try {
                if (correctScore(score, score.getScoreNoMods(), affectedByCategory, affectedDifficulties)) {
                    pass2Corrected++;
                }
            } catch (Exception e) {
                pass2Failed++;
                log.error("Pass 2 - Failed to correct score {}: {}", score.getId(), e.getMessage());
            }

            if ((i + 1) % 500 == 0) {
                log.info("Pass 2 progress: {}/{} checked, {} corrected so far",
                        i + 1, inflatedNoLinks.size(), pass2Corrected);
            }
        }

        corrected += pass2Corrected;
        failed += pass2Failed;
        log.info("Pass 2 done: {} corrected, {} failed out of {} checked",
                pass2Corrected, pass2Failed, inflatedNoLinks.size());

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

        log.info("Modifier score correction complete: {} total corrected, {} total failed", corrected, failed);
    }

    private boolean correctScore(Score score, Integer correctedScore,
            ConcurrentHashMap<UUID, Set<Long>> affectedByCategory, Set<UUID> affectedDifficulties) {
        Integer maxScore = score.getMapDifficulty().getMaxScore();
        if (maxScore == null || maxScore == 0) {
            log.warn("Score {} has no maxScore on difficulty - skipping", score.getId());
            return false;
        }

        BigDecimal accuracy = BigDecimal.valueOf(correctedScore)
                .divide(BigDecimal.valueOf(maxScore), ACCURACY_SCALE, RoundingMode.HALF_UP);
        BigDecimal complexity = mapComplexityService.findActiveComplexity(score.getMapDifficulty().getId())
                .orElse(null);
        if (complexity == null) {
            log.warn("Score {} has no active complexity - skipping", score.getId());
            return false;
        }

        APResult apResult = apCalculationService.calculateRawAP(
                accuracy, complexity, score.getMapDifficulty().getCategory().getScoreCurve());

        log.info("Correcting score {} (user {}): score {} -> {}, ap {} -> {}",
                score.getId(), score.getUser().getId(),
                score.getScore(), correctedScore, score.getAp(), apResult.rawAP());

        score.setScore(correctedScore);
        score.setAp(apResult.rawAP());
        scoreRepository.save(score);

        affectedByCategory.computeIfAbsent(
                score.getMapDifficulty().getCategory().getId(),
                k -> ConcurrentHashMap.newKeySet())
                .add(score.getUser().getId());
        affectedDifficulties.add(score.getMapDifficulty().getId());
        return true;
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
