package jp.keepia.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import jakarta.servlet.FilterChain;
import jp.keepia.config.SecurityProperties;
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
 * {@link CurrentAccessFilter} による認証済み利用者の
 * アクセス制限とセッションの有効性を検証するテストクラスです。
 * <p>
 * 利用者及び所属組織の状態、セキュリティバージョン、
 * セッションの絶対有効期限に応じたアクセス制御を検証します。
 * <p>
 * RepositoryはMockitoのモックを使用し、
 * データベースに依存せずフィルターの動作を検証します。
 */
@ExtendWith(MockitoExtension.class)
class CurrentAccessFilterTest {
	
	/** テストで使用する静的定数です */
	private static final Instant NOW = Instant.parse("2026-10-29T08:00:00Z");
	private static final Duration ABSOLUTE_TIMEOUT = Duration.ofHours(8);
	private static final UUID USER_ID =
			UUID.fromString("20000000-0000-0000-0000-000000000001");
	private static final UUID TENANT_ID =
			UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final UUID CUSTOMER_ID =
			UUID.fromString("30000000-0000-0000-0000-000000000001");
	private static final String EMAIL = "user@example.com";
	private static final String PASSWORD_HASH = "$2a$10$test-password-hash";
	
	/** テストで使用するリポジトリのモックです。 */
	@Mock
	private AppUserRepository appUserRepository;
	
	@Mock
	private TenantRepository tenantRepository;
	
	@Mock
	private CustomerRepository customerRepository;
	
	private CurrentAccessFilter filter;
	
	/**
	 * 各テストの実行前にアクセス制御フィルターを初期化します。
	 * <p>
	 * テスト用のセキュリティ設定と固定時間のClockを使用し、
	 * 各リポジトリのモックを組み込んだフィルターを生成します。
	 */
	@BeforeEach
	void setUp() {
		SecurityProperties properties = new SecurityProperties(
				ABSOLUTE_TIMEOUT,
				Duration.ofMinutes(15),
				5,
				Duration.ofMinutes(30),
				"test-login-key-secret-32-characters-minimum");
		Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
		filter = new CurrentAccessFilter(
				appUserRepository,
				tenantRepository,
				customerRepository,
				properties,
				clock);
	}
	
	/**
	 * 各テストの終了後にセキュリティコンテキストを初期化します。
	 * <p>
	 * 認証情報が後続のテストに影響しないようにします。
	 */
	@AfterEach
	void tearDown() {
		SecurityContextHolder.clearContext();
	}
	
	/**
	 * 未認証リクエストがフィルターを通過することを検証します。
	 * <p>
	 * 認証情報が存在しない場合は後続のフィルターへ処理を渡し、
	 * 各リポジトリへの問い合わせを行わないことを確認します。
	 * @throws Exception
	 */
	@Test
	@DisplayName("未認証リクエストをRepository確認なしで通過させる")
	void unauthenticatedRequestPassesThrough() throws Exception {
		MockHttpServletRequest request = request();
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);
		
		filter.doFilter(request, response, chain);
		
