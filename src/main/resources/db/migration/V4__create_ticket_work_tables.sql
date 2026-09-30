-- Keepia V4案: 問い合わせ、投稿、添付、作業記録、変更履歴
-- V1～V3が適用済みのDBを前提とする。
-- このファイルは設計案。既存のFlyway配置先には追加していない。

-- V3で作成したholidaysに、同一テナント内のカレンダー版への参照を追加する。

ALTER TABLE holidays
	ADD CONSTRAINT fk_holidays_calendar
	FOREIGN KEY (tenant_id, calendar_version_id)
	REFERENCES calendar_versions (tenant_id, id)
	ON DELETE RESTRICT;
	
-- 受付番号はDBのシーケンスから欠番する。欠番は許容する。

CREATE SEQUENCE ticket_number_seq AS BIGINT START WITH 1 INCREMENT BY 1;

-- =========================================================
-- 問い合わせ
-- =========================================================

CREATE TABLE tickets (
	id UUID PRIMARY KEY,
	tenant_id UUID NOT NULL,
	customer_id UUID NOT NULL,
	service_id UUID NOT NULL,
	ticket_number VARCHAR(40) NOT NULL
		DEFAULT ('K-' || nextval('ticket_number_seq')::TEXT),
	subject VARCHAR(200) NOT NULL,
	body TEXT NOT NULL,
	category VARCHAR(16) NOT NULL,
	created_by UUID NOT NULL,
	contact_user_id UUID NOT NULL,
	assignee_id UUID,
	status VARCHAR(24) NOT NULL DEFAULT 'NEW',
	received_at TIMESTAMPTZ NOT NULL,
	in_scope BOOLEAN NOT NULL,
	contract_version_id UUID,
	calendar_version_id UUID,
	target_minutes INTEGER,
	first_reply_due_at TIMESTAMPTZ,
	first_reply_post_id UUID,
	first_reply_at TIMESTAMPTZ,
	created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    
    CONSTRAINT fk_tickets_tenant
    	FOREIGN KEY (tenant_id)
    	REFERENCES tenants (id)
    	ON DELETE RESTRICT,
    	
    CONSTRAINT fk_ticket_customer
    	FOREIGN KEY (tenant_id, customer_id)
    	REFERENCES customers (tenant_id, id)
    	ON DELETE RESTRICT,
    	
    CONSTRAINT fk_tichets_servcvice
    	FOREIGN KEY (tenant_id, service_id)
    	REFERENCES services (tenant_id, id)
    	ON DELETE RESTRICT,
    	
    CONSTRAINT fk_tickets_created_by
    	FOREIGN KEY (tenant_id, created_by)
    	REFERENCES app_users (tenant_id, id)
    	ON DELETE RESTRICT,
    	
    CONSTRAINT fk_tickets_contact_user
    	FOREIGN KEY (tenant_id, customer_id, contact_user_id)
    	REFERENCES app_users (tenant_id, customer_id, id)
    	ON DELETE RESTRICT,
    	
    CONSTRAINT fk_tickets_assignee
    	FOREIGN KEY (tenant_id, assignee_id)
    	REFERENCES app_users (tenant_id, id)
    	ON DELETE RESTRICT,
    	
    CONSTRAINT fk_tickets_contract_version
    	FOREIGN KEY (tenant_id, contract_version_id)
    	REFERENCES contract_versions (tenant_id, id)
    	ON DELETE RESTRICT,
    	
    CONSTRAINT fk_tickets_calendar_version
    	FOREIGN KEY (tenant_id, calendar_version_id)
    	REFERENCES calendar_versions (tenant_id, id)
    	ON DELETE RESTRICT,
    	
    CONSTRAINT uq_tickets_tenant_number
    	UNIQUE (tenant_id, ticket_number),
    	
    CONSTRAINT uq_tickets_tenant_id
    	UNIQUE (tenant_id, id),
    
    CONSTRAINT chk_tickets_number
    	CHECK (BTRIM(ticket_number) <> ''),
    	
    CONSTRAINT chk_tickets_subject
    	CHECK (BTRIM(subject) <> ''),
    	
    CONSTRAINT chk_tickets_body
    	CHECK (CHAR_LENGTH(body) BETWEEN 1 AND 10000 AND BTRIM(body) <> ''),
    	
    CONSTRAINT chk_tickets_category
    	CHECK (category IN ('QUESTION', 'INCIDENT')),
    	
    CONSTRAINT chk_tickets_status
    	CHECK (status IN (
    		'NEW', 'IN_PROGRESS', 'WAITING_CUSTOMER', 'RESOLVED', 'CLOSED'
    	)),
    	
    CONSTRAINT chk_tickets_scope_snapshot
    	CHECK (
    		(
    			in_scope
    			AND contract_version_id IS NOT NULL
    			AND calendar_version_id IS NOT NULL
    			AND target_minutes IS NOT NULL
    			AND target_minutes > 0
    			AND first_reply_due_at IS NOT NULL
    		)
    		OR
    		(
    			NOT in_scope
    			AND contract_version_id IS NULL
    			AND calendar_version_id IS NULL
    			AND target_minutes IS NULL
    			AND first_reply_due_at IS NULL
    		)
    	),
    	
    CONSTRAINT chk_tickets_first_reply_pair
    	CHECK (
    		(first_reply_post_id IS NULL AND first_reply_at IS NULL)
    		OR
    		(first_reply_post_id IS NOT NULL AND first_reply_at IS NOT NULL)
    	),
    	
    CONSTRAINT chk_tickets_first_reply_time
    	CHECK (first_reply_at IS NULL OR first_reply_at >= received_at),
    	
    CONSTRAINT chk_tickets_resolved_has_reply
    	CHECK (
    		status NOT IN ('RESOLVED', 'CLOSED')
    		OR first_reply_post_id IS NOT NULL
    	),
    CONSTRAINT chk_tickets_version
    	CHECK (version >= 0)
    	
);

