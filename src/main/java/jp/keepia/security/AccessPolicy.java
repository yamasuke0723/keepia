package jp.keepia.security;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import jp.keepia.domain.UserRole;

/**
 * Service層から利用する共通認証ポリシー
 * 
 * <p>Controllerの表示制御だけに依存せず、業務更新を行うServiceから
 *    このポリシーを呼び出して認可を確定します。</p>
 */
@Component
public class AccessPolicy {
	
	/**
	 * 利用者の役割が許可された役割に含まれることを確認します。
	 * 
	 * @param scope 認証済みアクセス範囲
	 * @param allowedRoles 許可する役割
	 * @throws AccessDeniedException 許可されていない役割の場合
	 */
	public void requireRole(AccessScope scope, UserRole... allowedRoles) {
		Objects.requireNonNull(scope, "scope");
		Objects.requireNonNull(allowedRoles, "allowedRoles");
		if(Arrays.stream(allowedRoles).noneMatch(scope.role()::equals)) {
			throw new AccessDeniedException("The current role is not allowed");
		}
	}
	
	/**
	 * 利用者が指定保守会社へアクセスできることを確認します。
	 * 
	 * <p>運営者は全保守会社を対象にできます。その他の利用者は自身の
	 *    tenantIdと一致する場合だけ許可できます。</p>
	 *    
	 * @param scope 認証済みアクセス範囲
	 * @param targetTenantId 対象保守会社ID
	 * @throws AccessDeniedException 対象が所属範囲外の場合
	 */
	public void requireTenant(AccessScope scope, UUID targetTenantId) {
		Objects.requireNonNull(scope, "scope");
		Objects.requireNonNull(targetTenantId, "targetTenantId");
		if(!scope.isOperator() && !targetTenantId.equals(scope.tenantId())) {
			throw new AccessDeniedException("The tenant is outside the current access scope");
		}
	}
	
	/**
	 * 顧客利用者が自身の顧客だけを対象としていることを確認します。
	 * 
	 * @param scope 認証済みアクセス範囲
	 * @param targetCustomerId 対象顧客ID
	 * @throws AccessDeniedException 顧客利用者が別顧客を対象とした場合
	 */
	public void requireCustomer(AccessScope scope, UUID targetCustomerId) {
		Objects.requireNonNull(scope, "scope");
		Objects.requireNonNull(targetCustomerId, "targetCustomerId");
		if(scope.role() == UserRole.CUSTOMER
				&& !targetCustomerId.equals(scope.customerId())) {
			throw new AccessDeniedException("The customer is outside the current access scope");
		}
			
	}

}
