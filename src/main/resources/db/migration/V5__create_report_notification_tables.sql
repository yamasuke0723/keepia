-- =========================================================
-- Keepia V5: 月次報告・通知・監査・冪等性
-- 推奨ファイル名: V5__create_report_notification_tables.sql
-- 前提: V1～V4が適用済みであること
-- =========================================================


-- =========================================================
-- 月次報告版
-- =========================================================

CREATE TABLE monthly_reports (
	id UUID PRIMARY KEY,
	tenant_id UUID NOT NULL,
	customer_id UUID NOT NULL,
	month_on DATE NOT NULL,
	revision INTEGER NOT NULL,
	status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
	cutoff_at TIMESTAMPTZ NOT NULL,
	generated_at TIMESTAMPTZ NOT NULL,
	summary TEXT NOT NULL,
	received_count INTEGER NOT NULL,
	worked_ticket_count INTEGER NOT NULL,
	work_minutes BIGINT NOT NULL,
	compliant_count INTEGER NOT NULL,
	overdue_count INTEGER NOT NULL,
	pending_count INTEGER NOT NULL,
	reviewed_by UUID,
	reviewed_at TIMESTAMPTZ,
	reviewed_version BIGINT,
	published_at TIMESTAMPTZ,
	source_report_id UUID,
	correction_reason VARCHAR(1000),
	created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    
    CONSTRAINT fk_monthly_reports_tenant
    	FOREIGN KEY (tenant_id)
    	REFERENCES tenants (id)
    	ON DELETE RESTRICT,
    	
    CONSTRAINT fk_monthly_reports_customer
    	FOREIGN KEY (tenant_id, customer_id)
    	REFERENCES customers (tenant_id, id)
    	ON DELETE RESTRICT,
    	
    CONSTRAINT fk_monthly_reports_reviewer
    	FOREIGN KEY (tenant_id, reviewed_by)
    	REFERENCES app_users (tenant_id, id)
    	ON DELETE RESTRICT,
    	
    -- report_itemsとnotification_eventsの複合外部キーで利用する。
    CONSTRAINT uq_monthly_reports_tenant_id
    	UNIQUE (tenant_id, id),
    	
    -- 訂正元が同じ顧客・対象月の報告であることを保証するために利用する。
    CONSTRAINT uq_monthly_reports_source_reference
    	UNIQUE (tenant_id, customer_id, month_on, id),
    	
    CONSTRAINT uq_monthly_reports_revision
    	UNIQUE (tenant_id, customer_id, month_on, revision),
    	
    CONSTRAINT fk_monthly_reports_source
    	FOREIGN KEY (
    		tenant_id,
    		customer_id,
    		month_on,
    		source_report_id
    	)
    	REFERENCES monthly_reports (
    		tenant_id,
    		customer_id,
    		month_on,
    		id
    	)
    	ON DELETE RESTRICT,
    	
    CONSTRAINT chk_monthly_reports_month
    	CHECK (EXTRACT (DAY FROM month_on) = 1),
    	
    -- 対象月の翌月1日00:00（日本時間）を月末評価基準にする。
    CONSTRAINT chk_monthly_reports_cutoff
    	CHECK (
    		cutoff_at = (
    			(month_on + INTERVAL '1 month')
    			AT TIME ZONE 'Asia/Tokyo'
    		)
    	),
    	
    CONSTRAINT chk_monthly_reports_completed_month
    	CHECK (generated_at >= cutoff_at),
    	
    CONSTRAINT chk_monthly_reports_revision
    	CHECK (revision > 0),
    	
    CONSTRAINT chk_monthly_reports_status
    	CHECK (status IN ('DRAFT', 'REVIEWED', 'PUBLISHED')),
    	
    CONSTRAINT chk_monthly_reports_summary
    	CHECK (
    		CHAR_LENGTH(summary) <= 10000
    		AND (
    			status = 'DRAFT'
    			OR BTRIM(summary) <> ''
    		)
    	),
    	
    CONSTRAINT chk_monthly_reports_counts
    	CHECK (
    		received_count >= 0
    		AND worked_ticket_count >= 0
    		AND work_minutes >= 0
    		AND compliant_count >= 0
    		AND overdue_count >= 0
    		AND pending_count >= 0
    		AND (
    			compliant_count::BIGINT
    			+ overdue_count::BIGINT
    			+ pending_count::BIGINT
    		) <= received_count::BIGINT
    	),
    	
    CONSTRAINT chk_monthly_reports_review_lifecycle
    	CHECK (
    		(
    			status = 'DRAFT'
    			AND reviewed_by IS NULL
    			AND reviewed_at IS NULL
    			AND reviewed_version IS NULL
    			AND published_at IS NULL
    		)
    		OR 
    		(
    			status = 'REVIEWED'
    			AND reviewed_by IS NOT NULL
    			AND reviewed_at IS NOT NULL
    			AND reviewed_version IS NOT NULL
    			AND reviewed_version >= 0
    			AND published_at IS NULL
    		)
    		OR
    		(
    			status = 'PUBLISHED'
    			AND reviewed_by IS NOT NULL
    			AND reviewed_at Is NOT NULL
    			AND reviewed_version IS NOT NULL
    			AND reviewed_version >= 0
    			AND published_at IS NOT NULL
    		)
    	),
    	
    	CONSTRAINT chk_monthly_reports_review_time
    		CHECK (
    			reviewed_at IS NULL
    			OR reviewed_at >= generated_at
    		),
    		
    	CONSTRAINT chk_monthly_reports_publish_time
    		CHECK (
    			published_at IS NULL
    			OR published_at >= reviewed_at
    		),
    		
    	CONSTRAINT chk_monthly_reports_correction
    		CHECK (
    			(
    				source_report_id IS NULL
    				AND correction_reason IS NULL
    			)
    			OR
    			(
    				source_report_id IS NOT NULL
    				AND source_report_id <> id
    				AND correction_reason IS NOT NULL
    				AND BTRIM(correction_reason) <> ''
    			)
    		),
    		
    	CONSTRAINT chk_monthly_reports_version
    		CHECK (version >= 0)
);