CREATE INDEX idx_tickets_tenant_customer_received
	ON tickets (tenant_id, customer_id, received_at DESC, id);
	
CREATE INDEX idx_tickets_tenant_status_due
	ON tickets (tenant_id, status, first_reply_due_at, id);

CREATE INDEX idx_tickets_tenant_assignee_status
	ON tickets (tenant_id, assignee_id, status);
	
	
-- 受付時に選んだ契約版が、同じ顧客・サービスの現在版かを検査する。
-- 期限計算自体はアプリケーションが行う。

CREATE FUNCTION keepia_validate_ticket_contract()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $function$
DECLARE
	v_customer_id UUID;
	v_service_id UUID;
	v_status VARCHAR(16);
	v_starts_on DATE;
	v_ends_on DATE;
	v_current_version_id UUID;
	v_calendar_version_id UUID;
	v_target_minutes INTEGER;
	v_received_on DATE;
BEGIN
	IF NEW.status <> 'NEW'
		OR NEW.assignee_id IS NOT NULL
		OR NEW.first_reply_post_id IS NOT NULL
		OR NEW.first_reply_at IS NOT NULL THEN
			RAISE EXCEPTION '受付時は新規・未割当・初回返信なしで登録してください'
				USING ERRCODE = '23514';
	END IF;
	
	IF NOT NEW.in_scope THEN
		RETURN NEW;
	END IF;
	
	SELECT
		c.customer_id,
		c.service_id,
		c.status,
		c.starts_on,
		c.ends_on,
		c.current_version_id,
		cv.calendar_version_id,
		cv.target_minutes
	INTO
		v_customer_id,
		v_service_id,
		v_status,
		v_starts_on,
		v_ends_on,
		v_current_version_id,
		v_calendar_version_id,
		v_target_minutes
	FROM contract_versions cv
	JOIN contracts c
	ON c.tenant_id = cv.tenant_id
	AND c.id = cv.contract_id
	WHERE cv.tenant_id = NEW.tenant_id
	AND cv.id = NEW.contract_version_id
	FOR SHARE OF c;
	
	IF NOT FOUND THEN
		RAISE EXCEPTION '受付契約版が見つかりません'
			USING ERRCODE = '23403';
	END IF;
	
	v_received_on := (NEW.received_at AT TIME ZONE 'Asia/Tokyo')::DATE;
	
	IF v_customer_id IS DISTINCT FROM NEW.customer_id
		OR v_service_id IS DISTINCT FROM NEW.service_id
		OR v_status <> 'ACTIVE'
		OR v_received_on < v_starts_on
		OR (v_ends_on IS NOT NULL AND v_received_on > v_ends_on)
		OR v_current_version_id IS DISTINCT FROM NEW.contract_version_id
		OR v_calendar_version_id IS DISTINCT FROM NEW.calendar_version_id
		OR v_target_minutes IS DISTINCT FROM NEW.target_minutes THEN
			RAISE EXCEPTION '受付時の顧客・サービス・契約版・カレンダー版・目標分が一致しません'
				USING ERRCODE = '23514';
	END IF;
	
	RETURN NEW;
