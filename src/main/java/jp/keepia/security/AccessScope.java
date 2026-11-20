package jp.keepia.security;

import java.util.Objects;
import java.util.UUID;

import jp.keepia.domain.UserRole;

/**
 * 1回の業務処理で使用する認証済み利用者のアクセス範囲を示すレコードクラス。
 * 
 * <p>HTTPパラメータやFormから生成してはならない。</p>
 * 
 * @param userId 認証済み利用者ID
 * @param tenantId 所属保守会社ID。運営者の場合はnull
 * @param customerId 所属顧客ID。顧客利用者以外はnull
 * @param role 認証済み利用者の役割
 * @param securityVersion 認証時点の認証世代
 */
public record AccessScope(
		UUID userId,
		UUID tenantId,
		UUID customerId,
		UserRole role,
		long securityVersion) {
	
	/**
	 * 必須値と役割ごとの所属関係を検証します。
	 * 
	 * @param userId 認証済み利用者ID
	 * @param tenantId 所属保守会社ID。運営者の場合はnull
	 * @param customerId 所属顧客ID。顧客利用者以外はnull
	 * @param role 認証済み利用者の役割
	 * @param securityVersion 認証時点の認証世代
	 */
	public AccessScope{
		Objects.requireNonNull(userId, "userid");
		Objects.requireNonNull(role, "role");
		boolean valid = switch(role) {
			case OPERATOR -> tenantId == null && customerId == null;
			case ADMIN, STAFF -> tenantId != null && customerId == null;
			case CUSTOMER -> tenantId != null && customerId != null;
		};
		if(!valid) {
			throw new IllegalArgumentException("role and assignment do not match");
		}
		if(securityVersion < 0) {
			throw new IllegalArgumentException("securityVersion must not be nagetive");
		}
	}
	
	/**
	 * 認証済みPrincipalからアクセス範囲を生成します。
	 * 
	 * @param principal 認証済みPrincipal
	 * @return アクセス範囲
	 */
	public static AccessScope from(AccountPrincipal principal) {
		Objects.requireNonNull(principal, "principal");
		return new AccessScope(
				principal.getUserId(),
				principal.getTenantId(),
				principal.getCustomerId(),
				principal.getRole(),
				principal.getSecurityVersion());
	}
	
	/**
	 * 運営者かを判定します。
	 * 
	 * @return 運営者の場合は{@code true}
	 */
	public boolean isOperator() {
		return role == UserRole.OPERATOR;
	}

}
