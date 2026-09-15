-- =========================================================
-- Keepia 利用者・認証関連テーブル
-- =========================================================

-- ---------------------------------------------------------
-- 利用者
-- ---------------------------------------------------------

CREATE TABLE app_users (
	id UUID PRIMARY KEY,
	tenant_id UUID,
	customer_id UUID,
	email VARCHAR(254) NOT NULL,
	display_name VARCHAR(100) NOT NULL,
	role VARCHAR(16) NOT NULL,
	status VARCHAR(16) NOT NULL DEFAULT 'INVITED',
	password_hash VARCHAR(255),
	security_version BIGINT NOT NULL DEFAULT 0,
	created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    
    CONSTRAINT fk_app_users_tenant
    	FOREIGN KEY (tenant_id)
    	REFERENCES tenants (id)
    	ON DELETE RESTRICT,
    	
    CONSTRAINT uq_app_users_email
    	UNIQUE (email),
    	
    -- 後続テーブルの複合外部キーで利用する
    CONSTRAINT uq_app_users_tenant_id
    	UNIQUE (tenant_id, id),
    	
    CONSTRAINT uq_app_users_tenant_customer_id
    	UNIQUE (tenant_id, customer_id, id),
    	
    CONSTRAINT chk_app_users_role
    	CHECK (role IN ('OPERATOR', 'ADMIN', 'STAFF', 'CUSTOMER')),
    	
    CONSTRAINT chk_app_users_status
    	CHECK (status IN ('INVITED', 'ACTIVE', 'STOPPED')),
    	
    CONSTRAINT chk_app_users_assignment
    	CHECK (
    		(
    			role = 'OPERATOR'
    			AND tenant_id IS NULL
    			AND customer_id IS NULL
    		)
    		OR
    		(
    			role IN ('ADMIN', 'STAFF')
    			AND tenant_id IS NOT NULL
    			AND customer_id IS NULL
    		)
    		OR
    		(
    			role = 'CUSTOMER'
    			AND tenant_id IS NOT NULL
    			AND customer_id IS NOT NULL
    		)
    	),
    	
    CONSTRAINT chk_app_users_active_password
    	CHECK (
    		status <> 'ACTIVE'
    		OR password_hash IS NOT NULL
    	),
    	
    CONSTRAINT chk_app_users_email_normalized
    	CHECK (
    		email = LOWER(BTRIM(email))
    		AND BTRIM(email) <> ''
    	),
    	
    CONSTRAINT chk_app_users_display_name
    	CHECK (BTRIM(display_name) <> ''),
    	
    CONSTRAINT chk_app_users_security_version
    	CHECK (security_version >= 0),
    	
    CONSTRAINT chk_app_users_version
    	CHECK (version >= 0)
 );
 
 CREATE INDEX idx_app_users_tenant_status
 	ON app_users (tenant_id, status);
 	
 CREATE INDEX idx_app_users_tenant_role
 	ON app_users (tenant_id, role);
 
 CREATE INDEX idx_app_users_tenant_customer
 	ON app_users (tenant_id, customer_id);
    	
    	
-- ---------------------------------------------------------
-- 招待・パスワード再設定トークン
-- --------------------------------------------------------- 	

CREATE TABLE user_tokens (
	id UUID PRIMARY KEY,
	user_id UUID NOT NULL,
	token_hash CHAR(64) NOT NULL,
	purpose VARCHAR(16) NOT NULL,
	expires_at TIMESTAMPTZ NOT NULL,
	used_at TIMESTAMPTZ,
	created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
	
	CONSTRAINT fk_user_tokens_user
		FOREIGN KEY (user_id)
		REFERENCES app_users (id)
		ON DELETE RESTRICT,
		
	CONSTRAINT uq_user_tokens_token_hash
		UNIQUE (token_hash),
		
	-- auth_mail_jobsの複合外部キーで利用する
	CONSTRAINT uq_user_tokens_user_id
		UNIQUE (user_id, id),
		
	CONSTRAINT chk_user_tokens_purpose
		CHECK (purpose IN ('INVITE', 'RESET')),
		
	CONSTRAINT chk_user_tokens_hash
		CHECK (token_hash ~ '^[0-9a-f]{64}$'),
		
	CONSTRAINT chk_user_tokens_expiration
		CHECK (expires_at > created_at),
		
	CONSTRAINT chk_user_tokens_used_at
		CHECK (
			used_at IS NULL
			OR used_at >= created_at
		)
);

