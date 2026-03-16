package com.accsaber.backend.service.milestone;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.accsaber.backend.model.dto.response.milestone.LevelResponse;
import com.accsaber.backend.model.entity.Curve;
import com.accsaber.backend.model.entity.milestone.LevelThreshold;
import com.accsaber.backend.repository.CurveRepository;
import com.accsaber.backend.repository.milestone.LevelThresholdRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LevelService {

    private static final UUID LEVEL_CURVE_ID = UUID.fromString("acc00000-0000-0000-0000-000000000004");

    private final LevelThresholdRepository levelThresholdRepository;
    private final CurveRepository curveRepository;

    private volatile Curve cachedLevelCurve;
    private final Object curveLock = new Object();

    public LevelResponse calculateLevel(BigDecimal totalXp) {
        if (totalXp == null || totalXp.compareTo(BigDecimal.ZERO) <= 0) {
            return LevelResponse.builder()
                    .level(0)
                    .title(null)
                    .totalXp(BigDecimal.ZERO)
                    .xpForCurrentLevel(BigDecimal.ZERO)
                    .xpForNextLevel(xpForLevel(1))
                    .progressPercent(BigDecimal.ZERO)
                    .build();
        }

        int level = 0;
        BigDecimal cumulative = BigDecimal.ZERO;

        while (true) {
            int nextLevel = level + 1;
            BigDecimal xpNeeded = xpForLevel(nextLevel);
            BigDecimal nextCumulative = cumulative.add(xpNeeded);
            if (nextCumulative.compareTo(totalXp) > 0) {
                break;
            }
            cumulative = nextCumulative;
            level = nextLevel;
        }

        BigDecimal xpIntoCurrentLevel = totalXp.subtract(cumulative);
        BigDecimal xpNeededForNext = xpForLevel(level + 1);
        BigDecimal progress = xpNeededForNext.compareTo(BigDecimal.ZERO) > 0
                ? xpIntoCurrentLevel.multiply(BigDecimal.valueOf(100))
                        .divide(xpNeededForNext, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        String title = levelThresholdRepository.findHighestTitleAtOrBelow(level)
                .map(LevelThreshold::getTitle)
                .orElse(null);

        return LevelResponse.builder()
                .level(level)
                .title(title)
                .totalXp(totalXp)
                .xpForCurrentLevel(xpIntoCurrentLevel)
                .xpForNextLevel(xpNeededForNext)
                .progressPercent(progress)
                .build();
    }

    public BigDecimal xpForLevel(int n) {
        if (n <= 0) {
            return BigDecimal.ZERO;
        }
        Curve curve = getLevelCurve();
        double base = curve.getXParameterValue().doubleValue();
        double exponent = curve.getYParameterValue().doubleValue();
        int effectiveN = Math.min(n, 100);
        return BigDecimal.valueOf(Math.floor(base * Math.pow(effectiveN, exponent)))
                .setScale(0, RoundingMode.UNNECESSARY);
    }

    public List<LevelThreshold> getAllThresholds() {
        return levelThresholdRepository.findAllByOrderByLevelAsc();
    }

    public void evictLevelCurveCache() {
        cachedLevelCurve = null;
    }

    private Curve getLevelCurve() {
        Curve curve = cachedLevelCurve;
        if (curve == null) {
            synchronized (curveLock) {
                curve = cachedLevelCurve;
                if (curve == null) {
                    curve = curveRepository.findById(LEVEL_CURVE_ID)
                            .orElseThrow(() -> new IllegalStateException("Level curve not found"));
                    cachedLevelCurve = curve;
                }
            }
        }
        return curve;
    }
}
