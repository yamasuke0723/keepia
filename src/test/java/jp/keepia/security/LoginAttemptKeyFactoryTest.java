package jp.keepia.security;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.keepia.config.SecurityProperties;

/**
 * {@link LoginAttemptKeyFactory} によるログイン試行の識別キー生成を検証するテストクラスです。
 * <p>
 * メールアドレスと接続元アドレスの正規化、HMACキーの一意生と形式、
 * 平文状態の保護、及び入力値が不足した場合の動作を検証します。
 */
class LoginAttemptKeyFactoryTest {
	
	/** HMACキーの生成に使用するテスト用の秘密鍵です。 */
	private static final String SECRET = "0123456789abcdef0123456789abcdef";
	
	/** テストで使用するメールアドレスです。 */
	private static final String EMAIL = "admin@example.com";
	
	/** テストで使用数する接続元IPアドレスです。 */
	private static final String SOURCE_ADDRESS = "2001:db8::1";
	
	/** テスト用のセキュリティ設定を使用して生成したキー生成ファクトリです。 */
	private final LoginAttemptKeyFactory factory = new LoginAttemptKeyFactory(
			new SecurityProperties(
					Duration.ofHours(8),
					Duration.ofMinutes(15),
					5,
					Duration.ofMinutes(30),
					SECRET));
	
	/**
	 * メールアドレスと接続元アドレスを正規化した結果が同じ場合に、
	 * 同一のHMACキーが生成することを検証します。
	 * <p>
	 * メールアドレスの大文字・小文字や前後の空白
	 * 接続元アドレスの表記の違いがキー生成に影響しないことを確認します。
	 */
	@Test
	@DisplayName("同じ正規化メールと接続元から同じHMACキーを生成する")
	void sameNormalizedInputCreatesSameKey() {
		String first = factory.create(" Admin@Example.COM ", " 2001:DB8::1");
		String second = factory.create(EMAIL, SOURCE_ADDRESS);
		
		assertThat(first).isEqualTo(second);
	}
	
	/**
	 * メールアドレスが同じでも接続元アドレスが異なる場合に、
	 * 異なるHMACキーが生成されることを検証します。
	 * <p>
	 * 接続元ごとにログイン試行を区別できることを確認します。
	 */
	@Test
	@DisplayName("同じメールでも接続元が異なれば別のHMACキーを生成する")
	void differentSourceAddressCreatesDifferentKey() {
		String first = factory.create(EMAIL, "192.0.2.10");
		String second = factory.create(EMAIL, "192.0.2.11");
		
		assertThat(first).isNotEqualTo(second);
	}
	
	/**
	 * 生成されたHMACキーの形式と機密情報の保護を検証します。
	 * <p>
	 * キーが64桁の小文字で構成され、
	 * メールアドレスと接続元アドレスの平文を含まないことを確認します。
	 */
	@Test
	@DisplayName("キーは64桁の小文字16進数で平文を含まない")
	void keyIsLowercaseHexAndDoesNotExposePlainText() {
		String key = factory.create(EMAIL, SOURCE_ADDRESS);
		
		assertThat(key)
				.matches("[0-9a-f]{64}")
				.doesNotContain(EMAIL)
				.doesNotContain(SOURCE_ADDRESS);
	}
	
	/**
	 * メールアドレスが空白文字のみの場合に、
	 * ログイン試行の識別キーが生成されないことを検証します。
	 * <p>
	 * 不正な入力に対して空文字が返されることを確認します。
	 */
	@Test
	@DisplayName("空のメールアドレスでは管理キーを生成しない")
	void blankEmailCreatesEmptyKey() {
		assertThat(factory.create(" ", SOURCE_ADDRESS)).isEmpty();
	}
	
	/**
	 * 接続元アドレスが取得できない場合に、
	 * 代替値を使用して安定したHMACキーが生成されることを検証します。
	 * <p>
	 * 接続元アドレスがnullの場合と空白文字のみの場合に、
	 * 同一のキーが生成されることを確認します。
	 */
	@Test
	@DisplayName("接続元が取得できない場合はunknownとして安定したキーを生成する")
	void missingSourceAddressUsesStableFallback() {
		assertThat(factory.create(EMAIL, null))
				.isEqualTo(factory.create(EMAIL, "  "));
	}

}
