-- =========================================================
-- Keepia 認証メール補正・顧客・サービス・契約・営業時間関連
-- =========================================================


-- ---------------------------------------------------------
-- V2認証メール予約の機密データ消去条件を補正
-- ---------------------------------------------------------

ALTER TABLE auth_mail_jobs
	DROP CONSTRAINT chk_auth_mail_jobs_payload;
	
ALTER TABLE auth_mail_jobs
	ALTER COLUMN encrypted_payload DROP NOT NULL;
	
-- 既存の送信済み・取消済みデータがある場合も機密リンクを消去する
UPDATE auth_mail_jobs
SET encrypted_payload = NULL,
	updated_at = CURRENT_TIMESTAMP,
	version = version + 1
WHERE status IN ('SENT', 'CANCELLED');

ALTER TABLE auth_mail_jobs
	ADD CONSTRAINT chk_auth_mail_jobs_payload_lifecycle
	CHECK (
		(
			status IN (
				'PENDING',
				'SENDING',
				'FAILED',
				'UNKNOWN'
			)
			AND encrypted_payload IS NOT NULL
			AND OCTET_LENGTH(encrypted_payload) > 0
		)
		OR
		(
			status IN ('SENT', 'CANCELLED')
			AND encrypted_payload IS NULL
		)
	);
	
-- UUIDやsmallintをGiSTの等価条件で扱うために使用する
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- ---------------------------------------------------------
-- 顧客
-- ---------------------------------------------------------

CREATE TABLE customers (
	id UUID PRIMARY KEY,
	tenant_id UUID NOT NULL,
	name VARCHAR(100) NOT NULL,
	contact_email VARCHAR(254) NOT NULL,
	contact_phone VARCHAR(30),
	status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
	created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    
    CONSTRAINT fk_customers_tenant
    	FOREIGN KEY (tenant_id)
    	REFERENCES tenants(id)
    	ON DELETE RESTRICT,
    	
    -- 同じ保守会社内の複合外部キーで利用する
    CONSTRAINT uq_customers_tenant_id
    	UNIQUE (tenant_id, id),
    	
    CONSTRAINT chk_customers_name
    	CHECK (BTRIM(name) <> ''),
    	
    CONSTRAINT chk_customers_contact_emial
    	CHECK (
     		contact_email = LOWER(BTRIM(contact_email))
     		AND BTRIM(contact_email) <> ''
    	),
    	
    CONSTRAINT chk_customers_contact_phone
    	CHECK (
    		contact_phone IS NULL
    		OR BTRIM(contact_phone) <> ''
    	),
    	
    CONSTRAINT chk_customers_status
    	CHECK (status IN ('ACTIVE', 'STOPPED')),
    	
    CONSTRAINT chk_customers_version
    	CHECK (version >= 0)
 );
 
 CREATE INDEX idx_customers_tenant_status
 	ON customers (tenant_id, status);
 	
 CREATE INDEX idx_customers_tenant_name
 	ON customers (tenant_id, name);
 	
 -- V2ではcustomersが存在しなかったため保留していた外部キー
 
 ALTER TABLE app_users
 	ADD CONSTRAINT fk_app_users_customer
 	FOREIGN KEY (tenant_id, customer_id)
 	REFERENCES customers (tenant_id, id)
 	ON DELETE RESTRICT;
 	
 
 -- ---------------------------------------------------------
-- 対象サービス
-- ---------------------------------------------------------

CREATE TABLE services (
	id UUID PRIMARY KEY,
	tenant_id UUID NOT NULL,
	name VARCHAR(100) NOT NULL,
	status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
	created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    
    CONSTRAINT fk_services_tenant
    	FOREIGN KEY (tenant_id)
    	REFERENCES tenants (id)
    	ON DELETE RESTRICT,
    	
    -- 同じ保守会社内の複合外部キーで利用する
    CONSTRAINT uq_services_tenant_id
    	UNIQUE (tenant_id, id),
    	
    CONSTRAINT chk_services_name
    	CHECK (BTRIM(name) <> ''),
    	
    CONSTRAINT chk_services_status
    	CHECK (status IN ('ACTIVE', 'STOPPED')),
    	
    CONSTRAINT chk_services_version
    	CHECK (version >= 0)
    	
 );
 
 CREATE INDEX idx_services_tenant_status
 	ON services (tenant_id, status);
 	
 CREATE INDEX idx_services_tenant_name
 	ON services (tenant_id, name);
 	
 	
