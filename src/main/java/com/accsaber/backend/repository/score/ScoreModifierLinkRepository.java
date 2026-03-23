package com.accsaber.backend.repository.score;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.accsaber.backend.model.entity.score.ScoreModifierLink;

public interface ScoreModifierLinkRepository extends JpaRepository<ScoreModifierLink, UUID> {

    List<ScoreModifierLink> findByScore_Id(UUID scoreId);

    @Query("SELECT sml FROM ScoreModifierLink sml JOIN FETCH sml.modifier WHERE sml.score.id = :scoreId")
    List<ScoreModifierLink> findByScoreIdWithModifier(@Param("scoreId") UUID scoreId);
}
