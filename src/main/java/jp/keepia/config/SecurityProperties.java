package jp.keepia.config;

import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * keepia固有の認証・セッション設定を保持します。
 * 
 * @param absoluteSessionTimeout ログイン後のセッションの絶対有効時間
 * @param loginFailureWindow ログイン失敗回数を集計する期間
 * @param loginMaxFailures 集計期間内に許容するログイン失敗回数
 * @param loginBlockDuration 失敗回数が上限に達した際にログインを禁止する期間
 * @param loginKeySecret ログインキーの生成に使用する32文字以上の秘密鍵
 */
@ConfigurationProperties(prefix = "keepia.security")
public record SecurityProperties(
		Duration absoluteSessionTimeout,
		Duration loginFailureWindow,
		int loginMaxFailures,
		Duration loginBlockDuration,
		String loginKeySecret){

	/**
	 * 各設定が有効であることを検証します。
	 * 
	 * @throws NullPointException 設定値に{@code null}が渡された場合
	 * @throws IllegalArgumentException 有効時間が正でない場合、ログイン失敗回数が1未満の場合
	 *                                   または秘密値が32文字未満の場合
	 */
	public SecurityProperties{
		requirePositive(absoluteSessionTimeout, "absoluteSessionTimeout");
		requirePositive(loginFailureWindow, "loginFailureWindow");
		requirePositive(loginBlockDuration, "loginBlockDuration");
		if(loginMaxFailures < 1) {
			throw new IllegalArgumentException("loginMaxFailures must be at least 1");
		}
		Objects.requireNonNull(loginKeySecret, "loginKeySecret");
		if(loginKeySecret.length() < 32) {
			throw new IllegalArgumentException("loginKeySecret must contain at least 32 characters");
		}
	}

	/**
	 * 指定された期間が{@code null}ではなく、正の値であることを検証します。
	 * 
	 * @param duration 検証対象の期間
	 * @param name 例外メッセージに使用する設定項目
	 * @throws NuuPointerException {@code duration}が{@code null}の場合
	 * @throws IllegalArgumentException {[code duration}がゼロまたは負の値の場合
	 */
	private static void requirePositive(Duration duration, String name) {
		Objects.requireNonNull(duration, name);
		if(duration.isZero() || duration.isNegative()) {
			throw new IllegalArgumentException(name + " must be positive");
		}
	}

}
