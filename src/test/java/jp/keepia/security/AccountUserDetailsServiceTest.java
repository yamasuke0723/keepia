package jp.keepia.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import jp.keepia.domain.AppUser;
import jp.keepia.domain.Customer;
import jp.keepia.domain.CustomerStatus;
import jp.keepia.domain.DomainFixtures;
import jp.keepia.domain.Tenant;
import jp.keepia.domain.TenantStatus;
import jp.keepia.domain.UserRole;
import jp.keepia.domain.UserStatus;
import jp.keepia.repository.AppUserRepository;
import jp.keepia.repository.CustomerRepository;
import jp.keepia.repository.TenantRepository;

/**
 * {@link AccountUserDetailsService} の認証対象利用者の取得処理を検証する
 * テストクラスです。
 * <p>
 * 利用者情報の取得とメールアドレスの正規化、利用者状態や所属組織の
 * 妥当性判定、認証情報の生成、認証失敗時の例外処理を検証します。
 * <p>
 * リポジトリにはMockitoのモックを使用し、
 * データベースに依存せず認証処理を検証します。
 */
@ExtendWith(MockitoExtension.class)
class AccountUserDetailsServiceTest {
	
	/** テストで使用する固定の現在日時です。 */
	private static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z");
	
	/** テストで使用する利用者IDです。 */
	private static final UUID USER_ID =
			UUID.fromString("20000000-0000-0000-0000-000000000001");
	
	/** テストで使用する保守会社IDです。 */
	private static final UUID TENANT_ID =
			UUID.fromString("10000000-0000-0000-0000-000000000001");
	
	/** テストで使用する顧客IDです。 */
	private static final UUID CUSTOMER_ID =
			UUID.fromString("30000000-0000-0000-0000-000000000001");
	
	/** テストで使用するメールアドレスです。 */
	private static final String EMAIL = "admin@example.com";
	
	/** テストで使用するパスワードハッシュです。 */
	private static final String PASSWORD_HASH = "$2a$10$test-password-hash";
	
	/** 利用者情報を取得するリポジトリのモックです。 */
	@Mock
	private AppUserRepository appUserRepository;
	
	/** 保守会社情報を取得するリポジトリのモックです。 */
	@Mock
	private TenantRepository tenantRepository;
	
	/** 顧客情報を取得するリポジトリのモックです。 */
	@Mock
	private CustomerRepository customerRepository;
	
	/** テスト対象の認証利用者情報取得サービスです。 */
	private AccountUserDetailsService service;
	
	/**
	 * 	各テストの実行前に、テスト対象のサービスを初期化します。
	 * <p>
	 * 現在日時を固定したClockと各リポジトリのモックを使用し、
	 * テスト結果が実行時刻やデータベースの状態に依存しないようにします。
	 */
	@BeforeEach
	void setUp() {
		Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
		service = new AccountUserDetailsService(
				appUserRepository,
				tenantRepository,
				customerRepository,
				clock);
	}
	
	/**
	 * 有効な管理者を正規化済みメールアドレスで取得できることを検証します。
	 * <p>
	 * 入力されたメールアドレスの空白や大文字・小文字が正規化され、
	 * 利用者情報、認証日時、管理者権限が正しく設定されることを確認します。
	 */
	@Test
	@DisplayName("ACTIVEの管理者を正規化済みメールで読み込める")
	void loadActiveAdminWithNormalizedEmail() {
		AppUser user = appUser(
				TENANT_ID, null, UserRole.ADMIN, UserStatus.ACTIVE, PASSWORD_HASH, 3L);
		when(appUserRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
		when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(activeTenant()));
		
		AccountPrincipal principal = 
				(AccountPrincipal)service.loadUserByUsername("  Admin@Example.COM ");
		
		assertThat(principal.getUserId()).isEqualTo(USER_ID);
		assertThat(principal.getUsername()).isEqualTo(EMAIL);
		assertThat(principal.getAuthenticatedAt()).isEqualTo(NOW);
		assertThat(principal.getAuthorities())
				.extracting(GrantedAuthority::getAuthority)
				.containsExactly("ROLE_ADMIN");
	}
	
