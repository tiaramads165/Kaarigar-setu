-- Development/test seed data only. Do not use these records as production data.

INSERT INTO workers (name, phone, trade, language, skill_score, trust_score)
VALUES
    ('Asha Kumari', '+910000000001', 'Tailoring', 'Hindi', 82.50, 70.00),
    ('Ravi Patel', '+910000000002', 'Carpentry', 'Gujarati', 76.00, 65.50);

INSERT INTO employers (name, phone, verified)
VALUES
    ('Setu Demo Employer', '+919999999999', TRUE);

