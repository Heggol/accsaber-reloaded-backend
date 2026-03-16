package com.accsaber.backend.repository.user;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.accsaber.backend.model.entity.user.User;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByIdAndActiveTrue(Long id);

    List<User> findByActiveTrue();

    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.totalXp = u.totalXp + :xp WHERE u.id = :id")
    void addXp(@Param("id") Long id, @Param("xp") BigDecimal xp);

    @Modifying
    @Transactional
    @Query(value = """
            UPDATE users u SET total_xp =
                COALESCE((SELECT SUM(s.xp_gained) FROM scores s WHERE s.user_id = u.id), 0)
                + COALESCE((SELECT SUM(m.xp) FROM user_milestone_links uml JOIN milestones m ON uml.milestone_id = m.id WHERE uml.user_id = u.id AND uml.completed = true), 0)
                + COALESCE((SELECT SUM(ms.set_bonus_xp) FROM user_milestone_set_bonuses umsb JOIN milestone_sets ms ON umsb.milestone_set_id = ms.id WHERE umsb.user_id = u.id), 0),
            updated_at = NOW()
            WHERE u.active = true
            """, nativeQuery = true)
    void recalculateTotalXpForAllActiveUsers();
}