CREATE INDEX idx_monthly_reports_customer_month
	ON monthly_reports (
		tenant_id,
		customer_id,
		month_on DESC,
		revision DESC
	);
	
CREATE INDEX idx_monthly_reports_published
	ON monthly_reports (
		tenant_id,
		customer_id,
		month_on DESC,
		revision DESC
	)
	WHERE status = 'PUBLISHED';
	
	
-- 月次報告の版、状態遷移、訂正元、公開順を検査します。

CREATE FUNCTION keepia_validate_monthly_report()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $function$
DECLARE
	v_source_status VARCHAR(16);
	v_source_revision INTEGER;
BEGIN
	IF TG_OP = 'DELETE' THEN
		IF OLD.status = 'PUBLISHED' THEN
			RAISE EXCEPTION '公開済み月次報告は削除できません。訂正版を作成してください。'
				USING ERRCODE = '55000';
		END IF;
		
		RETURN OLD;
	END IF;
	
	IF TG_OP = 'INSERT' AND NEW.status <> 'DRAFT' THEN
		RAISE EXCEPTION '月次報告は下書きとして登録してください。'
			USING ERRCODE = '23514';
	END IF;
	
	IF TG_OP = 'UPDATE' THEN
		IF OLD.status = 'PUBLISHED' THEN
			RAISE EXCEPTION '公開済み月次報告は変更できません。訂正版を作成してください。'
				USING ERRCODE = '55000';
		END IF;
		
		IF ROW(
			NEW.id,
			NEW.tenant_id,
			NEW.customer_id,
			NEW.month_on,
			NEW.revision,
			NEW.source_report_id,
			NEW.correction_reason,
			NEW.created_at
		) IS DISTINCT FROM ROW(
			OLD.id,
			OLD.tenant_id,
			OLD.customer_id,
			OLD.month_on,
			OLD.revision,
			OLD.source_report_id,
			OLD.correction_reason,
			OLD.created_at
		) THEN
			RAISE EXCEPTION '月次報告の識別情報・対象月・版・訂正元は変更できません。'
				USING ERRCODE = '55000';
		END IF;
		
		IF NOT (
			(OLD.status = 'DRAFT' AND NEW.status IN ('DRAFT', 'REVIEWED'))
			OR
			(OLD.status = 'REVIEWED' AND NEW.status IN ('DRAFT', 'REVIEWED', 'PUBLISHED'))
		) THEN
			RAISE EXCEPTION '許可されていない月次報告の状態遷移です： % -> %',
				OLD.status,
				NEW.status
				USING ERRCODE = '23514';
		END IF;
		
		-- 確認済みの内容は、そのままREVIEWEDに保つ場合も公開する場合も変更しない。
        -- 内容を直すときは、確認情報を消してDRAFTへ戻してから更新する。
        
        IF OLD.status = 'REVIEWED'
        	AND NEW.status IN ('REVIEWED', 'PUBLISHED')
        	AND ROW(
        		NEW.cutoff_at,
        		NEW.generated_at,
        		NEW.summary,
        		NEW.received_count,
        		NEW.worked_ticket_count,
        		NEW.work_minutes,
        		NEW.compliant_count,
        		NEW.overdue_count,
        		NEW.pending_count
        	) IS DISTINCT FROM ROW(
        		OLD.cutoff_at,
        		OLD.generated_at,
        		OLD.summary,
        		OLD.received_count,
        		OLD.worked_ticket_count,
        		OLD.work_minutes,
        		OLD.compliant_count,
        		OLD.overdue_count,
        		OLD.pending_count
        	) THEN
        	RAISE EXCEPTION '確認済みの報告の内容は変更できません。DRAFTへ戻して再確認してください。'
        		USING ERRCODE = '55000';
        END IF;
        
        -- 確認者・確認日時・確認版は、DRAFTへ戻して再確認するまで固定する。
        
        IF OLD.status = 'REVIEWED'
        	AND NEW.status = 'REVIEWED'
        	AND ROW(
        		NEW.reviewed_by,
        		NEW.reviewed_at,
        		NEW.reviewed_version
       		) IS DISTINCT FROM ROW(
       			OLD.reviewed_by,
       			OLD.reviewed_at,
       			OLD.reviewed_version
       		) THEN
       		RAISE EXCEPTION '確認済み報告の確認情報は変更できません。DRAFTへ戻して再確認してください。'
       			USING ERRCODE ='55000';
       	END IF;
	END IF;
	
	IF NEW.source_report_id IS NOT NULL THEN
		SELECT source.status, source.revision
		INTO v_source_status, v_source_revision
		FROM monthly_reports source
		WHERE source.tenant_id = NEW.tenant_id
			AND source.customer_id = NEW.customer_id
			AND source.month_on = NEW.month_on
			AND source.id = NEW.source_report_id;
			
		IF NOT FOUND
			OR v_source_status <> 'PUBLISHED'
			OR v_source_revision >= NEW.revision THEN
			RAISE EXCEPTION '訂正元には同じ顧客・対象月の古い公開版を指定してください'
				USING ERRCODE = '23514';
		END IF;
	END IF;
	
	IF NEW.status = 'REVIEWED'
		AND NEW.reviewed_version IS DISTINCT FROM NEW.version THEN
		RAISE EXCEPTION '確認版には保存後のversionを指定してください。'
			USING ERRCODE = '23514';
	END IF;
	
	IF NEW.status = 'PUBLISHED' THEN
	
		IF TG_OP <> 'UPDATE' OR OLD.status <> 'REVIEWED' THEN
			RAISE EXCEPTION '確認済みの月次報告だけを公開できます。'
				USING ERRCODE = '23514';
		END IF;
		
		IF OLD.reviewed_version IS DISTINCT FROM OLD.version THEN
			RAISE EXCEPTION '確認後に変更された月次報告は公開できません。'
				USING ERRCODE = '23514';
		END IF;
		
		IF ROW(
			NEW.reviewed_by,
			NEW.reviewed_at,
			NEW.reviewed_version
		) IS DISTINCT FROM ROW(
			OLD.reviewed_by,
			OLD.reviewed_at,
			OLD.reviewed_version
		) THEN
			RAISE EXCEPTION '公開時に確認情報を変更できません。'
				USING ERRCODE = '55000';
				
		END IF;
		
		PERFORM 1
		FROM monthly_reports published
		WHERE published.tenant_id = NEW.tenant_id
			AND published.customer_id = NEW.customer_id
			AND published.month_on = NEW.month_on
			AND published.status = 'PUBLISHED'
			AND published.revision > NEW.revision;
			
		IF FOUND THEN
			RAISE EXCEPTION '最新公開版より古い月次報告は公開できません。'
				USING ERRCODE = '23514';
		END IF;
	END IF;
	
	RETURN NEW;