		verify(chain).doFilter(request, response);
		verifyNoInteractions(appUserRepository, tenantRepository, customerRepository);
	}
	
	/**
	 * 現在も有効な管理者のリクエストが通過することを検証します。
	 * <p>
	 * 利用者と保守会社が有効で、セッション期限内の場合に、
	 * 認証情報を維持したまま後続のフィルターへ処理を渡すことを確認します。
	 */
	@Test
	@DisplayName("現在も有効な管理者のリクエストを通過させる")
	void currentAdminRequestPassesThrough() throws Exception {
		AppUser user = admin(UserStatus.ACTIVE, 1L);
		AccountPrincipal principal = authenticate(user, NOW.minus(Duration.ofMinutes(1)));
		when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(user));
		when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(activeTenant()));
		MockHttpServletRequest request = request();
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);
		
		filter.doFilter(request, response, chain);
		
		verify(chain).doFilter(request, response);
		assertThat(response.getRedirectedUrl()).isNull();
		assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
				.isSameAs(principal);
	}
	
	/**
	 * 認証後に利用者が停止された場合に、
	 * 既存セッションが無効化されることを検証します。
	 * <p>
	 * 利用者の現在の状態がSTOPPEDの場合はアクセスを拒否し、
	 * 所属組織の検索が実行されないことを確認します。
	 * @throws Exception
	 */
	@Test
	@DisplayName("利用者停止後は既存セッションを無効化する")
	void stoppedUserInvalidatesSession() throws Exception {
		AppUser authenticatedUser = admin(UserStatus.ACTIVE, 1L);
		authenticate(authenticatedUser, NOW.minus(Duration.ofMinutes(1)));
		AppUser stoppedUser = admin(UserStatus.STOPPED, 1L);
		when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(stoppedUser));
		
		assertSessionIsInvalidated();
		verifyNoInteractions(tenantRepository, customerRepository);
	}
	
	/**
	 * 認証後に保守会社が停止された場合に、
	 * 既存セッションが無効化されることを検証します。
	 * <p>
	 * 利用者自身が有効でも所属する保守会社がSTOPPEDの場合は、
	 * アクセスを拒否し、顧客情報の検索を行わないことを確認します。
	 */
	@Test
	@DisplayName("保守会社停止後は既存セッションを無効化する")
	void stoppedTenantInvalidatesSession() throws Exception { 
		AppUser user = admin(UserStatus.ACTIVE, 1L);
		authenticate(user, NOW.minus(Duration.ofMinutes(1)));
		Tenant stoppedTenant = DomainFixtures.tenant(TENANT_ID, TenantStatus.STOPPED);
		when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(user));
		when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(stoppedTenant));
		
		assertSessionIsInvalidated();
		verifyNoInteractions(customerRepository);
	}
	
	/**
	 * 認証後に顧客が停止された場合に、
	 * 既存セッションが無効化されることを検証します。
	 * <p>
	 * 利用者と保守会社が有効でも、所属する顧客がSTOPPEDの場合は
	 * アクセスを拒否することを確認します。
	 */
	@Test
	@DisplayName("顧客停止後は既存セッションを無効化する")
	void stoppedCustomerInvalidatesSession() throws Exception {
		AppUser user = customer(UserStatus.ACTIVE, 1L);
		authenticate(user, NOW.minus(Duration.ofMinutes(1)));
		Customer stoppedCustomer = 
				DomainFixtures.customer(CUSTOMER_ID, TENANT_ID, CustomerStatus.STOPPED);
		when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(user));
		when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(activeTenant()));
		when(customerRepository.findByIdAndTenantId(CUSTOMER_ID, TENANT_ID))
				.thenReturn(Optional.of(stoppedCustomer));
		
		assertSessionIsInvalidated();
	}
	
	/**
	 * 認証後にセキュリティバージョンが変更された場合に、
	 * 既存セッションが無効化されることを検証します。
	 * <p>
	 * 認証時と現在のバージョンが一致しない場合は、
	 * 所属組織を確認する前にアクセスを拒否することを確認します。
	 */
	@Test
	@DisplayName("securityVersion変更後は既存セッションを無効化する")
	void changedSecurityVersionInvalidatesSession() throws Exception {
		AppUser authenticatedUser = admin(UserStatus.ACTIVE, 1L);
		authenticate(authenticatedUser, NOW.minus(Duration.ofMinutes(1)));
		AppUser changedUser = admin(UserStatus.ACTIVE, 2L);
		when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(changedUser));
		
		assertSessionIsInvalidated();
		verify(tenantRepository, never()).findById(TENANT_ID);
	}
	
	/**
	 * セッションの絶対有効期限に到達した場合に、
	 * 既存セッションが無効化されることを検証します。
	 * <p>
	 * 認証時刻から8時間が経過した時点でアクセスを拒否し、
	 * 各リポジトリへの問い合わせを行わないことを確認します。
	 */
	@Test
	@DisplayName("絶対期限ちょうどで既存セッションを無効化する")
	void absoluteDeadlineInvalidatesSession() throws Exception {
		AppUser user = admin(UserStatus.ACTIVE, 1L);
		authenticate(user, NOW.minus(ABSOLUTE_TIMEOUT));
		
		assertSessionIsInvalidated();
		verifyNoInteractions(appUserRepository, tenantRepository, customerRepository);
	}
	
	/**
	 * フィルターの実行後にセッションが無効化されることを検証します。
	 * <p>
	 * セッションの破棄、ログイン画面へのリダイレクト、
	 * セキュリティコンテキストの認証情報消去を確認します。
	 * 
	 * @throws Exception フィルター処理中に例外が発生した場合です。
	 */
	private void assertSessionIsInvalidated() throws Exception {
		MockHttpServletRequest request = request();
		MockHttpSession session = (MockHttpSession) request.getSession(true);
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);
		
		filter.doFilter(request, response, chain);
		
		assertThat(session.isInvalid()).isTrue();
		assertThat(response.getRedirectedUrl()).isEqualTo("/login?expired");
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
		verify(chain, never()).doFilter(request, response);
	}
	
	/**
	 * 指定された利用者を認証済みとして設定します。
	 * <p>
	 * 利用者情報からPrincipalを生成し、
	 * セキュリティコンテキストに認証情報を登録します。
	 * 
	 * @param user 認証対象の利用者です。
	 * @param authenticatedAt 認証日時です。
	 * @return 生成した認証済みPrincipalを返します。
	 */
	private AccountPrincipal authenticate(AppUser user, Instant authenticatedAt) {
		AccountPrincipal principal = AccountPrincipal.from(user, authenticatedAt);
		UsernamePasswordAuthenticationToken authentication = 
				UsernamePasswordAuthenticationToken.authenticated(
						principal, 
						null, 
						principal.getAuthorities());
		SecurityContextHolder.getContext().setAuthentication(authentication);
		return principal;
	}
	
	/**
	 * テスト用のHTTPリクエストを生成します。
	 * 
	 * @return ルートパスへのGETリクエストを返します。
	 */
	private MockHttpServletRequest request() {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
		request.setServletPath("/");
		return request;
	}
	
	/**
	 * 指定された状態のテスト用管理者を生成します。
	 * 
	 * @param status 利用者の状態です。
	 * @param securityVersion セキュリティバージョンです。
	 * @return テスト用の管理者を返します。
	 */
	private AppUser admin(UserStatus status, long securityVersion) {
		return DomainFixtures.appUser(
				USER_ID, 
				TENANT_ID, 
				null, 
				EMAIL, 
				"Test Admin", 
				UserRole.ADMIN, 
				status, 
				PASSWORD_HASH, 
				securityVersion);
	}
	
	/**
	 * 指定された状態のテスト用顧客利用者を生成します。
	 * 
	 * @param status 利用者の状態です。
	 * @param securityVersion セキュリティバージョンです。
	 * @return テスト用の顧客利用者を返します。
	 */
	private AppUser customer(UserStatus status, long securityVersion) {
		return DomainFixtures.appUser(
				USER_ID, 
				TENANT_ID, 
				CUSTOMER_ID, 
				EMAIL, 
				"Test Customer",
				UserRole.CUSTOMER,
				status,
				PASSWORD_HASH, 
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