END;
$function$;

CREATE TRIGGER trg_tickets_validate_contract
	BEFORE INSERT ON tickets
	FOR EACH ROW
	EXECUTE FUNCTION keepia_validate_ticket_contract();
	
	
	
-- 受付時に決まる情報と、一度確定した初回返信を変更できなくする。

CREATE FUNCTION keepia_guard_ticket_snapshot()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $function$
DECLARE
	v_visibility VARCHAR(16);
	v_author_role VARCHAR(16);
	v_published_at TIMESTAMPTZ;
BEGIN
	IF ROW(
		NEW.tenant_id, NEW.customer_id, NEW.service_id,
		NEW.created_by, NEW.received_at, NEW.in_scope,
		NEW.contract_version_id, NEW.calendar_version_id,
		NEW.target_minutes, NEW.first_reply_due_at
	) IS DISTINCT FROM ROW(
		OLD.tenant_id, OLD.customer_id, OLD.service_id,
		OLD.created_by, OLD.received_at, OLD.in_scope,
		OLD.contract_version_id, OLD.calendar_version_id,
		OLD.target_minutes, OLD.first_reply_due_at
	) THEN
		RAISE EXCEPTION '受付時の契約・期限スナップショットは変更できません。'
			USING ERRCODE ='55000';
	END IF;
	
	IF OLD.first_reply_post_id IS NOT NULL AND
		(
			NEW.first_reply_post_id IS DISTINCT FROM OLD.first_reply_post_id
			OR NEW.first_reply_at IS DISTINCT FROM OLD.first_reply_at
		) THEN
			RAISE EXCEPTION '確定済みの初回返信は変更できません'
				USING ERRCODE = '55000';
	END IF;
	
	IF OLD.first_reply_post_id IS NULL
		AND NEW.first_reply_post_id IS NOT NULL THEN
			SELECT p.visibility, p.author_role, p.published_at
			INTO v_visibility, v_author_role, v_published_at
			FROM ticket_posts p
			WHERE p.tenant_id = NEW.tenant_id
				AND p.ticket_id = NEW.id
				AND p.id = NEW.first_reply_post_id;
				
			IF NOT FOUND
				OR v_visibility <> 'PUBLIC'
				OR v_author_role NOT IN ('ADMIN', 'STAFF')
				OR v_published_at IS DISTINCT FROM NEW.first_reply_at THEN
					RAISE EXCEPTION '初回返信には同じ案件の保守側公開投稿とその公開日時を指定してください'
						USING ERRCODE ='23514';
			END IF;
			
			IF EXISTS (
				SELECT 1
				FROM ticket_posts p
				WHERE p.tenant_id = NEW.tenant_id
					AND p.ticket_id = NEW.id
					AND p.visibility = 'PUBLIC'
					AND p.author_role IN ('ADMIN', 'STAFF')
					AND p.published_at < NEW.first_reply_at
				) THEN
					RAISE EXCEPTION '先に保存された保守側公開投稿があります'
						USING ERRCODE = '23514';
			END IF;
	END IF;
	
	RETURN NEW;
END;
$function$;

-- ticket_posts作成後にトリガーを追加します。」



-- =========================================================
-- 公開返信・社内メモ
-- =========================================================

