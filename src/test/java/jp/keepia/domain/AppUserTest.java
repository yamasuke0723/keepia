package jp.keepia.domain;

import static org.assertj.core.api.Assertions.*;

import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@link AppUser}の役割と所属情報、利用状態、
 * パスワード設定状態を検証テストクラスです。
 * 
 * <p>
 * 各判定メソッドが、利用者の属性に応じて
 * 正しい結果を変えることを確認します。
 */
class AppUserTest {
	
	/** テストで使用するテナントID */
	private static final UUID TENANT_ID = UUID.randomUUID();
	
	/** テストで使用する顧客ID */
	private static final UUID CUSTOMER_ID = UUID.randomUUID();
	
	/**
	 * 役割と所属IDの組み合わせが正しい場合に、
	 * {@code hasValidAssignment()}が{@code true}を返すことを検証します。
	 * 
	 * @param role 利用者の役割
	 * @param tenantId 所属テナントID
	 * @param customerId 所属顧客ID
	 */
	@ParameterizedTest
	@MethodSource("validAssignments")
	@DisplayName("役割と所属IDが正しい組み合わせを受け入れる")
	void validRoleAssignmentsAreAccepted(
			UserRole role,
			UUID tenantId,
			UUID customerId) {
		
		AppUser user = newUser(role, tenantId, customerId, UserStatus.ACTIVE, "hash");
		
		assertThat(user.hasValidAssignment()).isTrue();
		
	}
	
	/**
	 * 役割と所属IDの組み合わせた不正な場合に、
	 * {@code hasValidAssigment()}が{@code false}を返すことを検証します。
	 * 
	 * @param role 利用者の役割
	 * @param tenantId 所属テナントID
	 * @param customerId 所属顧客ID
	 */
	@ParameterizedTest
	@MethodSource("invalidAssignments")
	@DisplayName("役割と所属IDが不正な組み合わせを拒否します")
	void invalidRoleAssignmentsAreRejected(
			UserRole role,
			UUID tenantId,
			UUID customerId) {
		
		AppUser user = newUser(role, tenantId, customerId, UserStatus.ACTIVE, "hash");
		
		assertThat(user.hasValidAssignment()).isFalse();
		
	}

	/**
	 * 利用状態が{@code ACTIVE}の場合のみ、
	 * {@code isActive()}が{@code STOPPED}の場合は、
	 * <p>
	 * {@code INVITED}および{@code STOPPED}の場合は、
	 * {@code false}を返すことを確認します。
	 */
	@Test
	@DisplayName("ACTIVE利用者だけを利用可能と判定する")
	void onlyActiveUserIsAvailable() {
		AppUser active = newUser(
				UserRole.ADMIN, TENANT_ID, null, UserStatus.ACTIVE, "hash");
		AppUser invited = newUser(
				UserRole.ADMIN, TENANT_ID, null, UserStatus.INVITED, "hash");
		AppUser stopped = newUser(
				UserRole.ADMIN, TENANT_ID, null, UserStatus.STOPPED, "hash");
				
		assertThat(active.isActive()).isTrue();
		assertThat(invited.isActive()).isFalse();
		assertThat(stopped.isActive()).isFalse();
	}
	/**
	 * パスワードハッシュの設定状態が正しく判定されることを検証します。
	 * <p>
	 * 値が設定されている場合は設定済みとし、nullまたは空白文字のみの場合は
	 * 未設定と判定されることを確認します。
	 */
	@Test
	@DisplayName("nullまたは空白のパスワードハッシュを未設定と判定する")
	void nullOrBlankPasswordIsNotConfigured() {
		AppUser configured = newUser(
				UserRole.ADMIN, TENANT_ID, null, UserStatus.ACTIVE, "hash");
		AppUser nullPassword = newUser(
				UserRole.ADMIN, TENANT_ID, null, UserStatus.ACTIVE, null);
		AppUser blankPassword = newUser(
				UserRole.ADMIN, TENANT_ID, null, UserStatus.ACTIVE, " ");
		
		assertThat(configured.hasPassword()).isTrue();
		assertThat(nullPassword.hasPassword()).isFalse();
		assertThat(blankPassword.hasPassword()).isFalse();
	}

	/**
	 * 役職と所属IDの正しい組み合わせをテストデータとして提供します。
	 * 
	 * @return 有効な役割、テナントID、顧客IDの組み合わせ
	 */
	private static Stream<Arguments> validAssignments(){
		return Stream.of(
				Arguments.of(UserRole.OPERATOR, null, null),
				Arguments.of(UserRole.ADMIN, TENANT_ID, null),
				Arguments.of(UserRole.STAFF, TENANT_ID, null),
				Arguments.of(UserRole.CUSTOMER, TENANT_ID, CUSTOMER_ID));
	}
	
	/**
	 * 役職と所属IDの不正な組み合わせをテストデータとして提供します。
	 * 
	 * @return 無効な役割、テナントID、顧客IDの組み合わせ
	 */
	private static Stream<Arguments> invalidAssignments(){
		return  Stream.of(
				Arguments.of(UserRole.OPERATOR, TENANT_ID, null),
				Arguments.of(UserRole.OPERATOR, null, CUSTOMER_ID),
				Arguments.of(UserRole.ADMIN, null, null),
				Arguments.of(UserRole.ADMIN, TENANT_ID, CUSTOMER_ID),
				Arguments.of(UserRole.STAFF, null, null),
				Arguments.of(UserRole.STAFF, TENANT_ID, CUSTOMER_ID),
				Arguments.of(UserRole.CUSTOMER, null, CUSTOMER_ID),
				Arguments.of(UserRole.CUSTOMER, TENANT_ID, null));
	}
	
	/**
	 * 指定された属性をもつテスト用のAppUserを生成します。
	 * <p>
	 * 利用者IDは新しいUUIDを生成し、メールアドレス、表示名、
	 * バージョンにはテスト用の固定値を使用します。
	 * 
	 * @param role 利用者の役割
	 * @param tenantId 所属テナントId
	 * @param customerId 所属顧客ID
	 * @param status 利用者の状態
	 * @param passwordHash パスワードハッシュ
	 * @return 指定された属性を持つテスト用のAppUser
	 */
	private AppUser newUser(
			UserRole role,
			UUID tenantId,
			UUID customerId,
			UserStatus status,
			String passwordHash) {
		
		return DomainFixtures.appUser(
				UUID.randomUUID(),
				tenantId,
				customerId,
				"user@example.com",
				"Test User",
				role,
				status,
				passwordHash,
				0L);
		
	}
	
}
