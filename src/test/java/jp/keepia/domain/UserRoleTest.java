package jp.keepia.domain;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link UserRole}のSpring Security権限名変更を確認するテストクラスです。
 */
class UserRoleTest {
	
	@Test
	@DisplayName("役割名にROLE_を付けた権限名を返します")
	void suthorityReturnsRolePrefixedName() {
		assertThat(UserRole.ADMIN.authority()).isEqualTo("ROLE_ADMIN");
	}
}
