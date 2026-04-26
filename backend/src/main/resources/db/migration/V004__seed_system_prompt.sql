-- V004: Seed the default system prompt into the config key-value table.
-- The config.value column stores JSON-in-TEXT; a JSON-encoded string is
-- the outer double-quoted value (e.g., '"hello\nworld"'). The content
-- below is the §9.3 default prompt from docs/ARCHITECTURE.md with real
-- newlines encoded as \n.
--
-- INSERT OR IGNORE keeps this idempotent for re-runs in dev / tests that
-- replay the migration set. Runtime updates (Phase 5) go through PUT
-- /api/config/system-prompt; future default changes ship as V005+.

INSERT OR IGNORE INTO config (key, value, updated_at)
VALUES (
    'system_prompt',
    '"You are an AOS Documentation Assistant. Your role is to help users find information \nin the AOS technical documentation.\n\nGuidelines:\n- Provide accurate, concise answers based on the documentation\n- Always cite your sources with document name and section\n- For troubleshooting codes (MA-XX), provide the full symptom, cause, and solution\n- If information is not available, clearly state that\n- Respond in German if the question is in German, otherwise in English\n- Format code and technical terms appropriately"',
    CURRENT_TIMESTAMP
);