	/**
	 * 招待中の利用者が認証対象として拒否されることを検証します。
	 * <p>
	 * 利用状態がINVITEDの場合は認証を許可せず、
	 * 所属する保守会社の検索も実行されないことを確認します。
	 */
	@Test
	@DisplayName("INVITEDの利用者を拒否する")
	void rejectsInvitedUser() {
		AppUser user = appUser(
				TENANT_ID, null, UserRole.ADMIN, UserStatus.INVITED, PASSWORD_HASH, 0L);
		when(appUserRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
		
		assertThatThrownBy(() -> service.loadUserByUsername(EMAIL))
				.isInstanceOf(DisabledException.class)
				.hasMessage("Authentication is unavailable");
		verify(tenantRepository, never()).findById(TENANT_ID);
	}
	
	/**
	 * 利用停止中の利用者が認証対象として拒否されることを検証します。
	 * <p>
	 * 利用状態がSTOPPEDの場合は例外が発生し、
	 * 所属する保守会社の検索が実行されないことを確認します。
	 */
	@Test
	@DisplayName("STOPPEDの利用者を拒否する")
	void rejectsStoppedUser() {
		AppUser user = appUser(
				TENANT_ID, null, UserRole.ADMIN, UserStatus.STOPPED, PASSWORD_HASH, 0L);
		when(appUserRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
		
		assertThatThrownBy(() -> service.loadUserByUsername(EMAIL))
				.isInstanceOf(DisabledException.class)
				.hasMessage("Authentication is unavailable");
		verify(tenantRepository, never()).findById(TENANT_ID);
	}
	
	/**
	 * パスワードハッシュが未設定の利用者を拒否することを検証します。
	 * 	<p>
	 * 利用状態がACTIVEでもパスワードハッシュが存在しない場合は
	 * 認証を拒否し、所属組織の検索を実行しないことを確認します。
	 */
	@Test
	@DisplayName("パスワードハッシュ未設定の利用者を拒否する")
	void rejectsUserWithoutPasswordHash() {
		AppUser user = appUser(
				TENANT_ID, null, UserRole.ADMIN, UserStatus.ACTIVE, null, 0L);
		when(appUserRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
		
		assertThatThrownBy(() -> service.loadUserByUsername(EMAIL))
				.isInstanceOf(DisabledException.class)
				.hasMessage("Authentication is unavailable");
		verifyNoInteractions(tenantRepository, customerRepository);
	}
	
	/**
	 * 停止中の保守会社に所属する利用者が拒否されることを検証します。
	 * <p>
	 * 利用者自身がACTIVEであっても、所属する保守会社がSTOPPEDの場合は
	 * 認証を許可しないことを確認します。
	 */
	@Test
	@DisplayName("停止中の保守会社に所属する利用者を拒否する")
	void rejectsUserInStoppedTenant() {
		AppUser user = appUser(
				TENANT_ID, null, UserRole.ADMIN, UserStatus.ACTIVE, PASSWORD_HASH, 0L);
		Tenant tenant = DomainFixtures.tenant(TENANT_ID, TenantStatus.STOPPED);
		when(appUserRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
		when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(tenant));
		
		assertThatThrownBy(() -> service.loadUserByUsername(EMAIL))
				.isInstanceOf(DisabledException.class)
				.hasMessage("Authentication is unavailable");
	}
	
	/**
	 * 停止中の顧客に所属する顧客利用者が拒否されることを検証するテストです。
	 * <p>
	 * 保守会社がACTIVEでも、所属する顧客がSTOPPEDの場合は
	 * 認証を許可しないことを検証するためのデータを準備します。
	 */
	@Test
	@DisplayName("停止中の顧客に所属する顧客利用者を拒否する")
	void rejectsUserInStoppedCustomer() {
		AppUser user = appUser(
				TENANT_ID,
				CUSTOMER_ID,
				UserRole.CUSTOMER,
				UserStatus.ACTIVE,
				PASSWORD_HASH,
				0L);
		Customer customer = 
				DomainFixtures.customer(CUSTOMER_ID, TENANT_ID, CustomerStatus.STOPPED);
		when(appUserRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
		when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(activeTenant()));
		when(customerRepository.findByIdAndTenantId(CUSTOMER_ID, TENANT_ID))
					.thenReturn(Optional.of(customer));
		
		assertThatThrownBy(() -> service.loadUserByUsername(EMAIL))
        .isInstanceOf(DisabledException.class)
        .hasMessage("Authentication is unavailable");
	}
	
	/**
	 * 運営者が所属組織の検索を行わずに取得されることを検証します。
	 * <p>
	 * OPERATORは保守会社と顧客に所属しないため、
	 * 両リポジトリへの問い合わせが発生しないことを確認します。
	 */
	@Test
	@DisplayName("運営者は保守会社と顧客を検索せずに読み込める")
	void loadsOperatorWithoutOrganizationLookup() {
		AppUser user = appUser(
				null, null, UserRole.OPERATOR, UserStatus.ACTIVE, PASSWORD_HASH, 0L);
		when(appUserRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
		
		AccountPrincipal principal = (AccountPrincipal) service.loadUserByUsername(EMAIL);
		
		assertThat(principal.getRole()).isEqualTo(UserRole.OPERATOR);
		assertThat(principal.getTenantId()).isNull();
		assertThat(principal.getCustomerId()).isNull();
		verifyNoInteractions(tenantRepository, customerRepository);
	}
	
	/**
	 * 保守会社IDが未設定の管理者を拒否することを検証します。
	 * <p>
	 * 管理者に必要な所属情報が存在しない場合は、
	 * 所属組織の検索を行わず認証を拒否することを確認します。
	 */
	@Test
	@DisplayName("保守会社IDのない管理者を拒否する")
	void rejectsAdminWithoutTenant() {
		AppUser user = appUser(
				null, null, UserRole.ADMIN, UserStatus.ACTIVE, PASSWORD_HASH, 0L);
		when(appUserRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
		
		assertThatThrownBy(() -> service.loadUserByUsername(EMAIL))
				.isInstanceOf(DisabledException.class);
		verifyNoInteractions(tenantRepository, customerRepository);
	}
	
	/**
	 * 保守会社IDが未設定の担当者を拒否することを検証します。
	 * <p>
	 * STAFFに必要な所属情報が存在しない場合は、
	 * 所属組織の検索を行わず認証を拒否することを確認します。
	 */
	@Test
	@DisplayName("保守会社IDのない担当者を拒否する")
	void rejectsStaffWithoutTenant() {
		AppUser user = appUser(
				null, null, UserRole.STAFF, UserStatus.ACTIVE, PASSWORD_HASH, 0L);
		when(appUserRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
		
		assertThatThrownBy(() -> service.loadUserByUsername(EMAIL))
				.isInstanceOf(DisabledException.class);
		verifyNoInteractions(tenantRepository, customerRepository);
	}
	
	/**
	 * 顧客IDが未設定の顧客利用者を拒否することを検証します。
	 * <p>
	 * CUSTOMERに必要な顧客IDが存在しない場合は、
	 * 所属組織の検索を行わず認証を拒否することを確認します。
	 */
	@Test
	@DisplayName("顧客IDのない顧客利用者を拒否する")
	void rejectsCustomerWithoutCustomerId() {
		AppUser user = appUser(
				TENANT_ID, null, UserRole.CUSTOMER, UserStatus.ACTIVE, PASSWORD_HASH, 0L);
		when(appUserRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
		
		assertThatThrownBy(() -> service.loadUserByUsername(EMAIL))
				.isInstanceOf(DisabledException.class);
		verifyNoInteractions(tenantRepository, customerRepository);
	}
	
	/**
	 * 保守会社IDが未設定の顧客利用者を拒否することを検証します。
	 * <p>
	 * 顧客IDが設定されていても保守会社IDが存在しない場合は、
	 * 所属組織の検索を行わず認証を拒否することを確認します。
	 */
	@Test
	@DisplayName("保守会社IDのない顧客利用者を拒否する")
	void rejectsCustomerWithoutTenantId() {
		AppUser user = appUser(
				null,
				CUSTOMER_ID,
				UserRole.CUSTOMER,
				UserStatus.ACTIVE,
				PASSWORD_HASH,
				0L);
		when(appUserRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
		
		assertThatThrownBy(() -> service.loadUserByUsername(EMAIL))
				.isInstanceOf(DisabledException.class);
		verifyNoInteractions(tenantRepository, customerRepository);
	}
	
	/**
	 * 有効な保守会社と顧客に所属する顧客利用者を取得できることを検証します。
	 * <p>
	 * 利用者と所属組織がいずれもACTIVEの場合に、
	 * 顧客権限、所属ID、セキュリティバージョンが正しく設定されることを確認します。
	 */
	@Test
	@DisplayName("有効な保守会社と顧客に所属する顧客利用者を読み込める")
	void loadsActiveCustomerUser() {
		AppUser user = appUser(
				TENANT_ID,
				CUSTOMER_ID,
				UserRole.CUSTOMER,
				UserStatus.ACTIVE,
				PASSWORD_HASH,
				7L);
		Customer customer = 
				DomainFixtures.customer(CUSTOMER_ID, TENANT_ID, CustomerStatus.ACTIVE);
		when(appUserRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
		when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(activeTenant()));
		when(customerRepository.findByIdAndTenantId(CUSTOMER_ID, TENANT_ID))
				.thenReturn(Optional.of(customer));
		
		AccountPrincipal principal = (AccountPrincipal) service.loadUserByUsername(EMAIL);
		
		assertThat(principal.getRole()).isEqualTo(UserRole.CUSTOMER);
		assertThat(principal.getTenantId()).isEqualTo(TENANT_ID);
		assertThat(principal.getCustomerId()).isEqualTo(CUSTOMER_ID);
		assertThat(principal.getSecurityVersion()).isEqualTo(7L);
	}
	
	/**
	 * 利用者の存在有無を推測できない認証エラーメッセージを検証します。
	 * <p>
	 * 存在しない利用者と利用停止中の利用者で例外の種類が異なっても、
	 * 同一の汎用エラーメッセージが返されることを確認します。
	 */
	@Test
	@DisplayName("存在しない利用者と利用停止利用者で同じ汎用メッセージを返す")
	void hidesWhetherAccountExists() {
		when(appUserRepository.findByEmail("missing@example.com")).thenReturn(Optional.empty());
		AppUser stopped = appUser(
				TENANT_ID, null, UserRole.ADMIN, UserStatus.STOPPED, PASSWORD_HASH, 0L);
		when(appUserRepository.findByEmail(EMAIL)).thenReturn(Optional.of(stopped));
		
		Throwable missing = org.assertj.core.api.Assertions.catchThrowable(
				() -> service.loadUserByUsername("missing@example.com"));
		Throwable disabled = org.assertj.core.api.Assertions.catchThrowable(
				() -> service.loadUserByUsername(EMAIL));
		
		assertThat(missing).isInstanceOf(UsernameNotFoundException.class);
		assertThat(disabled).isInstanceOf(DisabledException.class);
		assertThat(missing.getMessage()).isEqualTo(disabled.getMessage());
		assertThat(missing.getMessage()).isEqualTo("Authentication is unavailable");
	}
	
	/**
	 * 指定された属性を持つテスト用の利用者を生成します。
	 * <p>
	 * 利用者ID,メールアドレス、表示名には固定値を使用し、
	 * 役割や利用状態などをテストケースに応じて設定します。
	 * 
	 * @param tenantId 所属する保守会社IDです
	 * @param customerId 所属する顧客IDです
	 * @param role 利用者の役割です
	 * @param status 利用者の状態です
	 * @param passwordHash パスワードハッシュです
	 * @param securityVersion セキュリティバージョンです
	 * @return 指定された属性をもつテスト用の利用者を返します。
	 */
	private AppUser appUser(
			UUID tenantId,
			UUID customerId,
			UserRole role,
			UserStatus status,
			String passwordHash,
			long securityVersion) {
		return DomainFixtures.appUser(
				USER_ID,
				tenantId,
				customerId,
				EMAIL,
				"Test User",
				role,
				status,
				passwordHash,
				securityVersion);
	}
	
	/**
	 * テスト用の有効な保守会社を生成します。
	 * 
	 * @return 利用状態がACTIVEの保守会社を返します。
	 */
	private Tenant activeTenant() {
		return DomainFixtures.tenant(TENANT_ID, TenantStatus.ACTIVE);
	}
	
}
