ALTER TABLE tracking_events
    DROP CONSTRAINT tracking_events_event_type_check,
    DROP CONSTRAINT tracking_events_check,
    DROP CONSTRAINT tracking_events_check1,
    DROP CONSTRAINT tracking_events_check2,
    DROP CONSTRAINT tracking_events_check3;

ALTER TABLE tracking_events
    ADD CONSTRAINT tracking_events_event_type_check CHECK (event_type IN (
        'PARTICIPANT_JOINED', 'PARTICIPANT_LEFT', 'PUZZLE_STARTED', 'ANSWER_SUBMITTED',
        'HINT_USED', 'PUZZLE_SOLVED', 'ROOM_EVENT')),
    ADD CONSTRAINT tracking_events_outcome_check CHECK (
        (event_type = 'ANSWER_SUBMITTED' AND outcome IN ('CORRECT', 'INCORRECT')
            AND object_id IS NOT NULL
            AND payload ? 'answer' AND payload -> 'answer' <> 'null'::jsonb
            AND jsonb_typeof(payload -> 'answerKind') = 'string'
            AND btrim(payload ->> 'answerKind') <> ''
            AND jsonb_typeof(payload -> 'attemptNumber') = 'number'
            AND payload ->> 'attemptNumber' ~ '^[1-9][0-9]*$')
        OR (event_type <> 'ANSWER_SUBMITTED' AND outcome IS NULL)),
    ADD CONSTRAINT tracking_events_hint_object_check CHECK (
        event_type <> 'HINT_USED' OR object_id IS NOT NULL),
    ADD CONSTRAINT tracking_events_puzzle_check CHECK (
        (event_type IN ('PUZZLE_STARTED', 'ANSWER_SUBMITTED', 'HINT_USED', 'PUZZLE_SOLVED',
            'ROOM_EVENT')) = (puzzle_id IS NOT NULL)),
    ADD CONSTRAINT tracking_events_participant_check CHECK (
        event_type = 'ROOM_EVENT'
        OR ((event_type IN ('PARTICIPANT_JOINED', 'PARTICIPANT_LEFT', 'ANSWER_SUBMITTED',
            'HINT_USED')) = (participant_id IS NOT NULL))),
    ADD CONSTRAINT tracking_events_room_object_check CHECK (
        event_type <> 'ROOM_EVENT' OR object_id IS NOT NULL);