-- ---------------------------------------------------------
-- 営業時間・休業日の版
-- ---------------------------------------------------------

CREATE TABLE calendar_versions (
	id UUID PRIMARY KEY,
	tenant_id UUID NOT NULL,
	name VARCHAR(100) NOT NULL,
	revision INTEGER NOT NULL,
	zone_id VARCHAR(40) NOT NULL DEFAULT 'Asia/Tokyo',
	created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
	
	CONSTRAINT fk_calendar_versions_tenant
		FOREIGN KEY (tenant_id)
		REFERENCES tenants (id)
		ON DELETE RESTRICT,
		
	CONSTRAINT fk_calendar_versions_created_by
		FOREIGN KEY (tenant_id, created_by)
		REFERENCES app_users (tenant_id, id)
		ON DELETE RESTRICT,
		
	-- 後続テーブルの複合外部キーで利用する
	CONSTRAINT uq_calendar_versions_tenant_id
		UNIQUE (tenant_id, id),
		
	CONSTRAINT uq_calendar_versions_name_revision
		UNIQUE (tenant_id, name, revision),
		
	CONSTRAINT chk_calendar_versions_name
		CHECK (BTRIM(name) <> ''),
		
	CONSTRAINT chk_calendar_versions_revision
		CHECK (revision > 0),
		
	CONSTRAINT chk_calendar_versions_zone_id
		CHECK (BTRIM(zone_id) <> '')
		
);
	
	

-- ---------------------------------------------------------
-- 曜日別営業時間
-- ---------------------------------------------------------

CREATE TABLE business_hours (
	id UUID PRIMARY KEY,
	tenant_id UUID NOT NULL,
	calendar_version_id UUID NOT NULL,
	
	-- 1=月曜日、2火曜日、....7=日曜日
	weekday SMALLINT NOT NULL,
	opens_at TIME WITHOUT TIME ZONE NOT NULL,
    closes_at TIME WITHOUT TIME ZONE NOT NULL,
    
    CONSTRAINT fk_business_hours_tenant
    	FOREIGN KEY (tenant_id)
    	REFERENCES tenants (id)
    	ON DELETE RESTRICT,
    	
    CONSTRAINT fk_business_hours_calendar
    	FOREIGN KEY (tenant_id, calendar_version_id)
    	REFERENCES calendar_versions (tenant_id, id)
    	ON DELETE RESTRICT,
    	
    CONSTRAINT uq_business_hours_tenant_id
    	UNIQUE (tenant_id, id),
    	
    CONSTRAINT chk_business_hours_weekday
    	CHECK (weekday BETWEEN 1 AND 7),
    	
    CONSTRAINT chk_business_hours_time
    	CHECK (opens_at < closes_at),
    	
    -- 同じカレンダー版・同じ曜日で営業時間帯を重複させない
    -- [)なので09:00～12:00と12:00～18:00は隣接可能
    
    CONSTRAINT ex_businnes_hours_no_overlap
    	EXCLUDE USING gist (
    		tenant_id WITH =,
    		calendar_version_id WITH =,
    		weekday WITH =,
    		tsrange(
    			DATE '2000-01-01' + opens_at,
    			DATE '2000-01-01' + closes_at,
    			'[)'
    		) WITH &&
    	)
);

CREATE INDEX idx_business_hours_calendar_weekday
	ON business_hours (
		tenant_id,
		calendar_version_id,
		weekday
	);
	
	
	
-- ---------------------------------------------------------
-- 休業日
-- ---------------------------------------------------------