END;
$function$;	

CREATE TRIGGER trg_monthly_reports_validate
	BEFORE INSERT OR UPDATE OR DELETE ON monthly_reports
	FOR EACH ROW
	EXECUTE FUNCTION keepia_validate_monthly_report();
	
CREATE TRIGGER trg_monthly_reports_no_truncate
	BEFORE TRUNCATE ON monthly_reports
	FOR EACH STATEMENT
	EXECUTE FUNCTION keepia_reject_append_only_change();
	
	
    
-- =========================================================
-- 月次報告に含める案件スナップショット
-- =========================================================

  
CREATE TABLE report_items (
	id UUID PRIMARY KEY,
	tenant_id UUID NOT NULL,
	report_id UUID NOT NULL,
	ticket_id UUID NOT NULL,
	ticket_number VARCHAR(40) NOT NULL,
	subject VARCHAR(200) NOT NULL,
	service_name VARCHAR(100) NOT NULL,
	received_at TIMESTAMPTZ NOT NULL,
	status_at_cutoff VARCHAR(24) NOT NULL,
	received_in_month BOOLEAN NOT NULL,
	worked_in_month BOOLEAN NOT NULL,
	work_minutes BIGINT NOT NULL,
	first_reply_due_at TIMESTAMPTZ,
	first_reply_at TIMESTAMPTZ,
	deadline_evaluation VARCHAR(24) NOT NULL,
	public_summary TEXT NOT NULL,
	
	CONSTRAINT fk_report_items_tenant
		FOREIGN KEY (tenant_id)
		REFERENCES tenants (id)
		ON DELETE RESTRICT,
		
	CONSTRAINT fk_report_items_report
		FOREIGN KEY (tenant_id, report_id)
		REFERENCES monthly_reports (tenant_id, id)
		ON DELETE RESTRICT,
		
	CONSTRAINT fk_report_items_ticket
		FOREIGN KEY (tenant_id, ticket_id)
		REFERENCES tickets (tenant_id, id)
		ON DELETE RESTRICT,
		
	CONSTRAINT uq_report_items_report_ticket
		UNIQUE (tenant_id, report_id, ticket_id),
		
	CONSTRAINT chk_report_items_ticket_number
		CHECK (BTRIM(ticket_number) <> ''),
		
	CONSTRAINT chk_report_items_subject
		CHECK (BTRIM(subject) <> ''),
		
	CONSTRAINT chk_report_items_service_name
		CHECK (BTRIM(service_name) <> ''),
		
	CONSTRAINT chk_report_items_status
		CHECK (
			status_at_cutoff IN (
				'NEW',
				'IN_PROGRESS',
				'WAITING_CUSTOMER',
				'RESOLVED',
				'CLOSED'
			)
		),
		
	CONSTRAINT chk_report_items_month_membership
		CHECK (received_in_month OR worked_in_month),
		
	CONSTRAINT chk_report_items_work_minutes
		CHECK (
			(
				worked_in_month
				AND work_minutes > 0
			)
			OR
			(
				NOT worked_in_month
				AND work_minutes = 0
			)
		),
		
	CONSTRAINT chk_report_items_reply_time
		CHECK (
			first_reply_at IS NULL
			OR first_reply_at >= received_at
		),
		
	CONSTRAINT chk_report_items_evaluation
		CHECK (
			deadline_evaluation IN (
				'COMPLIANT',
				'OVERDUE',
				'PENDING',
				'OUT_OF_SCOPE',
				'NOT_RECEIVED_MONTH'
			)
			AND (
				(
					received_in_month
					AND deadline_evaluation IN (
						'COMPLIANT',
						'OVERDUE',
						'PENDING',
						'OUT_OF_SCOPE'
					)
				)
				OR
				(
					NOT received_in_month
					AND deadline_evaluation = 'NOT_RECEIVED_MONTH'
				)
			)
			AND (
				deadline_evaluation NOT IN ('COMPLIANT', 'OVERDUE', 'PENDING')
				OR first_reply_due_at IS NOT NULL
			)
			AND (
				deadline_evaluation <> 'OUT_OF_SCOPE'
				OR first_reply_due_at IS NULL
			)
		),
		
	CONSTRAINT chk_report_items_public_summary
		CHECK (CHAR_LENGTH(public_summary) <= 10000)

);

