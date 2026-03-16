INSERT INTO curves (id, name, type, scale, shift, formula,
                    x_parameter_name, x_parameter_value,
                    y_parameter_name, y_parameter_value,
                    z_parameter_name, z_parameter_value)
VALUES (
    'acc00000-0000-0000-0000-000000000004',
    'AccSaber Level Curve',
    'FORMULA',
    NULL, NULL,
    'POWER_FLOOR',
    'base', 52,
    'exponent', 1.2,
    NULL, NULL
);

DELETE FROM level_thresholds;
ALTER TABLE level_thresholds DROP COLUMN xp_required;

INSERT INTO level_thresholds (level, title) VALUES
    (0,   'Newcomer'),
    (10,  'Apprentice'),
    (20,  'Adept'),
    (30,  'Skilled'),
    (40,  'Expert'),
    (50,  'Master'),
    (60,  'Grandmaster'),
    (70,  'Legend'),
    (80,  'Transcendent'),
    (90,  'Mythic'),
    (100, 'Ascendant');
