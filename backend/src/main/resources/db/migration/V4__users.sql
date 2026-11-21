-- Milestone 3: people who log in (ADR 0011). A row is created on first login.
-- Data minimisation: only the identity provider's issuer and subject (a pseudonymous ID) and the
-- user's own settings. No name, e-mail address or other profile data: those come from the ID token
-- when needed and are never stored.

CREATE TABLE app_users (
    id         uuid PRIMARY KEY,
    issuer     text        NOT NULL,
    subject    text        NOT NULL,
    -- BCP 47 language tag the user chose, e.g. 'nb'. NULL until they choose: the browser language
    -- and then English apply. Which tags are supported is decided by the app (the translation files),
    -- not by a check constraint, so a new language needs no migration.
    language   text,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version    bigint      NOT NULL DEFAULT 0,
    -- A person is identified by their identity provider and its subject, never by name or e-mail.
    CONSTRAINT app_users_identity_uq UNIQUE (issuer, subject),
    CONSTRAINT app_users_language_ck CHECK (language IS NULL OR length(language) BETWEEN 2 AND 35)
);