CREATE TABLE ticket_posts (
	id UUID PRIMARY KEY,
	tenant_id UUID NOT NULL,
	ticket_id UUID NOT NULL,
	author_id UUID NOT NULL,
	author_role VARCHAR(16) NOT NULL,
	visibility VARCHAR(16) NOT NULL,
	body TEXT NOT NULL,
	correction_of_id UUID,
	published_at TIMESTAMPTZ NOT NULL,
	created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
	
	CONSTRAINT fk_ticket_posts_tenant
		FOREIGN KEY (tenant_id)
		REFERENCES tenants(id)
		ON DELETE RESTRICT,
		
	CONSTRAINT fk_ticket_posts_ticket
		FOREIGN KEY (tenant_id, ticket_id)
		REFERENCES tickets (tenant_id, id)
		ON DELETE RESTRICT,
		
	CONSTRAINT fk_ticket_posts_author
		FOREIGN KEY (tenant_id, author_id)
		REFERENCES app_users (tenant_id, id)
		ON DELETE RESTRICT,
		
	CONSTRAINT uq_ticket_posts_tenant_ticket_id
		UNIQUE (tenant_id, ticket_id, id),
		
	CONSTRAINT fk_ticket_posts_correction
		FOREIGN KEY (tenant_id, ticket_id, correction_of_id)
		REFERENCES ticket_posts (tenant_id, ticket_id, id)
		ON DELETE RESTRICT,
		
	CONSTRAINT chk_ticket_posts_author_role
		CHECK (author_role IN ('ADMIN', 'STAFF', 'CUSTOMER')),
		
	CONSTRAINT chk_ticket_posts_visibility
		CHECK (visibility IN ('PUBLIC', 'INTERNAL')),
		
	CONSTRAINT chk_ticket_posts_customer_public
		CHECK (author_role <> 'CUSTOMER' OR visibility  = 'PUBLIC'),
		
	CONSTRAINT chk_ticket_posts_body
		CHECK (CHAR_LENGTH(body) BETWEEN 1 AND 10000 AND BTRIM(body) <> '')
		
);

CREATE INDEX idx_ticket_posts_ticket_published
	ON ticket_posts (tenant_id, ticket_id, published_at, id);
	
CREATE FUNCTION keepia_validate_ticket_post()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $function$
DECLARE
	v_original_visibility VARCHAR(16);
BEGIN
	IF NEW.correction_of_id IS NOT NULL THEN
		SELECT p.visibility
		INTO v_original_visibility
		FROM ticket_posts p
		WHERE p.tenant_id = NEW.tenant_id
			AND p.ticket_id = NEW.ticket_id
			AND p.id = NEW.correction_of_id;
			
		IF NOT FOUND
			OR NEW.visibility <> 'PUBLIC'
			OR v_original_visibility <> 'PUBLIC' THEN
				RAISE EXCEPTION '訂正返信は同じ案件の公開投稿を参照してください'
					USING ERRCODE = '23514';
		END IF;
	END IF;
	
	RETURN NEW;
END;
$function$;

CREATE TRIGGER trg_ticket_posts_validate
	BEFORE INSERT ON ticket_posts
	FOR EACH ROW
	EXECUTE FUNCTION keepia_validate_ticket_post();
	
-- 同じ案件の投稿だけを初回返信として参照できるようにする。

ALTER TABLE tickets
	ADD CONSTRAINT fk_tickets_first_reply_post
	FOREIGN KEY (tenant_id, id, first_reply_post_id)
	REFERENCES ticket_posts (tenant_id, ticket_id, id)
	ON DELETE RESTRICT;
	
CREATE TRIGGER trg_tickets_guard_snapshot
	BEFORE UPDATE ON tickets
	FOR EACH ROW
	EXECUTE FUNCTION keepia_guard_ticket_snapshot();
	
	
	
-- =========================================================
-- 添付ファイルのメタデータ
-- =========================================================

