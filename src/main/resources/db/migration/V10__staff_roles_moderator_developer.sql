ALTER TABLE staff_users
    DROP CONSTRAINT IF EXISTS staff_users_role_check;

ALTER TABLE staff_users
    ADD CONSTRAINT staff_users_role_check
        CHECK (role IN ('moderator', 'ranking', 'ranking_head', 'developer', 'admin'));
