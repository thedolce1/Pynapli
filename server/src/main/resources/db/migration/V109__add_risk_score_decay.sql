-- Risk score decay (TRUST_AND_SAFETY_SPEC "Recovery And Appeals", safety/RiskDecayPolicy.kt).
-- decayable_points: the part of score that came from minor, behavioural signals (rate-limit hits)
-- and decays with time. score - decayable_points never decays. Existing rows start at 0, so points
-- recorded before this migration keep today's behaviour and do not decay.
-- decay_anchor_at: when the decay clock for decayable_points started (advanced by whole decay
-- intervals as decay is applied); NULL while decayable_points is 0.
ALTER TABLE account_risk_states
    ADD COLUMN IF NOT EXISTS decayable_points INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS decay_anchor_at TIMESTAMPTZ;

ALTER TABLE account_risk_states
    ADD CONSTRAINT account_risk_states_decayable_points_check CHECK (decayable_points >= 0);
