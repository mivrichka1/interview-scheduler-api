CREATE INDEX slots_recruiter_schedule_idx ON slots(recruiter_id, starts_at, ends_at);
CREATE INDEX slots_candidate_schedule_idx ON slots(candidate_id, starts_at, ends_at);