CREATE TABLE holidays (
	id UUID PRIMARY KEY,
	tenant_id UUID NOT NULL,
	calendar_version_id UUID NOT NULL,
	holiday_on DATE NOT NULL,
	name VARCHAR(100),
	
	CONSTRAINT fk_holidays_tenant
		FOREIGN KEY (tenant_id)
		REFERENCES tenants (id)
		ON DELETE RESTRICT,
	
	CONSTRAINT uq_holidays_tenant_id
		UNIQUE (tenant_id, id),
		
	CONSTRAINT uq_holidays_calendar_date
		UNIQUE (
			tenant_id,
			calendar_version_id,
			holiday_on
		),
		
	CONSTRAINT chk_holidays_name
		CHECK (
			name IS NULL
			OR BTRIM(name) <> ''
	)
);	



-- ---------------------------------------------------------
-- 契約
-- ---------------------------------------------------------

CREATE TABLE contracts (
	id UUID PRIMARY KEY,
	tenant_id UUID NOT NULL,
	customer_id UUID NOT NULL,
	service_id UUID NOT NULL,
	starts_on DATE NOT NULL,
	ends_on DATE,
	status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
	
	-- 現在利用する契約条件
	current_version_id UUID NOT NULL,
	created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    
    CONSTRAINT fk_contracts_tenant
    	FOREIGN KEY (tenant_id)
    	REFERENCES tenants (id)
    	ON DELETE RESTRICT,
    	
    CONSTRAINT fk_contracts_customer
    	FOREIGN KEY (tenant_id, customer_id)
    	REFERENCES customers (tenant_id, id)
    	ON DELETE RESTRICT,
    	
    CONSTRAINT fk_contracts_service
    	FOREIGN KEY (tenant_id, service_id)
    	REFERENCES services (tenant_id, id)
    	ON DELETE RESTRICT,
    	
    -- contract_versionsから同じ保守会社の契約を参照する
    
    CONSTRAINT uq_contracts_tenant_id
    	UNIQUE (tenant_id, id),
    	
    CONSTRAINT chk_contracts_period
    	CHECK (
    		ends_on IS NULL
    		OR ends_on >= starts_on
    	),
    	
    CONSTRAINT chk_contracts_status
    	CHECK (status IN ('ACTIVE', 'STOPPED')),
    	
    CONSTRAINT chk_contracts_version
    	CHECK (version >= 0),
    	
    -- 同じ保守会社・顧客・サービスの有効契約期間を重複させない
    -- 終了日は契約期間に含む。
    -- ends_on = NULL は無期限として扱う。
    
    CONSTRAINT ex_contracts_active_period
    	EXCLUDE USING gist (
    		tenant_id WITH =,
    		customer_id WITH =,
    		service_id WITH =,
    		daterange(
    			starts_on,
    			ends_on,
    			'[]'
    		)WITH &&
    	)
    	WHERE (status = 'ACTIVE')
   );
   
CREATE INDEX idx_contracts_tenant_customer_status
	ON contracts (
		tenant_id,
		customer_id,
		status
	);
	
CREATE INDEX idx_contracts_tenant_service_status
	ON contracts (
		tenant_id,
		service_id,
		status
	);
	
	

-- ---------------------------------------------------------
-- 契約条件
-- ---------------------------------------------------------
	
