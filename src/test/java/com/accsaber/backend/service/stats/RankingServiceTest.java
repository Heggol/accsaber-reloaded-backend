package com.accsaber.backend.service.stats;

import static org.mockito.Mockito.verify;

import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.accsaber.backend.repository.user.UserCategoryStatisticsRepository;
import com.accsaber.backend.service.infra.CacheService;

@ExtendWith(MockitoExtension.class)
class RankingServiceTest {

        @Mock
        private UserCategoryStatisticsRepository statisticsRepository;

        @Mock
        private CacheService cacheService;

        @InjectMocks
        private RankingService rankingService;

        @Nested
        class UpdateRankings {

                @Test
                void assignsGlobalAndCountryRankings() {
                        UUID categoryId = UUID.randomUUID();

                        rankingService.updateRankings(categoryId);

                        verify(statisticsRepository).assignGlobalRankings(categoryId);
                        verify(statisticsRepository).assignCountryRankings(categoryId);
                }

                @Test
                void evictsLeaderboardCache() {
                        UUID categoryId = UUID.randomUUID();

                        rankingService.updateRankings(categoryId);

                        verify(cacheService).evictLeaderboard(categoryId);
                }
        }
}
