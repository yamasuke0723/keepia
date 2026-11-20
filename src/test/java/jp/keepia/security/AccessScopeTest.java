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
 * {@link AccessScope} の生成とアクセス範囲の妥当性を検証するテストクラスです。
 * <p>
 * 利用者の役割に応じた所属IDの制約、認証済み利用者からのアクセス範囲の生成、
 * セキュリティバージョンの妥当性を検証します。
 */
class AccessScopeTest {
	
	/**
	 * 運営者がテナントIDと顧客IDを持たない場合に、
	 * 運営者として正しく判定されることを検証します。
	 */
	@Test
	@DisplayName("運営者はtenantIdとcustomerIdを持たない")
	void operatorDoesNotHaveTenantOrCustomer() {
		AccessScope scope = new AccessScope(
				UUID.randomUUID(), null, null, UserRole.OPERATOR, 0L);
		
		assertThat(scope.isOperator()).isTrue();
		assertThat(scope.tenantId()).isNull();
		assertThat(scope.customerId()).isNull();
	}
	
	/**
	 * 管理者のアクセス範囲が正しく生成されることを検証します。
	 * <p>
	 * テナントIDが設定され、顧客IDが未設定であることを確認します。
	 */
	@Test
	@DisplayName("管理者はtenantIdをもちcustomerIdを持たない")
	void adminHasTenantOnly() {
		AccessScope scope = new AccessScope(
				UUID.randomUUID(), UUID.randomUUID(), null, UserRole.ADMIN, 0L);
		
		assertThat(scope.tenantId()).isNotNull();
		assertThat(scope.customerId()).isNull();
	}
	
	/**
	 * 顧客利用者に顧客IDが設定されていない場合に、
	 * アクセス範囲の生成が拒否されることを検証します。
	 * <p>
	 * 顧客IDを未設定にすると、IllegalArgumentExceptionが発生することを確認します。
	 */
	@Test
	@DisplayName("顧客利用者にcustomerIdが無い場合は生成を拒否する")
	void customerWithoutCustomerIdIsRejected() {
		UUID userId = UUID.randomUUID();
		UUID tenantId = UUID.randomUUID();
		
		assertThatThrownBy(() -> new AccessScope(
				userId, tenantId, null, UserRole.CUSTOMER, 0L))
				.isInstanceOf(IllegalArgumentException.class);
	}
	
	/**
	 * 認証済み利用者のprincipalから、
	 * アクセス範囲が正しく生成されることを検証します。
	 * <p>利用者ID、テナントID、顧客ID、役割、
	 * セキュリティバージョンが引き継がれることを確認します。
	 */
	@Test
	@DisplayName("認証済みPrincipalから全てのアクセス範囲を生成する")
	void createsScopeFromAuthenticatedPrincipal() {
		UUID userId = UUID.randomUUID();
		UUID tenantId = UUID.randomUUID();
		UUID customerId = UUID.randomUUID();
		AppUser user = DomainFixtures.appUser(
				userId,
				tenantId,
				customerId,
				"customer@example.com",
				"Test Customer",
				UserRole.CUSTOMER,
				UserStatus.ACTIVE,
				"password-hash",
				7L);
		AccountPrincipal principal = AccountPrincipal.from(
				user, Instant.parse("2026-10-29T00:00:00Z"));
		AccessScope scope = AccessScope.from(principal);
		
		assertThat(scope.userId()).isEqualTo(userId);
		assertThat(scope.tenantId()).isEqualTo(tenantId);
		assertThat(scope.customerId()).isEqualTo(customerId);
		assertThat(scope.role()).isEqualTo(UserRole.CUSTOMER);
		assertThat(scope.securityVersion()).isEqualTo(7L);
	}
	
	/**
	 * 運営者に所属IDが設定されている場合に、
	 * アクセス範囲の生成が拒否されることを検証します。
	 * <p>
	 * テナントIDまたは顧客IDが設定されている場合に、
	 * IllegalArgumentExceptionが発生することを確認します。
	 */
	@Test
	@DisplayName("運営者がtenantIdまたはcustomerIdを持つ場合は生成を拒否する")
	void operatorWithAssignmentIsRejected() {
		assertThatThrownBy(() -> new AccessScope(
				UUID.randomUUID(), UUID.randomUUID(), null, UserRole.OPERATOR, 0L))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AccessScope(
				UUID.randomUUID(), null, UUID.randomUUID(), UserRole.OPERATOR, 0L))
				.isInstanceOf(IllegalArgumentException.class);
	}
	
	/**
	 * 管理者または担当者の所属IDが不正な場合、
	 * アクセス範囲の生成が拒否されることを検証します。
	 * <p>
	 * 管理者のテナントIDが未設定の場合と、
	 * 担当者に不要な顧客IDが設定されている場合に、
	 * IllegalArgumentExceptionが発生することを確認します。
	 */
	@Test
	@DisplayName("管理者または担当者の所属関係が不正な場合は生成を拒否する")
	void tenantUserWithInvalidAssignmentIsRejected() {
		assertThatThrownBy(() -> new AccessScope(
				UUID.randomUUID(), null, null, UserRole.ADMIN, 0L))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AccessScope(
				UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				UserRole.STAFF, 0L))
				.isInstanceOf(IllegalArgumentException.class);
	}
	
	/**
	 * セキュリティバージョンに負の値を指定した場合に、
	 * アクセス範囲の生成が拒否されることを検証します。
	 * <p>
	 * セキュリティバージョンに-1を指定すると、
	 * IllegalArgumentExceptionが発生することを確認します。
	 */
	@Test
	@DisplayName("負のsecurityVersionでは生成を拒否する")
	void negativeSecurityVersionIsRejected() {
		assertThatThrownBy(() -> new AccessScope(
				UUID.randomUUID(), null, null, UserRole.OPERATOR, -1L))
				.isInstanceOf(IllegalArgumentException.class);
	}

}
