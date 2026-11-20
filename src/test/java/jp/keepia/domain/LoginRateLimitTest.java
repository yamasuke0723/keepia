package jp.keepia.domain;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link LoginRateLimit}のログイン失敗回数と
 * ブロック状態の管理を検証するテストクラスです。
 * <p>
 * 失敗回数の上限判定、ブロック期間の管理、
 * 計測窓の更新、状態の初期化、引数の妥当性を検証します。
 */
class LoginRateLimitTest {
	
	/** テスト期間で使用するログイン制限対象のキーのハッシュ値です */
	private static final String KEY_HASH = "a".repeat(64);
	
	/** テスト期間で使用する基準時刻です */
	private static final Instant START = Instant.parse("2026-10-29T00:00:00Z");
	
	/** ログイン失敗回数を計測する期間(15分)です */
	private static final Duration WINDOW = Duration.ofMinutes(15);
	
	/** ログインをブロックする期間(30分)です */
	private static final Duration BLOCK = Duration.ofMinutes(30);
	
	/**
	 * ログイン失敗回数が上限未満の場合に、
	 * ブロックされないことを検証します。
	 * <p>
	 * 上限を3回とし、2回の失敗ではログイン可能な状態を維持することを確認します。
	 */
	@Test
	@DisplayName("上限未満の失敗ではブロックしない")
	void failuresBelowLimitDoNotBlockLogin() {
		LoginRateLimit rate = LoginRateLimit.create(KEY_HASH, START);
		
		rate.recordFailure(START.plusSeconds(1), WINDOW, 3, BLOCK);
		rate.recordFailure(START.plusSeconds(2), WINDOW, 3, BLOCK);
		
		assertThat(rate.getFailureCount()).isEqualTo(2);
		assertThat(rate.isBlocked(START.plusSeconds(3))).isFalse();
	}
	
	/**
	 * ログイン失敗回数が上限に到達した場合に、
	 * 指定された期間だけブロックされることを検証します。
	 * <p>
	 * 3回の失敗後にブロック状態となり、
	 * ブロック期間の経過後は解除されることを確認します。
	 */
	@Test
	@DisplayName("上限到達時に設定時間だけブロックする")
	void reachingLimitBlockLogin() {
		LoginRateLimit rate = LoginRateLimit.create(KEY_HASH, START);
		
		rate.recordFailure(START.plusSeconds(1), WINDOW, 3, BLOCK);
		rate.recordFailure(START.plusSeconds(2), WINDOW, 3, BLOCK);
		rate.recordFailure(START.plusSeconds(3), WINDOW, 3, BLOCK);
		
		assertThat(rate.isBlocked(START.plusSeconds(4))).isTrue();
		assertThat(rate.isBlocked(START.plus(BLOCK).plusSeconds(4))).isFalse();
	}
	
	/**
	 * ログイン成功後の状態初期化によって、
	 * 失敗回数とブロック状態が解除されることを検証します。
	 * <p>
	 * ブロック状態となった後にリセット処理を実行し、
	 * 失敗回数が0、ブロック期限が未設定になることを確認します。
	 */
	@Test
	@DisplayName("ログイン成功後は失敗回数とブロックを初期化する")
	void resetClearsFailureState() {
		LoginRateLimit rate = LoginRateLimit.create(KEY_HASH, START);
		rate.recordFailure(START.plusSeconds(1), WINDOW, 1, BLOCK);
		
		rate.reset(START.plusSeconds(2));
		
		assertThat(rate.getFailureCount()).isZero();
		assertThat(rate.getBlockedUntil()).isNull();
	}
	
	/**
	 * 計測窓の終了時刻に新たな失敗が発生した場合に、
	 * 失敗回数が1から数え直されることを検証します。
	 * <p>
	 * 計測開始時刻が更新され、以前の失敗回数が引き継がれず、
	 * ブロック状態にもならないことを確認します。
	 */
	@Test
	@DisplayName("計測窓を過ぎた失敗は回数を1から数え直す")
	void failureAfterWindowStartsNewCount() {
		LoginRateLimit rate = LoginRateLimit.create(KEY_HASH, START);
		rate.recordFailure(START.plusSeconds(1), WINDOW, 3, BLOCK);
		rate.recordFailure(START.plusSeconds(2), WINDOW, 3, BLOCK);
		
		Instant nextWindow = START.plus(WINDOW);
		rate.recordFailure(nextWindow, WINDOW, 3, BLOCK);
		
		assertThat(rate.getFailureCount()).isEqualTo(1);
		assertThat(rate.getWindowStartedAt()).isEqualTo(nextWindow);
		assertThat(rate.getBlockedUntil()).isNull();
	}

	/**
	 * ブロック期限の境界時刻で、
	 * ブロック状態が正しく解除されることを検証します
	 * <p>
	 * 期限の1ナノ秒前まではブロック状態を維持し、
	 * 期限と同時に再試行可能となることを確認します。
	 */
	@Test
	@DisplayName("ブロック期限丁度の時刻から再試行できる")
	void blockExpiresAtExactDeadline() {
		LoginRateLimit rate = LoginRateLimit.create(KEY_HASH, START);
		Instant failureAt = START.plusSeconds(1);
		rate.recordFailure(failureAt, WINDOW, 1, BLOCK);
		
		assertThat(rate.isBlocked(failureAt.plus(BLOCK).minusNanos(1))).isTrue();
		assertThat(rate.isBlocked(failureAt.plus(BLOCK))).isFalse();
	}
	
	/**
	 * ログイン失敗回数の上限に1未満の値を指定した場合に、
	 * 不正な引数として例外が発生することを検証します。
	 * <p>
	 * 上限に0を指定すると、IllegalArgumentExceptionが
	 * スローされることを確認します。
	 */
	@Test
	@DisplayName("失敗上限が1未満の場合は記録を拒否する")
	void nonPositiveFailureLimitRejected() {
		LoginRateLimit rate = LoginRateLimit.create(KEY_HASH, START);
		
		assertThatThrownBy(() -> rate.recordFailure(
				START.plusSeconds(1), WINDOW, 0, BLOCK))
				.isInstanceOf(IllegalArgumentException.class);
	}
	
	/**
	 * 計測窓またはブロック期間に正でない値を指定した場合に、不正な引数として例外が発生することを検証します。
	 * <p>
	 * 計測窓が0の場合と、ブロック期間が負の場合に、
	 * IllegalArgumentExceptionがスローされることを確認します。
	 */
	@Test
	@DisplayName("計測窓またはブロック時間が正で無い場合は記録を拒否する")
	void nonPositiveDurationsAreRejected() {
		LoginRateLimit rate = LoginRateLimit.create(KEY_HASH, START);
		
		assertThatThrownBy(() -> rate.recordFailure(
				START.plusSeconds(1), Duration.ZERO, 3, BLOCK))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> rate.recordFailure(
				START.plusSeconds(1), WINDOW, 3, Duration.ofSeconds(-1)))
				.isInstanceOf(IllegalArgumentException.class);
	}
	
}
