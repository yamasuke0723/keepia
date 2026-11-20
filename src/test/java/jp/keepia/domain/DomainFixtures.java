package jp.keepia.domain;

import java.time.Instant;
import java.util.UUID;

import org.springframework.test.util.ReflectionTestUtils;

/**
 * テスト用のドメインエンティティを生成します。
 * DBに保存せず、IDや状態などをリフレクションで直接設定します。
 */
public final class DomainFixtures {
	
	 /** テスト結果が実行時刻に依存しないよう、作成日時と更新日時を固定します */
	private static final Instant CREATED_AT = Instant.parse("2026-10-29T00:00:00Z");
	
	private DomainFixtures() {
	}
	
	/** 指定された所属先・権限・アカウント状態を持つユーザーを生成します */
	public static AppUser appUser(
			UUID id,
			UUID tenantId,
			UUID customerId,
			String email,
			String displayName,
			UserRole role,
			UserStatus status,
			String passwordHash,
			long securityVersion) {
		
		AppUser user= new AppUser();
		ReflectionTestUtils.setField(user, "id", id);
        ReflectionTestUtils.setField(user, "tenantId", tenantId);
        ReflectionTestUtils.setField(user, "customerId", customerId);
        ReflectionTestUtils.setField(user, "email", email);
        ReflectionTestUtils.setField(user, "displayName", displayName);
        ReflectionTestUtils.setField(user, "role", role);
        ReflectionTestUtils.setField(user, "status", status);
        ReflectionTestUtils.setField(user, "passwordHash", passwordHash);
        ReflectionTestUtils.setField(user, "securityVersion", securityVersion);
        ReflectionTestUtils.setField(user, "createdAt", CREATED_AT);
        ReflectionTestUtils.setField(user, "updatedAt", CREATED_AT);
        ReflectionTestUtils.setField(user, "version", 0L);
        return user;
	}
	
	/** テスト用の固定名と、指定された状態を持つテナントを生成します  */
	public static Tenant tenant(UUID id, TenantStatus status) {
		Tenant tenant = new Tenant();
		ReflectionTestUtils.setField(tenant, "id", id);
		ReflectionTestUtils.setField(tenant, "name", "Test Tenant");
		ReflectionTestUtils.setField(tenant, "status", status);
		ReflectionTestUtils.setField(tenant, "createdAt", CREATED_AT);
		ReflectionTestUtils.setField(tenant, "updatedAt", CREATED_AT);
		ReflectionTestUtils.setField(tenant, "version", 0L);
		return  tenant;
	}
	
	/** 指定されたテナントに属する顧客を、テスト用の名前と連絡先で生成します */
	public static Customer customer(
			UUID id,
			UUID tenantId,
			CustomerStatus status) {
		
		Customer customer = new Customer();
		ReflectionTestUtils.setField(customer, "id", id);
		ReflectionTestUtils.setField(customer, "tenantId", tenantId);
		ReflectionTestUtils.setField(customer, "name", "Test Customer");
		ReflectionTestUtils.setField(customer, "contactEmail", "contact@example.com");
		ReflectionTestUtils.setField(customer, "contactPhone", null);
		ReflectionTestUtils.setField(customer, "status", status);
		ReflectionTestUtils.setField(customer, "createdAt", CREATED_AT);
		ReflectionTestUtils.setField(customer, "updatedAt", CREATED_AT);
		ReflectionTestUtils.setField(customer, "version", 0L);
		return customer;
	}
	
}
