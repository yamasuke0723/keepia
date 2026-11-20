package jp.keepia.security;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.keepia.domain.AppUser;
import jp.keepia.domain.DomainFixtures;
import jp.keepia.domain.UserRole;
import jp.keepia.domain.UserStatus;

/**
 * {@link AccountPrincipal} の生成と認証情報の管理を検証するテストクラスです。
 * <p>
 * 利用者情報からの認証情報と権限の生成、資格情報の消去、
 * 文字列表現における機密情報の保護を検証します。
 */
class AccountPrincipalTest {
	
	/** テストで使用する利用者IDです */
	private static final UUID USER_ID = UUID.randomUUID();
	
	/** テストで使用するテナントIDです */
	private static final UUID TENANT_ID = UUID.randomUUID();
	
	/** テストで使用するメールアドレスです */
	private static final String EMAIL = "admin@example.com";
	
	/** テストで使用するパスワードハッシュです */
	private static final String PASSWORD_HASH = "$2a$10$test-password-hash";
	
	/** テストで使用する認証日時です */
	private static final Instant AUTHENTICATED_AT = 
			Instant.parse("2026-10-29T01:00:00Z");
	
	/**
	 * 利用者情報から認証principalが正しく生成されることを検証します。
	 * <p>
	 * 利用者ID,テナントID、メールアドレス、役割、認証日時が
	 * 正しく設定され、ADMIN権限が付与される事を確認します。
	 */
	@Test
	@DisplayName("利用者情報から認証PrincipalとROLE権限を生成する")
	void createsPrincipalAndAuthorityFromUser() {
		AccountPrincipal principal = AccountPrincipal.from(activeAdmin(), AUTHENTICATED_AT);
		
		assertThat(principal.getUserId()).isEqualTo(USER_ID);
		assertThat(principal.getTenantId()).isEqualTo(TENANT_ID);
		assertThat(principal.getUsername()).isEqualTo(EMAIL);
		assertThat(principal.getRole()).isEqualTo(UserRole.ADMIN);
		assertThat(principal.getAuthenticatedAt()).isEqualTo(AUTHENTICATED_AT);
		assertThat(principal.getAuthorities())
				.extracting("authority")
				.containsExactly("ROLE_ADMIN");
	}
	
	/**
	 * 資格情報の消去によって、パスワードのみが破棄されることを検証します。
	 * <p>
	 * 消去後も利用者ID、メールアドレス、役割は維持され、
	 * 認証済み利用者の識別情報が失われないことを確認します。
	 */
	@Test
	@DisplayName("資格情報消去後も識別情報を維持しパスワードだけを破棄する")
	void erasesOnlyPasswordCredentials() {
		AccountPrincipal principal = AccountPrincipal.from(activeAdmin(), AUTHENTICATED_AT);
		
		principal.eraseCredentials();
		
		assertThat(principal.getPassword()).isNull();
		assertThat(principal.getUserId()).isEqualTo(USER_ID);
		assertThat(principal.getUsername()).isEqualTo(EMAIL);
		assertThat(principal.getRole()).isEqualTo(UserRole.ADMIN);
	}
	
	/**
	 * 文字列表現に機密情報が含まれないことを検証します。
	 * <p>
	 * メールアドレスとパスワードハッシュが出力されず、
	 * 利用者IDと役割は含まれることを確認します。
	 * これにより、ログ出力などを通じた機密情報の漏えいを防ぎます。
	 */
	@Test
	@DisplayName("文字列表現へメールアドレスとパスワードハッシュを含めない")
	void stringRepresentationDoesNotExposeCredentials() {
		AccountPrincipal principal = AccountPrincipal.from(activeAdmin(), AUTHENTICATED_AT);
		
		assertThat(principal.toString())
				.doesNotContain(EMAIL)
				.doesNotContain(PASSWORD_HASH)
				.contains(USER_ID.toString())
				.contains("ADMIN");
	}
	
	/**
	 * テスト用の有効な管理者利用者を生成します。
	 * <p>
	 * 固定のメールアドレスとパスワードハッシュを設定し、
	 * 役割をADMIN、利用状態をACTIVEとして生成します。
	 * 
	 * @return テスト用の管理者利用者を返します。
	 */
	private AppUser activeAdmin() {
		return DomainFixtures.appUser(
				USER_ID,
				TENANT_ID,
				null,
				EMAIL,
				"Test Admin",
				UserRole.ADMIN,
				UserStatus.ACTIVE,
				PASSWORD_HASH,
				3L);
	}

}
