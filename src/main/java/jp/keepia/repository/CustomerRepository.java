package jp.keepia.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import jp.keepia.domain.Customer;

/**
 * 顧客を保守会社のスコープ付きで検索するRepositoryです。
 */
public interface CustomerRepository extends JpaRepository<Customer, UUID> {
	
	/**
	 * 顧客IDと保守会社IDの両方が一致する顧客を検索します。
	 * 
	 * @param id 顧客ID
	 * @param tenantId 保守会社ID
	 * @return 一致する顧客。存在しない場合はEmpty
	 */
	Optional<Customer> findByIdAndTenantId(UUID id, UUID tenantId);

}