CREATE INDEX idx_report_items_report
	ON report_items (tenant_id, report_id, received_at, ticket_id);
	
CREATE INDEX idx_report_items_ticket
	ON report_items (tenant_id, ticket_id);
	
	
-- 明細は同じ顧客の案件だけを参照し、DRAFTの間だけ変更できます。

CREATE FUNCTION keepia_guard_report_item()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $function$
DECLARE
	v_tenant_id UUID;
	v_report_id UUID;
	v_report_status VARCHAR(16);
	v_report_customer_id UUID;
	v_ticket_customer_id UUID;
BEGIN
	IF TG_OP = 'DELETE' THEN
		v_tenant_id := OLD.tenant_id;
		v_report_id := OLD.report_id;
	ELSE
		v_tenant_id := NEW.tenant_id;
		v_report_id := NEW.report_id;
	END IF;
	
	IF TG_OP = 'UPDATE'
		AND ROW(
			NEW.id,
			NEW.tenant_id,
			NEW.report_id,
			NEW.ticket_id
		) IS DISTINCT FROM ROW(
			OLD.id,
			OLD.tenant_id,
			OLD.report_id,
			OLD.ticket_id
		)THEN
		RAISE EXCEPTION	'報告明細のID・報告版・元案件は変更できません'
			USING ERRCODE = '55000';
	END IF;
	
	SELECT report.status, report.customer_id
	INTO v_report_status, v_report_customer_id
	FROM monthly_reports report
	WHERE report.tenant_id = v_tenant_id
		AND report.id = v_report_id
	FOR KEY SHARE;
	
	IF NOT FOUND THEN
		RAISE EXCEPTION '月次報告が見つかりません'
			USING ERRCODE = '23503';
	END IF;
	
	IF v_report_status <> 'DRAFT' THEN
		RAISE EXCEPTION '確認済みまたは公開済みの報告明細は変更できません'
			USING ERRCODE = '55000';
	END IF;
	
	IF TG_OP <> 'DELETE' THEN
		SELECT ticket.customer_id
		INTO v_ticket_customer_id
		FROM tickets ticket
		WHERE ticket.tenant_id = NEW.tenant_id
			AND ticket.id = NEW.ticket_id;
			
		IF NOT FOUND
			OR v_ticket_customer_id IS DISTINCT FROM v_report_customer_id THEN
			RAISE EXCEPTION '報告明細には同じ顧客の案件を指定してください'
				USING ERRCODE = '23514';
		END IF;
	END IF;
	
	IF TG_OP = 'DELETE' THEN
		RETURN OLD;
	END IF;
	
	RETURN NEW;
END;
$function$;

CREATE TRIGGER trg_report_items_guard
	BEFORE INSERT OR UPDATE OR DELETE ON report_items
	FOR EACH ROW
	EXECUTE FUNCTION keepia_guard_report_item();
	
CREATE TRIGGER trg_report_items_no_truncate
	BEFORE TRUNCATE ON report_items
	FOR EACH STATEMENT
	EXECUTE FUNCTION keepia_reject_append_only_change();
	



-- =========================================================
-- 業務通知イベント
-- =========================================================

