package jp.keepia.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import jp.keepia.config.SecurityProperties;

/**
 * ログイン指向を識別するためのキーを生成するクラスです。
 * 
 * <p>メールアドレスとアクセス元アドレスを組み合わせ、
 *    HMAC-SHA256によって一方向の識別キーを生成します。
 *    生成したキーは、ログイン試行回数の管理などに使用します。</p>
 */
@Component
public class LoginAttemptKeyFactory {
	
	private static final String HMAC_ALGORITHM = "HmacSHA256";
	
	private final byte[] secret;
	
	/**
	 * セキュリティ設定からHMAC生成用秘密鍵を初期化します。
	 * 
	 * @param properties keepiaセキュリティ設定
	 */
	public LoginAttemptKeyFactory(SecurityProperties properties) {
		this.secret = properties.loginKeySecret().getBytes(StandardCharsets.UTF_8);
	}
	
	/**
	 * メールアドレスとアクセス元アドレスからログイン試行識別キーを生成します。
	 * 
	 * メールアドレスとアクセス元アドレスを正規化した後、
	 * HMAC-SHA256を使用して識別キーを生成します。
	 * メールアドレスが空の場合は、空文字列を返します。
	 * 
	 * @param rewEmail ログイン時に入力されたメールアドレス
	 * @param sourceAddress ログイン要求のアクセス元アドレス
	 * @return 生成された16進数形式の識別キー。メールアドレスが空の場合は空文字列
	 * @throws IllegalStateException HMACの初期化または生成に失敗した場合
	 */
	public String create(String rawEmail, String sourceAddress) {
		String email = EmailNormalizer.normalize(rawEmail);
		if(email.isBlank()) {
			return "";
		}
		String source = normalizeSourceAddress(sourceAddress);
		String keyMaterial = "email:" + email.length() + ":" + email
				+ "\nsource:" + source.length() + ":" + source;
		try {
			Mac mac = Mac.getInstance(HMAC_ALGORITHM);
			mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
			byte[] digest = mac.doFinal(keyMaterial.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch(GeneralSecurityException exception) {
			throw new IllegalStateException("Unable to create login attempt key", exception);
		}
	}
	
	/**
	 * アクセス元アドレスを識別キー生成用に正規化します。
	 * 
	 * 前後の空白を除去し、小文字に変換します。
	 * nullまたは空白のみの場合は、"unknown"を返します。
	 * 
	 * @param sourceAddress 正規化対象のアクセス元アドレス
	 * @return 正規化されたアクセス元アドレス
	 */
	private String normalizeSourceAddress(String sourceAddress) {
		if(sourceAddress == null || sourceAddress.isBlank()) {
			return "unknown";
		}
		return sourceAddress.trim().toLowerCase(Locale.ROOT);
	}

}
