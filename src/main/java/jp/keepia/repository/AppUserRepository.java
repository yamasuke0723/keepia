package jp.keepia.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import jp.keepia.domain.AppUser;

/**
 * keepia利用者を永続化及び検索するRepositoryです。
 */
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
	
	/**
	 * ログイン用の正規化済みメールアドレスで利用者を検索します。
	 * 
	 * <p>メールアドレスはDB全体で一意であるため、この認証用検索だけは
	 *    tenantIdを検索条件に含めません。</p>
	 * 
	 * @param email 小文字化及び前後空白除去済みのメールアドレス
	 * @return 一致する利用者。存在しない場合はEmpty
	 */
	Optional<AppUser> findByEmail(String email);
	
	/**
	 * 利用者IDと保守会社IDの両方が一致する利用者を検索します。
	 * 
	 * @param id 利用者ID
	 * @param tenantId 保守会社ID
	 * @return 一致する利用者。存在しない場合はEmpty
	 */
	Optional<AppUser> findByIdAndTenantId(UUID id, UUID tenantId);

}
