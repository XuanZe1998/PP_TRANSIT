ALTER TABLE model_identity_aliases
    ADD COLUMN explicit_override BOOLEAN NOT NULL DEFAULT FALSE;
