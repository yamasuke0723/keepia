package jp.keepia.security;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import jp.keepia.domain.AppUser;
import jp.keepia.domain.UserRole;

/**
 * Spring Securityのセッションへ保存する認証済み利用者情報のクラスです。
 * 
 * <p>利用者入力から生成せず、DBから取得して検証済みの
 *    {@link AppUser}だけを生成元とします。</p>
 */
public final class AccountPrincipal
		implements UserDetails, CredentialsContainer, Serializable {
	
	@Serial
	private static final long serialVersionUID = 1L;
	
	private final UUID userId;
	private final UUID tenantId;
	private final UUID customerId;
	private final String email;
	private final String displayName;
	private final UserRole role;
	private String passwordHash;
	private final long securityVersion;
	private final Instant authenticatedAt;
	private final GrantedAuthority authority;
	
	private AccountPrincipal(AppUser user, Instant authenticatedAt) {
		this.userId = Objects.requireNonNull(user.getId(), "user.id");
		this.tenantId = user.getTenantId();
		this.customerId = user.getCustomerId();
		this.email = Objects.requireNonNull(user.getEmail(), "user.eimal");
		this.displayName = Objects.requireNonNull(user.getDisplayName(), "user.displayName");
		this.role = Objects.requireNonNull(user.getRole(), "user.role");
		this.passwordHash = Objects.requireNonNull(user.getPasswordHash(), "user.passwordHash");
		this.securityVersion = user.getSecurityVersion();
		this.authenticatedAt = Objects.requireNonNull(authenticatedAt, "authenticatedAt");
		this.authority = new SimpleGrantedAuthority(role.authority());
	}

	/**
	 * 検証済み利用者から認証Principalを生成します。
	 * 
	 * @param user 検証済み利用者
	 * @param authenticatedAt 認証を開始した時刻
	 * @return 不変の認証Principal
	 */
	public static AccountPrincipal from(AppUser user, Instant authenticatedAt) {
		return new AccountPrincipal(user, authenticatedAt);
	}
	
	/**
     * 認証済み利用者IDを返す。
     *
     * @return 利用者を一意に識別するUUID
     */
    public UUID getUserId() {
        return userId;
    }

    /**
     * 認証済み利用者の保守会社IDを返す。
     *
     * @return 所属先の保守会社ID。運営者の場合は{@code null}
     */
    public UUID getTenantId() {
        return tenantId;
    }

    /**
     * 認証済み利用者の顧客IDを返す。
     *
     * @return 所属先の顧客ID。顧客利用者以外は{@code null}
     */
    public UUID getCustomerId() {
        return customerId;
    }

    /**
     * 認証済み利用者の画面表示名を返す。
     *
     * @return 利用者の表示名
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * 認証済み利用者の役割を返す。
     *
     * @return 認可判定に使用する役割
     */
    public UserRole getRole() {
        return role;
    }

    /**
     * 認証時点のセキュリティ版番号を返す。
     *
     * @return セッション失効判定に使用する版番号
     */
    public long getSecurityVersion() {
        return securityVersion;
    }

    /**
     * 認証が成立した日時を返す。
     *
     * @return 認証成立日時
     */
    public Instant getAuthenticatedAt() {
        return authenticatedAt;
    }
    
    /**
     * Spring Securityが認可判定に使用する権限を返します。
     * 
     * @return 利用者の役割から生成した変更不可の権限一覧
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities(){
    	return List.of(authority);
    }
    
    /**
     * Spring Securityが認証時に照合するパスワードハッシュを返します。
     * 
     * @return 認証処理中はDBから取得したハッシュ。認証完了後は{@code null}
     */
    @Override
    public String getPassword() {
    	return passwordHash;
    }
	
	/**
	 * 認証完了後、セッションへパスワードハッシュを残さないように消去します。
	 * 
	 * <p>Spring Securityの{@link CredentialContainer}契約により、
	 *    認証処理が完了した後に呼び出されます。</p>
	 */
    @Override
    public void eraseCredentials() {
    	passwordHash = null;
    }
    
    /**
     * Spring Security上の利用者名を返します。
     * 
     * @return ログイン識別子であるメールアドレス
     */
    @Override
    public String getUsername() {
    	return email;
    }
	
    /**
     * アカウントの有効期限が切れていないかを返します。
     * 
     * 	<p>有効状態は認証サービスとリクエストフィルターで別途確認するため、
     *     この実装では常に{@code true}を返します。</p>
     *     
     *  @return 常に{@code true}
     */
    @Override
    public boolean isAccountNonExpired() {
    	return true;
    }
	
    /**
     * アカウントがロックされていないかを返します。
     * 
     * <p>keepiaの利用停止状態はDBの状態値で判定するため、
     *    この実装では常に{@code true}を返します。</p>
     *    
     * @return 常に{@code true}
     */
	@Override
	public boolean isAccountNonLocked() {
		return true;
	}
	
	/**
	 * 認証情報の有効期限が切れていないかを返します。
	 * 
	 * <p>パスワード変更時はsecurityVersionを更新してセッションを失効されるため、
	 *    この実装では常に{@code true}を返します。</p>
	 *    
	 * @return 常に{@code true}
	 */
	@Override
	public boolean isCredentialsNonExpired() {
		return true;
	}
	
	/**
	 * Spring Security上で利用可能なPrincipalかを返します。
	 * 
	 * <p>DB上の利用状態は認証時及び各リクエストで確認するため、
	 *    この実装では常に{@code true}を返します。</p>
	 *    
	 * @return 常に{@code true}
	 */
	@Override
	public boolean isEnabled() {
		return true;
	}
	
	/**
	 * 利用者IDを基準にPrincipalの同一性を判定します。
	 * 
	 * 	@param other 比較対象
	 *  @return 同じ利用者IDを持つ場合は{@code true}
	 */
	@Override
	public boolean equals(Object other) {
		if(this == other) {
			return true;
		}
		if(!(other instanceof AccountPrincipal that)) {
			return false;
		}
		return userId.equals(that.userId);
	}
	
	/**
	 * 利用者IDを基準にハッシュ値を返します。
	 * 
	 * @return 利用者IDのハッシュ値
	 */
	@Override
	public int hashCode() {
		return userId.hashCode();
	}
	
	/**
	 * ログ確認用の安全な文字列表現を返します。
	 * 
	 * <p>メールアドレスおよびパスワードハッシュは含めません。</p>
	 * 
	 * @return 利用者IDと役割だけを含む文字列
	 */
	@Override
	public String toString() {
		return "AccountPrincipal[userId= " + userId + ", role=" + role + "]"; 
	}
	
	
}
