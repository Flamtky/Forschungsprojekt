-- Independent of the tracking outbox upload order. No videos or client filesystem paths.
CREATE TABLE replay_responses (
    recording_id UUID PRIMARY KEY,
    session_id UUID NOT NULL,
    participant_id UUID NOT NULL,
    study_id TEXT NOT NULL,
    plan JSONB NOT NULL,
    responses JSONB NOT NULL,
    submission_hash TEXT NOT NULL,
    receipt_id UUID NOT NULL UNIQUE,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX replay_responses_session ON replay_responses(session_id);

CREATE VIEW replay_ratings AS
SELECT r.session_id, r.participant_id, r.study_id, r.recording_id, r.received_at,
       clip->>'id' AS clip_id, clip->>'episode' AS episode,
       clip->>'risk' AS risk, clip->>'action' AS action,
       (clip->>'eventSequence')::bigint AS event_sequence,
       (clip->>'eventMs')::bigint AS event_ms,
       answer->'baseline' AS baseline,
       answer->'intervention' AS intervention,
       ((answer->'baseline'->>'frustrated')::numeric
        + (answer->'baseline'->>'irritated')::numeric
        + (answer->'baseline'->>'dissatisfied')::numeric) / 3 AS frustration_mean
FROM replay_responses r,
     LATERAL jsonb_array_elements(r.plan->'clips') clip,
     LATERAL jsonb_array_elements(r.responses->'answers') answer
WHERE clip->>'id' = answer->>'clipId';
