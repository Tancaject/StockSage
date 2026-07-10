ALTER TABLE users
    ADD COLUMN email VARCHAR(255),
    ADD COLUMN password_hash VARCHAR(100),
    ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE users
    ADD UNIQUE KEY uk_users_email (email);

UPDATE users
SET email='demo@stocksage.local',
    password_hash='$2a$10$yZIDB4.iYXaU80ulABqbjOl3JOwASXZhZUZMDd3qb13H64OYap6P2',
    email_verified=TRUE
WHERE user_id='u_001';
