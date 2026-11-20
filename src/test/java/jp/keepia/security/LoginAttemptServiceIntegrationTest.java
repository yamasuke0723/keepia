package jp.keepia.security;

import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import jp.keepia.domain.LoginRateLimit;
import jp.keepia.repository.LoginRateLimitRepository;

/**
 * {@link LoginAttemptService} のログイン失敗管理処理を、
 * 実際のデータベースに接続して検証する結合テストクラスです。
 * <p>
 * ログイン失敗回数、ブロック状態、ログイン成功時の初期化、
 * 接続元ごとの管理状態及び識別情報の保存形式を検証します。
 * <p>
 * 複数スレッドによる同時更新も実行し、
 * 失敗回数の取りこぼしや管理レコードの重複が発生しないことを確認します。
 */
@SpringBootTest
@ActiveProfiles("test")
class LoginAttemptServiceIntegrationTest {
		
	/** テストで使用する定数フィールドです。 */
	private static final String EMAIL = "rate-limit@example.com";
	private static final String SOURCE_ADDRESS = "192.0.2.10";
	private static final String OTHER_SOURCE_ADDRESS = "192.0.2.11";
	private static final int MAX_FAILURES = 5;
	
	/** テスト対象のログイン試行管理サービスです。 */
	@Autowired
	private LoginAttemptService loginAttemptService;
	
	/** ログイン試行を識別するHMACキーの生成クラスです。 */
	@Autowired
	private LoginAttemptKeyFactory keyFactory;
	
	/** ログイン失敗管理情報を操作するRepositoryです。 */
	@Autowired
	private LoginRateLimitRepository repository;
	
	/** テストデータの初期化とDBへの直接問い合わせに使用します。 */
	@Autowired
	private JdbcTemplate jdbcTemplate;
	
	/**
	 * 各テスト実行前にログイン失敗管理情報を初期化します。
	 * <p>
	 * 前回のテスト結果が後続のテストに影響しないように、
	 * ログイン失敗管理テーブルのレコードを削除します。
	 */
	@BeforeEach
	void setUp() {
		jdbcTemplate.update("delete from login_rate_limits");
	}
	
	/**
	 * ログイン失敗回数が上限未満の場合に、
	 * ログインがブロックされないことを検証します。
	 * <p>
	 * 失敗回数がDBに正しく記録されていることも確認します。
	 */
	@Test
	@DisplayName("上限未満の失敗はログインをブロックしない")
	void failuresBelowLimitDoNotBlockLogin() {
		repeatFailure(MAX_FAILURES - 1, EMAIL, SOURCE_ADDRESS);
		
		assertThat(loginAttemptService.isBlocked(EMAIL, SOURCE_ADDRESS)).isFalse();
		assertThat(findRate(EMAIL, SOURCE_ADDRESS).getFailureCount())
				.isEqualTo(MAX_FAILURES -1);
	}
	
	/**
	 * ログイン成功時に失敗回数とブロック期限が
	 * 初期化されることを検証します。
	 * <p>
	 * ブロック状態の利用者についてログイン成功を記録し、
	 * 再びログイン可能な状態になることを確認します。
	 */
	@Test
	@DisplayName("ログイン成功時に失敗回数とブロック期限を初期化する")
	void successResetsFailureState() {
		repeatFailure(MAX_FAILURES, EMAIL, SOURCE_ADDRESS);
		assertThat(loginAttemptService.isBlocked(EMAIL, SOURCE_ADDRESS)).isTrue();
		
		loginAttemptService.recordSuccess(EMAIL, SOURCE_ADDRESS);
		
		LoginRateLimit rate = findRate(EMAIL, SOURCE_ADDRESS);
		assertThat(rate.getFailureCount()).isZero();
		assertThat(rate.getBlockedUntil()).isNull();
		assertThat(loginAttemptService.isBlocked(EMAIL, SOURCE_ADDRESS)).isFalse();
	}
	