CREATE INDEX idx_user_tokens_user_purpose
	ON user_tokens (user_id, purpose, expires_at)
	WHERE used_at IS NULL;
	
	
-- ---------------------------------------------------------
-- 招待・パスワード再設定メール送信予約
-- ---------------------------------------------------------

CREATE TABLE auth_mail_jobs (
	id UUID PRIMARY KEY,
	user_id UUID NOT NULL,
	token_id UUID NOT NULL,
	encrypted_payload BYTEA NOT NULL,
	status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
	attempt_count INTEGER NOT NULL DEFAULT 0,
	next_attempt_at TIMESTAMPTZ NOT NULL,
	expires_at TIMESTAMPTZ NOT NULL,
	created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    
    CONSTRAINT fk_auth_mail_jobs_user
    	FOREIGN KEY (user_id)
    	REFERENCES app_users (id)
    	ON DELETE RESTRICT,
    	
    -- 指定されたトークンが同じ利用者のものかDBでも保証する
    CONSTRAINT fk_auth_mail_jobs_token
    	FOREIGN KEY (user_id, token_id)
    	REFERENCES user_tokens (user_id, id)
    	ON DELETE RESTRICT,
    	
    CONSTRAINT chk_auth_mail_jobs_status
    	CHECK (
    		status IN (
    			'PENDING',
    			'SENDING',
    			'SENT',
    			'FAILED',
    			'UNKNOWN',
    			'CANCELLED'
    		)
    	),
    	
    CONSTRAINT chk_auth_mail_jobs_attempt_count
    	CHECK (attempt_count >= 0),
    	
    CONSTRAINT chk_auth_mail_jobs_payload
    	CHECK (OCTET_LENGTH(encrypted_payload) > 0),
    	
    CONSTRAINT chk_auth_mail_jobs_expiration
    	CHECK (expires_at > created_at),
    	
    CONSTRAINT chk_auth_mail_jobs_next_attempt
    	CHECK (next_attempt_at <= expires_at),
    	
    CONSTRAINT chk_auth_mail_jobs_version
    	CHECK (version >= 0)
 );	
    
CREATE INDEX idx_auth_mail_jobs_pending
	ON auth_mail_jobs (next_attempt_at, id)
	WHERE status = 'PENDING';
	
CREATE INDEX idx_auth_mail_jobs_user
	ON auth_mail_jobs (user_id);
    	
    	
-- ---------------------------------------------------------
-- ログイン試行回数・一時制限
-- ---------------------------------------------------------

CREATE TABLE login_rate_limits (
	id UUID PRIMARY KEY,
	
	-- メールアドレスやIPアドレスそのものは保存しない
	key_hash CHAR(64) NOT NULL,
	failure_count INTEGER NOT NULL DEFAULT 0,
	window_started_at TIMESTAMPTZ NOT NULL,
	blocked_until TIMESTAMPTZ,
	created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    
    CONSTRAINT uq_login_rate_limits_key_hash
    	UNIQUE (key_hash),
    	
    CONSTRAINT chk_login_rate_limits_key_hash
    	CHECK (key_hash ~ '^[0-9a-f]{64}$'),
    	
    CONSTRAINT chk_login_rate_limits_failure_count
    	CHECK (failure_count >= 0),
    	
    CONSTRAINT chk_login_rate_limits_blocked_until
    	CHECK (
    		blocked_until IS NULL
    		OR blocked_until >= window_started_at
    	),
    	
    CONSTRAINT chk_login_rate_limits_version
    	CHECK (version >= 0)
);

CREATE INDEX idx_login_rate_limits_blocked
	ON login_rate_limits (blocked_until)
	WHERE blocked_until IS NOT NULL;
    	
    	
    	
    	
    	
    	
    	
    	
    	
    	
    	
    	
    	
    	