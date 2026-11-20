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
 * keepiaへログインする利用者を表すJPA Entityです。
 * 
 * <p>運営者だけは保守会社へ所属しません。顧客利用者だけは保守会社と顧客の
 *    両方へ所属します。DB制約に加え、認証時にも所属関係を検証します。</p>
 */
@Entity
@Table(name = "app_users")
public class AppUser {
	
	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;
	
	@Column(name = "tenant_id", updatable = false)
	private UUID tenantId;
	
	@Column(name = "customer_id", updatable = false)
	private UUID customerId;
	
	@Column(name = "email", nullable = false, length = 254)
	private String email;
	
	@Column(name = "display_name", nullable = false, length = 100)
	private String displayName;
	
	@Enumerated(EnumType.STRING)
	@Column(name = "role", nullable = false, length = 16)
	private UserRole role;
	
	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 16)
	private UserStatus status;
	
	@Column(name = "password_hash", length = 255)
	private String passwordHash;
	
	@Column(name = "security_version", nullable = false)
	private long securityVersion;
	
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
	protected AppUser() {
	}
	
	/**
	 * 利用者IDを返します。
	 * 
	 * @return 利用者を一意に識別するUUID
	 */
	public UUID getId() {
		return id;
	}
	
	/**
	 * 利用者が所属する保守会社IDを返します。
	 * 
	 * @return 所属先の保守会社ID。運営者の場合は{@code null}
	 */
	public UUID getTenantId() {
		return tenantId;
	}
	
	/**
	 * 利用者が所属する顧客IDを返します。
	 * 
	 * 	@return 所属先の顧客ID。顧客利用者以外は{@code null}
	 */
	public UUID getCustomerId() {
		return customerId;
	}
	
	/**
	 * ログイン識別子として使用するメールアドレスを返します。
	 * 
	 * @return 正規化して保存されたメールアドレス
	 */
	public String getEmail() {
		return email;
	}
	
	/**
	 * 画面表示用の利用者名を返します。
	 * 
	 * @return 利用者の表示名
	 */
	public String getDisplayName() {
		return displayName;
	}
	
	/**
	 * 利用者へ割り当てられた役割を返します。
	 * 
	 * @return 認可判定に使用する役割
	 */
	public UserRole getRole() {
		return role;
	}
	
	/**
	 * 利用者のアカウント状態を返します。
	 * 
	 * @return 現在の利用者状態
	 */
	public UserStatus getStatus() {
		return status;
	}
	
	/**
	 * Spring Securityがパスワード照合に使用するハッシュ値を返します。
	 * 
	 * <p>画面、DTO、ログへ渡してはならない。</p>
	 * 
	 * @return 保存済みパスワードハッシュ。未設定の場合は{@code null}
	 */
	public String getPasswordHash() {
		return passwordHash;
	}
	
	/**
	 * セッションの強制失効判定に使用するセキュリティ版番号を返します。
	 * 
	 * @return 現在のセキュリティ版番号
	 */
	public long getSecurityVersion() {
		return securityVersion;
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
	 * 楽観ロックに使用するバージョン番号を返します。
	 * 
	 * @return JPAが管理するバージョン番号
	 */
	public long getVersion() {
		return version;
	}
	
	/**
	 * 利用者が現在ログイン可能な状態かを判定します。
	 * 
	 * @return {@link UserStatus#ACTIVE}の場合は{@code true}
	 */
	public boolean isActive() {
		return status == UserStatus.ACTIVE;
	}
	
	/**
	 * パスワードハッシュが設定されているかを判定します。
	 * 
	 * @return nullでも空文字でもない場合は{code true}
	 */
	public boolean hasPassword() {
		return passwordHash != null && !passwordHash.isBlank(); 
	}
	
	/**
	 * 運営者アカウントかを判定します。
	 * 
	 * @return 運営者の場合は{@code true}
	 */
	public boolean isOperator() {
		return role == UserRole.OPERATOR;
	}
	
	/**
	 * 顧客利用者アカウント化を判定します。
	 * 
	 * @return 顧客利用者の場合は{@code true}
	 */
	public boolean isCustomer() {
		return role == UserRole.CUSTOMER;
	}
	
	/**
	 * 役割と所属IDの組み合わせがDB設計に適合するかを判定します。
	 * 
	 * @return 組み合わせが正しい場合は{@code true}
	 */
	public boolean hasValidAssignment() {
		return switch(role) {
			case OPERATOR -> tenantId == null && customerId == null;
			case ADMIN, STAFF -> tenantId != null && customerId == null;
			case CUSTOMER -> tenantId != null && customerId != null;
		};
	}
}