CREATE TABLE notification_events (
	id UUID PRIMARY KEY,
	tenant_id UUID NOT NULL,
	event_key VARCHAR(200) NOT NULL,
	event_type VARCHAR(32) NOT NULL,
	ticket_id UUID,
	report_id UUID,
	occurred_at TIMESTAMPTZ NOT NULL,
	
	CONSTRAINT fk_notification_events_tenant
		FOREIGN KEY (tenant_id)
		REFERENCES tenants (id)
		ON DELETE RESTRICT,
		
	CONSTRAINT fk_notification_events_ticket
		FOREIGN KEY (tenant_id, ticket_id)
		REFERENCES tickets (tenant_id, id)
		ON DELETE RESTRICT,
		
	CONSTRAINT fk_notification_events_report
		FOREIGN KEY (tenant_id, report_id)
		REFERENCES monthly_reports (tenant_id, id)
		ON DELETE RESTRICT,
		
	-- notificationsとnotification_deliveriesの複合外部キーで利用します。
	
	CONSTRAINT uq_notification_events_tenant_id
		UNIQUE (tenant_id, id),
		
	CONSTRAINT uq_notification_events_key
		UNIQUE (tenant_id, event_key),
		
	CONSTRAINT chk_notification_events_key
		CHECK (BTRIM(event_key) <> ''),
		
	CONSTRAINT chk_notification_events_type
		CHECK (BTRIM(event_type) <> ''),
		
	CONSTRAINT chk_notification_events_target
		CHECK (
			(ticket_id IS NOT NULL AND report_id IS NULL)
			OR
			(ticket_id IS NULL AND report_id IS NOT NULL)
		)
);

CREATE INDEX idx_notification_events_ticket
	ON notification_events (tenant_id, ticket_id, occurred_at, id)
	WHERE ticket_id IS NOT NULL;
	
CREATE INDEX idx_notification_events_report
	ON notification_events (tenant_id, report_id, occurred_at, id)
	WHERE report_id IS NOT NULL;
	
CREATE TRIGGER trg_notification_events_append_only
	BEFORE UPDATE OR DELETE ON notification_events
	FOR EACH ROW
	EXECUTE FUNCTION keepia_reject_append_only_change();
	
CREATE TRIGGER trg_notification_events_no_truncate
	BEFORE TRUNCATE ON notification_events
	FOR EACH STATEMENT
	EXECUTE FUNCTION keepia_reject_append_only_change();
	
	
	
	
-- =========================================================
-- 本人宛画面通知
-- =========================================================


CREATE TABLE notifications (
	id UUID PRIMARY KEY,
	tenant_id UUID NOT NULL,
	event_id UUID NOT NULL,
	recipient_id UUID NOT NULL,
	read_at TIMESTAMPTZ,
	created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
	
	CONSTRAINT fk_notifications_tenant
		FOREIGN KEY (tenant_id)
		REFERENCES tenants (id)
		ON DELETE RESTRICT,
		
	CONSTRAINT fk_notifications_event	
		FOREIGN KEY (tenant_id, event_id)
		REFERENCES notification_events (tenant_id, id)
		ON DELETE RESTRICT,
	
	CONSTRAINT fk_notifications_recipient
		FOREIGN KEY (tenant_id, recipient_id)
		REFERENCES app_users (tenant_id, id)
		ON DELETE RESTRICT,
		
	CONSTRAINT uq_notifications_event_recipient
		UNIQUE (tenant_id, event_id, recipient_id),
		
	CONSTRAINT chk_notifications_read_at
		CHECK (
			read_at IS NULL
			OR read_at >= created_at
		)
);

CREATE INDEX idx_notifications_recipient_unread
	ON notifications (tenant_id, recipient_id, created_at DESC, id)
	WHERE read_at IS NULL;
	
CREATE INDEX idx_notifications_recipient_created
	ON notifications (tenant_id, recipient_id, created_at DESC, id);
	

-- 通知の宛先やイベントは変更せず、既読日時だけを一度設定できます。

CREATE FUNCTION keepia_guard_notification_read()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $function$
BEGIN
	IF ROW(
		NEW.id,
		NEW.tenant_id,
		NEW.event_id,
		NEW.recipient_id,
		NEW.created_at
	) IS DISTINCT FROM ROW (
		OLD.id,
		OLD.tenant_id,
		OLD.event_id,
		OLD.recipient_id,
		OLD.created_at
	) THEN
		RAISE EXCEPTION '画面通知のイベント・宛先は変更できません'
			USING ERRCODE = '55000';
	END IF;
	
	IF OLD.read_at IS NOT NULL
		AND NEW.read_at IS DISTINCT FROM OLD.read_at THEN
		RAISE EXCEPTION '確定済みの既読日時は変更できません'
			USING ERRCODE = '55000';
	END IF;
	
	RETURN NEW;
END;
$function$;

CREATE TRIGGER trg_notifications_guard_read
	BEFORE UPDATE ON notifications
	FOR EACH ROW
	EXECUTE FUNCTION keepia_guard_notification_read();
	



-- =========================================================
-- メール送信予約
-- =========================================================


