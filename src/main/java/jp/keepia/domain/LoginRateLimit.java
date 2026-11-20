package jp.keepia.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * ログイン失敗回数と一時的なブロック状態を保持するJPA Entity
 * 
 * <p>メールアドレスなどの識別情報は平文で保存せず、HMACで生成した
 *    64桁の16進数文字列を{@code keyHash}へ保存します。</p>
 */
@Entity
@Table(name = "login_rate_limits")
public class LoginRateLimit {
	
	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;
	
	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "key_hash", nullable = false, updatable = false, length = 64)
	private String keyHash;  
	
	@Column(name = "failure_count", nullable = false)
	private int failureCount;
	
	@Column(name = "window_started_at", nullable = false)
	private Instant windowStartedAt;
	
	@Column(name = "blocked_until")
	private Instant blockedUntil;
	
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;
	
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;
	
	@Version
	@Column(name = "version", nullable = false)
	private long version;
	
	/**
	 * JPAがEntityを復元するために使用するコンストラクタです。
	 */
	protected LoginRateLimit() {
	}
	
	/**
	 * ログイン失敗管理Entiryを初期状態で生成します。
	 * 
	 * <p>外部から直接呼び出さず、{@link #create(String, Instant)}を使用して生成します。</p>
	 * <p>生成時点では失敗回数を0、ブロック制限を未設定とし、
	 *    作成日時と更新日時には同じ時刻を設定します。</p>
	 *    
	 * @param keyHash HMACで生成したログイン試行識別キー
	 * @param now Entityの生成時刻
	 * @throws NullPointerException keyHashまたはnowがnullの場合
	 */
	private LoginRateLimit(String keyHash, Instant now) {
		this.id = UUID.randomUUID();
		this.keyHash = Objects.requireNonNull(keyHash, "keyHash");
		this.failureCount = 0;
		this.windowStartedAt = Objects.requireNonNull(now, "now");
		this.blockedUntil = null;
		this.createdAt = now;
		this.updatedAt = now;
	}
	
	/**
	 * 新しいログイン失敗管理行を生成します。
	 * 
	 * @param keyHash HMAC で生成した64桁の小文字16進数文字列
	 * @param now 生成時刻
	 * @return 未失敗状態のEntity
	 */
	public static LoginRateLimit create(String keyHash, Instant now) {
		return new LoginRateLimit(keyHash, now);
	}
	
	/**
	 * ログイン指向管理行のIDを返します。
	 * 
	 * @return 管理行を一意に識別するUUID
	 */
	public UUID getId() {
		return id;
	}
	
	/**
	 * ログイン指向を識別するHMAC値を返します。
	 * 
	 * @return 64桁の小文字16進数文字列
	 */
	public String getKeyHash() {
		return keyHash;
	}
	
	/**
	 * 現在の集計期間内の連続失敗回数を返します。
	 * 
	 * @return ログイン失敗回数
	 */
	public int getFailureCount() {
		return failureCount;
	}
	
	/**
	 * 現在の失敗回数集計期間の開始日時を返します。
	 * 
	 * @return 集計期間の開始日時
	 */
	public Instant getWindowStartedAt() {
		return windowStartedAt;
	}
	
	/**
	 * ログインを拒否する期限を返します。
	 * 
	 * @return ブロック期限。ブロックされていない場合は{@code null}
	 */
	public Instant getBlockedUntil() {
		return blockedUntil;
	}
	
	/**
	 * レコードの作成日時を返します。
	 * 
	 * @return UTCで保存された作成日時
	 */
	public Instant getCreatedAt() {
		return createdAt;
	}
	
	/**
	 * レコードの最終更新日時を返します。
	 * 
	 * @return UTCで保存された最終更新日時
	 */
	public Instant getUpdatedAt() {
		return updatedAt;
	}
	
	/**
	 * 楽観ロックに使用するバージョン番号を返します。
	 * 
	 * @return JPAが管理するバージョン番号
	 */
	public long getVersion() {
		return version;
	}
	
	/**
	 * 指定時刻にログインがブロックされているかを判定します。
	 * 
	 * @param now 判定時刻
	 * @return ブロック期限前の場合は{@code true}
	 */
	public boolean isBlocked(Instant now) {
		Objects.requireNonNull(now, "now");
		return blockedUntil != null && now.isBefore(blockedUntil);
	}
	
	/**
	 * 1回のログイン失敗を記録し、上限到達時にブロック期限を設定します。
	 * 
	 * <p>現在の集計期間が終了している場合は失敗回数を初期化し、
	 *    今回の失敗時刻から新しい集計期間を開始します。</p>
	 * 
	 * @param now 今回ログインに失敗した時刻
	 * @param failureWindow 失敗回数を集計する期間
	 * @param maxFailures ブロックへ移行する失敗回数
	 * @param blockDuration ブロックを継続する時間
	 * @throws NullPointerException
	 *          now,failureWindowまたはblockDurationがnullの場合
	 * @throws IllegalArgumentException 
	 *          failureWindowまたはblockDurationが正の時間でない場合、
	 *          またはmaxFailuresが1未満の場合
	 */
	public void recordFailure(
			Instant now,
			Duration failureWindow,
			int maxFailures,
			Duration blockDuration) {
		
		Objects.requireNonNull(now, "now");
		requirePositive(failureWindow, "failureWindow");
		requirePositive(blockDuration, "blockDuration");
		if(maxFailures < 1) {
			throw new IllegalArgumentException("maxFailures must be at least 1");
		}
		
		Instant windowEnd = windowStartedAt.plus(failureWindow);
		if(!now.isBefore(windowEnd)) {
			failureCount = 0;
			windowStartedAt = now;
			blockedUntil = null;
		}
		
		failureCount ++;
		if(failureCount >= maxFailures) {
			blockedUntil = now.plus(blockDuration);
		}
		updatedAt = now;
	}
	
	/**
	 * ログイン成功後に失敗回数とブロック状態を初期化します。
	 * 
	 * @param now 初期化時刻
	 */
	public void reset(Instant now) {
		Objects.requireNonNull(now, "now");
		failureCount = 0;
		windowStartedAt = now;
		blockedUntil = null;
		updatedAt = now;
	}
		
	/**
	 * 指定された時間が正の値であることを検証します。
	 * 
	 * <p>null,0,および負の時間は、有効な設定値として認めません。</p>
	 * 
	 * @param duration 検証する時間
	 * @param name 例外メッセージへ表示する設定項目名
	 * @throws NullPointerException durationがnullの場合
	 * @throws IllegalArgumentException durationが0または負の値の場合
	 */
	private static void requirePositive(Duration duration, String name) {
		Objects.requireNonNull(duration, name);
		if(duration.isZero() || duration.isNegative()) {
			throw new IllegalArgumentException(name + " must be positive");
		}
	}
}