	/**
	 * 同じメールアドレスでも接続元が異なる場合に、
	 * ログイン失敗状態が独立して管理されることを検証します。
	 * <p>
	 * 一方の接続元がブロックされても、
	 * 別の接続元はブロックされないことを確認します。
	 */
	@Test
	@DisplayName("同じメールでも接続元が異なれば失敗状態を分離する")
	void differentSourceAddressesHaveIndependentState() {
		repeatFailure(MAX_FAILURES, EMAIL, SOURCE_ADDRESS);
		
		assertThat(loginAttemptService.isBlocked(EMAIL, SOURCE_ADDRESS)).isTrue();
		assertThat(loginAttemptService.isBlocked(EMAIL, OTHER_SOURCE_ADDRESS)).isFalse();
		assertThat(repository.count()).isEqualTo(1L);
	}
	
	/**
	 * メールアドレスと接続元IPアドレスが、
	 * 平文のまま識別キーとして保存されないことを検証します。
	 * <p>
	 * DBに保存されたキーが64文字の小文字16進数形式であり、
	 * 元の識別情報を含まないことを確認します。
	 */
	@Test
	@DisplayName("DBにはメールアドレスと接続元アドレスの平文を保存しない")
	void databaseDoesNotContainPlainIdentifiers() {
		loginAttemptService.recordFailure(EMAIL, SOURCE_ADDRESS);
		
		String keyHash = jdbcTemplate.queryForObject(
				"select key_hash from login_rate_limits",
				String.class);
		
		assertThat(keyHash)
				.hasSize(64)
				.doesNotContain(EMAIL)
				.doesNotContain(SOURCE_ADDRESS)
				.matches("[0-9a-f]{64}");
	}
	
	/**
	 * 同一キーに対する複数ログイン失敗が同時に発生した場合に、
	 * DB上で失敗回数が正しく記録されることを検証します。
	 * <p>
	 * 複数スレッドの処理開始を同期させ、
	 * すべての処理が完了した後に、管理レコードが1件だけ存在し、
	 * 失敗回数が実行回数と一致することを確認します。
	 * 
	 * @throws Exception 並行処理の待機中または実行結果の取得中に
	 * 						例外が発生した場合です。
	 */
	@Test
	@DisplayName("同一キーへの同時失敗でも回数を失わず管理行を1件だけ作成する")
	void concurrentFailuresAreSerializedByDatabase() throws Exception {
		int workerCount = 8;
		CountDownLatch ready = new CountDownLatch(workerCount);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(workerCount);
		List<Future<?>> futures = new ArrayList<>();
		
		try {
			for(int index = 0; index < workerCount; index++) {
				futures.add(executor.submit(() -> {
					ready.countDown();
					if(!start.await(10, TimeUnit.SECONDS)) {
						throw new IllegalStateException("Concurrent test did not start in time");
					}
					loginAttemptService.recordFailure(EMAIL, SOURCE_ADDRESS);
					return null;
				}));
			}
			
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			for(Future<?> future : futures) {
				future.get(20, TimeUnit.SECONDS);
			}
		} finally {
			start.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
		}
		
		assertThat(repository.count()).isEqualTo(1L);
		assertThat(findRate(EMAIL, SOURCE_ADDRESS).getFailureCount()).isEqualTo(workerCount);
 	}
	
	/**
	 * 指定された回数だけログイン失敗を記録します。
	 * 
	 * @param count ログイン失敗を記録する回数です。
	 * @param email 対象のメールアドレスです。
	 * @param sourceAddress 接続元IPアドレスです。
	 */
	private void repeatFailure(int count, String email, String sourceAddress) {
		for(int index = 0; index < count; index++) {
			loginAttemptService.recordFailure(email, sourceAddress);
		}
	}
	
	/**
	 * 指定されたメールアドレスと接続元IPアドレスから
	 * ログイン失敗管理情報を取得します。
	 * 
	 * @param email 対象のメールアドレスです。
	 * @param sourceAddress 接続元IPアドレスです。
	 * @return 該当するログイン失敗管理情報を返します。
	 * @throws java.util.NoSuchElementException 該当する管理情報が存在しない場合です。
	 */
	private LoginRateLimit findRate(String email, String sourceAddress) {
		String keyHash = keyFactory.create(email, sourceAddress);
		return repository.findByKeyHash(keyHash).orElseThrow();
	}

}
