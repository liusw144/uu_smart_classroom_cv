CREATE TABLE school_class (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    owner_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    grade VARCHAR(64) NOT NULL,
    student_count INT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_class_scope (tenant_id,id,owner_id),
    INDEX ix_class_owner (tenant_id,owner_id,id),
    FOREIGN KEY (tenant_id,owner_id) REFERENCES school_account(tenant_id,id),
    CHECK (student_count BETWEEN 0 AND 500)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE course (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    owner_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    class_id BIGINT NOT NULL,
    term VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_course_scope (tenant_id,id,owner_id),
    INDEX ix_course_owner (tenant_id,owner_id,id),
    FOREIGN KEY (tenant_id,class_id,owner_id) REFERENCES school_class(tenant_id,id,owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE lesson_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    owner_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    course_id BIGINT NOT NULL,
    scheduled_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_task_scope (tenant_id,id,owner_id),
    INDEX ix_task_owner (tenant_id,owner_id,id),
    FOREIGN KEY (tenant_id,course_id,owner_id) REFERENCES course(tenant_id,id,owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE VIEW lesson_task_view AS
SELECT t.*, c.class_id FROM lesson_task t
JOIN course c ON c.id=t.course_id AND c.tenant_id=t.tenant_id AND c.owner_id=t.owner_id;
