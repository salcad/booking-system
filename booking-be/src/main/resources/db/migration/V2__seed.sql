-- Synthetic seed data.
--
-- Covers the four cases the brief asks to be demonstrable:
--   1. a class with available seats            -> Science Trial (Mon), 0/4
--   2. a class with exactly 3 confirmed        -> Math Trial (Tue),    3/4
--   3. a duplicate booking attempt             -> Science Trial (Wed), Maya already booked
--   4. a payment failure case                  -> Noah (always_fails_payment)
--
-- Class start times are relative to now() so the data never goes stale.

INSERT INTO parents (id, name, email) VALUES
    (1, 'Priya Raman',   'priya@example.com'),
    (2, 'Daniel Okafor', 'daniel@example.com'),
    (3, 'Wei Ling Tan',  'weiling@example.com');

INSERT INTO students (id, parent_id, name, grade, always_fails_payment) VALUES
    (1, 1, 'Maya Raman',  'P4', FALSE),
    (2, 1, 'Arjun Raman', 'P3', FALSE),
    (3, 2, 'Noah Okafor', 'P5', TRUE),   -- payment always declines
    (4, 2, 'Zara Okafor', 'P2', FALSE),
    (5, 3, 'Jun Hao Tan', 'P4', FALSE);

INSERT INTO trial_classes (id, subject, starts_at, capacity, claimed_seats) VALUES
    -- Case 1: wide open.
    (1, 'Science - States of Matter', now() + INTERVAL '2 days', 4, 0),
    -- Case 2: THE RACE FIXTURE. One seat left, three already confirmed.
    (2, 'Math - Fractions',           now() + INTERVAL '3 days', 4, 3),
    -- Case 3: Maya already holds a live booking here.
    (3, 'Science - Simple Circuits',  now() + INTERVAL '4 days', 4, 1),
    -- Case 4: room for Noah's payment to fail against.
    (4, 'Math - Area and Perimeter',  now() + INTERVAL '5 days', 4, 2);

-- Class 2: three confirmed students. Leaves exactly one seat for the race demo.
INSERT INTO bookings (id, student_id, trial_class_id, status, hold_expires_at) VALUES
    (1, 2, 2, 'CONFIRMED', NULL),
    (2, 4, 2, 'CONFIRMED', NULL),
    (3, 5, 2, 'CONFIRMED', NULL),
    -- Class 3: Maya is already booked -> a second attempt must be rejected.
    (4, 1, 3, 'CONFIRMED', NULL),
    -- Class 4: two unrelated confirmed students.
    (5, 2, 4, 'CONFIRMED', NULL),
    (6, 5, 4, 'CONFIRMED', NULL);

INSERT INTO payment_attempts (booking_id, idempotency_key, amount_cents, status, provider_ref) VALUES
    (1, 'seed-1', 4900, 'SUCCEEDED', 'mock_seed_1'),
    (2, 'seed-2', 4900, 'SUCCEEDED', 'mock_seed_2'),
    (3, 'seed-3', 4900, 'SUCCEEDED', 'mock_seed_3'),
    (4, 'seed-4', 4900, 'SUCCEEDED', 'mock_seed_4'),
    (5, 'seed-5', 4900, 'SUCCEEDED', 'mock_seed_5'),
    (6, 'seed-6', 4900, 'SUCCEEDED', 'mock_seed_6');

INSERT INTO booking_events (booking_id, from_status, to_status, reason, actor) VALUES
    (1, NULL, 'CONFIRMED', 'seed data', 'system'),
    (2, NULL, 'CONFIRMED', 'seed data', 'system'),
    (3, NULL, 'CONFIRMED', 'seed data', 'system'),
    (4, NULL, 'CONFIRMED', 'seed data', 'system'),
    (5, NULL, 'CONFIRMED', 'seed data', 'system'),
    (6, NULL, 'CONFIRMED', 'seed data', 'system');

-- Explicit IDs above were inserted directly, so the sequences must be moved
-- past them or the first real insert will collide.
SELECT setval('parents_id_seq',          (SELECT max(id) FROM parents));
SELECT setval('students_id_seq',         (SELECT max(id) FROM students));
SELECT setval('trial_classes_id_seq',    (SELECT max(id) FROM trial_classes));
SELECT setval('bookings_id_seq',         (SELECT max(id) FROM bookings));
SELECT setval('payment_attempts_id_seq', (SELECT max(id) FROM payment_attempts));
SELECT setval('booking_events_id_seq',   (SELECT max(id) FROM booking_events));
