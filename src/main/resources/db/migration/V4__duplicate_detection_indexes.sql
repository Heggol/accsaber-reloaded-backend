CREATE INDEX idx_scores_map_difficulty_user ON scores(map_difficulty_id, user_id) WHERE active = true;
CREATE INDEX idx_scores_bl_score_id_user ON scores(user_id) WHERE bl_score_id IS NOT NULL;
