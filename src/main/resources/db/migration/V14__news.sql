CREATE TABLE news (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    staff_user_id   UUID NOT NULL REFERENCES staff_users(id),
    batch_id        UUID REFERENCES batches(id),
    campaign_id     UUID REFERENCES campaigns(id),
    milestone_set_id UUID REFERENCES milestone_sets(id),
    curve_id        UUID REFERENCES curves(id),
    title           VARCHAR(255) NOT NULL,
    slug            VARCHAR(255) NOT NULL UNIQUE,
    description     TEXT,
    content         TEXT NOT NULL,
    image_url       TEXT,
    status          VARCHAR(10) NOT NULL DEFAULT 'DRAFT',
    pinned          BOOLEAN NOT NULL DEFAULT false,
    published_at    TIMESTAMPTZ,
    active          BOOLEAN NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_news_status_published ON news (status, published_at DESC) WHERE active = true;
CREATE INDEX idx_news_pinned ON news (pinned, published_at DESC) WHERE active = true AND status = 'PUBLISHED';
CREATE INDEX idx_news_staff_user ON news (staff_user_id);
CREATE INDEX idx_news_slug ON news (slug) WHERE active = true;