CREATE TABLE attachments (
	id UUID PRIMARY KEY,
	tenant_id UUID NOT NULL,
	ticket_id UUID NOT NULL,
	post_id UUID,
	visibility VARCHAR(16) NOT NULL,
	original_name VARCHAR(255) NOT NULL,
	storage_key VARCHAR(255) NOT NULL,
	media_type VARCHAR(100) NOT NULL,
	size_bytes BIGINT NOT NULL,
	sha256 CHAR(64) NOT NULL,
	created_by UUID NOT NULL,
	created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
	
	CONSTRAINT fk_attachments_tenant
		FOREIGN KEY (tenant_id)
		REFERENCES tenants (id)
		ON DELETE RESTRICT,
		
	CONSTRAINT fk_attachments_ticket
		FOREIGN KEY (tenant_id, ticket_id)
		REFERENCES tickets (tenant_id, id)
		ON DELETE RESTRICT,
		
	CONSTRAINT fk_attachments_post
		FOREIGN KEY (tenant_id, ticket_id, post_id)
		REFERENCES ticket_posts (tenant_id, ticket_id, id)
		ON DELETE RESTRICT,
		
	CONSTRAINT fk_attachments_created_by
		FOREIGN KEY (tenant_id, created_by)
		REFERENCES app_users (tenant_id, id)
		ON DELETE RESTRICT,
	
	CONSTRAINT uq_attachments_storage_key
		UNIQUE (storage_key),
		
	CONSTRAINT chk_attachments_visibility
		CHECK (visibility IN ('PUBLIC', 'INTERNAL')),
		
	CONSTRAINT chk_attachments_name
		CHECK (BTRIM(original_name) <> ''),
		
	CONSTRAINT chk_attachments_strage_key
		CHECK (BTRIM(storage_key) <> ''),
		
	CONSTRAINT chk_attachments_media_type
		CHECK (BTRIM(media_type) <> ''),
		
	CONSTRAINT chk_attachments_size
		CHECK (size_bytes BETWEEN 1 AND 10485760),
		
	CONSTRAINT chk_attachments_sha256
		CHECK (sha256 ~ '^[0-9a-f]{64}$'),
		
	CONSTRAINT chk_attachments_reception_public
		CHECK (post_id IS NOT NULL OR visibility = 'PUBLIC')
		
);

CREATE INDEX idx_attachments_ticket
	ON attachments (tenant_id, ticket_id, created_at, id);
	
	
	
-- 親案件をロックして同時登録を直列化子、案件あたり5件までに制限する
-- AFTER INSERTで確認するため、同じINSERT文で追加した行も件数に含める。
-- 投稿添付の公開区分は親投稿と一致させる。

CREATE FUNCTION keepia_validate_attachment()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $function$
DECLARE
	v_count INTEGER;
	v_post_visibility VARCHAR(16);
BEGIN
	PERFORM 1
	FROM tickets
	WHERE tenant_id = NEW.tenant_id
		AND id = NEW.ticket_id
		FOR NO KEY UPDATE;
		
		IF NOT FOUND THEN
			RAISE EXCEPTION '添付先の案件が見つかりません'
				USING ERRCODE = '23503';
		END IF;
		
		SELECT COUNT(*)
		INTO v_count
		FROM attachments
		WHERE tenant_id = NEW.tenant_id
			AND ticket_id = NEW.ticket_id;
			
		IF v_count > 5 THEN
			RAISE EXCEPTION '1案件の添付は5件までです'
				USING ERRCODE = '23514';
		END IF;
		
		IF NEW.post_id IS NOT NULL THEN
			SELECT p.visibility
			INTO v_post_visibility
			FROM ticket_posts p
			WHERE p.tenant_id = NEW.tenant_id
				AND p.ticket_id = NEW.ticket_id
				AND p.id = NEW.post_id;
				
			IF NOT FOUND OR v_post_visibility IS DISTINCT FROM NEW.visibility THEN
				RAISE EXCEPTION '添付の公開区分は親投稿と一致させてください'
					USING ERRCODE = '23514';
			END IF;
		END IF;
		
		RETURN NEW;
END;
$function$;

CREATE TRIGGER trg_attachments_validate
	AFTER INSERT ON attachments
	FOR EACH ROW
	EXECUTE FUNCTION keepia_validate_attachment();
	
	

-- =========================================================
-- 作業記録
-- =========================================================

