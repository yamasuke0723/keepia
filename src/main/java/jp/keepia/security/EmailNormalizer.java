package jp.keepia.security;

import java.util.Locale;

/**
 * ログイン用メールアドレスの正規化規則を一元管理します。
 */
public final class EmailNormalizer {
	
	private EmailNormalizer() {
	}
	
	/**
	 * メールアドレスの前後空白を除去し、小文字へ変換します。
	 * 
	 * @param rawEmail 画面などから受け取ったメールアドレス
	 * @return 正規化済みメールアドレス。nullの場合は空文字
	 */
	public static String normalize(String rawEmail) {
		if(rawEmail == null) {
			return "";
		}
		return rawEmail.strip().toLowerCase(Locale.ROOT);
	}

}
