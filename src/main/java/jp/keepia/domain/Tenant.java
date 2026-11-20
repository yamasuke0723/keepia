package jp.keepia.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * 1つの保守会社を表すJPA Entityです。
 * 	<p>認証処理では、利用者が所属する保守会社が
 *     現在も利用可能かをリクエストごとに確認するために使用します。</p>
 */
@Entity
@Table(name = "tenants")
public class Tenant {
	
	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;
	
	@Column(name = "name", nullable = false, length = 100)
	private String name;
	
	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 16)
	private TenantStatus status;
	
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;
	
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;
	
	@Version
	@Column(name = "version", nullable = false)
	private long version;
	
	/***
	 * JPAがEntityを復元するために使用するコンストラクタです。
	 */
	protected Tenant() {
	}
	
	/**
	 * 保守会社IDを返します。
	 * 
	 * @return 保守会社を一意に識別するUUID
	 */
	public UUID getId() {
		return id;
	}
	
	/**
	 * 保守会社名を返します。
	 * 
	 * @return 画面表示および帳票で使用する保守会社名
	 */
	public String getName() {
		return name;
	}
	
	/**
	 * 保守会社の利用状態を返します。
	 * 
	 * @return 現在の保守会社状態
	 */
	public TenantStatus getStatus() {
		return status;
	}
	
	/**
	 * レコードの作成日時を返します。
	 * 
	 * @return UTCで保存された作成日時
	 */
	public Instant getCreatedAt() {
		return createdAt;
	}
	
	/**
	 * レコードの最終更新日時を返します。
	 * 
	 * @return UTCで保存された最終更新日時
	 */
	public Instant getUpdatedAt(){
		return updatedAt;
	}
	
	/**
	 * 楽観ロックに使用するバージョン番号を返します。
	 * 
	 * @return JPAが管理するバージョン番号
	 */
	public long getVersion() {
		return version;
	}
	
	/**
	 * 保守会社が現在利用可能かを判定します
	 * 
	 * @return {@link TenantStatus#ACTIVE}の場合は{@code true}
	 */
	public boolean isActive() {
		return status == TenantStatus.ACTIVE;
	}

}