CREATE TABLE notification_deliveries (
	id UUID PRIMARY KEY,
	tenant_id UUID NOT NULL,
	event_id UUID NOT NULL,
	recipient_id UUID NOT NULL,
	channel VARCHAR(16) NOT NULL DEFAULT 'EMAIL',
	status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
	attempt_count INTEGER NOT NULL DEFAULT 0,
	next_attempt_at TIMESTAMPTZ NOT NULL,
	provider_message_id VARCHAR(200),
	lease_until TIMESTAMPTZ,
	sent_at TIMESTAMPTZ,
	created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    
    CONSTRAINT fk_notification_deliveries_tenant
    	FOREIGN KEY (tenant_id)
    	REFERENCES tenants (id)
    	ON DELETE RESTRICT,
    	
    CONSTRAINT fk_notification_deliveries_event
    	FOREIGN KEY (tenant_id, event_id)
    	REFERENCES notification_events (tenant_id, id)
    	ON DELETE RESTRICT,
    	
    CONSTRAINT fk_notification_deliveries_recipient
    	FOREIGN KEY (tenant_id, recipient_id)
    	REFERENCES app_users (tenant_id, id)
    	ON DELETE RESTRICT,
    	
    -- notification_attemptsの複合外部キーで利用します。
    CONSTRAINT uq_notification_deliveries_tenant_id
    	UNIQUE (tenant_id, id),
    	
    CONSTRAINT uq_notification_deliveries_event_recipient_channel
    	UNIQUE (tenant_id, event_id, recipient_id, channel),
    	
    CONSTRAINT chk_notification_deliveries_channel
    	CHECK (channel IN ('EMAIL')),
    	
    CONSTRAINT chk_notification_deliveries_status
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
    	
    CONSTRAINT chk_notification_deliveries_attempt_count
    	CHECK (attempt_count >= 0),
    	
    CONSTRAINT chk_notification_deliveries_provider_id
    	CHECK (
    		provider_message_id IS NULL
    		OR BTRIM(provider_message_id) <> ''
    	),
    	
    CONSTRAINT chk_notification_deliveries_lease
    	CHECK (
    		(status = 'SENDING' AND lease_until IS NOT NULL)
    		OR
    		(status <> 'SENDING' AND lease_until IS NULL)
    	),
    
    CONSTRAINT chk_notification_deliveries_sent
    	CHECK (
    		(status = 'SENT' AND sent_at IS NOT NULL)
    		OR
    		(status <> 'SENT' AND sent_at IS NULL)
    	),
    	
    CONSTRAINT chk_notification_deliveries_sent_time
    	CHECK (
    		sent_at IS NULL
    		OR sent_at >= created_at
    	),
    	
    CONSTRAINT chk_notification_deliveries_updated_at
    	CHECK (updated_at >= created_at),
    	
    CONSTRAINT chk_notification_deliveries_version
    	CHECK (version >= 0)

);

CREATE INDEX idx_notification_deliveries_pending
	ON notification_deliveries (next_attempt_at, id)
	WHERE status = 'PENDING';
	
CREATE INDEX idx_notification_deliveries_lease
	ON notification_deliveries (lease_until, id)
	WHERE status = 'SENDING';
	
CREATE INDEX idx_notification_deliveries_recipient
	ON notification_deliveries (tenant_id, recipient_id, created_at DESC, id);
	
	
CREATE FUNCTION keepia_guard_notification_delivery()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $function$
BEGIN
	IF ROW(
		NEW.id,
		NEW.tenant_id,
		NEW.event_id,
		NEW.recipient_id,
		NEW.channel,
		NEW.created_at
	) IS DISTINCT FROM ROW(
		OLD.id,
		OLD.tenant_id,
		OLD.event_id,
		OLD.recipient_id,
		OLD.channel,
		OLD.created_at
	) THEN 
		RAISE EXCEPTION 'メール送信予約のイベント・宛先・方式は変更できません'
			USING ERRCODE = '55000';
	END IF;
	
	IF OLD.status IN ('SENT', 'CANCELLED')
		AND ROW(
			NEW.status,
			NEW.attempt_count,
			NEW.next_attempt_at,
			NEW.provider_message_id,
			NEW.lease_until,
			NEW.sent_at,
			NEW.updated_at,
			NEW.version
		) IS DISTINCT FROM ROW(
			OLD.status,
			OLD.attempt_count,
			OLD.next_attempt_at,
			OLD.provider_message_id,
			OLD.lease_until,
			OLD.sent_at,
			OLD.updated_at,
			OLD.version
		) THEN
			RAISE EXCEPTION '送信済みまたは取消済みのメール送信予約は変更できません'
				USING ERRCODE = '55000';
	END IF;
	
	-- 結果不明のまま再送状態へ戻すことを拒否する。
    -- 外部受付履歴を照合し、SENT・FAILED・CANCELLEDのいずれかへ確定する。
    
    IF OLD.status = 'UNKNOWN'
    	AND NEW.status IN ('PENDING', 'SENDING') THEN
    	RAISE EXCEPTION '結果不明の送信は外部履歴を照合してから状態を確定してください'
    		USING ERRCODE = '55000';
    END IF;
    
    IF NEW.attempt_count < OLD.attempt_count THEN
    	RAISE EXCEPTION 'メール送信の総試行回数は減らせません'
    		USING ERRCODE = '23514';
    END IF;
    
    RETURN NEW;
