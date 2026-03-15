package com.accsaber.backend.service.stats;

import java.util.UUID;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.accsaber.backend.repository.user.UserCategoryStatisticsRepository;
import com.accsaber.backend.service.infra.CacheService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RankingService {

    private final UserCategoryStatisticsRepository statisticsRepository;
    private final CacheService cacheService;

    @Async("rankingExecutor")
    @Transactional
    public void updateRankings(UUID categoryId) {
        statisticsRepository.assignGlobalRankings(categoryId);
        statisticsRepository.assignCountryRankings(categoryId);
        cacheService.evictLeaderboard(categoryId);
    }
}