CREATE TABLE work_logs (
	id UUID PRIMARY KEY,
	tenant_id UUID NOT NULL,
	ticket_id UUID NOT NULL,
	worker_id UUID NOT NULL,
	work_on DATE NOT NULL,
	minutes INTEGER NOT NULL,
	content TEXT NOT NULL,
	cancelled_at TIMESTAMPTZ,
	created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    
    CONSTRAINT fk_work_logs_tenant
    	FOREIGN KEY (tenant_id)
    	REFERENCES tenants (id)
    	ON DELETE RESTRICT,
    	
    CONSTRAINT fk_work_logs_ticket
    	FOREIGN KEY (tenant_id, ticket_id)
    	REFERENCES tickets (tenant_id, id)
    	ON DELETE RESTRICT,
    	
    CONSTRAINT fk_work_logs_worker
    	FOREIGN KEY (tenant_id, worker_id)
    	REFERENCES app_users (tenant_id, id)
    	ON DELETE RESTRICT,
    	
    CONSTRAINT 	uq_work_logs_tenant_id
    	UNIQUE (tenant_id, id),
    	
    CONSTRAINT chk_work_logs_minutes
    	CHECK (minutes BETWEEN 1 AND 1440),
    	
    CONSTRAINT chk_work_logs_content
    	CHECK (CHAR_LENGTH(content) BETWEEN 1 AND 10000 AND BTRIM(content) <> ''),
    	
    CONSTRAINT chk_work_logs_cancelled_at
    	CHECK (cancelled_at IS NULL OR cancelled_at >= created_at),
    	
    CONSTRAINT chk_work_logs_version
    	CHECK (version >= 0)
    	
);

CREATE INDEX idx_work_logs_tenant_work_on
	ON work_logs (tenant_id, work_on, ticket_id);
	
CREATE INDEX idx_work_logs_ticket
	ON work_logs (tenant_id, ticket_id, work_on);
	
	
	
-- =========================================================
-- 作業記録の訂正・取消履歴
-- =========================================================

CREATE TABLE work_log_revisions (
	id UUID PRIMARY KEY,
	tenant_id UUID NOT NULL,
	work_log_id UUID NOT NULL,
	old_values JSONB NOT NULL,
	new_values JSONB NOT NULL,
	reason VARCHAR(10000) NOT NULL,
	changed_by UUID NOT NULL,
	changed_at TIMESTAMPTZ NOT NULL,
	
	CONSTRAINT fk_work_log_revisions_tenant
		FOREIGN KEY (tenant_id)
		REFERENCES tenants (id)
		ON DELETE RESTRICT,
		
	CONSTRAINT fk_work_log_revisions_work_log
		FOREIGN KEY (tenant_id, work_log_id)
		REFERENCES work_logs (tenant_id, id)
		ON DELETE RESTRICT,
	
	CONSTRAINT fk_work_log_revisions_changed_by
		FOREIGN KEY (tenant_id, changed_by)
		REFERENCES app_users (tenant_id, id)
		ON DELETE RESTRICT,
		
	CONSTRAINT chk_work_log_revisions_old_json
		CHECK (JSONB_TYPEOF(old_values) = 'object'),
		
	CONSTRAINT chk_work_log_revisions_new_json
		CHECK (JSONB_TYPEOF(new_values) = 'object'),
		
	CONSTRAINT chk_work_log_revisions_reason
		CHECK (BTRIM(reason) <> '')
		
);

CREATE INDEX idx_work_log_revisions_work_log
	ON work_log_revisions (tenant_id, work_log_id, changed_at, id);
	
	
	
-- =========================================================
-- 問い合わせの変更履歴
-- =========================================================