END;
$function$;

CREATE TRIGGER trg_notification_deliveries_guard
	BEFORE UPDATE ON notification_deliveries
	FOR EACH ROW
	EXECUTE FUNCTION keepia_guard_notification_delivery();
	
	
	
-- =========================================================
-- メール送信・照合の試行履歴
-- =========================================================


CREATE TABLE notification_attempts (
	id UUID PRIMARY KEY,
	tenant_id UUID NOT NULL,
	delivery_id UUID NOT NULL,
	attempt_no INTEGER NOT NULL,
	trigger_type VARCHAR(16) NOT NULL,
	result VARCHAR(16) NOT NULL,
	error_code VARCHAR(100),
	reason VARCHAR(1000),
	started_at TIMESTAMPTZ NOT NULL,
	finished_at TIMESTAMPTZ,
	
	CONSTRAINT fk_notification_attempts_tenant
		FOREIGN KEY (tenant_id)
		REFERENCES tenants (id)
		ON DELETE RESTRICT,	
		
	CONSTRAINT fk_notification_attempts_delivery
		FOREIGN KEY (tenant_id, delivery_id)
		REFERENCES notification_deliveries (tenant_id, id)
		ON DELETE RESTRICT,
		
	CONSTRAINT uq_notification_attempts_number
		UNIQUE (tenant_id, delivery_id, attempt_no),
		
	CONSTRAINT chk_notification_attempts_number
		CHECK (attempt_no > 0),
		
	CONSTRAINT chk_notification_attempts_trigger
		CHECK (
			trigger_type IN ('INITIAL', 'AUTO', 'MANUAL', 'RECONCILE')
		),
		
	-- resultは送信予約の確定状態と同じ語彙を使います。
	CONSTRAINT chk_notification_attempts_result
		CHECK (result IN  ('SENT', 'FAILED', 'UNKNOWN', 'CANCELLED')),
		
	CONSTRAINT chk_notification_attempts_error_code
		CHECK (
			error_code IS NULL
			OR BTRIM(error_code) <> ''
		),
		
	CONSTRAINT chk_notification_attempts_reason
		CHECK (
			(
				trigger_type IN ('MANUAL', 'RECONCILE')
				AND reason IS NOT NULL
				AND BTRIM(reason) <> ''
			)
			OR
			(
				trigger_type IN ('INITIAL', 'AUTO')
				AND (reason IS NULL OR BTRIM(reason) <> '')
			)
		),
		
	CONSTRAINT chk_notification_attempts_time
		CHECK (
			finished_at IS NULL
			OR finished_at >= started_at
		)
		
);

CREATE INDEX idx_notification_attempts_delivery
	ON notification_attempts (
		tenant_id,
		delivery_id,
		attempt_no DESC
	);
	
CREATE TRIGGER trg_notification_attempts_append_only
	BEFORE UPDATE OR DELETE ON notification_attempts
	FOR EACH ROW
	EXECUTE FUNCTION keepia_reject_append_only_change();
	
CREATE TRIGGER trg_notification_attempts_no_truncate
	BEFORE TRUNCATE ON notification_attempts
	FOR EACH STATEMENT
	EXECUTE FUNCTION keepia_reject_append_only_change();
	
	
	
-- =========================================================
-- 管理操作の監査ログ
-- =========================================================

CREATE TABLE audit_logs (
	id UUID PRIMARY KEY,
	tenant_id UUID,
	actor_id UUID NOT NULL,
	action VARCHAR(64) NOT NULL,
	target_type VARCHAR(40) NOT NULL,
	target_id UUID NOT NULL,
	before_values JSONB NOT NULL,
	after_values JSONB NOT NULL,
	reason VARCHAR(1000),
	occurred_at TIMESTAMPTZ NOT NULL,
	
	CONSTRAINT fk_audit_logs_tenant
		FOREIGN KEY (tenant_id)
		REFERENCES tenants (id)
		ON DELETE RESTRICT,
		
	CONSTRAINT fk_audit_logs_actor
		FOREIGN KEY (actor_id)
		REFERENCES app_users (id)
		ON DELETE RESTRICT,
		
	CONSTRAINT chk_audit_logs_action
		CHECK (BTRIM(action) <> ''),
		
	CONSTRAINT chk_audit_logs_target_type
		CHECK (BTRIM(target_type) <> ''),
		
	CONSTRAINT chk_audit_logs_before_json
		CHECK (JSONB_TYPEOF(before_values) = 'object'),
		
	CONSTRAINT chk_audit_logs_after_json
		CHECK (JSONB_TYPEOF(after_values) = 'object'),
		
	CONSTRAINT chk_audit_logs_reason
		CHECK (
			reason IS NULL
			OR BTRIM(reason) <> ''
		)
);

