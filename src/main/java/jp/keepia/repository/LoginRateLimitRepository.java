package jp.keepia.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import jp.keepia.domain.LoginRateLimit;

public interface LoginRateLimitRepository extends JpaRepository<LoginRateLimit, UUID> {
	
	/**
	 * ハッシュキーが未登録の場合だけ、失敗回数0の管理行を追加します。
	 * 
	 * <p>PostgreSQLの{@code ON CONFLICT}を使用するため、複数トランザクションが
	 *    同じキーを同時に初期化しても一意制約違反にならない。</p>
	 * 
	 * @param id 新規行へ割り当てるUUID
	 * @param keyHash HMACで生成したハッシュキー
	 * @param now 作成日時及び集計期間の開始日時
	 * @return 行を追加した場合は1、既存行があった場合は0
	 */
	@Modifying
	@Query(value = """
			INSERT INTO login_rate_limits (
				id,
				key_hash,
				failure_count,
				window_started_at,
				blocked_until,
				created_at,
				updated_at,
				version
			) values (
				:id,
				:keyHash,
				0,
				:now,
				null,
				:now,
				:now,
				0
			)
			on conflict (key_hash) do nothing
			""", nativeQuery = true)
	int insertIfAbsent(
			@Param("id") UUID id,
			@Param("keyHash") String keyHash,
			@Param("now") Instant now);
	
	/**
	 * ハッシュキーに一致する失敗状態を読み取り専用で検索します。
	 * 
	 * @param keyHash HMACで生成したハッシュキー
	 * @return 一致する失敗状態。存在しない場合はEmpty
	 */
	Optional<LoginRateLimit> findByKeyHash(String keyHash);
	
	/**
	 * ハッシュキーに一致する失敗状態を更新用の行ロック付きで検索します。
	 * 
	 * @param keyHash HMACで生成したハッシュキー
	 * @return 一致する失敗状態。存在しない場合はEmpty
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT rate from LoginRateLimit rate where rate.keyHash = :keyHash")
	Optional<LoginRateLimit> findByKeyHashForUpdate(@Param("keyHash") String keyHash);

}
