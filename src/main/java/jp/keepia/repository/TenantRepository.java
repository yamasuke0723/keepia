package jp.keepia.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import jp.keepia.domain.Tenant;

/*
 * 保守会社を永続化及び検索するRepositoryです。
 */
public interface TenantRepository extends JpaRepository<Tenant, UUID> {

}