CREATE INDEX idx_audit_logs_tenant_occurred
	ON audit_logs (tenant_id, occurred_at DESC, id);
	
CREATE INDEX idx_audit_logs_actor_occurred
	ON audit_logs (actor_id, occurred_at DESC, id);
	
CREATE INDEX idx_audit_logs_target
	ON audit_logs (target_type, target_id, occurred_at DESC, id);
	
	
-- tenant_idがNULLの監査は運営者だけ、業務監査は同一テナントの利用者だけにします。
CREATE FUNCTION keepia_validate_audit_log_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $function$
DECLARE
	v_actor_tenant_id UUID;
	v_actor_role VARCHAR(16);
BEGIN
	SELECT actor.tenant_id, actor.role
	INTO v_actor_tenant_id, v_actor_role
	FROM app_users actor
	WHERE actor.id = NEW.actor_id;
	
	IF NOT FOUND THEN
		RAISE EXCEPTION '監査ログの実施者が見つかりません'
			USING ERRCODE = '23503';
	END IF;
	
	IF NEW.tenant_id IS NULL THEN
		IF v_actor_role <> 'OPERATOR' THEN
			RAISE EXCEPTION 'テナント無しの監査ログは運営者だけが登録できます'
				USING ERRCODE = '23514';
				
		END IF;
	ELSIF v_actor_tenant_id IS DISTINCT FROM NEW.tenant_id THEN
		RAISE EXCEPTION '監査ログの実施者と対象テナントが一致しません'
			USING ERRCODE = '23514';
	END IF;
	
	RETURN NEW;
END;
$function$;

CREATE TRIGGER trg_audit_logs_validate_scope
	BEFORE INSERT ON audit_logs
	FOR EACH ROW
	EXECUTE FUNCTION keepia_validate_audit_log_scope();
	
CREATE TRIGGER trg_audit_logs_append_only
	BEFORE UPDATE OR DELETE ON audit_logs
	FOR EACH ROW
	EXECUTE FUNCTION keepia_reject_append_only_change();
	
CREATE TRIGGER trg_audit_logs_no_truncate
	BEFORE TRUNCATE ON audit_logs
	FOR EACH STATEMENT
	EXECUTE FUNCTION keepia_reject_append_only_change();
	
	
    
-- =========================================================
-- 二重送信防止キー
-- =========================================================

CREATE TABLE idempotency_keys (
	id UUID PRIMARY KEY,
	actor_id UUID NOT NULL,
	operation VARCHAR(64) NOT NULL,
	request_key UUID NOT NULL,
	request_hash CHAR(64) NOT NULL,
	result_type VARCHAR(40) NOT NULL,
	result_id UUID NOT NULL,
	created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
	expires_at TIMESTAMPTZ NOT NULL,
	
	CONSTRAINT fk_idempotency_keys_actor
		FOREIGN KEY (actor_id)
		REFERENCES app_users (id)
		ON DELETE RESTRICT,
	
	CONSTRAINT uq_idempotency_keys_request
		UNIQUE (actor_id, operation, request_key),
		
	CONSTRAINT chk_idempotency_keys_operation
		CHECK (BTRIM(operation) <> ''),
		
	CONSTRAINT chk_idempotency_keys_request_hash
		CHECK (request_hash ~ '^[0-9a-f]{64}$'),
		
	CONSTRAINT chk_idempotency_keys_result_type
		CHECK (BTRIM(result_type) <> ''),
		
	CONSTRAINT chk_idempotency_keys_expiration
		CHECK (
			expires_at > created_at
			AND expires_at <= created_at + INTERVAL '24 hours'
		)
);

CREATE INDEX idx_idempotency_key_expiration
	ON idempotency_keys (expires_at, id);
	
	
-- 成功結果と入力ハッシュは変更せず、期限切れの行の削除だけを別運用で許可します。

CREATE FUNCTION keepia_reject_update_only()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $function$
BEGIN
	RAISE EXCEPTION '%の既存行は変更できません', TG_TABLE_NAME
    	USING ERRCODE = '55000';
	RETURN NULL;
END;
$function$;

CREATE TRIGGER trg_idempotency_keys_no_update
	BEFORE UPDATE ON idempotency_keys
	FOR EACH ROW
	EXECUTE FUNCTION keepia_reject_update_only();
	

-- =========================================================
-- Java側で同一トランザクションにまとめる主な処理
-- =========================================================
-- 1. 月次報告生成:
--    顧客行ロック、版採番、monthly_reports、report_items、audit_logs。
-- 2. 報告公開:
--    確認版照合、PUBLISHED更新、notification_events、notifications、
--    notification_deliveries。
-- 3. 業務通知登録:
--    安定event_keyのイベント、宛先別画面通知、宛先別メール予約。
-- 4. メール送信:
--    短いTXでSENDINGとリースを保存し、外部送信はTX外で実施する。
--    結果をnotification_attemptsとdelivery状態へ保存する。
-- 5. 二重送信防止:
--    現在の認可を先に確認し、idempotency_keysと業務結果を同じTXで保存する。
	
    
    
    
    
    
    
    
    
    
    
    
    
    







