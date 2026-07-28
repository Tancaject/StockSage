ALTER TABLE investment_report_versions
    ADD COLUMN review_status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    ADD COLUMN reviewer_user_id VARCHAR(32) NULL,
    ADD COLUMN review_comment TEXT NULL,
    ADD COLUMN reviewed_at DATETIME NULL,
    ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    ADD COLUMN lock_version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE investment_report_reviews (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    report_version_id BIGINT NOT NULL,
    user_id           VARCHAR(32) NOT NULL,
    from_status       VARCHAR(32) NOT NULL,
    to_status         VARCHAR(32) NOT NULL,
    comment           TEXT NULL,
    created_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_report_review_version (report_version_id),
    INDEX idx_report_review_user (user_id)
) ENGINE=InnoDB;
