ALTER TABLE milestones
    ADD COLUMN status VARCHAR(10) NOT NULL DEFAULT 'DRAFT';

UPDATE milestones SET status = 'ACTIVE' WHERE active = true;

CREATE INDEX idx_milestones_active_status ON milestones (active, status);