CREATE TABLE contract_versions (
	id UUID PRIMARY KEY,
	tenant_id UUID NOT NULL,
	contract_id UUID NOT NULL,
	revision INTEGER NOT NULL,
	
	-- 受付時に固定参照する契約期間のスナップショット
	starts_on DATE NOT NULL,
	ends_on DATE,
	
	-- 初回返信までの目標時間(分)
	target_minutes INTEGER NOT NULL,
	calendar_version_id UUID NOT NULL,
	created_by UUID NOT NULL,
	created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
	
	CONSTRAINT fk_contract_versions_tenant
		FOREIGN KEY (tenant_id)
		REFERENCES tenants (id)
		ON DELETE RESTRICT,
		
	CONSTRAINT fk_contract_versions_contract
		FOREIGN KEY (tenant_id, contract_id)
		REFERENCES contracts (tenant_id, id)
		ON DELETE RESTRICT
		DEFERRABLE INITIALLY DEFERRED,
		
	CONSTRAINT fk_contract_versions_calendar
		FOREIGN KEY (tenant_id, calendar_version_id)
		REFERENCES calendar_versions (tenant_id, id)
		ON DELETE RESTRICT,
		
	CONSTRAINT fk_contract_versions_created_by
		FOREIGN KEY (tenant_id, created_by)
		REFERENCES app_users (tenant_id, id)
		ON DELETE RESTRICT,
		
	-- V4以降の複合外部キーで利用する
	
	CONSTRAINT uq_contract_versions_tenant_id
		UNIQUE (tenant_id, id),
		
	-- contracts.current_version_idが同じ契約の時だけを参照するために利用する
	
	CONSTRAINT uq_contract_versions_tenant_contract_id
		UNIQUE (tenant_id, contract_id, id),
		
	CONSTRAINT uq_contract_versions_contract_revision
		UNIQUE (tenant_id, contract_id, revision),
		
	CONSTRAINT chk_contract_versions_revision
		CHECK (revision > 0),
		
	CONSTRAINT chk_contract_versions_period
		CHECK (
			ends_on IS NULL
			OR ends_on >= starts_on
		),
		
	CONSTRAINT chk_contrat_versions_target_minutes
		CHECK (target_minutes > 0)
);
	

-- contractsとcontract_versionsは相互参照になるため、contract_versions作成後に外部キーを追加する
-- 検査をCOMMIT時まで遅延させることで、同一トランザクション内で両方を登録できる

ALTER TABLE contracts
	ADD CONSTRAINT fk_contracts_current_version
	FOREIGN KEY (
		tenant_id,
		id,
		current_version_id
	)
	REFERENCES contract_versions (
		tenant_id,
		contract_id,
		id
	)
	ON DELETE RESTRICT
	DEFERRABLE INITIALLY DEFERRED;
	
	
-- ---------------------------------------------------------
-- 版管理テーブルの変更・削除禁止
-- ---------------------------------------------------------

CREATE FUNCTION keepia_reject_immutable_version_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $function$
BEGIN
	RAISE EXCEPTION
		'%の既存行は変更または削除できません。新しい版(バージョン)を追加してください。',
		TG_TABLE_NAME
		USING ERRCODE = '55000';
	RETURN OLD;
END;
$function$;

CREATE TRIGGER trg_calendar_versions_immutable
	BEFORE UPDATE OR DELETE ON calendar_versions
	FOR EACH ROW
	EXECUTE FUNCTION keepia_reject_immutable_version_change();
	
CREATE TRIGGER trg_calendar_versions_no_truncate
	BEFORE TRUNCATE ON calendar_versions
	FOR EACH STATEMENT
	EXECUTE FUNCTION keepia_reject_immutable_version_change();
	
CREATE TRIGGER trg_business_hours_immtable
	BEFORE UPDATE OR DELETE ON business_hours
	FOR EACH ROW
	EXECUTE FUNCTION keepia_reject_immutable_version_change();
	
CREATE TRIGGER trg_business_hours_no_truncate
	BEFORE TRUNCATE ON business_hours
	FOR EACH STATEMENT
	EXECUTE FUNCTION keepia_reject_immutable_version_change();
	
CREATE TRIGGER trg_holidays_immutable
	BEFORE UPDATE OR DELETE ON holidays
	FOR EACH ROW
	EXECUTE FUNCTION keepia_reject_immutable_version_change();
	
CREATE TRIGGER trg_holidays_truncate
	BEFORE TRUNCATE ON holidays
	FOR EACH STATEMENT
	EXECUTE FUNCTION keepia_reject_immutable_version_change();
	
CREATE TRIGGER trg_contract_versions_immutable
	BEFORE UPDATE OR DELETE ON contract_versions
	FOR EACH ROW
	EXECUTE FUNCTION keepia_reject_immutable_version_change();
	
CREATE TRIGGER trg_contract_versions_no_truncate
	BEFORE TRUNCATE ON contract_versions
	FOR EACH STATEMENT
	EXECUTE FUNCTION keepia_reject_immutable_version_change();
    








