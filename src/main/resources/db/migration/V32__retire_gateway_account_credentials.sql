-- AiAPIBank group discovery now uses the unauthenticated model plaza only.
-- Remove obsolete browser-session credentials so they cannot be used accidentally.
DELETE FROM gateway_account_credentials;
