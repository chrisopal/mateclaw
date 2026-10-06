-- Original execution package, appended atomically with task/project/operation receipt.
-- No historical backfill: original bytes cannot be inferred from the currently installed skill.
CREATE TABLE mate_presales_task_package (
 task_id VARCHAR(64) PRIMARY KEY,
 workspace_id VARCHAR(64) NOT NULL, project_id VARCHAR(64) NOT NULL,
 run_id VARCHAR(64) NOT NULL, actor_id VARCHAR(64) NOT NULL,
 employee_id VARCHAR(64) NOT NULL, package_digest VARCHAR(64) NOT NULL,
 body_json LONGTEXT NOT NULL,
 UNIQUE(workspace_id,project_id,run_id)
);
