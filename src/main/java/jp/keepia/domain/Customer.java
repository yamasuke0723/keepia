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
 * 保守会社に所属する顧客を表すJPA Entityです。
 * 
 *  <p>第一実装工程では顧客管理機能を提供せず、顧客利用者の認証状態を
 *     再確認する目的で読み取りに使用します。</p>
 */
@Entity
@Table(name= "customers")
public class Customer {
	
	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;
	
	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;
	
	@Column(name = "name", nullable = false, length = 100)
	private String name;
	
	@Column(name = "contact_email", nullable = false, length = 254)
	private String contactEmail;
	
	@Column(name = "contact_phone", length = 30)
	private String contactPhone;
	
	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 16)
	private CustomerStatus status;
	
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;
	
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;
	
	@Version
	@Column(name = "version", nullable = false)
	private long version;
	
	/**
	 * JPAがEntityを復元するために使用するコンストラクタです。
	 */
	protected Customer() {
	}
	
	/**
	 * 顧客IDを返します。
	 * 
	 * @return 顧客を一意に識別するUUID
	 */
	public UUID getId() {
		return id;
	}
	
	/**
	 * 顧客が所属する保守会社IDを返します。
	 * 
	 * @return 所属先の保守会社Id
	 */
	public UUID getTenantId() {
		return tenantId;
	}
	
	/**
	 * 顧客名を返します。
	 * 
	 * @return 画面表示および帳票で使用する顧客名
	 */
	public String getName() {
		return name;
	}
	
	/**
	 * 顧客の連絡先メールアドレスを返します。
	 * 
	 * @return 連絡先メールアドレス
	 */
	public String getContactEmail() {
		return contactEmail;
	}
	
	/**
	 * 顧客の連絡先電話番号を返します。
	 * 
	 * @return 連絡先電話番号。未登録の場合は{@code null}
	 */
	public String getContactPhone() {
		return contactPhone;
	}
	
	/**
	 * 顧客の利用状態を返します。
	 * 
	 * @return 現在の顧客状態
	 */
	public CustomerStatus getStatus() {
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
	public Instant getUpdatedAt() {
		return updatedAt;
	}
	
	/**
	 * 楽観ロックに使用するバージョンを返します。
	 * 
	 * @return JPAが管理するバージョン番号
	 */
	public long getVersion() {
		return version;
	}
	
	/**
	 * 顧客が現在利用可能かを判定します
	 * 
	 * @return {@link CustomerStatus#ACTIVE}の場合は{@code true}
	 */
	public boolean isActive() {
		return status == CustomerStatus.ACTIVE;
	}
}