CREATE TABLE ticket_histories (
	id UUID PRIMARY KEY,
	tenant_id UUID NOT NULL,
	ticket_id UUID NOT NULL,
	ticket_version BIGINT NOT NULL,
	event_type VARCHAR(32) NOT NULL,
	status_after VARCHAR(24) NOT NULL,
	before_values JSONB NOT NULL,
	after_values JSONB NOT NULL,
	reason VARCHAR(1000),
	changed_by UUID NOT NULL,
	changed_at TIMESTAMPTZ NOT NULL,
	
	CONSTRAINT fk_ticket_histories_tenant
		FOREIGN KEY (tenant_id)
		REFERENCES tenants (id)
		ON DELETE RESTRICT,
		
	CONSTRAINT fk_ticket_histories_ticket
		FOREIGN KEY (tenant_id, ticket_id)
		REFERENCES tickets (tenant_id, id)
		ON DELETE RESTRICT,
	
	CONSTRAINT fk_ticket_histories_changed_by
		FOREIGN KEY (tenant_id, changed_by)
		REFERENCES app_users (tenant_id, id)
		ON DELETE RESTRICT,
		
	CONSTRAINT uq_ticket_histories_ticket_version
		UNIQUE (tenant_id, ticket_id, ticket_version),
		
	CONSTRAINT chk_ticket_histories_version
		CHECK (ticket_version > 0),
		
	CONSTRAINT chk_ticket_histories_event_type
		CHECK (BTRIM(event_type) <> ''),
		
	CONSTRAINT chk_ticket_histories_status
		CHECK (status_after IN (
			'NEW', 'IN_PROGRESS', 'WAITING_CUSTOMER', 'RESOLVED', 'CLOSED'
		)),
		
	CONSTRAINT chk_ticket_histories_before_json
		CHECK (JSONB_TYPEOF(before_values) = 'object'),
		
	CONSTRAINT chk_ticket_histories_after_json
		CHECK (JSONB_TYPEOF(after_values) = 'object'),
		
	CONSTRAINT chk_ticket_histories_reason
		CHECK (reason IS NULL OR BTRIM(reason) <> '')
		
);

CREATE INDEX idx_ticket_histories_ticket_changed
	ON ticket_histories (tenant_id, ticket_id, changed_at, id);
	


-- 投稿・添付・履歴は追記専用とします。

CREATE FUNCTION keepia_reject_append_only_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $function$
BEGIN
	RAISE EXCEPTION '%の既存行は変更または削除できません', TG_TABLE_NAME
		USING ERRCODE = '55000';
	RETURN NULL;
END;
$function$;

CREATE TRIGGER trg_ticket_posts_append_only
	BEFORE UPDATE OR DELETE ON ticket_posts
	FOR EACH ROW
	EXECUTE FUNCTION keepia_reject_append_only_change();
		
CREATE TRIGGER trg_ticket_posts_no_truncate
	BEFORE TRUNCATE ON ticket_posts
	FOR EACH STATEMENT
	EXECUTE FUNCTION keepia_reject_append_only_change();
	
CREATE TRIGGER trg_attachments_append_only
	BEFORE UPDATE OR DELETE ON attachments
	FOR EACH ROW
	EXECUTE FUNCTION keepia_reject_append_only_change();
	
CREATE TRIGGER trg_attachments_no_truncate
	BEFORE TRUNCATE ON attachments
	FOR EACH STATEMENT
	EXECUTE FUNCTION keepia_reject_append_only_change();
	
CREATE TRIGGER trg_work_log_revisions_append_only
	BEFORE UPDATE OR DELETE ON work_log_revisions
	FOR EACH ROW
	EXECUTE FUNCTION keepia_reject_append_only_change();
	
CREATE TRIGGER trg_work_log_revisions_no_truncate
	BEFORE TRUNCATE ON work_log_revisions
	FOR EACH STATEMENT
	EXECUTE FUNCTION keepia_reject_append_only_change();
	
CREATE TRIGGER trg_ticket_histories_append_only
	BEFORE UPDATE OR DELETE ON ticket_histories
	FOR EACH ROW
	EXECUTE FUNCTION keepia_reject_append_only_change();
	
CREATE TRIGGER trg_ticket_histories_no_truncate
	BEFORE TRUNCATE ON ticket_histories
	FOR EACH STATEMENT
	EXECUTE FUNCTION keepia_reject_append_only_change();
	


-- 業務側で同一トランザクションにまとめる処理:
-- 受付時のticket_histories作成、返信時のticket更新と投稿・通知登録、
-- 作業訂正・取消時のwork_log_revisions作成、version照合、権限確認。











