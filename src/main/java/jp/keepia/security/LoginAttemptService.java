package jp.keepia.security;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import jp.keepia.config.SecurityProperties;
import jp.keepia.domain.LoginRateLimit;
import jp.keepia.repository.LoginRateLimitRepository;

/**
 * ログイン試行回数とアクセス制限を管理するサービスクラスです。
 * 
 * <p>メールアドレスとアクセス元アドレスから生成した識別キーを使用し、
 *    ログイン失敗回数の記録、制限状態の判定、成功時の記録リセットを行います。</p>
 */
@Service
public class LoginAttemptService {
	
	private final LoginRateLimitRepository repository;
	private final LoginAttemptKeyFactory keyFactory;
	private final SecurityProperties properties;
	private final Clock clock;
	
	/**
	 * ログイン試行管理に必要な依存オブジェクトを初期化します。
	 * 
	 * @param repository ログイン試行制限情報を管理するRepository
	 * @param keyFactory ログイン試行識別キーの生成クラス
	 * @param properties セキュリティ設定
	 * @param clock 現在時刻を取得する時刻源
	 */
	public LoginAttemptService(
			LoginRateLimitRepository repository,
			LoginAttemptKeyFactory keyFactory,
			SecurityProperties properties,
			Clock clock) {
		this.repository = repository;
		this.keyFactory = keyFactory;
		this.properties = properties;
		this.clock = clock;
	}
	
	/**
	 * 指定されたログイン試行が現在制限されているかを判定します。
	 * 
	 * <p>メールアドレスとアクセス元アドレスから識別キーを生成し、
	 *    保存されているログイン試行制限情報を参照します。
	 *    識別キーが空、または制限情報が存在しない場合falseを返します。</p>
	 *    
	 * @param rawEmail ログイン時に入力されたメールアドレス
	 * @param sourceAddress ログイン要求のアクセス元アドレス
	 * @return ログイン制限中の場合はtrue、それ以外はfalse
	 */
	@Transactional(readOnly = true)
	public boolean isBlocked(String rawEmail, String sourceAddress) {
		String keyHash = keyFactory.create(rawEmail, sourceAddress);
		if(keyHash.isBlank()) {
			return false;
		}
		Instant now = clock.instant();
		return repository.findByKeyHash(keyHash)
				.map(rate -> rate.isBlocked(now))
				.orElse(false);
	}
	/**
	 * ログイン失敗を記録し、試行回数と制限状態を更新します。
	 * 
	 * <p>識別キーに対応する管理情報が存在しない場合は新規作成します。
	 *    対象レコードを排他ロックしてから、設定された判定期間、
	 *    最大失敗回数、制限時間に基づいて状態を更新します。
	 *    識別キーが空の場合は処理を終了します。</p>
	 *    
	 * @param rawEmail ログイン時に入力されたメールアドレス
	 * @param sourceAddress ログイン要求のアクセス元アドレス
	 * @throws IllegalStateException 管理情報の初期化後に対象レコードを取得できない場合
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public void recordFailure(String rawEmail, String sourceAddress) {
		String keyHash = keyFactory.create(rawEmail, sourceAddress);
		if(keyHash.isBlank()) {
			return;
		}
		
		Instant now = clock.instant();
		repository.insertIfAbsent(UUID.randomUUID(), keyHash, now);
		LoginRateLimit rate = repository.findByKeyHashForUpdate(keyHash)
				.orElseThrow(() -> new IllegalStateException(
						"Login rate-limit row was not initialized"));
		rate.recordFailure(
				now,
				properties.loginFailureWindow(),
				properties.loginMaxFailures(),
				properties.loginBlockDuration());
	}
	
	/**
	 * ログイン成功時に、対象のログイン試行制限情報をリセットします。
	 * 
	 * <p>メールアドレスとアクセス元アドレスから識別キーを生成し、
	 *    対応する管理情報が存在進場合は排他ロックを取得してリセットします。
	 *    識別キーが空、または管理情報が存在しない場合は何も行いません。</p>
	 *    
	 * @param rawEmail ログインに成功したメールアドレス
	 * @param sourceAddress ログイン要求のアクセス元アドレス
	 */
	@Transactional
	public void recordSuccess(String rawEmail, String sourceAddress) {
		String keyHash = keyFactory.create(rawEmail, sourceAddress);
		if(keyHash.isBlank()) {
			return;
		}
		repository.findByKeyHashForUpdate(keyHash)
				.ifPresent(rate -> rate.reset(clock.instant()));
	}
	
}
